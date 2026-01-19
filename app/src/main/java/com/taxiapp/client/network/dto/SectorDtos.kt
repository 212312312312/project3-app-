package com.taxiapp.client.network.dto

data class PointDto(
    val lat: Double,
    val lng: Double
)

data class SectorDto(
    val id: Long,
    val name: String,
    val isCity: Boolean, // true = Місто, false = За містом
    val points: List<PointDto>
)