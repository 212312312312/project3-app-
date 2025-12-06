package com.taxiapp.client.ui

data class PlaceSuggestion(
    val placeId: String,
    val title: String,
    val subtitle: String,
    val distanceMeters: Int? = null
)