package com.taxiapp.client.network.dto

data class OrderDriverDto(
    val id: Long,
    val fullName: String,
    val phoneNumber: String,
    val carModel: String?,       // Toyota Camry
    val carColor: String?,       // Чорний
    val carPlateNumber: String?  // AA5555AA
)