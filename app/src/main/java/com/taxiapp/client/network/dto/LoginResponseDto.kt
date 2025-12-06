package com.taxiapp.client.network.dto

data class LoginResponseDto(
    val token: String,
    val userId: Long,
    val phoneNumber: String,
    val fullName: String,
    val role: String,
    val isNewUser: Boolean // <-- Додано
)