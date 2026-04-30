package com.taxiapp.client.network

// Убрали ошибочный импорт TokenRefreshRequestDto, так как он уже в этом же пакете
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

    // Сюда передаем sessionManager из приложения
    var sessionManager: SessionManager? = null

    // Перехватчик для глобального отлова ошибок сервера
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

    // Авторизатор: перехватывает 401 ошибку и автоматически обновляет токен
    private val tokenAuthenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            // Если предыдущий запрос тоже был за рефрешем и вернул 401 — сдаемся
            if (response.priorResponse?.code == 401) {
                return null
            }

            val sm = sessionManager ?: return null
            val refreshToken = sm.fetchRefreshToken() ?: return null

            try {
                // Делаем СИНХРОННЫЙ запрос на обновление токена
                val refreshCall = instance.refreshToken(TokenRefreshRequestDto(refreshToken))
                val refreshResponse = refreshCall.execute()

                if (refreshResponse.isSuccessful && refreshResponse.body() != null) {
                    val loginResponse = refreshResponse.body()!!

                    // ИСПРАВЛЕНО: accessToken -> token
                    sm.saveAuthToken(loginResponse.token)

                    // Сохраняем свежий рефреш токен, если сервер его прислал
                    if (!loginResponse.refreshToken.isNullOrEmpty()) {
                        sm.saveRefreshToken(loginResponse.refreshToken)
                    }

                    // ИСПРАВЛЕНО: accessToken -> token
                    // Повторяем оригинальный запрос с новым токеном!
                    return response.request.newBuilder()
                        .header("Authorization", "Bearer ${loginResponse.token}")
                        .build()
                } else {
                    // Если refresh token тоже протух - разлогиниваем юзера
                    sm.clearSession()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            return null
        }
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(errorInterceptor)
        .authenticator(tokenAuthenticator) // <-- ПОДКЛЮЧАЕМ АВТОРИЗАТОР
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