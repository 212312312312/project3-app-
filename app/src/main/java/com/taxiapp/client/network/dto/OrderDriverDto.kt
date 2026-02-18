package com.taxiapp.client.network.dto

import com.google.gson.annotations.SerializedName

data class OrderDriverDto(
    val id: Long,
    val fullName: String,
    val phoneNumber: String,
    val carModel: String?,
    val carColor: String?,
    val carPlateNumber: String?,
    val photoUrl: String?,

    // Статистика
    val completedRides: Int,
    val monthsInService: Int,

    // --- ВАЖЛИВО: Поля локації для відображення авто ---
    @SerializedName("latitude")
    val latitude: Double?,

    @SerializedName("longitude")
    val longitude: Double?,

    @SerializedName("bearing")
    val bearing: Float? = 0f,

    // --- НОВЫЕ ПОЛЯ (Медицинская информация) ---
    val hasMovementIssue: Boolean = false,
    val hasHearingIssue: Boolean = false,
    val isDeaf: Boolean = false,
    val hasSpeechIssue: Boolean = false
)