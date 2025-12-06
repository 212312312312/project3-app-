package com.taxiapp.client.network.dto

// DTO для перевірки коду
data class SmsVerifyDto(
    val phoneNumber: String,
    val code: String
)