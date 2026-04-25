package com.taxiapp.client.network.dto

import com.google.gson.annotations.SerializedName
import java.io.Serializable

data class CarTariffDto(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("basePrice") val basePrice: Double,
    @SerializedName("pricePerKm") val pricePerKm: Double,

    // --- НОВЫЕ ПОЛЯ ---
    @SerializedName("isBeta") val isBeta: Boolean = false,
    @SerializedName("isUnavailable") val isUnavailable: Boolean = false,
    // ------------------

    @SerializedName("pricePerWaitingMinute") val pricePerWaitingMinute: Double = 0.0,
    @SerializedName("imageUrl") val imageUrl: String?,

    @SerializedName("calculatedPrice") val calculatedPrice: Double? = null,
    @SerializedName("description") val description: String? = null
) : Serializable