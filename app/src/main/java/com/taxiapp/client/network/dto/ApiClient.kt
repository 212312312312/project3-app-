package com.taxiapp.client.network

import com.taxiapp.client.TaxiApplication
import com.taxiapp.client.utils.SessionManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

object ApiClient {

    const val BASE_URL = "http://192.168.0.107:8080/api/v1/"

    var sessionManager: SessionManager? = null

    private val errorInterceptor = Interceptor { chain ->
        try {
            val response = chain.proceed(chain.request())

            if (response.code == 502 || response.code == 503) {
                // Если сервер реально прислал 502/503 (упал) — бьем тревогу всегда
                ServerStatusBus.triggerServerError()
            } else if (response.isSuccessful) {
                // НОВОЕ: Если запрос прошел успешно, значит сервер жив. Гасим ошибку!
                ServerStatusBus.resetServerError()
            }
            response
        } catch (e: Exception) {
            if (e is ConnectException || e is SocketTimeoutException) {
                // НОВОЕ: Железная логика
                if (TaxiApplication.isAppInForeground) {
                    println(">>> NETWORK: Timeout. App is in FOREGROUND. Triggering error.")
                    ServerStatusBus.triggerServerError()
                } else {
                    println(">>> NETWORK: Timeout. App is in BACKGROUND. Ignored system network restriction.")
                }
            }
            throw e
        }
    }

    // =====================================================================
    // Чистый клиент только для обновления токенов (без AuthInterceptor)
    // =====================================================================
    private val cleanOkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cleanRetrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(cleanOkHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val authService = cleanRetrofit.create(ApiService::class.java)
    // =====================================================================

    // =====================================================================
    // НОВЫЙ ИНТЕРЦЕПТОР: Железобетонная обработка 401 ошибок
    // =====================================================================
    private val authInterceptor = Interceptor { chain ->
        val sm = sessionManager
        val originalRequest = chain.request()

        // 1. Если токен есть в сессии, автоматически добавляем его к запросу
        val currentToken = sm?.fetchAuthToken()
        var requestBuilder = originalRequest.newBuilder()
        if (!currentToken.isNullOrEmpty() && originalRequest.header("Authorization") == null) {
            requestBuilder.header("Authorization", "Bearer $currentToken")
        }

        // 2. Выполняем запрос
        var response = chain.proceed(requestBuilder.build())

        // 3. Если сервер ответил 401 Unauthorized (Токен протух)
        if (response.code == 401 && sm != null) {
            println(">>> AUTH INTERCEPTOR: Caught 401 Unauthorized for ${originalRequest.url}")

            // Блокируем другие потоки, пока обновляем токен (чтобы не было гонки запросов)
            synchronized(this) {
                // Проверяем, не обновил ли токен другой поток, пока мы ждали в очереди
                val newToken = sm.fetchAuthToken()
                if (newToken != null && newToken != currentToken) {
                    println(">>> AUTH INTERCEPTOR: Token already refreshed. Retrying original request...")
                    response.close() // Обязательно закрываем старый ответ
                    val newRequest = originalRequest.newBuilder()
                        .header("Authorization", "Bearer $newToken")
                        .build()
                    return@Interceptor chain.proceed(newRequest)
                }

                // Токен не обновляли. Берем рефреш-токен.
                val refreshToken = sm.fetchRefreshToken()
                if (refreshToken.isNullOrEmpty()) {
                    println(">>> AUTH INTERCEPTOR: NO REFRESH TOKEN! Throwing to login screen.")
                    sm.clearSession()
                    ServerStatusBus.triggerSessionExpired()
                    return@Interceptor response // Возвращаем 401, UI перекинет на логин
                }

                try {
                    println(">>> AUTH INTERCEPTOR: Sending request to /auth/refresh...")
                    // СИНХРОННЫЙ запрос чистым клиентом (поток заблокирован)
                    val refreshCall = authService.refreshToken(TokenRefreshRequestDto(refreshToken))
                    val refreshResponse = refreshCall.execute()

                    if (refreshResponse.isSuccessful && refreshResponse.body() != null) {
                        println(">>> AUTH INTERCEPTOR: SUCCESS! Got new tokens.")
                        val loginResponse = refreshResponse.body()!!

                        // Сохраняем новые токены
                        sm.saveAuthToken(loginResponse.token)
                        if (!loginResponse.refreshToken.isNullOrEmpty()) {
                            sm.saveRefreshToken(loginResponse.refreshToken)
                        }

                        // Сообщаем всему приложению (и сокетам), что токен обновился!
                        ServerStatusBus.triggerTokenRefreshed(loginResponse.token)

                        // Повторяем оригинальный запрос с НОВЫМ токеном
                        response.close()
                        val retryRequest = originalRequest.newBuilder()
                            .header("Authorization", "Bearer ${loginResponse.token}")
                            .build()
                        response = chain.proceed(retryRequest)
                    } else {
                        println(">>> AUTH INTERCEPTOR: Refresh FAILED with code ${refreshResponse.code()}")
                        sm.clearSession()
                        ServerStatusBus.triggerSessionExpired()
                    }
                } catch (e: Exception) {
                    println(">>> AUTH INTERCEPTOR: CRASH during refresh: ${e.message}")
                    sm.clearSession()
                    ServerStatusBus.triggerSessionExpired()
                }
            }
        }
        response
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(errorInterceptor)
        // НОВОЕ: 5 секунд для усиления надежности при переключении сетей
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    val instance: ApiService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        retrofit.create(ApiService::class.java)
    }
}