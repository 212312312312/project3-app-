package com.taxiapp.client.network

import com.taxiapp.client.network.dto.CarTariffDto
import com.taxiapp.client.network.dto.ActiveDiscountDto
import com.taxiapp.client.network.dto.ClientPromoProgressDto // <-- ДОДАНО ЦЕЙ ІМПОРТ
import com.taxiapp.client.network.dto.CreateOrderRequestDto
import com.taxiapp.client.network.dto.LoginResponseDto
import com.taxiapp.client.network.dto.SmsRequestDto
import com.taxiapp.client.network.dto.SmsVerifyDto
import com.taxiapp.client.network.dto.TaxiOrderDto
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

// Прості класи відповідей
data class ErrorResponse(val message: String)
data class MessageResponse(val message: String)

interface ApiService {

    // --- АВТОРИЗАЦІЯ ---
    @POST("auth/client/sms/request")
    fun requestSmsCode(@Body request: SmsRequestDto): Call<MessageResponse>

    @POST("auth/client/sms/verify")
    fun verifySmsCode(@Body request: SmsVerifyDto): Call<LoginResponseDto>

    // --- ТАРИФИ ---
    @GET("public/tariffs")
    fun getActiveTariffs(): Call<List<CarTariffDto>>

    // --- АКЦІЇ (НОВИЙ МЕТОД) ---
    @GET("client/promos")
    fun getClientPromos(
        @Header("Authorization") token: String
    ): Call<List<ClientPromoProgressDto>> // <-- Правильний синтаксис списку

    // --- КЛІЄНТ (ЗАМОВЛЕННЯ) ---

    // Створення
    @POST("client/orders")
    fun createOrder(
        @Header("Authorization") token: String,
        @Body request: CreateOrderRequestDto
    ): Call<TaxiOrderDto>

    // Отримання статусу
    @GET("client/orders/{id}")
    fun getOrder(
        @Header("Authorization") token: String,
        @Path("id") id: Long
    ): Call<TaxiOrderDto>

    // Скасування
    @POST("client/orders/{id}/cancel")
    fun cancelOrder(
        @Header("Authorization") token: String,
        @Path("id") orderId: Long
    ): Call<TaxiOrderDto>

    // Видалення акаунту
    @DELETE("client/account")
    fun deleteAccount(
        @Header("Authorization") token: String
    ): Call<MessageResponse>

    @GET("client/promos/discount")
    fun getActiveDiscount(
        @Header("Authorization") token: String
    ): Call<ActiveDiscountDto>
}
