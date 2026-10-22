package com.taxiapp.client.network

import com.taxiapp.client.network.dto.RoutePointDto
import com.taxiapp.client.network.dto.RouteRequestDto
import com.taxiapp.client.network.dto.RouteResponseDto
import retrofit2.Response

/**
 * Прокси-клиент маршрутизации на базе Kotlin Coroutines (suspend).
 * Все маршруты строятся через серверный эндпоинт POST /api/v1/public/route (OSRM + Redis кэш).
 */
object DirectionsApiClient {

    suspend fun calculateRoute(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        waypoints: List<RoutePointDto>? = null
    ): Response<RouteResponseDto> {
        val request = RouteRequestDto(
            originLat = originLat,
            originLng = originLng,
            destLat = destLat,
            destLng = destLng,
            waypoints = waypoints?.takeIf { it.isNotEmpty() }
        )
        return ApiClient.instance.calculateRoute(request)
    }
}