package com.taxiapp.client.network.dto

import com.google.gson.annotations.SerializedName

data class OrderServiceDto(
    val id: Long,
    val name: String,
    val price: Double
)

data class OrderPassengerDto(
    val id: Long,
    val fullName: String
)

data class TaxiOrderDto(
    val id: String,
    val idLong: Long?,
    val client: OrderPassengerDto?,
    val driver: OrderDriverDto?,
    val status: String,
    val fromAddress: String,
    val toAddress: String,
    val price: Double,
    val clientPayAmount: Double? = null,
    val appliedDiscount: Double = 0.0,
    val tariffName: String?,
    val originLat: Double?,
    val originLng: Double?,
    val destLat: Double?,
    val distanceMeters: Int?,
    val durationSeconds: Int?,
    val destLng: Double?,
    val createdAt: String?,
    val googleRoutePolyline: String?,
    val formattedWaypoints: String?,
    val stops: List<WaypointDto>? = null,
    val paymentMethod: String? = "CASH",
    val serviceIds: List<Long>? = null,
    val comment: String? = null,

    @SerializedName(value = "addedValue", alternate = ["boostAmount", "added_value"])
    val addedValue: Double = 0.0,

    @SerializedName("services")
    val services: List<OrderServiceDto> = emptyList(),

    val isRatedByClient: Boolean = false,

    @SerializedName(value = "arrivedAt", alternate = ["arrived_at", "driverArrivedAt", "driver_arrived_at"])
    val arrivedAt: String? = null,

    @SerializedName(value = "startedAt", alternate = ["started_at", "inProgressAt"])
    val startedAt: String? = null,

    @SerializedName(value = "waitingStartTime", alternate = ["waiting_start_time", "waitingStartedAt"])
    val waitingStartTime: String? = null,

    @SerializedName(value = "waitingElapsedSeconds", alternate = ["waiting_elapsed_seconds", "elapsedWaitingSeconds"])
    val waitingElapsedSeconds: Long? = null,

    @SerializedName(value = "serverTime", alternate = ["server_time", "serverNow"])
    val serverTime: String? = null,

    val waitingPrice: Double = 0.0,

    @SerializedName(value = "freeWaitingMinutes", alternate = ["free_waiting_minutes"])
    val freeWaitingMinutes: Int = 3,

    val pricePerWaitingMinute: Double = 0.0,
    val cancellationReason: String? = null,
    val scheduledAt: String? = null,
    val driverToPickupPolyline: String? = null
) {
    /**
     * Итоговая сумма к оплате для клиента с учетом промокода / скидки.
     */
    val effectiveClientPrice: Double
        get() = if (clientPayAmount != null && clientPayAmount > 0.0) clientPayAmount else price
}