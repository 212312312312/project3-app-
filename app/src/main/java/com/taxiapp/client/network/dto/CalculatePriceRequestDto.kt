package com.taxiapp.client.network.dto

data class CalculatePriceRequestDto(
    val googleRoutePolyline: String,
    val distanceMeters: Int
)