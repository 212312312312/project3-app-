package com.taxiapp.client.network.dto

import com.google.gson.annotations.SerializedName

data class PromoDto(
    val id: Long,
    val code: String,

    // Це дозволяє читати поле, навіть якщо сервер називає його по-різному
    @SerializedName("percent", alternate = ["discountPercent", "discount_percent"])
    val percent: Double,

    @SerializedName("maxAmount", alternate = ["maxDiscountAmount", "max_discount_amount"])
    val maxAmount: Double? = null,

    val active: Boolean
)
data class ClientPromoProgressDto(
    val id: Long,
    val title: String,
    val description: String,
    val requiredRides: Int,
    val currentRides: Int,
    val discountPercent: Double,
    val isRewardAvailable: Boolean,
    val requiredTariffName: String? = null,
    val isFullyCompleted: Boolean = false,
    val maxDiscountAmount: Double? = null,

    // Нові поля
    val requiredDistanceMeters: Long = 0,
    val currentDistanceMeters: Long = 0,
    val rewardExpiresAt: String? = null
)
