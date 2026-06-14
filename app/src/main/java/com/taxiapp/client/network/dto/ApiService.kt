package com.taxiapp.client.network

import com.taxiapp.client.network.dto.*
import com.taxiapp.client.data.model.TaxiService
import retrofit2.Call
import retrofit2.http.*

data class ErrorResponse(val message: String)
data class MessageResponse(val message: String)

data class RateDriverRequest(
    val orderId: String,
    val score: Int,
    val comment: String?
)

data class ClientProfileResponse(
    val id: Long,
    val phoneNumber: String,
    val fullName: String,
    val isBlocked: Boolean,
    val cardMask: String?
)

data class TokenRefreshRequestDto(val refreshToken: String)
data class GoogleAuthRequestDto(val idToken: String)
data class InitBindCardResponse(val paymentUrl: String)

interface ApiService {

    // --- АВТОРИЗАЦІЯ ---
    @POST("auth/client/sms/request")
    fun requestSmsCode(@Body request: SmsRequestDto): Call<MessageResponse>

    @POST("auth/client/sms/verify")
    fun verifySmsCode(@Body request: SmsVerifyDto): Call<LoginResponseDto>

    @POST("auth/client/google")
    fun loginWithGoogle(@Body request: GoogleAuthRequestDto): Call<LoginResponseDto>

    @POST("auth/client/link-phone")
    fun linkPhone(@Body request: SmsVerifyDto): Call<LoginResponseDto>

    @POST("auth/refresh")
    fun refreshToken(@Body request: TokenRefreshRequestDto): Call<LoginResponseDto>

    @POST("auth/fcm-token")
    fun updateFcmToken(@Body body: Map<String, String>): Call<Void>

    // --- ОПЛАТА ---
    @POST("payments/bind-card/init")
    fun initBindCard(): Call<InitBindCardResponse>

    @DELETE("payments/unbind-card")
    fun unbindCard(): Call<MessageResponseDto>

    // --- ТАРИФИ ---
    @GET("public/tariffs")
    fun getActiveTariffs(): Call<List<CarTariffDto>>

    @GET("public/tariffs")
    fun getTariffs(): Call<List<CarTariffDto>>

    // --- АКЦІЇ ТА ПРОМОКОДИ ---
    @GET("client/promos")
    fun getClientPromos(): Call<List<ClientPromoProgressDto>>

    @GET("client/promos/discount")
    fun getActiveDiscount(): Call<ActiveDiscountDto>

    @POST("client/promos/apply")
    fun applyPromo(@Body request: ApplyPromoRequestDto): Call<MessageResponse>

    // --- ЗАМОВЛЕННЯ ---
    @POST("client/orders")
    fun createOrder(@Body request: CreateOrderRequestDto): Call<TaxiOrderDto>

    @GET("client/orders/{id}")
    fun getOrder(@Path("id") id: String): Call<TaxiOrderDto> // <-- String

    @POST("client/orders/{id}/cancel")
    fun cancelOrder(@Path("id") orderId: String): Call<TaxiOrderDto> // <-- String

    @POST("client/orders/{id}/cancel")
    fun cancelOrder(
        @Path("id") orderId: String, // <-- String
        @Query("reasonText") reasonText: String? = null
    ): Call<TaxiOrderDto>

    @PUT("orders/{id}/price")
    fun updateOrderPrice(
        @Path("id") orderId: String, // <-- String
        @Query("addedValue") addedValue: Double
    ): Call<MessageResponseDto>

    @PUT("orders/{id}/payment-method")
    fun updatePaymentMethod(
        @Path("id") orderId: String, // <-- String
        @Query("method") method: String
    ): Call<MessageResponseDto>

    @GET("cancellation-reasons")
    fun getCancellationReasons(@Query("target") target: String): Call<List<CancellationReasonDto>>

    @POST("public/calculate-price")
    fun calculatePrice(@Body request: CalculatePriceRequestDto): Call<List<CarTariffDto>>

    // --- ІНШЕ ---
    @DELETE("client/account")
    fun deleteAccount(): Call<MessageResponse>

    @POST("client/rate")
    fun rateDriver(@Body request: RateDriverRequest): Call<MessageResponse>

    @GET("client/profile")
    fun getClientProfile(): Call<ClientProfileResponse>

    @GET("client/orders")
    fun getHistory(): Call<List<TaxiOrderDto>>

    @GET("client/orders")
    fun getOrderHistory(): Call<List<TaxiOrderDto>>

    @GET("client/news")
    fun getClientNews(): Call<List<NewsDto>>

    @GET("client/services")
    fun getServices(): Call<List<TaxiService>>

    @GET("public/sectors")
    fun getSectors(): Call<List<SectorDto>>

    @GET("chat/{orderId}")
    fun getChatMessages(@Path("orderId") orderId: String): Call<List<ChatMessageDto>> // <-- String

    @POST("chat/client/{orderId}")
    fun sendChatMessage(
        @Path("orderId") orderId: String, // <-- String
        @Body request: SendMessageRequest
    ): Call<ChatMessageDto>

    @GET("public/settings/car-icon")
    fun getCarIconUrl(): Call<Map<String, String>>
}