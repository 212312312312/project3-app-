package com.taxiapp.client.network

import android.util.Log
import ua.naiksoftware.stomp.Stomp
import ua.naiksoftware.stomp.StompClient
import ua.naiksoftware.stomp.dto.LifecycleEvent
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import android.annotation.SuppressLint
import com.taxiapp.client.network.dto.ChatMessageDto
import com.google.gson.Gson
import com.taxiapp.client.network.dto.TrackingLocationDto

class WebSocketManager(private val baseUrl: String) {

    private var stompClient: StompClient? = null
    private val compositeDisposable = CompositeDisposable()
    private val gson = Gson()

    // Преобразуем HTTP URL в WS URL (http://... -> ws://.../ws)
    private val wsUrl: String
        get() {
            // Берем базовый URL (например http://192.168.0.104:8080/api/v1/)
            // Убираем "api/v1/" и меняем протокол на ws
            val cleanBase = baseUrl
                .replace("http://", "ws://")
                .replace("https://", "wss://")
                .substringBefore("api/v1")

            // Получаем ws://192.168.0.104:8080/ws
            return "${cleanBase}ws"
        }

    fun connect(token: String?) {
        if (stompClient != null && stompClient!!.isConnected) return

        stompClient = Stomp.over(Stomp.ConnectionProvider.OKHTTP, wsUrl)

        // Добавляем токен авторизации (если он есть)
        val headers = mutableListOf<ua.naiksoftware.stomp.dto.StompHeader>()
        if (token != null) {
            headers.add(ua.naiksoftware.stomp.dto.StompHeader("Authorization", "Bearer $token"))
        }

        stompClient?.connect(headers)

        val disp = stompClient!!.lifecycle()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { lifecycleEvent ->
                when (lifecycleEvent.type) {
                    LifecycleEvent.Type.OPENED -> Log.d("WebSocket", "Stomp connection opened")
                    LifecycleEvent.Type.ERROR -> Log.e("WebSocket", "Error", lifecycleEvent.exception)
                    LifecycleEvent.Type.CLOSED -> Log.d("WebSocket", "Stomp connection closed")
                    else -> {}
                }
            }
        compositeDisposable.add(disp)
    }

    fun subscribeToDriverLocation(orderId: Long, onLocationReceived: (TrackingLocationDto) -> Unit) {
        val topic = "/topic/order/$orderId/tracking"

        val disp = stompClient!!.topic(topic)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ topicMessage ->
                try {
                    val payload = topicMessage.payload
                    val location = gson.fromJson(payload, TrackingLocationDto::class.java)
                    onLocationReceived(location)
                } catch (e: Exception) {
                    Log.e("WebSocket", "Json Parse Error", e)
                }
            }, { throwable ->
                Log.e("WebSocket", "Subscription Error", throwable)
            })

        compositeDisposable.add(disp)
    }

    @SuppressLint("CheckResult")
    fun subscribeToChat(orderId: Long, onMessageReceived: (ChatMessageDto) -> Unit) {
        val topic = "/topic/chat/$orderId"
        Log.d("WebSocketManager", "Subscribing to chat: $topic")

        val client = stompClient ?: return // Защита от Null

        val disp = client.topic(topic)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ topicMessage ->
                try {
                    val message = gson.fromJson(topicMessage.payload, ChatMessageDto::class.java)
                    onMessageReceived(message)
                } catch (e: Exception) {
                    Log.e("WebSocketManager", "Error parsing chat message: ${e.message}")
                }
            }, { error ->
                Log.e("WebSocketManager", "Chat subscription error", error)
            })

        compositeDisposable.add(disp)
    }

    fun disconnect() {
        stompClient?.disconnect()
        compositeDisposable.clear()
        stompClient = null
    }
}