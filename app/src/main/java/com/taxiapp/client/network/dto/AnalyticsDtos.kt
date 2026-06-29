package com.taxiapp.client.network.dto

data class ClientEventBatchRequest(
    val sessionId: String,
    val utmSource: String?,
    val utmMedium: String?,
    val utmCampaign: String?,
    val events: List<ScreenEventDto>
)

data class ScreenEventDto(
    val screenName: String,
    val durationSeconds: Long
)