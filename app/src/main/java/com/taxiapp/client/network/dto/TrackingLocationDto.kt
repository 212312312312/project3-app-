package com.taxiapp.client.network.dto

import com.google.gson.annotations.SerializedName

data class TrackingLocationDto(
    @SerializedName("lat") val lat: Double,
    @SerializedName("lng") val lng: Double,
    @SerializedName("bearing") val bearing: Float = 0f
)