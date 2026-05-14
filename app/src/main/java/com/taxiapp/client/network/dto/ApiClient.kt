package com.taxiapp.client.network

import com.taxiapp.client.utils.SessionManager
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
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
                ServerStatusBus.triggerServerError()
            }
            response
        } catch (e: Exception) {
            if (e is ConnectException || e is SocketTimeoutException) {
                ServerStatusBus.triggerServerError()
            }
            throw e
        }
    }

    // =====================================================================
    // НОВОЕ: Отдельный "чистый" клиент только для рефреша токенов!
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

    private val tokenAuthenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            println(">>> OKHTTP AUTHENTICATOR: Triggered for 401 error!") // <-- Проверяем, сработал ли триггер

            if (response.priorResponse?.code == 401) {
                println(">>> OKHTTP AUTHENTICATOR: Double 401, aborting.")
                return null
            }

            val sm = sessionManager ?: return null

            synchronized(this) {
                val currentSavedToken = sm.fetchAuthToken()
                val requestToken = response.request.header("Authorization")?.removePrefix("Bearer ")

                if (currentSavedToken != null && currentSavedToken != requestToken) {
                    println(">>> OKHTTP AUTHENTICATOR: Token already refreshed, retrying.")
                    return response.request.newBuilder()
                        .header("Authorization", "Bearer $currentSavedToken")
                        .build()
                }

                val refreshToken = sm.fetchRefreshToken()
                println(">>> OKHTTP AUTHENTICATOR: Refresh token in SessionManager: '$refreshToken'") // <-- Смотрим, есть ли токен

                if (refreshToken.isNullOrEmpty()) {
                    println(">>> OKHTTP AUTHENTICATOR: NO REFRESH TOKEN! Throwing to login screen.")
                    ServerStatusBus.triggerSessionExpired()
                    return null
                }

                try {
                    println(">>> OKHTTP AUTHENTICATOR: Sending request to /auth/refresh...")
                    val refreshCall = authService.refreshToken(TokenRefreshRequestDto(refreshToken))
                    val refreshResponse = refreshCall.execute()

                    if (refreshResponse.isSuccessful && refreshResponse.body() != null) {
                        println(">>> OKHTTP AUTHENTICATOR: SUCCESS! Got new tokens.")
                        val loginResponse = refreshResponse.body()!!

                        sm.saveAuthToken(loginResponse.token)
                        if (!loginResponse.refreshToken.isNullOrEmpty()) {
                            sm.saveRefreshToken(loginResponse.refreshToken)
                        }

                        return response.request.newBuilder()
                            .header("Authorization", "Bearer ${loginResponse.token}")
                            .build()
                    } else {
                        println(">>> OKHTTP AUTHENTICATOR: Refresh FAILED with code ${refreshResponse.code()}")
                        sm.clearSession()
                        ServerStatusBus.triggerSessionExpired()
                        return null
                    }
                } catch (e: Exception) {
                    println(">>> OKHTTP AUTHENTICATOR: CRASH during refresh: ${e.message}")
                    e.printStackTrace()
                    return null
                }
            }
        }
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(errorInterceptor)
        .authenticator(tokenAuthenticator)
        .connectTimeout(3, TimeUnit.SECONDS)
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