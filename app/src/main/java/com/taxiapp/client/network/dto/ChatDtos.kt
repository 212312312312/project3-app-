package com.taxiapp.client.network.dto

data class ChatMessageDto(
    val id: Long?,
    val orderId: Long,
    val senderRole: String, // "CLIENT" или "DRIVER"
    val senderId: Long,
    val content: String,
    val createdAt: String
)

data class SendMessageRequest(
    val content: String
)