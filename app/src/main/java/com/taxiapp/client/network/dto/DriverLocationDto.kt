package com.taxiapp.client.network.dto

data class DriverLocationDto(
    val driverId: Long,
    val fullName: String,
    val lat: Double,
    val lng: Double,
    val bearing: Float,
    val status: String,
    val isOnline: Boolean,
    val carModel: String,
    val carColor: String
)

data class ClientLocationRequest(
    val clientId: String,
    val lat: Double,
    val lng: Double
)