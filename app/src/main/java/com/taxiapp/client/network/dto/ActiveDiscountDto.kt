package com.taxiapp.client.network.dto

import com.google.gson.annotations.SerializedName

data class ActiveDiscountDto(
    @SerializedName("percent")
    val percent: Double,

    // Используем alternate, чтобы ловить любые варианты названия поля от сервера
    @SerializedName("maxAmount", alternate = ["maxDiscountAmount", "max_discount_amount", "max_amount"])
    val maxAmount: Double? = null
)