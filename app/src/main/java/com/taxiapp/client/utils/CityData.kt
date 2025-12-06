package com.taxiapp.client.utils

// Простий клас для зберігання даних про місто у пам'яті
data class CityData(
    val name: String,
    val lat: Double,
    val lng: Double,
    val zoom: Float
)