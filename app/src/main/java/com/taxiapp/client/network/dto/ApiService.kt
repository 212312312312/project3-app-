package com.taxiapp.client.network

import com.taxiapp.client.network.dto.*
import com.taxiapp.client.data.model.TaxiService
import retrofit2.Call
import retrofit2.http.*

// === ВАЖНО: Эти классы должны быть здесь ===
data class ErrorResponse(val message: String)
data class MessageResponse(val message: String)

data class RateDriverRequest(
    val orderId: Long,
    val score: Int,
    val comment: String?
)

data class ClientProfileResponse(
    val id: Long,
    val phoneNumber: String,
    val fullName: String,
    val isBlocked: Boolean,
    val cardMask: String? // <-- Самое главное поле для нас
)
// <-- ДОБАВЛЕН DTO ДЛЯ РЕФРЕША -->
data class TokenRefreshRequestDto(val refreshToken: String)

data class GoogleAuthRequestDto(val idToken: String)
data class InitBindCardResponse(val paymentUrl: String)
// ==========================================

interface ApiService {

    // --- АВТОРИЗАЦІЯ ---
    @POST("auth/client/sms/request")
    fun requestSmsCode(@Body request: SmsRequestDto): Call<MessageResponse>

    @POST("auth/client/sms/verify")
    fun verifySmsCode(@Body request: SmsVerifyDto): Call<LoginResponseDto>

    @POST("auth/client/google")
    fun loginWithGoogle(@Body request: GoogleAuthRequestDto): Call<LoginResponseDto>

    @POST("auth/client/link-phone")
    fun linkPhone(
        @Header("Authorization") token: String,
        @Body request: SmsVerifyDto
    ): Call<LoginResponseDto>


    // --- ОПЛАТА ---
    @POST("payments/bind-card/init")
    fun initBindCard(
        @Header("Authorization") token: String
    ): Call<InitBindCardResponse>
    // <-- ДОБАВЛЕН ЭНДПОИНТ ДЛЯ РЕФРЕША -->
    @POST("auth/refresh")
    fun refreshToken(@Body request: TokenRefreshRequestDto): Call<LoginResponseDto>

    @POST("auth/fcm-token")
    fun updateFcmToken(
        @Header("Authorization") token: String,
        @Body body: Map<String, String>
    ): Call<Void>

    // --- ТАРИФИ ---
    @GET("public/tariffs")
    fun getActiveTariffs(): Call<List<CarTariffDto>>

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

    @POST("public/calculate-price")
    fun calculatePrice(@Body request: CalculatePriceRequestDto): Call<List<CarTariffDto>>

    @DELETE("client/account")
    fun deleteAccount(@Header("Authorization") token: String): Call<MessageResponse>

    // --- ОЦЕНКА ---
    @POST("client/rate")
    fun rateDriver(
        @Header("Authorization") token: String,
        @Body request: RateDriverRequest
    ): Call<MessageResponse>

    @GET("client/profile")
    fun getClientProfile(
        @Header("Authorization") token: String
    ): Call<ClientProfileResponse>

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

    @GET("chat/{orderId}")
    fun getChatMessages(
        @Header("Authorization") token: String,
        @Path("orderId") orderId: Long
    ): Call<List<ChatMessageDto>>

    @POST("chat/client/{orderId}")
    fun sendChatMessage(
        @Header("Authorization") token: String,
        @Path("orderId") orderId: Long,
        @Body request: SendMessageRequest
    ): Call<ChatMessageDto>

    @GET("public/settings/car-icon")
    fun getCarIconUrl(): Call<Map<String, String>>
}