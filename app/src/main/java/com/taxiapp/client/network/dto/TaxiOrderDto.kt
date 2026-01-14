package com.taxiapp.client.network.dto

import com.google.gson.annotations.SerializedName

// 1. Додаємо цей маленький клас, щоб Android знав, як виглядає послуга
data class OrderServiceDto(
    val id: Long,
    val name: String,
    val price: Double
)

// 2. Оновлюємо основний клас, зберігаючи твою структуру
data class TaxiOrderDto(
    val id: Long,
    val client: Any?, // Залишаємо Any?, як у тебе було
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

    // Старі поля (залишаємо)
    val serviceIds: List<Long>? = null,
    val comment: String? = null,

    // !!! НОВЕ ПОЛЕ, ЯКЕ ВИКЛИКАЛО ПОМИЛКУ !!!
    // Тепер HomeActivity побачить цей список
    @SerializedName("services")
    val services: List<OrderServiceDto> = emptyList()
)