package com.taxiapp.client.network.dto

data class CalculatePriceRequestDto(
    val googleRoutePolyline: String,
    val distanceMeters: Int,

    // --- НОВІ ПОЛЯ ---
    val waypointsCount: Int = 0,
    val waypoints: List<String>? = null
    // -----------------
)