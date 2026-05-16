package com.taxiapp.client.network

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Observer
import com.google.gson.Gson
import com.taxiapp.client.network.dto.ChatMessageDto
import com.taxiapp.client.network.dto.TaxiOrderDto
import com.taxiapp.client.network.dto.TrackingLocationDto
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import ua.naiksoftware.stomp.Stomp
import ua.naiksoftware.stomp.StompClient
import ua.naiksoftware.stomp.dto.LifecycleEvent

data class OrderSocketMessageDto(
    val action: String,
    val orderId: Long,
    val order: TaxiOrderDto?
)

class WebSocketManager(private val baseUrl: String) {

    private var stompClient: StompClient? = null
    private val compositeDisposable = CompositeDisposable()
    private val gson = Gson()

    // --- НОВОЕ: Храним активные подписки для авто-восстановления ---
    private var currentChatSub: Pair<Long, (ChatMessageDto) -> Unit>? = null
    private var currentLocationSub: Pair<Long, (TrackingLocationDto) -> Unit>? = null
    private var currentOrderSub: Pair<Long, (OrderSocketMessageDto) -> Unit>? = null

    private var lastKnownToken: String? = null

    // НОВОЕ: Слушаем обновление токена из нашего AuthInterceptor
    private val tokenObserver = Observer<String> { newToken ->
        if (lastKnownToken != newToken && stompClient != null) {
            Log.d("WebSocketManager", "Token refreshed in background! Reconnecting WS...")
            reconnect(newToken)
        }
    }

    init {
        // Подписываемся на шину событий (Обязательно в главном потоке)
        Handler(Looper.getMainLooper()).post {
            ServerStatusBus.tokenRefreshed.observeForever(tokenObserver)
        }
    }

    private val wsUrl: String
        get() {
            val cleanBase = baseUrl
                .replace("http://", "ws://")
                .replace("https://", "wss://")
                .substringBefore("api/v1")
            return "${cleanBase}ws"
        }

    fun connect(token: String?) {
        if (stompClient != null && stompClient!!.isConnected) return

        lastKnownToken = token
        stompClient = Stomp.over(Stomp.ConnectionProvider.OKHTTP, wsUrl)

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

    // --- НОВОЕ: Метод тихого переподключения ---
    private fun reconnect(newToken: String) {
        // Отключаем старый сокет, но НЕ удаляем переменные подписок
        stompClient?.disconnect()
        compositeDisposable.clear()
        stompClient = null

        // Подключаемся с новым токеном
        connect(newToken)

        // Автоматически восстанавливаем все подписки
        currentChatSub?.let { subscribeToChat(it.first, it.second) }
        currentLocationSub?.let { subscribeToDriverLocation(it.first, it.second) }
        currentOrderSub?.let { subscribeToClientOrders(it.first, it.second) }
    }

    fun subscribeToDriverLocation(orderId: Long, onLocationReceived: (TrackingLocationDto) -> Unit) {
        currentLocationSub = Pair(orderId, onLocationReceived) // Запоминаем подписку
        val topic = "/topic/order/$orderId/tracking"

        val disp = stompClient?.topic(topic)
            ?.subscribeOn(Schedulers.io())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({ topicMessage ->
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

        if (disp != null) compositeDisposable.add(disp)
    }

    @SuppressLint("CheckResult")
    fun subscribeToChat(orderId: Long, onMessageReceived: (ChatMessageDto) -> Unit) {
        currentChatSub = Pair(orderId, onMessageReceived) // Запоминаем подписку
        val topic = "/topic/chat/$orderId"

        val disp = stompClient?.topic(topic)
            ?.subscribeOn(Schedulers.io())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({ topicMessage ->
                try {
                    val message = gson.fromJson(topicMessage.payload, ChatMessageDto::class.java)
                    onMessageReceived(message)
                } catch (e: Exception) {
                    Log.e("WebSocketManager", "Error parsing chat message: ${e.message}")
                }
            }, { error ->
                Log.e("WebSocketManager", "Chat subscription error", error)
            })

        if (disp != null) compositeDisposable.add(disp)
    }

    @SuppressLint("CheckResult")
    fun subscribeToClientOrders(clientId: Long, onOrderUpdated: (OrderSocketMessageDto) -> Unit) {
        currentOrderSub = Pair(clientId, onOrderUpdated) // Запоминаем подписку
        val topic = "/topic/clients/$clientId/orders"

        val disp = stompClient?.topic(topic)
            ?.subscribeOn(Schedulers.io())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({ topicMessage ->
                try {
                    val message = gson.fromJson(topicMessage.payload, OrderSocketMessageDto::class.java)
                    onOrderUpdated(message)
                } catch (e: Exception) {
                    Log.e("WebSocketManager", "Error parsing order message: ${e.message}")
                }
            }, { error ->
                Log.e("WebSocketManager", "Client orders subscription error", error)
            })

        if (disp != null) compositeDisposable.add(disp)
    }

    fun disconnect() {
        stompClient?.disconnect()
        compositeDisposable.clear()
        stompClient = null

        // Очищаем подписки только при явном дисконнекте (например, логаут)
        currentChatSub = null
        currentLocationSub = null
        currentOrderSub = null
    }

    // Желательно вызывать при уничтожении компонента, который держит WebSocketManager
    fun destroy() {
        disconnect()
        Handler(Looper.getMainLooper()).post {
            ServerStatusBus.tokenRefreshed.removeObserver(tokenObserver)
        }
    }
}