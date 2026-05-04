package com.taxiapp.client.network.dto

data class LoginResponseDto(
    val token: String,
    val refreshToken: String?,
    val userId: Long,
    val phoneNumber: String,
    val fullName: String,
    val role: String,
    val isNewUser: Boolean,
    val cardMask: String? // <-- ДОБАВЛЕНО: Маска привязанной карты (напр. 4149****1234)
)