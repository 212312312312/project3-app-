package com.taxiapp.client.network.dto

import com.google.gson.annotations.SerializedName // <-- Додайте імпорт

data class CarTariffDto(
    val id: Long,
    val name: String,
    val basePrice: Double,
    val pricePerKm: Double,

    @SerializedName("imageUrl") // <-- Кажемо: "Сервер надсилає imageUrl"
    val iconUrl: String? = null // ...але ми в коді називаємо це iconUrl
)