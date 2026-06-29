package com.taxiapp.client.analytics

import android.util.Log
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.dto.ClientEventBatchRequest
import com.taxiapp.client.network.dto.ScreenEventDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

object AnalyticsManager {
    private const val TAG = "AnalyticsManager"

    // Уникальная сессия при запуске приложения
    val sessionId: String = UUID.randomUUID().toString()

    // Сюда можно засетить UTM при парсинге Интента/Диплинка в SplashActivity
    var utmSource: String? = null
    var utmMedium: String? = null
    var utmCampaign: String? = null

    private val eventBuffer = mutableListOf<ScreenEventDto>()
    private val scope = CoroutineScope(Dispatchers.IO)

    @Synchronized
    fun trackScreenDuration(screenName: String, durationSeconds: Long) {
        if (durationSeconds < 1) return // Пропускаем мгновенные скачки экранов

        eventBuffer.add(ScreenEventDto(screenName, durationSeconds))
        Log.d(TAG, "Buffered: $screenName for $durationSeconds sec")

        // Отправляем батч пачкой по 5 событий
        if (eventBuffer.size >= 5) {
            flushEvents()
        }
    }

    @Synchronized
    fun flushEvents() {
        if (eventBuffer.isEmpty()) return

        val eventsToSend = ArrayList(eventBuffer)
        eventBuffer.clear()

        val request = ClientEventBatchRequest(
            sessionId = sessionId,
            utmSource = utmSource,
            utmMedium = utmMedium,
            utmCampaign = utmCampaign,
            events = eventsToSend
        )

        scope.launch {
            try {
                val response = ApiClient.instance.sendAnalyticsEvents(request).execute()
                if (response.isSuccessful) {
                    Log.d(TAG, "Analytics batch sent successfully. Size: ${eventsToSend.size}")
                } else {
                    Log.e(TAG, "Failed to send analytics: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error flushing analytics packet", e)
            }
        }
    }
}