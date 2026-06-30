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
    private val customEventBuffer = mutableListOf<com.taxiapp.client.network.dto.CustomEventDto>()
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
    fun trackCustomEvent(eventName: String, eventValue: String? = null) {
        customEventBuffer.add(com.taxiapp.client.network.dto.CustomEventDto(eventName, eventValue))
        Log.d(TAG, "Buffered Custom Event: $eventName -> $eventValue")
    }

    @Synchronized
    fun flushEvents() {
        // Отправляем, если есть ХОТЯ БЫ одно событие экрана или кастомный клик
        if (eventBuffer.isEmpty() && customEventBuffer.isEmpty()) return

        val eventsToSend = ArrayList(eventBuffer)
        val customEventsToSend = ArrayList(customEventBuffer)

        eventBuffer.clear()
        customEventBuffer.clear()

        val request = ClientEventBatchRequest(
            sessionId = sessionId,
            utmSource = utmSource,
            utmMedium = utmMedium,
            utmCampaign = utmCampaign,
            events = eventsToSend,
            customEvents = customEventsToSend // Прикрепили клики к пакету
        )

        scope.launch {
            try {
                val response = com.taxiapp.client.network.ApiClient.instance.sendAnalyticsEvents(request).execute()
                if (response.isSuccessful) {
                    Log.d(TAG, "Analytics batch sent successfully. Screens: ${eventsToSend.size}, Actions: ${customEventsToSend.size}")
                } else {
                    Log.e(TAG, "Failed to send analytics: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error flushing analytics packet", e)
            }
        }
    }
}