package com.taxiapp.client.network.dto

data class ClientPromoProgressDto(
    val id: Long,
    val title: String,
    val description: String,
    val requiredRides: Int,
    val currentRides: Int,
    val discountPercent: Double,
    val isRewardAvailable: Boolean
)

data class ActiveDiscountDto(
    val percent: Double
)