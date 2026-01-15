package com.taxiapp.client.network.dto

// DTO, который мы отправляем на бэкенд (POST /client/order)
data class CreateOrderRequestDto(
    val fromAddress: String,
    val toAddress: String,
    val tariffId: Long,
    val price: Double,
    val originLat: Double?,
    val originLng: Double?,

    // Ці поля важливі для функції "Додому"
    val destLat: Double?,
    val destLng: Double?,

    // Поле для маршруту на карті
    val googleRoutePolyline: String?,

    val waypoints: List<WaypointDto>? = null,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val comment: String? = null,
    val paymentMethod: String,
    val serviceIds: List<Long> = emptyList(),
    val addedValue: Double = 0.0
)