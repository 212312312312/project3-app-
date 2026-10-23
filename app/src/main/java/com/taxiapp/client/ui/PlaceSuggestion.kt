package com.taxiapp.client.ui

data class PlaceSuggestion(
    val placeId: String,
    val title: String,
    val subtitle: String,
    val distanceMeters: Int? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val category: String? = null
)