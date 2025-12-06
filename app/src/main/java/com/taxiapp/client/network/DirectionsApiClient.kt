package com.taxiapp.client.network

import com.taxiapp.client.network.dto.DirectionsResponse
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

interface DirectionsApiService {
    @GET("directions/json")
    fun getDirections(
        @Query("origin") origin: String,
        @Query("destination") destination: String,

        // !!! ВИПРАВЛЕННЯ: encoded = true !!!
        @Query("waypoints", encoded = true) waypoints: String?,

        @Query("key") apiKey: String
    ): Call<DirectionsResponse>
}

object DirectionsApiClient {
    private const val BASE_URL = "https://maps.googleapis.com/maps/api/"

    val instance: DirectionsApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DirectionsApiService::class.java)
    }
}