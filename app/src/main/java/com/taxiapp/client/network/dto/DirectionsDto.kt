package com.taxiapp.client.network.dto

import com.google.gson.annotations.SerializedName

data class DirectionsResponse(
    val routes: List<Route>
)

data class Route(
    @SerializedName("overview_polyline")
    val overviewPolyline: OverviewPolyline,
    val legs: List<Leg>
)

data class OverviewPolyline(
    val points: String
)

data class Leg(
    val distance: Distance,
    val duration: Duration // <-- ДОБАВЛЕНО ЭТО ПОЛЕ
)

data class Distance(
    val text: String,
    @SerializedName("value")
    val meters: Int // Google отдает "value", мы называем "meters"
)

data class Duration(
    val text: String,
    @SerializedName("value")
    val seconds: Int // <-- ВАЖНО: Google отдает "value", мы называем "seconds"
)