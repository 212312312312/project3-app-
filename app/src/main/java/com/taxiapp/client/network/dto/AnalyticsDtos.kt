package com.taxiapp.client.network.dto

data class CustomEventDto(
    val eventName: String,
    val eventValue: String?
)

data class ClientEventBatchRequest(
    val sessionId: String,
    val utmSource: String?,
    val utmMedium: String?,
    val utmCampaign: String?,
    val events: List<ScreenEventDto>,
    val customEvents: List<CustomEventDto> = emptyList() // Добавили список действий
)

data class ScreenEventDto(
    val screenName: String,
    val durationSeconds: Long
)