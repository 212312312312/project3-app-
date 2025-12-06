package com.taxiapp.client.network.dto

data class TaxiOrderDto(
    val id: Long,
    val client: Any?, // Нам не важливі дані клієнта тут
    val driver: OrderDriverDto?, // <-- Використовуємо новий DTO
    val status: String,
    val fromAddress: String,
    val toAddress: String,
    val price: Double,
    val tariffName: String,
    val originLat: Double?,
    val originLng: Double?,
    val destLat: Double?,
    val destLng: Double?,
    val googleRoutePolyline: String?
)