package com.taxiapp.client.network.dto

import com.google.gson.annotations.SerializedName

data class RoutePointDto(
    val lat: Double,
    val lng: Double
)

data class RouteRequestDto(
    val originLat: Double,
    val originLng: Double,
    val destLat: Double,
    val destLng: Double,
    val waypoints: List<RoutePointDto>? = null
)

data class RouteResponseDto(
    val polyline: String,
    val distanceMeters: Int,
    val durationSeconds: Int
)

data class ReverseGeocodeResponseDto(
    val address: String,
    val rawDisplayName: String? = null,
    val source: String? = null
)

data class NearbyPlaceSuggestionDto(
    val placeId: String,
    val title: String,
    val subtitle: String,
    val lat: Double,
    val lng: Double,
    val distanceMeters: Int? = null,
    val category: String? = null
)

// Legacy DTOs (оставлены для обратной совместимости)
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
    val duration: Duration
)

data class Distance(
    val text: String,
    @SerializedName("value")
    val meters: Int
)

data class Duration(
    val text: String,
    @SerializedName("value")
    val seconds: Int
)