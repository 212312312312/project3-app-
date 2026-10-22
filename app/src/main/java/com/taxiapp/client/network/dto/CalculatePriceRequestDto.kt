package com.taxiapp.client.network.dto

data class CalculatePriceRequestDto(
    val googleRoutePolyline: String,
    val distanceMeters: Int,
    val waypointsCount: Int = 0,
    val waypoints: List<String>? = null,
    val originLat: Double? = null,
    val originLng: Double? = null
)