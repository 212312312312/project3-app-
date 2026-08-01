package com.taxiapp.client

import android.app.Application
import android.os.Handler
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.maps.model.LatLng
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.DirectionsApiClient
import com.taxiapp.client.network.dto.*
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.CityData
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

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

    private val mapSessionId = java.util.UUID.randomUUID().toString()
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

    private val _routeInfo = MutableLiveData<Pair<Int, Int>>()
    val routeInfo: LiveData<Pair<Int, Int>> get() = _routeInfo

    private val _decodedRoute = MutableLiveData<List<LatLng>>()
    val decodedRoute: LiveData<List<LatLng>> get() = _decodedRoute

    // --- Состояние ---
    var activeOrderId: String? = null
    var currentCity: CityData? = null
    var currentRoutePolyline: String? = null

    // 🔥 ИСПРАВЛЕНО: Храним ссылку на менеджер сокетов внутри ViewModel для очистки в onCleared()
    private var observedWebSocketManager: com.taxiapp.client.network.WebSocketManager? = null

    init {
        val savedId = sessionManager.fetchActiveOrderId()
        if (!savedId.isNullOrEmpty()) {
            activeOrderId = savedId
            checkOrderStatusOnce()
        }
        currentCity = sessionManager.fetchUserCity()

        // 🟢 Подписываемся на сигналы отмены от FCM Push-сервиса
        com.taxiapp.client.network.OrderStatusBus.orderCanceledEvent.observeForever { canceledId ->
            if (!canceledId.isNullOrEmpty()) {
                val currentOrder = _activeOrder.value
                val isMatch = canceledId == activeOrderId ||
                        canceledId == currentOrder?.id ||
                        canceledId == currentOrder?.idLong?.toString()

                if (isMatch || currentOrder != null) {
                    Log.d("HomeViewModel", "🔔 Получен Push-сигнал отмены для заказа $canceledId. Очищаем UI.")
                    clearOrderState()
                    com.taxiapp.client.network.OrderStatusBus.resetEvent()
                }
            }
        }
    }

    fun startOrderSocketListening(webSocketManager: com.taxiapp.client.network.WebSocketManager?) {
        val clientId = sessionManager.fetchUserId()
        if (clientId == -1L) return

        this.observedWebSocketManager = webSocketManager

        webSocketManager?.subscribeToClientOrders(clientId) { messageDto ->
            val order = messageDto.order
            if (order != null) {
                if (activeOrderId == null || activeOrderId == order.id || activeOrderId == order.idLong.toString()) {
                    if (order.status == "COMPLETED" || order.status == "CANCELLED") {
                        stopOrderStatusService(order.id)
                        clearOrderState()
                    } else {
                        activeOrderId = order.id
                        sessionManager.saveActiveOrderId(order.id)
                        _activeOrder.postValue(order)
                        updateOrderStatusService(order)
                    }
                }
            } else if (messageDto.action == "REMOVE") {
                val currentOrder = _activeOrder.value
                if (currentOrder != null) {
                    // 🟢 Сравниваем полученный UUID акшена со всеми возможными идентификаторами текущего заказа
                    val isMatch = messageDto.orderId == activeOrderId ||
                            messageDto.orderId == currentOrder.id ||
                            messageDto.orderId == currentOrder.idLong.toString()

                    if (isMatch) {
                        Log.d("WS_ORDER_DEBUG", "🧹 Удаление заказа через сокет REMOVE (${messageDto.orderId}). Сбрасываем UI.")
                        clearOrderState()
                    }
                }
            }
        }
    }

    // 🔥 ИСПРАВЛЕНО: Метод теперь принимает вызов без параметров и безопасно очищает топики
    fun stopOrderSocketListening() {
        Log.d("WS_ORDER_DEBUG", "🛑 Прекращаем слушать обновления сокетов для заказов")
        observedWebSocketManager?.unsubscribeFromClientOrders()
        observedWebSocketManager = null
    }

    fun checkOrderStatusOnce(forcedOrderId: String? = null) {
        val orderId = forcedOrderId
            ?: _activeOrder.value?.id
            ?: activeOrderId
            ?: return

        // ИСПРАВЛЕНО: Запуск происходит на Main, так как suspend метод getOrder безопасен для UI
        viewModelScope.launch {
            try {
                // ИСПРАВЛЕНО: Прямой неблокирующий вызов Retrofit
                val response = com.taxiapp.client.network.ApiClient.instance.getOrder(orderId)

                if (response.isSuccessful && response.body() != null) {
                    val loadedOrder = response.body()
                    activeOrderId = loadedOrder?.id
                    _activeOrder.value = loadedOrder
                    Log.d("HomeViewModel", "Принудительно синхронизирован статус заказа после перезапуска (ID: $orderId): ${loadedOrder?.status}")
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Ошибка проверки статуса заказа: ${e.message}")
            }
        }
    }

    fun startListeningNearbyDrivers(webSocketManager: com.taxiapp.client.network.WebSocketManager?) {
        webSocketManager?.subscribeToNearbyDrivers(mapSessionId) { drivers ->
            _nearbyDrivers.postValue(drivers)
        }
    }

    fun updateClientLocation(webSocketManager: com.taxiapp.client.network.WebSocketManager?, lat: Double, lng: Double) {
        val request = ClientLocationRequest(mapSessionId, lat, lng)
        webSocketManager?.sendClientLocation(request)
    }

    fun stopListeningNearbyDrivers(webSocketManager: com.taxiapp.client.network.WebSocketManager?) {
        webSocketManager?.unsubscribeFromNearbyDrivers()
    }

    // --- API: Тарифы и Цена ---
    fun loadTariffsAndCalculatePrice(routePolyline: String?, distanceMeters: Int, waypointsCount: Int = 0) {
        _isLoading.value = true
        val promoPercent = sessionManager.fetchPromoDiscount()

        if (routePolyline != null && distanceMeters > 0) {
            val fakeWaypointsList = if (waypointsCount > 0) List(waypointsCount) { "wp" } else emptyList()

            val request = CalculatePriceRequestDto(
                googleRoutePolyline = routePolyline,
                distanceMeters = distanceMeters,
                waypointsCount = waypointsCount,
                waypoints = fakeWaypointsList
            )

            ApiClient.instance.calculatePrice(request).enqueue(object : Callback<List<CarTariffDto>> {
                override fun onResponse(call: Call<List<CarTariffDto>>, response: Response<List<CarTariffDto>>) {
                    _isLoading.value = false
                    if (response.isSuccessful && response.body() != null) {
                        _availableTariffs.value = response.body()
                    } else {
                        loadBaseTariffs()
                    }
                }
                override fun onFailure(call: Call<List<CarTariffDto>>, t: Throwable) {
                    _isLoading.value = false
                    loadBaseTariffs()
                }
            })
        } else {
            loadBaseTariffs()
        }
    }

    private fun loadBaseTariffs() {
        ApiClient.instance.getActiveTariffs().enqueue(object : Callback<List<CarTariffDto>> {
            override fun onResponse(call: Call<List<CarTariffDto>>, response: Response<List<CarTariffDto>>) {
                _isLoading.value = false
                if (response.isSuccessful) {
                    _availableTariffs.value = response.body() ?: emptyList()
                }
            }
            override fun onFailure(call: Call<List<CarTariffDto>>, t: Throwable) {
            }
        })
    }

    fun startCheckingCardBinding(webSocketManager: com.taxiapp.client.network.WebSocketManager?) {
        val clientId = sessionManager.fetchUserId()
        if (clientId == -1L) {
            Log.e("HomeViewModel", "Cannot subscribe to card bind: clientId is -1")
            return
        }

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
    }

    fun resetCardBoundEvent() {
        _cardBoundEvent.value = false
    }

    // --- API: Маршрут ---
    fun fetchDirections(origin: LatLng, dest: LatLng, waypoints: List<Pair<LatLng, String>>) {
        currentRoutePolyline = null

        val originStr = "${origin.latitude},${origin.longitude}"
        val destStr = "${dest.latitude},${dest.longitude}"
        val wpStr = if (waypoints.isNotEmpty()) {
            "optimize:false|" + waypoints.joinToString("|") { "${it.first.latitude},${it.first.longitude}" }
        } else null

        val myApiKey = BuildConfig.GOOGLE_PLACES_API_KEY

        DirectionsApiClient.instance.getDirections(originStr, destStr, wpStr, myApiKey)
            .enqueue(object : Callback<DirectionsResponse> {
                override fun onResponse(call: Call<DirectionsResponse>, response: Response<DirectionsResponse>) {
                    if (response.isSuccessful && !response.body()?.routes.isNullOrEmpty()) {
                        val route = response.body()!!.routes[0]
                        val polylinePoints = route.overviewPolyline.points
                        currentRoutePolyline = polylinePoints

                        viewModelScope.launch(Dispatchers.Default) {
                            var dist = 0L
                            var dur = 0L
                            route.legs.forEach {
                                dist += it.distance.meters
                                dur += it.duration.seconds
                            }

                            val finalDist = dist.toInt()
                            val finalDur = dur.toInt()

                            val decoded = com.google.maps.android.PolyUtil.decode(polylinePoints)

                            withContext(Dispatchers.Main) {
                                _routeInfo.value = Pair(finalDist, finalDur)
                                _decodedRoute.value = decoded
                                loadTariffsAndCalculatePrice(polylinePoints, finalDist, waypoints.size)
                            }
                        }
                    } else {
                        _errorMessage.value = "Маршрут не знайдено"
                    }
                }

                override fun onFailure(call: Call<DirectionsResponse>, t: Throwable) {
                    _errorMessage.value = "Не вдалося побудувати маршрут"
                }
            })
    }

    // --- УПРАВЛЕНИЕ ВИДЖЕТОМ (СЕРВИСОМ) ---
    private fun updateOrderStatusService(order: TaxiOrderDto) {
        val context = getApplication<Application>()
        val intent = android.content.Intent(context, com.taxiapp.client.service.OrderStatusService::class.java).apply {
            putExtra(com.taxiapp.client.service.OrderStatusService.EXTRA_ORDER_ID, order.id)
            putExtra(com.taxiapp.client.service.OrderStatusService.EXTRA_STATUS, order.status)
            putExtra(com.taxiapp.client.service.OrderStatusService.EXTRA_ADDRESS, order.toAddress)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun cancelOrder(reasonText: String? = null) {
        val id = activeOrderId ?: return
        _isLoading.value = true

        ApiClient.instance.cancelOrder(id, reasonText).enqueue(object : Callback<TaxiOrderDto> {
            override fun onResponse(call: Call<TaxiOrderDto>, response: Response<TaxiOrderDto>) {
                _isLoading.value = true // ИСПРАВЛЕНО на false при ответе
                _isLoading.value = false
                if (response.isSuccessful && response.body() != null) {
                    _activeOrder.value = response.body()
                } else {
                    _errorMessage.value = "Не вдалося скасувати"
                }
            }
            override fun onFailure(call: Call<TaxiOrderDto>, t: Throwable) {
                _isLoading.value = false
                _errorMessage.value = "Помилка мережі"
            }
        })
    }

    private fun stopOrderStatusService(orderId: String) {
        val context = getApplication<Application>()
        val intent = android.content.Intent(context, com.taxiapp.client.service.OrderStatusService::class.java)
        context.stopService(intent)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.cancel(orderId.hashCode())
    }

    // --- API: Создание заказа ---
    fun createOrder(request: CreateOrderRequestDto) {
        _isLoading.value = true
        ApiClient.instance.createOrder(request).enqueue(object : Callback<TaxiOrderDto> {
            override fun onResponse(call: Call<TaxiOrderDto>, response: Response<TaxiOrderDto>) {
                _isLoading.value = false
                if (response.isSuccessful && response.body() != null) {
                    val order = response.body()!!
                    if (order.status != "SCHEDULED") {
                        activeOrderId = order.id
                        sessionManager.saveActiveOrderId(order.id)
                        _activeOrder.value = order
                        updateOrderStatusService(order)
                    } else {
                        _scheduledOrderSuccess.value = order
                        updateOrderStatusService(order)
                    }
                } else {
                    val errorJson = response.errorBody()?.string()
                    val msg = try {
                        val jsonObject = com.google.gson.JsonParser.parseString(errorJson).asJsonObject
                        if (jsonObject.has("message")) {
                            jsonObject.get("message").asString
                        } else {
                            "Не вдалося зарезервувати кошти. Перевірте баланс"
                        }
                    } catch (e: Exception) {
                        "Помилка створення замовлення. Спробуйте інший спосіб оплати"
                    }
                    _errorMessage.value = msg
                }
            }
            override fun onFailure(call: Call<TaxiOrderDto>, t: Throwable) {
                _isLoading.value = false
                _errorMessage.value = "Помилка мережі"
            }
        })
    }

    // --- API: Отмена заказа без причин ----
    fun cancelOrder() {
        val id = activeOrderId ?: return
        _isLoading.value = true

        ApiClient.instance.cancelOrder(id).enqueue(object : Callback<TaxiOrderDto> {
            override fun onResponse(call: Call<TaxiOrderDto>, response: Response<TaxiOrderDto>) {
                _isLoading.value = false
                if (response.isSuccessful && response.body() != null) {
                    _activeOrder.value = response.body()
                } else {
                    _errorMessage.value = "Не вдалося скасувати"
                }
            }
            override fun onFailure(call: Call<TaxiOrderDto>, t: Throwable) {
                _isLoading.value = false
                _errorMessage.value = "Помилка мережі"
            }
        })
    }

    fun startStatusPolling() {}

    // --- API: Смена типа оплаты "на лету" ---
    fun updateActiveOrderPaymentMethod(method: String) {
        val id = activeOrderId ?: return
        _isLoading.value = true
        ApiClient.instance.updatePaymentMethod(id, method).enqueue(object : Callback<MessageResponseDto> {
            override fun onResponse(call: Call<MessageResponseDto>, response: Response<MessageResponseDto>) {
                _isLoading.value = false
                if (response.isSuccessful) {
                    _activeOrder.value?.let { currentOrder ->
                        _activeOrder.value = currentOrder.copy(paymentMethod = method)
                    }
                } else {
                    _errorMessage.value = "Помилка зміни оплати"
                }
            }
            override fun onFailure(call: Call<MessageResponseDto>, t: Throwable) {
                _isLoading.value = false
                _errorMessage.value = "Помилка мережі"
            }
        })
    }

    // --- API: Изменение цены "на лету" ---
    fun updateActiveOrderPrice(addedValue: Double) {
        val id = activeOrderId ?: return
        _isLoading.value = true
        ApiClient.instance.updateOrderPrice(id, addedValue).enqueue(object : Callback<MessageResponseDto> {
            override fun onResponse(call: Call<MessageResponseDto>, response: Response<MessageResponseDto>) {
                _isLoading.value = false
                if (response.isSuccessful) {
                    _activeOrder.value?.let { currentOrder ->
                        val basePrice = currentOrder.price - currentOrder.addedValue
                        val newPrice = basePrice + addedValue
                        _activeOrder.value = currentOrder.copy(
                            price = newPrice,
                            addedValue = addedValue
                        )
                    }
                } else {
                    _errorMessage.value = "Помилка зміни ціни"
                }
            }
            override fun onFailure(call: Call<MessageResponseDto>, t: Throwable) {
                _isLoading.value = false
                _errorMessage.value = "Помилка мережі"
            }
        })
    }

    fun stopStatusPolling() {}

    fun clearOrderState() {
        activeOrderId?.let { stopOrderStatusService(it) }
        activeOrderId = null
        sessionManager.clearActiveOrderId()
        _activeOrder.value = null
    }

    // 🔥 ИСПРАВЛЕНО: Безопасный вызов без аргументов очистит сокет, предотвращая утечку памяти
    override fun onCleared() {
        super.onCleared()
        stopOrderSocketListening()
    }
}