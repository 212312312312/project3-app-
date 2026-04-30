package com.taxiapp.client.network.dto

data class LoginResponseDto(
    val token: String,
    val refreshToken: String?, // <-- ДОБАВЛЕНО: Теперь мы принимаем Refresh Token от сервера
    val userId: Long,
    val phoneNumber: String,
    val fullName: String,
    val role: String,
    val isNewUser: Boolean
)