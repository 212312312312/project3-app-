package com.taxiapp.client.network

import com.taxiapp.client.TaxiApplication
import com.taxiapp.client.utils.SessionManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

object ApiClient {

    const val BASE_URL = "http://192.168.0.107:8080/api/v1/"

    var sessionManager: SessionManager? = null
    private val refreshLock = Any() // Объект для безопасной блокировки потоков

    private val errorInterceptor = Interceptor { chain ->
        try {
            val response = chain.proceed(chain.request())
            if (response.code == 502 || response.code == 503) {
                ServerStatusBus.triggerServerError()
            } else if (response.isSuccessful) {
                ServerStatusBus.resetServerError()
            }
            response
        } catch (e: Exception) {
            if (e is ConnectException || e is SocketTimeoutException) {
                if (TaxiApplication.isAppInForeground) {
                    println(">>> NETWORK: Timeout. App is in FOREGROUND. Triggering error.")
                    ServerStatusBus.triggerServerError()
                }
            }
            throw e
        }
    }

    // Чистый клиент только для /refresh (без AuthInterceptor)
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

    // Идеальный перехватчик токенов
    private val authInterceptor = Interceptor { chain ->
        val sm = sessionManager
        val originalRequest = chain.request()

        // 1. Автоматически прикрепляем токен ко всем запросам (если он есть)
        val currentToken = sm?.fetchAuthToken()
        var requestBuilder = originalRequest.newBuilder()
        if (!currentToken.isNullOrEmpty() && originalRequest.header("Authorization") == null) {
            requestBuilder.header("Authorization", "Bearer $currentToken")
        }

        var response = chain.proceed(requestBuilder.build())

        // 2. Ловим 401 ошибку просроченного токена
        if (response.code == 401 && sm != null) {
            println(">>> AUTH INTERCEPTOR: Caught 401 Unauthorized for ${originalRequest.url}")

            synchronized(refreshLock) {
                // 3. Проверка: не обновил ли токен другой поток (чтобы не дублировать запросы)
                val newToken = sm.fetchAuthToken()
                if (newToken != null && newToken != currentToken) {
                    println(">>> AUTH INTERCEPTOR: Token already refreshed. Retrying...")
                    response.close()
                    val newRequest = originalRequest.newBuilder()
                        .header("Authorization", "Bearer $newToken")
                        .build()
                    return@Interceptor chain.proceed(newRequest)
                }

                // 4. Берем Refresh Token
                val refreshToken = sm.fetchRefreshToken()
                if (refreshToken.isNullOrEmpty()) {
                    println(">>> AUTH INTERCEPTOR: NO REFRESH TOKEN! Throwing to login screen.")
                    sm.clearSession()
                    ServerStatusBus.triggerSessionExpired()
                    return@Interceptor response
                }

                try {
                    println(">>> AUTH INTERCEPTOR: Sending request to /auth/refresh...")
                    // Выполняем СИНХРОННЫЙ запрос чистым клиентом
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

                        // Уведомляем приложение
                        ServerStatusBus.triggerTokenRefreshed(loginResponse.token)

                        // Повторяем упавший запрос с новым токеном!
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