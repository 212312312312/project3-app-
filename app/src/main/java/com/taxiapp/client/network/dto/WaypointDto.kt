package com.taxiapp.client.network.dto

import com.google.gson.annotations.SerializedName

data class WaypointDto(
    @SerializedName("address") val address: String,
    @SerializedName("lat") val lat: Double,
    @SerializedName("lng") val lng: Double
)