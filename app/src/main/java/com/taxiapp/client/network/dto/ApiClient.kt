package com.taxiapp.client.network

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import com.taxiapp.client.BuildConfig
import com.taxiapp.client.TaxiApplication
import com.taxiapp.client.network.dto.*
import com.taxiapp.client.utils.SessionManager
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val TAG = "ApiClient_Secure"

    val BASE_URL: String = BuildConfig.BASE_URL + "api/v1/"

    var sessionManager: SessionManager? = null
    private val refreshLock = Any()

    private fun logDebug(message: String, exception: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (exception != null) {
                Log.d(TAG, message, exception)
            } else {
                Log.d(TAG, message)
            }
        }
    }

    /**
     * Проверяет, истек ли JWT-токен (или истечет в ближайшие [leewaySeconds] секунд).
     * Позволяет обновлять токен до того, как WebSocket или HTTP-запрос получит отказ.
     */
    fun isJwtExpiredOrExpiringSoon(token: String?, leewaySeconds: Long = 15L): Boolean {
        if (token.isNullOrBlank()) return true
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return false
            val payloadBytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val json = JSONObject(String(payloadBytes, Charsets.UTF_8))
            val expSeconds = json.optLong("exp", 0L)
            if (expSeconds <= 0L) return false
            val nowSeconds = System.currentTimeMillis() / 1000L
            (nowSeconds + leewaySeconds) >= expSeconds
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Потокобезопасное обновление JWT через /auth/refresh.
     * Используется как в OkHttp-интерцепторе, так и перед реконнектом WebSocket.
     */
    fun refreshAccessTokenIfNeeded(force: Boolean = false): String? {
        val sm = sessionManager ?: return null
        val currentToken = sm.fetchAuthToken()
        if (!force && !isJwtExpiredOrExpiringSoon(currentToken)) {
            return currentToken
        }

        synchronized(refreshLock) {
            val latestToken = sm.fetchAuthToken()
            if (!force && !isJwtExpiredOrExpiringSoon(latestToken)) {
                return latestToken
            }
            if (force && latestToken != null && latestToken != currentToken && !isJwtExpiredOrExpiringSoon(latestToken)) {
                return latestToken
            }

            val refreshToken = sm.fetchRefreshToken()
            if (refreshToken.isNullOrEmpty()) {
                logDebug("AUTH REFRESH: No refresh token available.")
                return latestToken
            }

            return try {
                logDebug("AUTH REFRESH: Requesting new JWT from /auth/refresh...")
                val refreshResponse = authService.refreshToken(TokenRefreshRequestDto(refreshToken)).execute()
                if (refreshResponse.isSuccessful && refreshResponse.body() != null) {
                    val loginResponse = refreshResponse.body()!!
                    sm.saveAuthToken(loginResponse.token)
                    if (!loginResponse.refreshToken.isNullOrEmpty()) {
                        sm.saveRefreshToken(loginResponse.refreshToken)
                    }
                    ServerStatusBus.triggerTokenRefreshed(loginResponse.token)
                    logDebug("AUTH REFRESH: Successfully refreshed JWT token.")
                    loginResponse.token
                } else {
                    logDebug("AUTH REFRESH: Failed with HTTP ${refreshResponse.code()}")
                    if (refreshResponse.code() in listOf(400, 401, 403, 404)) {
                        sm.clearSession()
                        ServerStatusBus.triggerSessionExpired()
                        null
                    } else {
                        latestToken
                    }
                }
            } catch (e: Exception) {
                logDebug("AUTH REFRESH: Network exception during refresh", e)
                latestToken
            }
        }
    }

    private fun isNonCriticalBackgroundEndpoint(path: String): Boolean {
        return path.contains("analytics/events") ||
                path.contains("settings/car-icon") ||
                path.contains("public/sectors") ||
                path.contains("auth/fcm-token")
    }

    // Перехоплювач серверних помилок (502/503) і таймаутів
    private val errorInterceptor = Interceptor { chain ->
        val request = chain.request()
        val path = request.url.encodedPath
        val isBackgroundEndpoint = isNonCriticalBackgroundEndpoint(path)

        try {
            val response = chain.proceed(request)
            if ((response.code == 502 || response.code == 503) && !isBackgroundEndpoint) {
                if (TaxiApplication.isAppInForeground && isDeviceInteractive()) {
                    ServerStatusBus.triggerServerError()
                }
            } else if (response.isSuccessful) {
                ServerStatusBus.resetServerError()
            }
            response
        } catch (e: Exception) {
            if ((e is ConnectException || e is SocketTimeoutException) && !isBackgroundEndpoint) {
                if (TaxiApplication.isAppInForeground && isDeviceInteractive()) {
                    logDebug("NETWORK: Timeout on $path in foreground. Triggering error.")
                    ServerStatusBus.triggerServerError()
                }
            }
            throw e
        }
    }

    private fun isDeviceInteractive(): Boolean {
        val context = TaxiApplication.instance.applicationContext ?: return false
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isInteractive ?: false
    }

    private fun OkHttpClient.Builder.applyProductionCertificatePinning(): OkHttpClient.Builder {
        if (!BuildConfig.DEBUG) {
            val uri = Uri.parse(BASE_URL)
            val host = uri.host
            if (host != null && host != "192.168.0.107" && !host.contains("localhost")) {
                val certificatePinner = CertificatePinner.Builder()
                    .add(host, "sha256/+HItYRvjasMipzdFALDcnxOY6SzYTephmJkdOc05PUg=")
                    .add(host, "sha256/kldp6NNEd8wsugYyyIYFsi1yIMCEDB3hZbSR8ZFsa/A4=")
                    .add(host, "sha256/mEfIZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=")
                    .build()
                certificatePinner(certificatePinner)
            }
        }
        return this
    }

    // Чистий клієнт тільки для /auth/refresh
    private val cleanOkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .applyProductionCertificatePinning()
        .build()

    private val cleanRetrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(cleanOkHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val authService = cleanRetrofit.create(ApiService::class.java)

    // Перехоплювач токенів (Проактивний + 401 Авто-Refresh сесії)
    private val authInterceptor = Interceptor { chain ->
        val sm = sessionManager
        val originalRequest = chain.request()

        var currentToken = sm?.fetchAuthToken()
        if (sm != null && !currentToken.isNullOrEmpty() && isJwtExpiredOrExpiringSoon(currentToken, leewaySeconds = 10L)) {
            currentToken = refreshAccessTokenIfNeeded(force = true) ?: currentToken
        }

        val requestBuilder = originalRequest.newBuilder()
        if (!currentToken.isNullOrEmpty() && originalRequest.header("Authorization") == null) {
            requestBuilder.header("Authorization", "Bearer $currentToken")
        }

        var response = chain.proceed(requestBuilder.build())

        if (response.code == 401 && sm != null) {
            logDebug("AUTH INTERCEPTOR: Caught 401 Unauthorized for ${originalRequest.url}")
            val refreshedToken = refreshAccessTokenIfNeeded(force = true)
            if (!refreshedToken.isNullOrEmpty() && refreshedToken != currentToken) {
                response.close()
                val retryRequest = originalRequest.newBuilder()
                    .header("Authorization", "Bearer $refreshedToken")
                    .build()
                response = chain.proceed(retryRequest)
            }
        }
        response
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(errorInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .applyProductionCertificatePinning()
        .build()

    // Захищений OkHttpClient для WebSocket (STOMP) із Certificate Pinning та Ping-KeepAlive
    val webSocketOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .applyProductionCertificatePinning()
        .build()

    val instance: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}