package com.taxiapp.client.network.dto

import com.google.gson.annotations.SerializedName

data class OrderServiceDto(
    val id: Long,
    val name: String,
    val price: Double
)

data class TaxiOrderDto(
    val id: Long,
    val client: Any?,
    val driver: OrderDriverDto?,
    val status: String,
    val fromAddress: String,
    val toAddress: String,
    val price: Double,
    val tariffName: String,
    val originLat: Double?,
    val originLng: Double?,
    val destLat: Double?,
    val distanceMeters: Int?,
    val durationSeconds: Int?,
    val destLng: Double?,
    val createdAt: String?,
    val googleRoutePolyline: String?,
    val formattedWaypoints: String?,
    val paymentMethod: String? = "CASH",
    val serviceIds: List<Long>? = null,
    val comment: String? = null,
    val addedValue: Double = 0.0,

    @SerializedName("services")
    val services: List<OrderServiceDto> = emptyList(),

    val isRatedByClient: Boolean = false,
    val arrivedAt: String? = null,
    val startedAt: String? = null,
    val waitingPrice: Double = 0.0,
    val freeWaitingMinutes: Int = 3,
    val pricePerWaitingMinute: Double = 0.0,


    // --- НОВОЕ ПОЛЕ ---
    val scheduledAt: String? = null
)