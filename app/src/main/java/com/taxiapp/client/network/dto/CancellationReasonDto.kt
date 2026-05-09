package com.taxiapp.client.network.dto

data class CancellationReasonDto(
    val id: Long,
    val reasonText: String,
    val penaltyScore: Int,
    val isActive: Boolean,
    val target: String
)