package com.taxiapp.client.network

import android.annotation.SuppressLint
import com.taxiapp.client.network.dto.ClientLocationRequest
import android.os.Handler
import com.taxiapp.client.network.dto.DriverLocationDto
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
    val orderId: String,
    val order: TaxiOrderDto?
)
data class CardBindSocketMessageDto(
    val status: String,
    val cardMask: String?,
    val message: String?
)
class WebSocketManager(private val baseUrl: String) {

    private var currentCardBindSub: Pair<Long, (CardBindSocketMessageDto) -> Unit>? = null
    private var nearbyDriversDisposable: io.reactivex.disposables.Disposable? = null
    private var driverTrackingDisposable: io.reactivex.disposables.Disposable? = null
    private var currentNearbyDriversSub: Pair<String, (List<DriverLocationDto>) -> Unit>? = null
    private var stompClient: StompClient? = null
    private val compositeDisposable = CompositeDisposable()
    private val gson = Gson()

    private var currentChatSub: Pair<String, (ChatMessageDto) -> Unit>? = null // <-- ТИП String
    private var currentLocationSub: Pair<String, (TrackingLocationDto) -> Unit>? = null // <-- ТИП String
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
            return "${cleanBase}ws-taxi/websocket"
        }

    private fun restoreSubscriptions() {
        Log.d("WS_TAXI_DEBUG", "🔄 Восстанавливаем подписки после открытия сокета...")
        currentChatSub?.let { subscribeToChat(it.first, it.second) }
        currentLocationSub?.let { subscribeToDriverLocation(it.first, it.second) }
        currentOrderSub?.let { subscribeToClientOrders(it.first, it.second) }

        // Наша подписка на машинки
        currentNearbyDriversSub?.let { subscribeToNearbyDrivers(it.first, it.second) }
        currentCardBindSub?.let { subscribeToCardBinding(it.first, it.second) }
    }


    @SuppressLint("CheckResult")
    fun subscribeToNearbyDrivers(clientId: String, onDriversReceived: (List<DriverLocationDto>) -> Unit) {
        currentNearbyDriversSub = Pair(clientId, onDriversReceived)

        // Проверяем, открыт ли сокет ПРЯМО СЕЙЧАС
        if (stompClient?.isConnected == true) {
            // ФИКС: Перед созданием новой подписки принудительно очищаем старую, если она была
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

            // ВАЖНО: Больше НЕ добавляем nearbyDriversDisposable в общий compositeDisposable,
            // так как мы будем управлять его жизненным циклом вручную.
        } else {
            Log.d("WS_TAXI_DEBUG", "⏳ Сокет еще подключается... Подписка отложена до OPENED.")
        }
    }

    fun unsubscribeFromNearbyDrivers() {
        nearbyDriversDisposable?.dispose()
        nearbyDriversDisposable = null
        currentNearbyDriversSub = null // Очищаем сохраненный коллбек, чтобы авто-восстановление не подняло его зря
        Log.d("WS_TAXI_DEBUG", "🛑 Успешно отписались от топика свободных водителей на сервере")
    }

    // НОВИЙ МЕТОД: Відправка координат клієнта на сервер
    fun sendClientLocation(request: ClientLocationRequest) {
        // Если сокет мертв — просто выходим, метод onResume выше всё равно скоро его поднимет
        if (!isConnected()) {
            Log.w("WS_TAXI_DEBUG", "⚠️ Сокет отключен, пропускаю отправку, жду onResume...")
            return
        }

        val jsonPayload = gson.toJson(request)
        stompClient?.send("/app/client/location", jsonPayload)
            ?.subscribeOn(Schedulers.io())
            ?.subscribe({
                Log.d("WS_TAXI_DEBUG", "✅ Координаты отправлены!")
            }, { err ->
                Log.e("WS_TAXI_DEBUG", "❌ Ошибка при отправке", err)
            })
            ?.let { compositeDisposable.add(it) }
    }

    fun isConnected(): Boolean {
        return stompClient != null && stompClient!!.isConnected
    }

    fun connect(token: String?) {
        if (stompClient != null && stompClient!!.isConnected) return

        lastKnownToken = token
        stompClient = Stomp.over(Stomp.ConnectionProvider.OKHTTP, wsUrl)

        val headers = mutableListOf<ua.naiksoftware.stomp.dto.StompHeader>()
        if (token != null) {
            headers.add(ua.naiksoftware.stomp.dto.StompHeader("Authorization", "Bearer $token"))
        }

        val token = ApiClient.sessionManager?.fetchAuthToken() // Достаем свежий токен из EncryptedPrefs
        if (!token.isNullOrEmpty()) {
            val authHeaders = listOf(ua.naiksoftware.stomp.dto.StompHeader("Authorization", "Bearer $token"))
            stompClient?.connect(authHeaders) // Подключаемся с токеном для прохождения валидации на сервере
        } else {
            stompClient?.connect() // Фолбэк-коннект (например, для публичных каналов, если применимо)
        }

        val disp = stompClient!!.lifecycle()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { lifecycleEvent ->
                when (lifecycleEvent.type) {
                    LifecycleEvent.Type.OPENED -> {
                        Log.d("WS_TAXI_DEBUG", "✅ [LIFECYCLE] Сокет УСПЕШНО ОТКРЫТ!")
                        restoreSubscriptions() // <-- ВАЖНО: Подписываемся только теперь!
                    }
                    LifecycleEvent.Type.ERROR -> Log.e("WS_TAXI_DEBUG", "❌ [LIFECYCLE] Ошибка сокета", lifecycleEvent.exception)
                    LifecycleEvent.Type.CLOSED -> Log.d("WS_TAXI_DEBUG", "⚠️ [LIFECYCLE] Сокет закрыт")
                    else -> {}
                }
            }
        compositeDisposable.add(disp)
    }

    // --- НОВОЕ: Метод тихого переподключения ---
    private fun reconnect(newToken: String) {
        stompClient?.disconnect()
        compositeDisposable.clear()
        stompClient = null

        connect(newToken)
    }

    fun subscribeToDriverLocation(orderId: String, onLocationReceived: (TrackingLocationDto) -> Unit) {
        currentLocationSub = Pair(orderId, onLocationReceived)
        val topic = "/topic/order/$orderId/tracking"

        // Очищаем предыдущую подписку, если она была активна
        driverTrackingDisposable?.dispose()

        driverTrackingDisposable = stompClient?.topic(topic)
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
    }

    fun unsubscribeFromDriverLocation() {
        driverTrackingDisposable?.dispose()
        driverTrackingDisposable = null
        currentLocationSub = null
        Log.d("WS_TAXI_DEBUG", "🛑 Успешно отписались от трекинга назначенного водителя")
    }

    @SuppressLint("CheckResult")
    fun subscribeToChat(orderId: String, onMessageReceived: (ChatMessageDto) -> Unit) { // <-- ТИП String
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

    @SuppressLint("CheckResult")
    fun subscribeToCardBinding(clientId: Long, onCardBindUpdated: (CardBindSocketMessageDto) -> Unit) {
        currentCardBindSub = Pair(clientId, onCardBindUpdated)
        val topic = "/topic/clients/$clientId/card-bind"

        val disp = stompClient?.topic(topic)
            ?.subscribeOn(Schedulers.io())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({ topicMessage ->
                try {
                    val message = gson.fromJson(topicMessage.payload, CardBindSocketMessageDto::class.java)
                    onCardBindUpdated(message)
                } catch (e: Exception) {
                    Log.e("WebSocketManager", "Error parsing card bind message: ${e.message}")
                }
            }, { error ->
                Log.e("WebSocketManager", "Card bind subscription error", error)
            })

        if (disp != null) compositeDisposable.add(disp)
    }

    fun disconnect() {
        stompClient?.disconnect()
        compositeDisposable.clear()
        stompClient = null

        nearbyDriversDisposable?.dispose()
        nearbyDriversDisposable = null

        // Очищаем подписки только при явном дисконнекте (например, логаут)
        currentChatSub = null
        currentLocationSub = null
        currentOrderSub = null
        currentCardBindSub = null
        driverTrackingDisposable?.dispose()
        driverTrackingDisposable = null
    }

    // Желательно вызывать при уничтожении компонента, который держит WebSocketManager
    fun destroy() {
        disconnect()
        Handler(Looper.getMainLooper()).post {
            ServerStatusBus.tokenRefreshed.removeObserver(tokenObserver)
        }
    }
}