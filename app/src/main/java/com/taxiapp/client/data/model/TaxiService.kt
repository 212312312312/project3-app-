package com.taxiapp.client.data.model

data class TaxiService(
    val id: Long,
    val name: String,
    val price: Double,
    // Поле isSelected должно иметь значение по умолчанию,
    // так как сервер его НЕ присылает!
    var isSelected: Boolean = false
)