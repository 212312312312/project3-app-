package com.taxiapp.client

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import com.facebook.appevents.AppEventsConstants
import com.facebook.appevents.AppEventsLogger
import com.google.android.gms.maps.model.LatLng
import com.google.gson.JsonParser
import com.google.maps.android.PolyUtil
import com.taxiapp.client.analytics.AnalyticsManager
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.DirectionsApiClient
import com.taxiapp.client.network.OrderStatusBus
import com.taxiapp.client.network.WebSocketManager
import com.taxiapp.client.network.dto.*
import com.taxiapp.client.service.OrderStatusService
import com.taxiapp.client.utils.CityData
import com.taxiapp.client.utils.LocationValidator
import com.taxiapp.client.utils.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Currency
import java.util.UUID

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionManager = SessionManager(application)

    // --- LiveData (наблюдаемые данные для UI) ---
    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> get() = _isLoading

    private val _errorMessage = MutableLiveData<String>()
    val errorMessage: LiveData<String> get() = _errorMessage

    private val _availableTariffs = MutableLiveData<List<CarTariffDto>>()
    val availableTariffs: LiveData<List<CarTariffDto>> get() = _availableTariffs

    private val _activeOrder = MutableLiveData<TaxiOrderDto?>()
    val activeOrder: LiveData<TaxiOrderDto?> get() = _activeOrder

    private val mapSessionId = UUID.randomUUID().toString()
    private val _nearbyDrivers = MutableLiveData<List<DriverLocationDto>>()
    val nearbyDrivers: LiveData<List<DriverLocationDto>> get() = _nearbyDrivers

    private val _cardBoundEvent = MutableLiveData<Boolean>()
    val cardBoundEvent: LiveData<Boolean> get() = _cardBoundEvent

    private val _scheduledOrderSuccess = MutableLiveData<TaxiOrderDto?>()
    val scheduledOrderSuccess: LiveData<TaxiOrderDto?> get() = _scheduledOrderSuccess

    fun resetScheduledOrderEvent() {
        _scheduledOrderSuccess.value = null
    }

    private val _orderStatus = MutableLiveData<String>()
    val orderStatus: LiveData<String> get() = _orderStatus

    private var lastTrackedCompletedOrderId: String? = null

    private val _routeInfo = MutableLiveData<Pair<Int, Int>>()
    val routeInfo: LiveData<Pair<Int, Int>> get() = _routeInfo

    var isBoostPromptShown = false

    private val _decodedRoute = MutableLiveData<List<LatLng>>()
    val decodedRoute: LiveData<List<LatLng>> get() = _decodedRoute

    // --- Состояние ---
    var activeOrderId: String? = null
    var currentCity: CityData? = null
    var currentRoutePolyline: String? = null

    private var observedWebSocketManager: WebSocketManager? = null
    private var directionsJob: Job? = null
    private var calculatePriceJob: Job? = null

    // Именованные Observer'ы для корректной отписки в onCleared() (устранение утечки памяти)
    private val orderCanceledObserver = Observer<String?> { canceledId ->
        if (!canceledId.isNullOrEmpty()) {
            val currentOrder = _activeOrder.value
            val isMatch = canceledId == activeOrderId ||
                    canceledId == currentOrder?.id ||
                    canceledId == currentOrder?.idLong?.toString()

            if (isMatch || currentOrder != null) {
                Log.d("HomeViewModel", "🔔 Получен Push-сигнал отмены для заказа $canceledId. Очищаем UI.")
                clearOrderState()
                OrderStatusBus.resetCanceledEvent()
            }
        }
    }

    private val orderUpdatedObserver = Observer<String?> { updatedId ->
        if (!updatedId.isNullOrEmpty()) {
            Log.d("HomeViewModel", "🔔 Получен Push-сигнал обновления заказа $updatedId. Синхронизируем статус...")
            syncActiveOrder()
            OrderStatusBus.resetUpdatedEvent()
        }
    }

    init {
        val savedId = sessionManager.fetchActiveOrderId()
        if (!savedId.isNullOrEmpty()) {
            activeOrderId = savedId
        }
        currentCity = sessionManager.fetchUserCity()

        // Первичная синхронизация активного заказа
        syncActiveOrder()

        // Подписываемся на сигналы от FCM Push-сервиса
        OrderStatusBus.orderCanceledEvent.observeForever(orderCanceledObserver)
        OrderStatusBus.orderUpdatedEvent.observeForever(orderUpdatedObserver)
    }

    /**
     * Безопасный парсинг серверного времени создания заказа (UTC) и расчет прошедших секунд.
     */
    private fun calculateElapsedSecondsFromUtc(createdAtStr: String?, fallbackSec: Long = 0L): Long {
        if (createdAtStr.isNullOrBlank()) return fallbackSec
        return try {
            val createdEpochMs = when {
                createdAtStr.endsWith("Z", ignoreCase = true) -> Instant.parse(createdAtStr).toEpochMilli()
                createdAtStr.contains("+") || (createdAtStr.length > 19 && createdAtStr.substring(19).contains("-")) ->
                    OffsetDateTime.parse(createdAtStr).toInstant().toEpochMilli()
                else -> LocalDateTime.parse(createdAtStr.take(19)).toInstant(ZoneOffset.UTC).toEpochMilli()
            }
            val diffSec = (System.currentTimeMillis() - createdEpochMs) / 1000L
            diffSec.coerceAtLeast(0L)
        } catch (e: Exception) {
            fallbackSec
        }
    }

    fun syncActiveOrder() {
        val pinnedOrderId = sessionManager.fetchActiveOrderId()
        activeOrderId = pinnedOrderId

        viewModelScope.launch {
            try {
                // 1. Если у клиента явно открыт конкретный заказ (создан только что или выбран в "Мої поїздки")
                if (!pinnedOrderId.isNullOrEmpty()) {
                    val specificResp = ApiClient.instance.getOrder(pinnedOrderId)
                    if (specificResp.isSuccessful && specificResp.body() != null) {
                        val order = specificResp.body()!!
                        when (order.status) {
                            "COMPLETED" -> {
                                sessionManager.clearActiveOrderId()
                                activeOrderId = order.id
                                stopOrderStatusService(order.id)
                                trackOrderCompletedSafely(order)
                                _activeOrder.value = order
                            }
                            "CANCELLED" -> {
                                stopOrderStatusService(order.id)
                                dismissCompletedOrder(order.id)
                                clearOrderState()
                            }
                            else -> {
                                activeOrderId = order.id
                                sessionManager.saveActiveOrderId(order.id)
                                _activeOrder.value = order
                                updateOrderStatusService(order)
                            }
                        }
                        return@launch
                    } else if (specificResp.code() == 404) {
                        clearOrderState()
                        return@launch
                    }
                }

                // 2. Если pinnedOrderId == null: проверяем сервер только ради финального окна COMPLETED
                val response = ApiClient.instance.getActiveOrder()
                if (response.isSuccessful) {
                    val order = response.body()
                    if (order != null) {
                        when (order.status) {
                            "COMPLETED" -> {
                                sessionManager.clearActiveOrderId()
                                activeOrderId = order.id
                                stopOrderStatusService(order.id)
                                trackOrderCompletedSafely(order)
                                _activeOrder.value = order
                            }
                            "CANCELLED" -> {
                                stopOrderStatusService(order.id)
                                dismissCompletedOrder(order.id)
                                if (activeOrderId == order.id) {
                                    clearOrderState()
                                }
                            }
                            else -> {
                                updateOrderStatusService(order)
                            }
                        }
                    } else {
                        if (_activeOrder.value?.status != "COMPLETED") {
                            if (activeOrderId != null || _activeOrder.value != null) {
                                clearOrderState()
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Помилка синхронізації активного замовлення: ${e.message}")
            }
        }
    }

    fun dismissCompletedOrder(orderId: String) {
        viewModelScope.launch {
            try {
                ApiClient.instance.dismissOrder(orderId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Помилка dismissOrder: ${e.message}")
            }
        }
    }

    fun startOrderSocketListening(webSocketManager: WebSocketManager?) {
        val clientId = sessionManager.fetchUserId()
        if (clientId == -1L) return

        this.observedWebSocketManager = webSocketManager

        webSocketManager?.subscribeToClientOrders(clientId) { messageDto ->
            val order = messageDto.order
            if (order != null) {
                val isCurrentlyPinned = activeOrderId != null &&
                        (activeOrderId == order.id || activeOrderId == order.idLong?.toString())

                when (order.status) {
                    "CANCELLED" -> {
                        stopOrderStatusService(order.id)
                        if (isCurrentlyPinned) {
                            clearOrderState()
                        }
                    }
                    "COMPLETED" -> {
                        sessionManager.clearActiveOrderId()
                        activeOrderId = order.id
                        stopOrderStatusService(order.id)
                        trackOrderCompletedSafely(order)
                        _activeOrder.postValue(order)
                    }
                    else -> {
                        updateOrderStatusService(order)
                        if (isCurrentlyPinned) {
                            activeOrderId = order.id
                            sessionManager.saveActiveOrderId(order.id)
                            _activeOrder.postValue(order)
                        }
                    }
                }
            } else if (messageDto.action == "REMOVE") {
                val currentOrder = _activeOrder.value
                if (currentOrder != null) {
                    val isMatch = messageDto.orderId == activeOrderId ||
                            messageDto.orderId == currentOrder.id ||
                            messageDto.orderId == currentOrder.idLong?.toString()

                    if (isMatch) {
                        Log.d("WS_ORDER_DEBUG", "🧹 Удаление заказа через сокет REMOVE (${messageDto.orderId}). Сбрасываем UI.")
                        clearOrderState()
                    }
                }
            }
        }
    }

    fun stopOrderSocketListening() {
        Log.d("WS_ORDER_DEBUG", "🛑 Прекращаем слушать обновления сокетов для заказов")
        observedWebSocketManager?.unsubscribeFromClientOrders()
    }

    fun checkOrderStatusOnce(forcedOrderId: String? = null) {
        val orderId = forcedOrderId
            ?: _activeOrder.value?.id
            ?: activeOrderId
            ?: return

        viewModelScope.launch {
            try {
                val response = ApiClient.instance.getOrder(orderId)
                if (response.isSuccessful && response.body() != null) {
                    val loadedOrder = response.body()
                    activeOrderId = loadedOrder?.id
                    _activeOrder.value = loadedOrder
                    Log.d("HomeViewModel", "Принудительно синхронизирован статус заказа (ID: $orderId): ${loadedOrder?.status}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Ошибка проверки статуса заказа: ${e.message}")
            }
        }
    }

    private fun trackOrderCompletedSafely(order: TaxiOrderDto) {
        if (lastTrackedCompletedOrderId == order.id) return
        lastTrackedCompletedOrderId = order.id

        val prefs = getApplication<Application>().getSharedPreferences("taxi_session", Context.MODE_PRIVATE)
        val clientOrderNumber = prefs.getInt("client_completed_rides_count", 0) + 1
        prefs.edit().putInt("client_completed_rides_count", clientOrderNumber).apply()

        AnalyticsManager.trackOrderCompleted(
            orderId = order.id,
            finalPrice = order.price,
            orderNumber = clientOrderNumber
        )

        try {
            val logger = AppEventsLogger.newLogger(getApplication())
            val params = Bundle().apply {
                putString(AppEventsConstants.EVENT_PARAM_CURRENCY, "UAH")
                putString(AppEventsConstants.EVENT_PARAM_CONTENT_TYPE, "ride")
                putString(AppEventsConstants.EVENT_PARAM_CONTENT_ID, order.id)
            }
            logger.logPurchase(
                BigDecimal.valueOf(order.price),
                Currency.getInstance("UAH"),
                params
            )
            Log.d("MetaAnalytics", "✅ Успешно отправлено событие Purchase: ${order.price} UAH")
        } catch (e: Exception) {
            Log.e("MetaAnalytics", "Ошибка отправки события в Meta: ${e.message}")
        }
    }

    fun startListeningNearbyDrivers(webSocketManager: WebSocketManager?) {
        webSocketManager?.subscribeToNearbyDrivers(mapSessionId) { drivers ->
            _nearbyDrivers.postValue(drivers)
        }
    }

    fun updateClientLocation(webSocketManager: WebSocketManager?, lat: Double, lng: Double) {
        if (!LocationValidator.isValidLocation(lat, lng)) return
        val request = ClientLocationRequest(mapSessionId, lat, lng)
        webSocketManager?.sendClientLocation(request)
    }

    fun stopListeningNearbyDrivers(webSocketManager: WebSocketManager?) {
        webSocketManager?.unsubscribeFromNearbyDrivers()
    }

    // --- API: Тарифы и Цена (suspend) ---
    fun loadTariffsAndCalculatePrice(
        routePolyline: String?,
        distanceMeters: Int,
        waypointsCount: Int = 0,
        originLat: Double? = null,
        originLng: Double? = null
    ) {
        _isLoading.value = true
        calculatePriceJob?.cancel()

        calculatePriceJob = viewModelScope.launch {
            if (routePolyline != null && distanceMeters > 0) {
                val fakeWaypointsList = if (waypointsCount > 0) List(waypointsCount) { "wp" } else emptyList()
                val request = CalculatePriceRequestDto(
                    googleRoutePolyline = routePolyline,
                    distanceMeters = distanceMeters,
                    waypointsCount = waypointsCount,
                    waypoints = fakeWaypointsList,
                    originLat = originLat,
                    originLng = originLng
                )
                try {
                    val response = ApiClient.instance.calculatePrice(request)
                    _isLoading.value = false
                    if (response.isSuccessful && response.body() != null) {
                        _availableTariffs.value = response.body()
                    } else {
                        loadBaseTariffsSuspend()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _isLoading.value = false
                    loadBaseTariffsSuspend()
                }
            } else {
                loadBaseTariffsSuspend()
            }
        }
    }

    private suspend fun loadBaseTariffsSuspend() {
        try {
            val response = ApiClient.instance.getActiveTariffs()
            _isLoading.value = false
            if (response.isSuccessful) {
                _availableTariffs.value = response.body() ?: emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _isLoading.value = false
        }
    }

    fun startCheckingCardBinding(webSocketManager: WebSocketManager?) {
        val clientId = sessionManager.fetchUserId()
        if (clientId == -1L) {
            Log.e("HomeViewModel", "Cannot subscribe to card bind: clientId is -1")
            return
        }

        this.observedWebSocketManager = webSocketManager
        Log.d("WS_BIND_DEBUG", "🎧 Подписываемся на WebSocket привязки карты для клиента: $clientId")
        webSocketManager?.subscribeToCardBinding(clientId) { messageDto ->
            Log.d("WS_BIND_DEBUG", "⚡ Получен сокет-апдейт привязки карты! Status: ${messageDto.status}")

            if (messageDto.status == "SUCCESS" && !messageDto.cardMask.isNullOrEmpty()) {
                sessionManager.saveCardMask(messageDto.cardMask)
                _cardBoundEvent.postValue(true)
            } else {
                _errorMessage.postValue(messageDto.message ?: "Помилка прив'язки картки")
            }
        }
    }

    fun stopCheckingCardBinding() {
        Log.d("WS_BIND_DEBUG", "🛑 Прекращаем ожидать привязку карты")
        observedWebSocketManager?.unsubscribeFromCardBinding()
    }

    fun resetCardBoundEvent() {
        _cardBoundEvent.value = false
    }

    // --- API: Маршрут (suspend через серверный OSRM + Redis кэш) ---
    fun fetchDirections(origin: LatLng, dest: LatLng, waypoints: List<Pair<LatLng, String>>) {
        currentRoutePolyline = null
        directionsJob?.cancel()

        val wpDtos = waypoints.map { RoutePointDto(it.first.latitude, it.first.longitude) }

        directionsJob = viewModelScope.launch {
            try {
                val response = DirectionsApiClient.calculateRoute(
                    originLat = origin.latitude,
                    originLng = origin.longitude,
                    destLat = dest.latitude,
                    destLng = dest.longitude,
                    waypoints = wpDtos
                )
                val route = response.body()
                if (response.isSuccessful && route != null && route.polyline.isNotBlank()) {
                    val polylinePoints = route.polyline
                    currentRoutePolyline = polylinePoints

                    val finalDist = route.distanceMeters
                    val finalDur = route.durationSeconds
                    val decoded = withContext(Dispatchers.Default) {
                        PolyUtil.decode(polylinePoints)
                    }

                    _routeInfo.value = Pair(finalDist, finalDur)
                    _decodedRoute.value = decoded
                    loadTariffsAndCalculatePrice(
                        routePolyline = polylinePoints,
                        distanceMeters = finalDist,
                        waypointsCount = waypoints.size,
                        originLat = origin.latitude,
                        originLng = origin.longitude
                    )
                } else {
                    _errorMessage.value = "Маршрут не знайдено"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = "Не вдалося побудувати маршрут"
            }
        }
    }

    // --- УПРАВЛЕНИЕ ВИДЖЕТОМ (СЕРВИСОМ) ---
    private fun updateOrderStatusService(order: TaxiOrderDto) {
        val context = getApplication<Application>()
        val intent = Intent(context, OrderStatusService::class.java).apply {
            putExtra(OrderStatusService.EXTRA_ORDER_ID, order.id)
            putExtra(OrderStatusService.EXTRA_STATUS, order.status)
            putExtra(OrderStatusService.EXTRA_ADDRESS, order.toAddress)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun cancelOrder(reasonText: String? = null) {
        val id = activeOrderId ?: return
        _isLoading.value = true

        viewModelScope.launch {
            try {
                val response = ApiClient.instance.cancelOrder(id, reasonText)
                _isLoading.value = false
                if (response.isSuccessful && response.body() != null) {
                    val order = response.body()!!
                    val durationSec = calculateElapsedSecondsFromUtc(order.createdAt, 0L)

                    AnalyticsManager.trackOrderFailed(
                        orderId = id,
                        reason = reasonText ?: "Скасовано клієнтом",
                        searchDurationSec = durationSec
                    )

                    stopOrderStatusService(id)
                    clearOrderState()
                } else {
                    _errorMessage.value = "Не вдалося скасувати"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _isLoading.value = false
                _errorMessage.value = "Помилка мережі"
            }
        }
    }

    fun cancelOrder() {
        cancelOrder(reasonText = null)
    }

    fun applyBoostPrice(boostAmount: Double = 20.0) {
        val order = _activeOrder.value ?: return
        val newAddedValue = order.addedValue + boostAmount
        val durationSec = calculateElapsedSecondsFromUtc(order.createdAt, 180L)

        AnalyticsManager.trackBoostClicked(
            orderId = order.id,
            boostAmount = newAddedValue,
            timeSinceCreationSec = durationSec
        )

        updateActiveOrderPrice(newAddedValue)
    }

    private fun stopOrderStatusService(orderId: String) {
        val context = getApplication<Application>()
        val intent = Intent(context, OrderStatusService::class.java)
        context.stopService(intent)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.cancel(orderId.hashCode())
    }

    fun createOrder(request: CreateOrderRequestDto) {
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val response = ApiClient.instance.createOrder(request)
                _isLoading.value = false
                if (response.isSuccessful && response.body() != null) {
                    val order = response.body()!!

                    activeOrderId = order.id
                    sessionManager.saveActiveOrderId(order.id)
                    _activeOrder.value = order
                    updateOrderStatusService(order)
                } else {
                    val msg = extractServerErrorMessage(
                        response,
                        "Не вдалося зарезервувати кошти. Перевірте баланс"
                    )
                    _errorMessage.value = msg
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _isLoading.value = false
                _errorMessage.value = "Помилка мережі"
            }
        }
    }

    fun startStatusPolling() {}

    private fun extractServerErrorMessage(response: Response<*>, defaultMsg: String): String {
        return try {
            val raw = response.errorBody()?.string()
            if (raw.isNullOrBlank()) return defaultMsg
            val json = JsonParser.parseString(raw).asJsonObject
            when {
                json.has("message") && !json.get("message").isJsonNull -> json.get("message").asString
                json.has("reason") && !json.get("reason").isJsonNull -> json.get("reason").asString
                json.has("error") && !json.get("error").isJsonNull -> json.get("error").asString
                else -> defaultMsg
            }
        } catch (e: Exception) {
            defaultMsg
        }
    }

    // --- API: Смена типа оплаты "на лету" (suspend) ---
    fun updateActiveOrderPaymentMethod(method: String) {
        val id = activeOrderId ?: return
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val response = ApiClient.instance.updatePaymentMethod(id, method)
                _isLoading.value = false
                if (response.isSuccessful) {
                    sessionManager.savePaymentMethod(method)
                    _activeOrder.value?.let { currentOrder ->
                        _activeOrder.value = currentOrder.copy(paymentMethod = method)
                    }
                } else {
                    val serverMsg = extractServerErrorMessage(response, "Помилка зміни оплати")
                    _errorMessage.value = serverMsg
                    _activeOrder.value = _activeOrder.value
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _isLoading.value = false
                _errorMessage.value = "Помилка мережі"
                _activeOrder.value = _activeOrder.value
            }
        }
    }

    // --- API: Изменение цены "на лету" (suspend) ---
    fun updateActiveOrderPrice(addedValue: Double) {
        val id = activeOrderId ?: return
        val currentOrder = _activeOrder.value
        if (currentOrder != null && currentOrder.addedValue == addedValue) {
            return
        }
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val response = ApiClient.instance.updateOrderPrice(id, addedValue)
                _isLoading.value = false
                if (response.isSuccessful) {
                    _activeOrder.value?.let { ord ->
                        val basePrice = ord.price - ord.addedValue
                        val newPrice = basePrice + addedValue
                        val newClientPay = if (ord.appliedDiscount > 0.0) {
                            (newPrice - ord.appliedDiscount).coerceAtLeast(1.0)
                        } else {
                            newPrice
                        }
                        _activeOrder.value = ord.copy(
                            price = newPrice,
                            clientPayAmount = newClientPay,
                            addedValue = addedValue
                        )
                    }
                } else {
                    val serverMsg = extractServerErrorMessage(response, "Помилка зміни ціни")
                    _errorMessage.value = serverMsg
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _isLoading.value = false
                _errorMessage.value = "Помилка мережі"
            }
        }
    }

    fun stopStatusPolling() {}

    fun hideOrderFromMainScreen() {
        activeOrderId = null
        sessionManager.clearActiveOrderId()
        val prefs = getApplication<Application>().applicationContext.getSharedPreferences("taxi_session", Context.MODE_PRIVATE)
        prefs.edit().remove("active_order_id").apply()
        _activeOrder.value = null
        isBoostPromptShown = false
    }

    fun clearOrderState() {
        activeOrderId?.let { stopOrderStatusService(it) }
        activeOrderId = null
        sessionManager.clearActiveOrderId()

        val prefs = getApplication<Application>().applicationContext.getSharedPreferences("taxi_session", Context.MODE_PRIVATE)
        prefs.edit().remove("active_order_id").apply()

        _activeOrder.postValue(null)
        isBoostPromptShown = false
    }

    override fun onCleared() {
        super.onCleared()
        OrderStatusBus.orderCanceledEvent.removeObserver(orderCanceledObserver)
        OrderStatusBus.orderUpdatedEvent.removeObserver(orderUpdatedObserver)
        directionsJob?.cancel()
        calculatePriceJob?.cancel()
        stopCheckingCardBinding()
        stopOrderSocketListening()
        observedWebSocketManager = null
    }
}