package com.taxiapp.client.network.dto

data class OrderDriverDto(
    val id: Long,
    val fullName: String,
    val phoneNumber: String,
    val carModel: String?,
    val carColor: String?,
    val carPlateNumber: String?,
    val photoUrl: String?,

    // Новые поля
    val completedRides: Int,
    val monthsInService: Int
)