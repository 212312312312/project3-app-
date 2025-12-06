package com.taxiapp.client.network.dto

// DTO, который мы отправляем на бэкенд (POST /client/order)
data class CreateOrderRequestDto(
    val fromAddress: String,
    val toAddress: String,
    val tariffId: Long,
    val price: Double,
    val originLat: Double?,
    val originLng: Double?,
    val destLat: Double?,
    val destLng: Double?,
    val googleRoutePolyline: String?, // <-- ЭТО ПОЛЕ НУЖНО БЫЛО ДОБАВИТЬ
    val waypoints: List<WaypointDto>? = null
)