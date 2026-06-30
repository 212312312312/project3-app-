package com.taxiapp.client.network

import android.annotation.SuppressLint
import com.taxiapp.client.network.dto.ClientLocationRequest
import android.os.Handler
import com.taxiapp.client.network.dto.DriverLocationDto
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LifecycleOwner
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
    val orderId: String,
    val order: TaxiOrderDto?
)
data class CardBindSocketMessageDto(
    val status: String,
    val cardMask: String?,
    val message: String?
)

// ИСПРАВЛЕНО: Добавили LifecycleOwner для безопасной подписки без утечек памяти
class WebSocketManager(private val baseUrl: String, private val lifecycleOwner: androidx.lifecycle.LifecycleOwner) {

    private var currentCardBindSub: Pair<Long, (CardBindSocketMessageDto) -> Unit>? = null
    private var nearbyDriversDisposable: io.reactivex.disposables.Disposable? = null
    private var driverTrackingDisposable: io.reactivex.disposables.Disposable? = null

    // ИСПРАВЛЕНО: Добавили disposable для управления топиком заказов клиента
    private var clientOrdersDisposable: io.reactivex.disposables.Disposable? = null

    private var currentNearbyDriversSub: Pair<String, (List<DriverLocationDto>) -> Unit>? = null
    private var stompClient: StompClient? = null
    private val compositeDisposable = CompositeDisposable()
    private val gson = Gson()

    private var currentChatSub: Pair<String, (ChatMessageDto) -> Unit>? = null
    private var currentLocationSub: Pair<String, (TrackingLocationDto) -> Unit>? = null
    private var currentOrderSub: Pair<Long, (OrderSocketMessageDto) -> Unit>? = null

    private var lastKnownToken: String? = null

    private val tokenObserver = Observer<String> { newToken ->
        if (lastKnownToken != newToken && stompClient != null) {
            Log.d("WebSocketManager", "Token refreshed in background! Reconnecting WS...")
            reconnect(newToken)
        }
    }

    init {
        // ИСПРАВЛЕНО: Вместо observeForever привязываемся к реальному жизненному циклу приложения/экрана
        Handler(Looper.getMainLooper()).post {
            ServerStatusBus.tokenRefreshed.observe(lifecycleOwner, tokenObserver)
        }
    }

    private val wsUrl: String
        get() {
            val cleanBase = baseUrl
                .replace("http://", "ws://")
                .replace("https://", "wss://")
                .substringBefore("api/v1")
            return "${cleanBase}ws-taxi/websocket"
        }

    private fun restoreSubscriptions() {
        Log.d("WS_TAXI_DEBUG", "🔄 Восстанавливаем подписки после открытия сокета...")
        currentChatSub?.let { subscribeToChat(it.first, it.second) }
        currentLocationSub?.let { subscribeToDriverLocation(it.first, it.second) }
        currentOrderSub?.let { subscribeToClientOrders(it.first, it.second) }
        currentNearbyDriversSub?.let { subscribeToNearbyDrivers(it.first, it.second) }
        currentCardBindSub?.let { subscribeToCardBinding(it.first, it.second) }
    }

    @SuppressLint("CheckResult")
    fun subscribeToNearbyDrivers(clientId: String, onDriversReceived: (List<DriverLocationDto>) -> Unit) {
        currentNearbyDriversSub = Pair(clientId, onDriversReceived)
        if (stompClient?.isConnected == true) {
            nearbyDriversDisposable?.dispose()

            val topic = "/topic/nearby-drivers/$clientId"
            nearbyDriversDisposable = stompClient?.topic(topic)
                ?.subscribeOn(Schedulers.io())
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe({ topicMessage ->
                    try {
                        val listType = object : com.google.gson.reflect.TypeToken<List<DriverLocationDto>>() {}.type
                        val drivers: List<DriverLocationDto> = gson.fromJson(topicMessage.payload, listType)
                        onDriversReceived(drivers)
                    } catch (e: Exception) {
                        Log.e("WS_TAXI_DEBUG", "Error parsing nearby drivers: ${e.message}")
                    }
                }, { error ->
                    Log.e("WS_TAXI_DEBUG", "Nearby drivers subscription error", error)
                })
        }
    }

    fun unsubscribeFromNearbyDrivers() {
        nearbyDriversDisposable?.dispose()
        nearbyDriversDisposable = null
        currentNearbyDriversSub = null
        Log.d("WS_TAXI_DEBUG", "🛑 Успешно отписались от топика свободных водителей")
    }

    fun sendClientLocation(request: ClientLocationRequest) {
        if (!isConnected()) return
        val jsonPayload = gson.toJson(request)
        stompClient?.send("/app/client/location", jsonPayload)
            ?.subscribeOn(Schedulers.io())
            ?.subscribe({}, { err -> Log.e("WS_TAXI_DEBUG", "❌ Ошибка отправки координат", err) })
            ?.let { compositeDisposable.add(it) }
    }

    fun isConnected(): Boolean = stompClient != null && stompClient!!.isConnected

    fun connect(token: String?) {
        if (stompClient != null && stompClient!!.isConnected) return

        val activeToken = token ?: ApiClient.sessionManager?.fetchAuthToken()
        lastKnownToken = activeToken

        stompClient = Stomp.over(Stomp.ConnectionProvider.OKHTTP, wsUrl)

        if (!activeToken.isNullOrEmpty()) {
            val authHeaders = listOf(ua.naiksoftware.stomp.dto.StompHeader("Authorization", "Bearer $activeToken"))
            stompClient?.connect(authHeaders)
        } else {
            stompClient?.connect()
        }

        val disp = stompClient!!.lifecycle()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { lifecycleEvent ->
                when (lifecycleEvent.type) {
                    LifecycleEvent.Type.OPENED -> {
                        Log.d("WS_TAXI_DEBUG", "✅ [LIFECYCLE] Сокет УСПЕШНО ОТКРЫТ!")
                        restoreSubscriptions()
                    }
                    LifecycleEvent.Type.ERROR -> Log.e("WS_TAXI_DEBUG", "❌ [LIFECYCLE] Ошибка сокета", lifecycleEvent.exception)
                    else -> {}
                }
            }
        compositeDisposable.add(disp)
    }

    private fun reconnect(newToken: String) {
        stompClient?.disconnect()
        compositeDisposable.clear()
        stompClient = null
        connect(newToken)
    }

    fun subscribeToDriverLocation(orderId: String, onLocationReceived: (TrackingLocationDto) -> Unit) {
        currentLocationSub = Pair(orderId, onLocationReceived)
        val topic = "/topic/order/$orderId/tracking"
        driverTrackingDisposable?.dispose()

        driverTrackingDisposable = stompClient?.topic(topic)
            ?.subscribeOn(Schedulers.io())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({ topicMessage ->
                val location = gson.fromJson(topicMessage.payload, TrackingLocationDto::class.java)
                onLocationReceived(location)
            }, { Log.e("WebSocket", "Tracking Error", it) })
    }

    fun unsubscribeFromDriverLocation() {
        driverTrackingDisposable?.dispose()
        driverTrackingDisposable = null
        currentLocationSub = null
    }

    @SuppressLint("CheckResult")
    fun subscribeToChat(orderId: String, onMessageReceived: (ChatMessageDto) -> Unit) {
        currentChatSub = Pair(orderId, onMessageReceived)
        val topic = "/topic/chat/$orderId"
        val disp = stompClient?.topic(topic)
            ?.subscribeOn(Schedulers.io())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({ topicMessage ->
                val message = gson.fromJson(topicMessage.payload, ChatMessageDto::class.java)
                onMessageReceived(message)
            }, { Log.e("WebSocketManager", "Chat error", it) })
        if (disp != null) compositeDisposable.add(disp)
    }

    @SuppressLint("CheckResult")
    fun subscribeToClientOrders(clientId: Long, onOrderUpdated: (OrderSocketMessageDto) -> Unit) {
        currentOrderSub = Pair(clientId, onOrderUpdated)

        if (stompClient?.isConnected == true) {
            // ИСПРАВЛЕНО: Перед созданием новой подписки очищаем предыдущую
            clientOrdersDisposable?.dispose()

            val topic = "/topic/clients/$clientId/orders"
            clientOrdersDisposable = stompClient?.topic(topic)
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
        }
    }

    // ИСПРАВЛЕНО: Метод для ручной отписки от топика заказов (чтобы не копить темы)
    fun unsubscribeFromClientOrders() {
        clientOrdersDisposable?.dispose()
        clientOrdersDisposable = null
        currentOrderSub = null
        Log.d("WS_TAXI_DEBUG", "🛑 Успешно отписались от топика активных заказов клиента")
    }

    @SuppressLint("CheckResult")
    fun subscribeToCardBinding(clientId: Long, onCardBindUpdated: (CardBindSocketMessageDto) -> Unit) {
        currentCardBindSub = Pair(clientId, onCardBindUpdated)
        val topic = "/topic/clients/$clientId/card-bind"
        val disp = stompClient?.topic(topic)
            ?.subscribeOn(Schedulers.io())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({ topicMessage ->
                val message = gson.fromJson(topicMessage.payload, CardBindSocketMessageDto::class.java)
                onCardBindUpdated(message)
            }, { Log.e("WebSocketManager", "Card bind error", it) })
        if (disp != null) compositeDisposable.add(disp)
    }

    fun disconnect() {
        stompClient?.disconnect()
        compositeDisposable.clear()
        stompClient = null

        nearbyDriversDisposable?.dispose()
        nearbyDriversDisposable = null

        // ИСПРАВЛЕНО: Очищаем disposable заказов при общем отключении
        clientOrdersDisposable?.dispose()
        clientOrdersDisposable = null

        currentChatSub = null
        currentLocationSub = null
        currentOrderSub = null
        currentCardBindSub = null
        driverTrackingDisposable?.dispose()
        driverTrackingDisposable = null
    }

    fun destroy() {
        disconnect()
        // Нам больше не нужно принудительно удалять observer, LiveData сделает это сама благодаря LifecycleOwner
    }
}