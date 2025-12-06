package com.taxiapp.client.network.dto

import com.google.gson.annotations.SerializedName

// DTO для ответа Google Directions
data class DirectionsResponse(
    val routes: List<Route>
)

data class Route(
    @SerializedName("overview_polyline")
    val overviewPolyline: Polyline,
    // НОВЫЙ БЛОК: Получаем дистанцию
    val legs: List<Leg>
)

data class Leg(
    val distance: Distance
)

data class Distance(
    val text: String, // "22.5 km"
    @SerializedName("value")
    val meters: Int // 22500 (в метрах)
)

data class Polyline(
    val points: String
)