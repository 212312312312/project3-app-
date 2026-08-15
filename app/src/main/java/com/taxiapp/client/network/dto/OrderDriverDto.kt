package com.taxiapp.client.network.dto

import com.google.gson.annotations.SerializedName

data class OrderDriverDto(
    @SerializedName("id")
    val id: Long = -1L,

    @SerializedName("fullName")
    val fullName: String = "",

    @SerializedName("phoneNumber")
    val phoneNumber: String = "",

    @SerializedName("carModel")
    val carModel: String? = null,

    @SerializedName("carColor")
    val carColor: String? = null,

    @SerializedName("carPlateNumber")
    val carPlateNumber: String? = null,

    @SerializedName("photoUrl")
    val photoUrl: String? = null,

    // Рейтинг водителя (из нашей БД или crew_average_rating из СОЗ)
    @SerializedName("rating")
    val rating: Double = 5.0,

    // Статистика
    @SerializedName("completedRides")
    val completedRides: Int = 0,

    @SerializedName("monthsInService")
    val monthsInService: Int = 0,

    // --- Поля локации для отображения авто на карте ---
    @SerializedName("latitude")
    val latitude: Double? = null,

    @SerializedName("longitude")
    val longitude: Double? = null,

    @SerializedName("bearing")
    val bearing: Float? = 0f,

    // --- Медицинская информация / инклюзивность ---
    @SerializedName("hasMovementIssue")
    val hasMovementIssue: Boolean = false,

    @SerializedName("hasHearingIssue")
    val hasHearingIssue: Boolean = false,

    @SerializedName("isDeaf")
    val isDeaf: Boolean = false,

    @SerializedName("hasSpeechIssue")
    val hasSpeechIssue: Boolean = false
)