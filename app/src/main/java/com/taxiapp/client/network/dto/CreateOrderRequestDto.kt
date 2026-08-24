package com.taxiapp.client.network.dto

data class CreateOrderRequestDto(
    val fromAddress: String,
    val toAddress: String,
    val tariffId: Long,
    val price: Double,
    val originLat: Double?,
    val originLng: Double?,
    val destLat: Double?,
    val destLng: Double?,
    val googleRoutePolyline: String?,
    val waypoints: List<WaypointDto>? = null,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val comment: String? = null,
    val paymentMethod: String,
    val serviceIds: List<Long> = emptyList(),
    val addedValue: Double = 0.0,
    val scheduledAt: String? = null,

    // ➕ ДОБАВИТЬ ПОЛЯ:
    val districtStart: String? = null,
    val districtEnd: String? = null,
    val deviceId: String? = null,
    val boostAmount: Double? = 0.0
)