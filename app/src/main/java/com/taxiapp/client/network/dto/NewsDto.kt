package com.taxiapp.client.network.dto

data class NewsDto(
    val id: Long,
    val title: String,
    val content: String,
    val date: String
)