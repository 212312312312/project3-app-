package com.taxiapp.client.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object  ApiClient {

    // !!! ВАЖЛИВО !!!
    // ЗАМІНІТЬ ЦЕ НА ВАШУ IP-АДРЕСУ З `ipconfig`
    private const val BASE_URL = "http://192.168.0.104:8080/api/v1/"

    // Створюємо "одинака" (singleton) для Retrofit
    val instance: ApiService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        retrofit.create(ApiService::class.java)
    }
}