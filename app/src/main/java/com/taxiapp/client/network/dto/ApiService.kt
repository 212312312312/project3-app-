package com.taxiapp.client.network

import com.taxiapp.client.data.model.TaxiService
import com.taxiapp.client.network.dto.*
import retrofit2.Response
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
    val email: String? = null,
    val isBlocked: Boolean,
    val cardMask: String?,
    val tripsCount: Int? = 0,
    val rating: Double? = 5.0
)

data class UpdateClientProfileRequestDto(
    val fullName: String
)

data class TokenRefreshRequestDto(val refreshToken: String)
data class GoogleAuthRequestDto(
    val idToken: String,
    val acquisitionSource: String? = null
)
data class InitBindCardResponse(val paymentUrl: String)

interface ApiService {

    // --- АВТОРИЗАЦІЯ ---
    @POST("auth/client/sms/request")
    suspend fun requestSmsCode(@Body request: SmsRequestDto): Response<MessageResponse>

    @POST("auth/client/sms/verify")
    suspend fun verifySmsCode(@Body request: SmsVerifyDto): Response<LoginResponseDto>

    @POST("auth/client/google")
    suspend fun loginWithGoogle(@Body request: GoogleAuthRequestDto): Response<LoginResponseDto>

    @POST("auth/client/link-phone")
    suspend fun linkPhone(@Body request: SmsVerifyDto): Response<LoginResponseDto>

    @POST("auth/refresh")
    suspend fun refreshToken(@Body request: TokenRefreshRequestDto): Response<LoginResponseDto>

    @POST("auth/fcm-token")
    suspend fun updateFcmToken(@Body body: Map<String, String>): Response<Unit>

    // --- ОПЛАТА ---
    @POST("payments/bind-card/init")
    suspend fun initBindCard(): Response<InitBindCardResponse>

    @DELETE("payments/unbind-card")
    suspend fun unbindCard(): Response<MessageResponseDto>

    // --- ТАРИФИ ТА МАРШРУТИ ---
    @GET("public/tariffs")
    suspend fun getActiveTariffs(): Response<List<CarTariffDto>>

    @GET("public/tariffs")
    suspend fun getTariffs(): Response<List<CarTariffDto>>

    @POST("public/route")
    suspend fun calculateRoute(@Body request: RouteRequestDto): Response<RouteResponseDto>

    @GET("public/reverse-geocode")
    suspend fun reverseGeocode(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("lang") lang: String = "uk"
    ): Response<ReverseGeocodeResponseDto>

    @GET("public/nearby-suggestions")
    suspend fun getNearbySuggestions(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("lang") lang: String = "uk",
        @Query("limit") limit: Int = 6
    ): Response<List<NearbyPlaceSuggestionDto>>

    // --- АКЦІЇ ТА ПРОМОКОДИ ---
    @GET("client/promos")
    suspend fun getClientPromos(): Response<List<ClientPromoProgressDto>>

    @GET("client/promos/discount")
    suspend fun getActiveDiscount(): Response<ActiveDiscountDto>

    @POST("client/promos/apply")
    suspend fun applyPromo(@Body request: ApplyPromoRequestDto): Response<MessageResponse>

    // --- ЗАМОВЛЕННЯ ---
    @POST("client/orders")
    suspend fun createOrder(@Body request: CreateOrderRequestDto): Response<TaxiOrderDto>

    @GET("client/orders/{id}")
    suspend fun getOrder(@Path("id") id: String): Response<TaxiOrderDto>

    // 🚀 СИНХРОНІЗАЦІЯ АКТИВНОГО / ЩОЙНО ЗАВЕРШЕНОГО ЗАМОВЛЕННЯ (REDIS)
    @GET("client/orders/active")
    suspend fun getActiveOrder(): Response<TaxiOrderDto>

    @POST("client/orders/{id}/dismiss")
    suspend fun dismissOrder(@Path("id") id: String): Response<MessageResponse>

    // --- ЗАМОВЛЕННЯ (ІСТОРІЯ З ПАГІНАЦІЄЮ) ---
    @GET("orders/history")
    suspend fun getHistory(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 30
    ): Response<List<TaxiOrderDto>>

    @POST("client/orders/{id}/cancel")
    suspend fun cancelOrder(
        @Path("id") orderId: String,
        @Query("reasonText") reasonText: String? = null
    ): Response<TaxiOrderDto>

    @PUT("orders/{id}/price")
    suspend fun updateOrderPrice(
        @Path("id") orderId: String,
        @Query("addedValue") addedValue: Double
    ): Response<MessageResponseDto>

    @PUT("orders/{id}/payment-method")
    suspend fun updatePaymentMethod(
        @Path("id") orderId: String,
        @Query("method") method: String
    ): Response<MessageResponseDto>

    @GET("cancellation-reasons")
    suspend fun getCancellationReasons(@Query("target") target: String): Response<List<CancellationReasonDto>>

    @POST("public/calculate-price")
    suspend fun calculatePrice(@Body request: CalculatePriceRequestDto): Response<List<CarTariffDto>>

    // --- НАЛАШТУВАННЯ ---
    @GET("public/settings/payment-methods")
    suspend fun getPaymentMethodsSettings(): Response<Map<String, Boolean>>

    // --- ПРОФІЛЬ ТА ІНШЕ ---
    @DELETE("client/account")
    suspend fun deleteAccount(): Response<MessageResponse>

    @POST("client/rate")
    suspend fun rateDriver(@Body request: RateDriverRequest): Response<MessageResponse>

    @GET("client/profile")
    suspend fun getClientProfile(): Response<ClientProfileResponse>

    @PUT("client/profile")
    suspend fun updateClientProfile(@Body request: UpdateClientProfileRequestDto): Response<ClientProfileResponse>

    @GET("client/orders")
    suspend fun getOrderHistory(): Response<List<TaxiOrderDto>>

    @GET("client/news")
    suspend fun getClientNews(): Response<List<NewsDto>>

    @POST("client/analytics/events")
    suspend fun sendAnalyticsEvents(@Body request: ClientEventBatchRequest): Response<Unit>

    @GET("client/services")
    suspend fun getServices(): Response<List<TaxiService>>

    @GET("public/sectors")
    suspend fun getSectors(): Response<List<SectorDto>>

    @GET("chat/{orderId}")
    suspend fun getChatMessages(@Path("orderId") orderId: String): Response<List<ChatMessageDto>>

    @POST("chat/client/{orderId}")
    suspend fun sendChatMessage(
        @Path("orderId") orderId: String,
        @Body request: SendMessageRequest
    ): Response<ChatMessageDto>

    @GET("public/settings/car-icon")
    suspend fun getCarIconUrl(): Response<Map<String, String>>
}