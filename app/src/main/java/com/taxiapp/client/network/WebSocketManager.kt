package com.taxiapp.client.network

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.taxiapp.client.network.dto.ChatMessageDto
import com.taxiapp.client.network.dto.ClientLocationRequest
import com.taxiapp.client.network.dto.DriverLocationDto
import com.taxiapp.client.network.dto.TaxiOrderDto
import com.taxiapp.client.network.dto.TrackingLocationDto
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import ua.naiksoftware.stomp.Stomp
import ua.naiksoftware.stomp.StompClient
import ua.naiksoftware.stomp.dto.LifecycleEvent
import ua.naiksoftware.stomp.dto.StompHeader
import kotlin.math.min
import kotlin.random.Random

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

class WebSocketManager(
    private val baseUrl: String,
    private val lifecycleOwner: LifecycleOwner
) {

    private var pendingLocationRequest: ClientLocationRequest? = null
    private var currentCardBindSub: Pair<Long, (CardBindSocketMessageDto) -> Unit>? = null
    private var nearbyDriversDisposable: Disposable? = null
    private var driverTrackingDisposable: Disposable? = null
    private var clientOrdersDisposable: Disposable? = null
    private var chatDisposable: Disposable? = null
    private var cardBindDisposable: Disposable? = null

    private var currentNearbyDriversSub: Pair<String, (List<DriverLocationDto>) -> Unit>? = null
    private var stompClient: StompClient? = null
    private val compositeDisposable = CompositeDisposable()
    private val gson = Gson()

    private var currentChatSub: Pair<String, (ChatMessageDto) -> Unit>? = null
    private var currentLocationSub: Pair<String, (TrackingLocationDto) -> Unit>? = null
    private var currentOrderSub: Pair<Long, (OrderSocketMessageDto) -> Unit>? = null

    private var lastKnownToken: String? = null
    private var isManuallyDisconnected = false
    private var isConnecting = false
    private var reconnectAttempt = 0
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable {
        if (!isManuallyDisconnected && !isConnected() && !isConnecting) {
            // Всегда берем самый свежий токен из SessionManager; если он истек — обновляем в фоне перед коннектом
            val currentToken = ApiClient.sessionManager?.fetchAuthToken() ?: lastKnownToken
            if (ApiClient.isJwtExpiredOrExpiringSoon(currentToken, leewaySeconds = 15L)) {
                val disp = io.reactivex.Single.fromCallable {
                    ApiClient.refreshAccessTokenIfNeeded(force = true) ?: ""
                }
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({ refreshedToken ->
                        if (refreshedToken.isNotEmpty()) {
                            connect(refreshedToken)
                        }
                    }, {
                        connect(currentToken)
                    })
                compositeDisposable.add(disp)
            } else {
                connect(currentToken)
            }
        }
    }

    private val tokenObserver = Observer<String> { newToken ->
        if (lastKnownToken != newToken && stompClient != null) {
            Log.d("WebSocketManager", "Token refreshed in background! Reconnecting WS...")
            reconnect(newToken)
        }
    }

    init {
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

    private fun scheduleReconnectWithJitter() {
        if (isManuallyDisconnected) return
        reconnectHandler.removeCallbacks(reconnectRunnable)

        val expDelay = min(30_000L, 1000L * (1L shl min(reconnectAttempt, 5)))
        val jitter = Random.nextLong(250L, 2500L)
        val totalDelay = expDelay + jitter
        reconnectAttempt++

        Log.d("WS_TAXI_DEBUG", "⏳ Reconnecting WebSocket in ${totalDelay}ms (attempt $reconnectAttempt)")
        reconnectHandler.postDelayed(reconnectRunnable, totalDelay)
    }

    private fun restoreSubscriptions() {
        Log.d("WS_TAXI_DEBUG", "🔄 Восстанавливаем подписки после открытия сокета...")
        currentChatSub?.let { subscribeToChat(it.first, it.second, forceResubscribe = true) }
        currentLocationSub?.let { subscribeToDriverLocation(it.first, it.second, forceResubscribe = true) }
        currentOrderSub?.let { subscribeToClientOrders(it.first, it.second, forceResubscribe = true) }
        currentNearbyDriversSub?.let { subscribeToNearbyDrivers(it.first, it.second, forceResubscribe = true) }
        currentCardBindSub?.let { subscribeToCardBinding(it.first, it.second, forceResubscribe = true) }

        pendingLocationRequest?.let { sendClientLocation(it) }
    }

    @SuppressLint("CheckResult")
    fun subscribeToNearbyDrivers(
        clientId: String,
        onDriversReceived: (List<DriverLocationDto>) -> Unit
    ) {
        subscribeToNearbyDrivers(clientId, onDriversReceived, forceResubscribe = false)
    }

    private fun subscribeToNearbyDrivers(
        clientId: String,
        onDriversReceived: (List<DriverLocationDto>) -> Unit,
        forceResubscribe: Boolean
    ) {
        val isSameTopic = currentNearbyDriversSub?.first == clientId
        currentNearbyDriversSub = Pair(clientId, onDriversReceived)

        if (!forceResubscribe && isSameTopic && nearbyDriversDisposable?.isDisposed == false && isConnected()) {
            return
        }

        if (stompClient?.isConnected == true) {
            nearbyDriversDisposable?.dispose()

            val topic = "/topic/nearby-drivers/$clientId"
            nearbyDriversDisposable = stompClient?.topic(topic)
                ?.subscribeOn(Schedulers.io())
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe({ topicMessage ->
                    try {
                        val listType = object : TypeToken<List<DriverLocationDto>>() {}.type
                        val drivers: List<DriverLocationDto> = gson.fromJson(topicMessage.payload, listType)
                        currentNearbyDriversSub?.second?.invoke(drivers)
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
        pendingLocationRequest = request
        if (!isConnected()) return

        val jsonPayload = gson.toJson(request)
        stompClient?.send("/app/client/location", jsonPayload)
            ?.subscribeOn(Schedulers.io())
            ?.subscribe({
                pendingLocationRequest = null
            }, { err ->
                Log.e("WS_TAXI_DEBUG", "❌ Ошибка отправки координат", err)
            })
            ?.let { compositeDisposable.add(it) }
    }

    fun isConnected(): Boolean = stompClient != null && stompClient!!.isConnected

    fun connect(token: String?) {
        if (isConnected() || isConnecting) return

        isManuallyDisconnected = false
        isConnecting = true
        reconnectHandler.removeCallbacks(reconnectRunnable)

        val activeToken = ApiClient.sessionManager?.fetchAuthToken() ?: token
        lastKnownToken = activeToken

        // Если переданный токен уже истек — сначала обновляем его на IO-потоке, чтобы не получать Invalid Token в STOMP
        if (!activeToken.isNullOrEmpty() && ApiClient.isJwtExpiredOrExpiringSoon(activeToken, leewaySeconds = 10L)) {
            val disp = io.reactivex.Single.fromCallable {
                ApiClient.refreshAccessTokenIfNeeded(force = true) ?: activeToken
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ freshToken ->
                    isConnecting = false
                    if (!isManuallyDisconnected) {
                        openStompWithToken(freshToken)
                    }
                }, {
                    isConnecting = false
                    if (!isManuallyDisconnected) {
                        openStompWithToken(activeToken)
                    }
                })
            compositeDisposable.add(disp)
            return
        }
        openStompWithToken(activeToken)
    }

    private fun openStompWithToken(activeToken: String?) {
        isConnecting = true
        lastKnownToken = activeToken

        stompClient?.disconnect()
        compositeDisposable.clear()

        // Використовуємо захищений OkHttpClient з CertificatePinner та Ping-інтервалом
        stompClient = Stomp.over(
            Stomp.ConnectionProvider.OKHTTP,
            wsUrl,
            null,
            ApiClient.webSocketOkHttpClient
        )

        val disp = stompClient!!.lifecycle()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ lifecycleEvent ->
                when (lifecycleEvent.type) {
                    LifecycleEvent.Type.OPENED -> {
                        isConnecting = false
                        reconnectAttempt = 0
                        Log.d("WS_TAXI_DEBUG", "✅ [LIFECYCLE] Сокет УСПЕШНО ОТКРЫТ!")
                        restoreSubscriptions()
                    }
                    LifecycleEvent.Type.ERROR -> {
                        isConnecting = false
                        Log.e("WS_TAXI_DEBUG", "❌ [LIFECYCLE] Ошибка сокета", lifecycleEvent.exception)
                        scheduleReconnectWithJitter()
                    }
                    LifecycleEvent.Type.CLOSED -> {
                        isConnecting = false
                        Log.d("WS_TAXI_DEBUG", "🔌 [LIFECYCLE] Сокет закрыт")
                        if (!isManuallyDisconnected) {
                            scheduleReconnectWithJitter()
                        }
                    }
                    else -> {}
                }
            }, { err ->
                isConnecting = false
                Log.e("WS_TAXI_DEBUG", "❌ [LIFECYCLE] Фатальная ошибка потока lifecycle", err)
                scheduleReconnectWithJitter()
            })
        compositeDisposable.add(disp)

        if (!activeToken.isNullOrEmpty()) {
            val authHeaders = listOf(StompHeader("Authorization", "Bearer $activeToken"))
            stompClient?.connect(authHeaders)
        } else {
            stompClient?.connect()
        }
    }

    private fun reconnect(newToken: String) {
        reconnectHandler.removeCallbacks(reconnectRunnable)
        isConnecting = false
        stompClient?.disconnect()
        compositeDisposable.clear()
        stompClient = null
        connect(newToken)
    }

    fun subscribeToDriverLocation(
        orderId: String,
        onLocationReceived: (TrackingLocationDto) -> Unit
    ) {
        subscribeToDriverLocation(orderId, onLocationReceived, forceResubscribe = false)
    }

    private fun subscribeToDriverLocation(
        orderId: String,
        onLocationReceived: (TrackingLocationDto) -> Unit,
        forceResubscribe: Boolean
    ) {
        val isSameOrder = currentLocationSub?.first == orderId
        currentLocationSub = Pair(orderId, onLocationReceived)

        if (!forceResubscribe && isSameOrder && driverTrackingDisposable?.isDisposed == false && isConnected()) {
            return
        }

        val topic = "/topic/order/$orderId/tracking"
        driverTrackingDisposable?.dispose()

        if (stompClient?.isConnected == true) {
            driverTrackingDisposable = stompClient?.topic(topic)
                ?.subscribeOn(Schedulers.io())
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe({ topicMessage ->
                    try {
                        val location = gson.fromJson(topicMessage.payload, TrackingLocationDto::class.java)
                        if (location != null && location.lat != 0.0 && location.lng != 0.0) {
                            currentLocationSub?.second?.invoke(location)
                        }
                    } catch (e: Exception) {
                        Log.e("WS_TAXI_DEBUG", "Error parsing tracking payload: ${e.message}")
                    }
                }, { Log.e("WS_TAXI_DEBUG", "Tracking Subscription Error", it) })
        }
    }

    fun unsubscribeFromDriverLocation() {
        driverTrackingDisposable?.dispose()
        driverTrackingDisposable = null
        currentLocationSub = null
    }

    @SuppressLint("CheckResult")
    fun subscribeToChat(
        orderId: String,
        onMessageReceived: (ChatMessageDto) -> Unit
    ) {
        subscribeToChat(orderId, onMessageReceived, forceResubscribe = false)
    }

    private fun subscribeToChat(
        orderId: String,
        onMessageReceived: (ChatMessageDto) -> Unit,
        forceResubscribe: Boolean
    ) {
        val isSameChat = currentChatSub?.first == orderId
        currentChatSub = Pair(orderId, onMessageReceived)

        if (!forceResubscribe && isSameChat && chatDisposable?.isDisposed == false && isConnected()) {
            return
        }

        chatDisposable?.dispose()

        if (stompClient?.isConnected == true) {
            val topic = "/topic/chat/$orderId"
            chatDisposable = stompClient?.topic(topic)
                ?.subscribeOn(Schedulers.io())
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe({ topicMessage ->
                    try {
                        val message = gson.fromJson(topicMessage.payload, ChatMessageDto::class.java)
                        currentChatSub?.second?.invoke(message)
                    } catch (e: Exception) {
                        Log.e("WebSocketManager", "Error parsing chat message: ${e.message}")
                    }
                }, { Log.e("WebSocketManager", "Chat error", it) })
        }
    }

    fun unsubscribeFromChat() {
        chatDisposable?.dispose()
        chatDisposable = null
        currentChatSub = null
        Log.d("WS_TAXI_DEBUG", "🛑 Успешно отписались от топика чата")
    }

    @SuppressLint("CheckResult")
    fun subscribeToClientOrders(
        clientId: Long,
        onOrderUpdated: (OrderSocketMessageDto) -> Unit
    ) {
        subscribeToClientOrders(clientId, onOrderUpdated, forceResubscribe = false)
    }

    private fun subscribeToClientOrders(
        clientId: Long,
        onOrderUpdated: (OrderSocketMessageDto) -> Unit,
        forceResubscribe: Boolean
    ) {
        val isSameClient = currentOrderSub?.first == clientId
        currentOrderSub = Pair(clientId, onOrderUpdated)

        if (!forceResubscribe && isSameClient && clientOrdersDisposable?.isDisposed == false && isConnected()) {
            return
        }

        if (stompClient?.isConnected == true) {
            clientOrdersDisposable?.dispose()

            val topic = "/topic/clients/$clientId/orders"
            clientOrdersDisposable = stompClient?.topic(topic)
                ?.subscribeOn(Schedulers.io())
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe({ topicMessage ->
                    try {
                        val message = gson.fromJson(topicMessage.payload, OrderSocketMessageDto::class.java)
                        currentOrderSub?.second?.invoke(message)
                    } catch (e: Exception) {
                        Log.e("WebSocketManager", "Error parsing order message: ${e.message}")
                    }
                }, { error ->
                    Log.e("WebSocketManager", "Client orders subscription error", error)
                })
        }
    }

    fun unsubscribeFromClientOrders() {
        clientOrdersDisposable?.dispose()
        clientOrdersDisposable = null
        currentOrderSub = null
        Log.d("WS_TAXI_DEBUG", "🛑 Успешно отписались от топика активных заказов клиента")
    }

    @SuppressLint("CheckResult")
    fun subscribeToCardBinding(
        clientId: Long,
        onCardBindUpdated: (CardBindSocketMessageDto) -> Unit
    ) {
        subscribeToCardBinding(clientId, onCardBindUpdated, forceResubscribe = false)
    }

    private fun subscribeToCardBinding(
        clientId: Long,
        onCardBindUpdated: (CardBindSocketMessageDto) -> Unit,
        forceResubscribe: Boolean
    ) {
        val isSameClient = currentCardBindSub?.first == clientId
        currentCardBindSub = Pair(clientId, onCardBindUpdated)

        if (!forceResubscribe && isSameClient && cardBindDisposable?.isDisposed == false && isConnected()) {
            return
        }

        cardBindDisposable?.dispose()

        if (stompClient?.isConnected == true) {
            val topic = "/topic/clients/$clientId/card-bind"
            cardBindDisposable = stompClient?.topic(topic)
                ?.subscribeOn(Schedulers.io())
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe({ topicMessage ->
                    try {
                        val message = gson.fromJson(topicMessage.payload, CardBindSocketMessageDto::class.java)
                        currentCardBindSub?.second?.invoke(message)
                    } catch (e: Exception) {
                        Log.e("WebSocketManager", "Error parsing card bind message: ${e.message}")
                    }
                }, { Log.e("WebSocketManager", "Card bind error", it) })
        }
    }

    fun unsubscribeFromCardBinding() {
        cardBindDisposable?.dispose()
        cardBindDisposable = null
        currentCardBindSub = null
        Log.d("WS_TAXI_DEBUG", "🛑 Успешно отписались от топика привязки карты")
    }

    fun disconnect() {
        isManuallyDisconnected = true
        isConnecting = false
        reconnectHandler.removeCallbacks(reconnectRunnable)

        stompClient?.disconnect()
        compositeDisposable.clear()
        stompClient = null

        nearbyDriversDisposable?.dispose()
        nearbyDriversDisposable = null

        clientOrdersDisposable?.dispose()
        clientOrdersDisposable = null

        chatDisposable?.dispose()
        chatDisposable = null

        cardBindDisposable?.dispose()
        cardBindDisposable = null

        currentChatSub = null
        currentLocationSub = null
        currentOrderSub = null
        currentCardBindSub = null
        currentNearbyDriversSub = null
        driverTrackingDisposable?.dispose()
        driverTrackingDisposable = null
    }

    fun destroy() {
        disconnect()
    }
}