package com.taxiapp.client.network.dto

import com.google.gson.annotations.SerializedName

data class CarTariffDto(
    val id: Long,
    val name: String,
    val basePrice: Double,
    val pricePerKm: Double,

    // ДОБАВИТЬ ЭТО ПОЛЕ (оно есть на сервере и нужно для UI):
    val pricePerWaitingMinute: Double = 0.0,
    val description: String? = null,

    @SerializedName("imageUrl")
    val iconUrl: String? = null
)