package com.taxiapp.client.network.dto

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

    // --- ОБОВ'ЯЗКОВО ДОДАЙ ЦЕ ---
    val serviceIds: List<Long>? = null, // Список послуг, які повернув сервер
    val comment: String? = null         // Коментар, який зберіг сервер
)