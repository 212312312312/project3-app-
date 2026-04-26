package com.taxiapp.client.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

object ApiClient {

    const val BASE_URL = "http://192.168.0.107:8080/api/v1/"

    // Перехватчик для глобального отлова ошибок сервера
    private val errorInterceptor = Interceptor { chain ->
        try {
            val response = chain.proceed(chain.request())
            // Если сервер отвечает 502 (Bad Gateway) или 503 (Maintenance)
            if (response.code == 502 || response.code == 503) {
                ServerStatusBus.triggerServerError()
            }
            response
        } catch (e: Exception) {
            // Если сервер вообще выключен (Отказ подключения или Таймаут)
            if (e is ConnectException || e is SocketTimeoutException) {
                ServerStatusBus.triggerServerError()
            }
            throw e // Прокидываем ошибку дальше, чтобы Retrofit отработал корректно
        }
    }

    // Настраиваем клиент с таймаутами (по 10 секунд) и нашим перехватчиком
    // Настраиваем клиент с ускоренными таймаутами
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(errorInterceptor)
        // Даем серверу всего 3 секунды на установку соединения (вместо 10)
        .connectTimeout(3, TimeUnit.SECONDS)
        // Чтение данных можно оставить 10 или сделать 15, если вдруг интернет медленный
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    val instance: ApiService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient) // <-- Подключаем OkHttpClient
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        retrofit.create(ApiService::class.java)
    }
}