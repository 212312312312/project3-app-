package com.taxiapp.client.analytics

import android.util.Log
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.dto.ClientEventBatchRequest
import com.taxiapp.client.network.dto.CustomEventDto
import com.taxiapp.client.network.dto.ScreenEventDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

object AnalyticsManager {
    private const val TAG = "AnalyticsManager"
    private const val BATCH_THRESHOLD = 10
    private const val MAX_BUFFER_SIZE = 100

    // Унікальна сесія при запуску додатку
    val sessionId: String = UUID.randomUUID().toString()

    // UTM-мітки з Deep Link у SplashActivity
    var utmSource: String? = null
    var utmMedium: String? = null
    var utmCampaign: String? = null

    private val customEventBuffer = mutableListOf<CustomEventDto>()
    private val eventBuffer = mutableListOf<ScreenEventDto>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun trackScreenDuration(screenName: String, durationSeconds: Long) {
        if (durationSeconds < 1) return

        if (eventBuffer.size >= MAX_BUFFER_SIZE) {
            eventBuffer.removeAt(0)
        }
        eventBuffer.add(ScreenEventDto(screenName, durationSeconds))

        if (eventBuffer.size + customEventBuffer.size >= BATCH_THRESHOLD) {
            flushEvents()
        }
    }

    @Synchronized
    fun trackCustomEvent(eventName: String, eventValue: String? = null) {
        if (customEventBuffer.size >= MAX_BUFFER_SIZE) {
            customEventBuffer.removeAt(0)
        }
        customEventBuffer.add(CustomEventDto(eventName, eventValue))

        if (eventBuffer.size + customEventBuffer.size >= BATCH_THRESHOLD) {
            flushEvents()
        }
    }

    @Synchronized
    private fun restoreFailedBatch(
        failedScreens: List<ScreenEventDto>,
        failedCustom: List<CustomEventDto>
    ) {
        if (eventBuffer.size + failedScreens.size <= MAX_BUFFER_SIZE) {
            eventBuffer.addAll(0, failedScreens)
        }
        if (customEventBuffer.size + failedCustom.size <= MAX_BUFFER_SIZE) {
            customEventBuffer.addAll(0, failedCustom)
        }
    }

    @Synchronized
    fun flushEvents() {
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
            customEvents = customEventsToSend
        )

        scope.launch {
            try {
                val response = ApiClient.instance.sendAnalyticsEvents(request)
                if (response.isSuccessful) {
                    if (com.taxiapp.client.BuildConfig.DEBUG) {
                        Log.d(TAG, "Analytics batch sent. Screens: ${eventsToSend.size}, Actions: ${customEventsToSend.size}")
                    }
                } else {
                    Log.w(TAG, "Analytics batch rejected (${response.code()}), re-queueing")
                    if (response.code() >= 500) {
                        restoreFailedBatch(eventsToSend, customEventsToSend)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Network error flushing analytics packet, re-queueing", e)
                restoreFailedBatch(eventsToSend, customEventsToSend)
            }
        }
    }

    fun trackAppOpen(userId: Long, deviceId: String) {
        trackCustomEvent("app_open", "user:$userId,device:$deviceId")
    }

    fun trackUserRegistered(userId: Long, source: String?) {
        trackCustomEvent("user_registered", "user:$userId,source:${source ?: "organic"}")
        flushEvents()
    }

    fun trackOrderFailed(orderId: String, reason: String, searchDurationSec: Long) {
        trackCustomEvent("order_failed", "order:$orderId,reason:$reason,duration:${searchDurationSec}s")
        flushEvents()
    }

    fun trackBoostClicked(orderId: String, boostAmount: Double, timeSinceCreationSec: Long) {
        trackCustomEvent("boost_clicked", "order:$orderId,boost:+$boostAmount,sec:$timeSinceCreationSec")
    }

    fun trackOrderAccepted(orderId: String, driverSource: String, searchDurationSec: Long) {
        trackCustomEvent("order_accepted", "order:$orderId,source:$driverSource,duration:${searchDurationSec}s")
    }

    fun trackOrderCompleted(orderId: String, finalPrice: Double, orderNumber: Int) {
        trackCustomEvent("order_completed", "order:$orderId,price:$finalPrice,ride_num:$orderNumber")
        flushEvents()
    }

    fun trackOrderCreated(orderId: String, district: String?, basePrice: Double) {
        trackCustomEvent("order_created", "order:$orderId,district:${district ?: "city"},base:$basePrice")
        flushEvents()
    }

    fun trackPushDiscountSent(userId: Long, triggerType: String) {
        trackCustomEvent("push_discount_sent", "user:$userId,trigger:$triggerType")
    }
}