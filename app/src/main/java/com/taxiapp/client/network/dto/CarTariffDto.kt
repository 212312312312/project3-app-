package com.taxiapp.client.network.dto
import com.google.gson.annotations.SerializedName

data class CarTariffDto(
    val id: Long,
    val name: String,
    val basePrice: Double,
    val pricePerKm: Double,
    val pricePerWaitingMinute: Double = 0.0,
    val description: String? = null,
    @SerializedName("imageUrl") val iconUrl: String? = null,

    // Новое поле для готовой цены от сервера
    var calculatedPrice: Double? = null
)