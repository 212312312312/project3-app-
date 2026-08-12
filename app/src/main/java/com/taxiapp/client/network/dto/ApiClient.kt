package com.taxiapp.client.network

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.taxiapp.client.TaxiApplication
import com.taxiapp.client.utils.SessionManager
// 🔥 КРИТИЧЕСКИЙ ИМПОРТ: Подключаем ApiService и все DTO из подпапки dto
import com.taxiapp.client.network.dto.*
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val TAG = "ApiClient_Secure"

    // Динамический URL: подгружает базовый адрес из build.gradle
    val BASE_URL = com.taxiapp.client.BuildConfig.BASE_URL + "api/v1/"

    var sessionManager: SessionManager? = null
    private val refreshLock = Any() // Объект для безопасной блокировки потоков при обновлении токена

    // Вспомогательный метод для безопасного логирования только в режиме отладки
    private fun logDebug(message: String, exception: Throwable? = null) {
        if (com.taxiapp.client.BuildConfig.DEBUG) {
            if (exception != null) {
                Log.d(TAG, message, exception)
            } else {
                Log.d(TAG, message)
            }
        }
    }

    // Перехватчик серверных ошибок (502/503) и таймаутов
    private val errorInterceptor = Interceptor { chain ->
        try {
            val response = chain.proceed(chain.request())
            if (response.code == 502 || response.code == 503) {
                // Проверяем Foreground, чтобы серверный сбой не генерировал ложные триггеры, когда приложение свернуто
                if (TaxiApplication.isAppInForeground && isDeviceInteractive()) {
                    ServerStatusBus.triggerServerError()
                }
            } else if (response.isSuccessful) {
                ServerStatusBus.resetServerError()
            }
            response
        } catch (e: Exception) {
            if (e is ConnectException || e is SocketTimeoutException) {
                // Триггерим ошибку сервера по таймауту ТОЛЬКО если приложение открыто на экране
                if (TaxiApplication.isAppInForeground && isDeviceInteractive()) {
                    logDebug("NETWORK: Timeout. App is in FOREGROUND and Screen is ON. Triggering error.")
                    ServerStatusBus.triggerServerError()
                } else {
                    logDebug("NETWORK: Timeout ignored. App in background or screen is OFF.")
                }
            }
            throw e
        }
    }

    /**
     * Проверяет, включен ли экран устройства в данный момент.
     */
    private fun isDeviceInteractive(): Boolean {
        val context = TaxiApplication.instance?.applicationContext ?: return false
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isInteractive ?: false
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

    // Перехватчик токенов (Авто-Refresh сессии)
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
            logDebug("AUTH INTERCEPTOR: Caught 401 Unauthorized for ${originalRequest.url}")

            synchronized(refreshLock) {
                // 3. Проверка: не обновил ли токен другой поток (чтобы не дублировать запросы)
                val newToken = sm.fetchAuthToken()
                if (newToken != null && newToken != currentToken) {
                    logDebug("AUTH INTERCEPTOR: Token already refreshed. Retrying...")
                    response.close()
                    val newRequest = originalRequest.newBuilder()
                        .header("Authorization", "Bearer $newToken")
                        .build()
                    return@Interceptor chain.proceed(newRequest)
                }

                // 4. Берем Refresh Token
                val refreshToken = sm.fetchRefreshToken()
                if (refreshToken.isNullOrEmpty()) {
                    logDebug("AUTH INTERCEPTOR: NO REFRESH TOKEN! Throwing to login screen.")
                    sm.clearSession()
                    ServerStatusBus.triggerSessionExpired()
                    return@Interceptor response
                }

                try {
                    logDebug("AUTH INTERCEPTOR: Sending request to /auth/refresh...")
                    // Выполняем СИНХРОННЫЙ запрос чистым клиентом
                    val refreshCall = authService.refreshToken(TokenRefreshRequestDto(refreshToken))
                    val refreshResponse = refreshCall.execute()

                    // === НАЧАЛО ОБНОВЛЕННОГО ФРАГМЕНТА ===
                    if (refreshResponse.isSuccessful && refreshResponse.body() != null) {
                        logDebug("AUTH INTERCEPTOR: SUCCESS! Got new tokens.")
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
                        logDebug("AUTH INTERCEPTOR: Refresh FAILED with code ${refreshResponse.code()}")

                        // 🔥 КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Выкидываем на регистрацию ТОЛЬКО если сервер явно
                        // ответил 400 или 401 (значит Refresh Token действительно протух или аннулирован).
                        // Если сервер лежит (500/503), мы НЕ стираем сессию пользователя!
                        if (refreshResponse.code() == 400 || refreshResponse.code() == 401) {
                            sm.clearSession()
                            ServerStatusBus.triggerSessionExpired()
                        }
                    }
                } catch (e: Exception) {
                    logDebug("AUTH INTERCEPTOR: Exception during refresh", e)

                    // 🔥 КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Если это ошибка сети (таймаут, отвалился Wi-Fi, телефон в Doze Mode),
                    // мы ОСТАВЛЯЕМ сессию целой. Просто возвращаем исходный 401, чтобы запрос упал по сети,
                    // но пользователя НЕ выкинуло из его аккаунта.
                    if (e is java.io.IOException) {
                        return@Interceptor response
                    } else {
                        // Если это какой-то внутренний Crash структуры данных — тогда безопасно сбрасываем
                        sm.clearSession()
                        ServerStatusBus.triggerSessionExpired()
                    }
                }
// === КОНЕЦ ОБНОВЛЕННОГО ФРАГМЕНТА ===
            }
        }
        response
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(errorInterceptor)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .apply {
            // Certificate Pinning с реальными хэшами сервера и промежуточного ЦА
            if (!com.taxiapp.client.BuildConfig.DEBUG) {
                val uri = android.net.Uri.parse(BASE_URL)
                val host = uri.host
                if (host != null && host != "192.168.0.107" && !host.contains("localhost")) {
                    val certificatePinner = CertificatePinner.Builder()
                        // 1. Точный хэш твоего сервера api.unitua.com из консоли
                        .add(host, "sha256/9JyM4kaYamI9aABprfG+BPHoX11cJLif0m9Da2XoEDw=")
                        // 2. Хэш промежуточного ЦА Google Trust Services (чтобы приложение не тыквынулось через 90 дней после авто-обновления SSL)
                        .add(host, "sha256/kldp6NNEd8wsugYyyIYFsi1yIMCEd3hZbSR8ZFsa/A4=")
                        .build()
                    certificatePinner(certificatePinner)
                }
            }
        }
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