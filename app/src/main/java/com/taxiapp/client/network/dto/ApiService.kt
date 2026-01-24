package com.taxiapp.client.network

import com.taxiapp.client.network.dto.*
import com.taxiapp.client.data.model.TaxiService
import retrofit2.Call
import retrofit2.http.*

data class ErrorResponse(val message: String)
data class MessageResponse(val message: String)

interface ApiService {

    // --- АВТОРИЗАЦІЯ ---
    @POST("auth/client/sms/request")
    fun requestSmsCode(@Body request: SmsRequestDto): Call<MessageResponse>

    @POST("auth/client/sms/verify")
    fun verifySmsCode(@Body request: SmsVerifyDto): Call<LoginResponseDto>

    @POST("auth/fcm-token")
    fun updateFcmToken(
        @Header("Authorization") token: String,
        @Body body: Map<String, String>
    ): Call<Void>

    // --- ТАРИФИ ---
    @GET("public/tariffs")
    fun getActiveTariffs(): Call<List<CarTariffDto>>

    // Тут теж краще прибрати повний шлях, якщо він дублюється, але поки хай буде, якщо працює
    @GET("public/tariffs")
    fun getTariffs(@Header("Authorization") token: String): Call<List<CarTariffDto>>

    // --- АКЦІЇ ТА ПРОМОКОДИ ---
    @GET("client/promos")
    fun getClientPromos(@Header("Authorization") token: String): Call<List<ClientPromoProgressDto>>

    @GET("client/promos/discount")
    fun getActiveDiscount(@Header("Authorization") token: String): Call<ActiveDiscountDto>

    @POST("client/promos/apply")
    fun applyPromo(
        @Header("Authorization") token: String,
        @Body request: ApplyPromoRequestDto
    ): Call<MessageResponse>

    // --- ЗАМОВЛЕННЯ ---
    @POST("client/orders")
    fun createOrder(
        @Header("Authorization") token: String,
        @Body request: CreateOrderRequestDto
    ): Call<TaxiOrderDto>

    @GET("client/orders/{id}")
    fun getOrder(
        @Header("Authorization") token: String,
        @Path("id") id: Long
    ): Call<TaxiOrderDto>

    @POST("client/orders/{id}/cancel")
    fun cancelOrder(
        @Header("Authorization") token: String,
        @Path("id") orderId: Long
    ): Call<TaxiOrderDto>

    // !!! ВИПРАВЛЕННЯ ТУТ: ПРИБРАЛИ СЛЕШ "/" НА ПОЧАТКУ !!!
    // Було: @POST("/public/calculate-price") -> Стало: @POST("public/calculate-price")
    @POST("public/calculate-price")
    fun calculatePrice(@Body request: CalculatePriceRequestDto): Call<List<CarTariffDto>>

    @DELETE("client/account")
    fun deleteAccount(@Header("Authorization") token: String): Call<MessageResponse>

    // --- ІСТОРІЯ ---
    @GET("client/orders")
    fun getHistory(@Header("Authorization") token: String): Call<List<TaxiOrderDto>>

    @GET("client/orders")
    fun getOrderHistory(@Header("Authorization") token: String): Call<List<TaxiOrderDto>>

    // --- НОВИНИ ТА ІНШЕ ---
    @GET("client/news")
    fun getClientNews(@Header("Authorization") token: String): Call<List<NewsDto>>

    @GET("client/services")
    fun getServices(@Header("Authorization") token: String): Call<List<TaxiService>>

    @GET("public/sectors")
    fun getSectors(): Call<List<SectorDto>>
}