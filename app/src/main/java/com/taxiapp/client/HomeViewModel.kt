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

    private val mapSessionId = java.util.UUID.randomUUID().toString() // Унікальний ID для сокету
    private val _nearbyDrivers = MutableLiveData<List<DriverLocationDto>>()
    val nearbyDrivers: LiveData<List<DriverLocationDto>> get() = _nearbyDrivers

    private val _cardBoundEvent = MutableLiveData<Boolean>()
    val cardBoundEvent: LiveData<Boolean> get() = _cardBoundEvent

    private val _scheduledOrderSuccess = MutableLiveData<TaxiOrderDto?>()
    val scheduledOrderSuccess: LiveData<TaxiOrderDto?> get() = _scheduledOrderSuccess
    fun resetScheduledOrderEvent() {
        _scheduledOrderSuccess.value = null
    }

    private var profilePollingHandler = Handler(Looper.getMainLooper())
    private var profilePollingRunnable: Runnable? = null

    private val _orderStatus = MutableLiveData<String>()
    val orderStatus: LiveData<String> get() = _orderStatus

    private val _routeInfo = MutableLiveData<Pair<Int, Int>>() // DistanceMeters, DurationSeconds
    val routeInfo: LiveData<Pair<Int, Int>> get() = _routeInfo

    private val _decodedRoute = MutableLiveData<List<LatLng>>()
    val decodedRoute: LiveData<List<LatLng>> get() = _decodedRoute

    // --- Состояние ---
    var activeOrderId: String? = null
    var currentCity: CityData? = null
    var currentRoutePolyline: String? = null



    init {
        // При старте проверяем, есть ли активный заказ
        val savedId = sessionManager.fetchActiveOrderId()
        if (!savedId.isNullOrEmpty()) { // <-- ПРОВЕРЯЕМ НА СТРОКУ
            activeOrderId = savedId
            checkOrderStatusOnce()
        }
        currentCity = sessionManager.fetchUserCity()
    }
    fun startOrderSocketListening(webSocketManager: com.taxiapp.client.network.WebSocketManager?) {
        val clientId = sessionManager.fetchUserId()
        if (clientId == -1L) {
            Log.e("HomeViewModel", "Cannot subscribe to orders: clientId is -1")
            return
        }

        Log.d("WS_ORDER_DEBUG", "🎧 Подписываемся на WebSocket топик заказов для клиента: $clientId")
        webSocketManager?.subscribeToClientOrders(clientId) { messageDto ->
            Log.d("WS_ORDER_DEBUG", "⚡ Получен сокет-апдейт заказа! Action: ${messageDto.action}, Status: ${messageDto.order?.status}")
            
            val order = messageDto.order
            if (order != null) {
                // Если прилетел заказ, который мы сейчас ведем
                if (activeOrderId == null || activeOrderId == order.id) {
                    activeOrderId = order.id
                    sessionManager.saveActiveOrderId(order.id)
                    _activeOrder.postValue(order)

                    // Управляем сервисом уведомлений на основе статуса из сокета
                    if (order.status == "COMPLETED" || order.status == "CANCELLED") {
                        stopOrderStatusService(order.id)
                        // Очищаем локальное состояние таймеров (если сокет принес финал)
                        sessionManager.clearActiveOrderId()
                    } else {
                        updateOrderStatusService(order)
                    }
                }
            } else if (messageDto.action == "REMOVE" && messageDto.orderId == activeOrderId) {
                // Сервер скомандовал удалить заказ с экрана клиента (например, жесткая отмена диспетчером)
                activeOrderId?.let { stopOrderStatusService(it) }
                clearOrderState()
            }
        }
    }

    // Новый метод: Отключаем прослушивание топика при выходе с экрана или закрытии
    fun stopOrderSocketListening() {
        // Метод unsubscribeFromClientOrders отсутствует в WebSocketManager, 
        // но благодаря тому, что сокет автоматически очистит или перезапишет подписку при вызове disconnect/destroy,
        // нам достаточно просто обнулить локальное ведение при очистке стейта.
        Log.d("WS_ORDER_DEBUG", "🛑 Прекращаем слушать обновления сокетов для заказов")
    }

    fun checkOrderStatusOnce(forcedOrderId: String? = null) { // <-- ИЗМЕНИЛИ С Long? НА String?
        val orderId = forcedOrderId
            ?: _activeOrder.value?.id
            ?: activeOrderId
            ?: return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = com.taxiapp.client.network.ApiClient.instance.getOrder(orderId).execute()
                
                if (response.isSuccessful && response.body() != null) {
                    withContext(Dispatchers.Main) {
                        val loadedOrder = response.body()
                        activeOrderId = loadedOrder?.id
                        _activeOrder.value = loadedOrder
                        Log.d("HomeViewModel", "Принудительно синхронизирован статус заказа после перезапуска (ID: $orderId): ${loadedOrder?.status}")
                    }
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

    // Відправляємо свої координати
    fun updateClientLocation(webSocketManager: com.taxiapp.client.network.WebSocketManager?, lat: Double, lng: Double) {
        val request = ClientLocationRequest(mapSessionId, lat, lng)
        webSocketManager?.sendClientLocation(request)
    }

    fun stopListeningNearbyDrivers(webSocketManager: com.taxiapp.client.network.WebSocketManager?) {
        webSocketManager?.unsubscribeFromNearbyDrivers()
    }

    // --- API: Тарифы и Цена ---
    // ДОДАНО параметр waypointsCount: Int = 0
    fun loadTariffsAndCalculatePrice(routePolyline: String?, distanceMeters: Int, waypointsCount: Int = 0) {
        _isLoading.value = true

        // 1. Проверяем промокод (логику можно расширить)
        val promoPercent = sessionManager.fetchPromoDiscount()

        // 2. Если есть маршрут - считаем цену на сервере
        if (routePolyline != null && distanceMeters > 0) {

            // --- НОВЕ: Формуємо фейковий список точок потрібного розміру ---
            // Це гарантує, що сервер зможе отримати кількість через request.waypoints?.size
            val fakeWaypointsList = if (waypointsCount > 0) List(waypointsCount) { "wp" } else emptyList()

            val request = CalculatePriceRequestDto(
                googleRoutePolyline = routePolyline,
                distanceMeters = distanceMeters,
                waypointsCount = waypointsCount,
                waypoints = fakeWaypointsList
            )
            // ----------------------------------------------------------------

            ApiClient.instance.calculatePrice(request).enqueue(object : Callback<List<CarTariffDto>> {
                override fun onResponse(call: Call<List<CarTariffDto>>, response: Response<List<CarTariffDto>>) {
                    _isLoading.value = false
                    if (response.isSuccessful && response.body() != null) {
                        _availableTariffs.value = response.body()
                    } else {
                        loadBaseTariffs() // Фолбек
                    }
                }
                override fun onFailure(call: Call<List<CarTariffDto>>, t: Throwable) {
                    _isLoading.value = false
                    loadBaseTariffs() // Фолбек
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

    fun startCheckingCardBinding() {
        profilePollingRunnable = object : Runnable {
            override fun run() {
                // Вызов очищен от ручной передачи токена
                ApiClient.instance.getClientProfile().enqueue(object : Callback<com.taxiapp.client.network.ClientProfileResponse> {
                    override fun onResponse(call: Call<com.taxiapp.client.network.ClientProfileResponse>, response: Response<com.taxiapp.client.network.ClientProfileResponse>) {
                        if (response.isSuccessful) {
                            val profile = response.body()
                            // Если сервер получил вебхук от LiqPay и записал маску:
                            if (profile != null && !profile.cardMask.isNullOrEmpty()) {
                                sessionManager.saveCardMask(profile.cardMask) // Сохраняем локально
                                _cardBoundEvent.value = true // Даем сигнал Activity!
                                stopCheckingCardBinding() // Останавливаем проверку
                            } else {
                                // Карты еще нет, ждем 3 сек. ИСПОЛЬЗУЕМ ПЕРЕМЕННУЮ ВМЕСТО this@Runnable
                                profilePollingRunnable?.let { profilePollingHandler.postDelayed(it, 3000) }
                            }
                        } else {
                            profilePollingRunnable?.let { profilePollingHandler.postDelayed(it, 3000) }
                        }
                    }
                    override fun onFailure(call: Call<com.taxiapp.client.network.ClientProfileResponse>, t: Throwable) {
                        profilePollingRunnable?.let { profilePollingHandler.postDelayed(it, 3000) }
                    }
                })
            }
        }
        // Запускаем в первый раз
        profilePollingHandler.post(profilePollingRunnable!!)
    }

    fun stopCheckingCardBinding() {
        profilePollingRunnable?.let { profilePollingHandler.removeCallbacks(it) }
    }

    fun resetCardBoundEvent() {
        _cardBoundEvent.value = false
    }

    // --- API: Маршрут ---
    fun fetchDirections(origin: LatLng, dest: LatLng, waypoints: List<Pair<LatLng, String>>) {
        // 1. ФІКС: Очищаємо старий маршрут, щоб гарантовано не відправити серверу старі кілометри!
        currentRoutePolyline = null

        val originStr = "${origin.latitude},${origin.longitude}"
        val destStr = "${dest.latitude},${dest.longitude}"
        val wpStr = if (waypoints.isNotEmpty()) {
            "optimize:false|" + waypoints.joinToString("|") { "${it.first.latitude},${it.first.longitude}" }
        } else null

        val myApiKey = "AIzaSyCcKH30fg81bqdUs62QzOBhmpy8hCOHNkI"

        DirectionsApiClient.instance.getDirections(originStr, destStr, wpStr, myApiKey)
            .enqueue(object : Callback<DirectionsResponse> {
                override fun onResponse(call: Call<DirectionsResponse>, response: Response<DirectionsResponse>) {
                    if (response.isSuccessful && !response.body()?.routes.isNullOrEmpty()) {
                        val route = response.body()!!.routes[0]
                        val polylinePoints = route.overviewPolyline.points
                        currentRoutePolyline = polylinePoints

                        // 🔥 ОПТИМИЗАЦИЯ 60 FPS: Уводим тяжелые вычисления и декодирование в фоновый поток
                        viewModelScope.launch(Dispatchers.Default) {
                            var dist = 0L
                            var dur = 0L
                            route.legs.forEach {
                                dist += it.distance.meters
                                dur += it.duration.seconds
                            }

                            val finalDist = dist.toInt()
                            val finalDur = dur.toInt()

                            // Метод decode для сложных маршрутов больше не блокирует UI-поток приложения
                            val decoded = com.google.maps.android.PolyUtil.decode(polylinePoints)

                            // Возвращаемся на главный поток исключительно для безопасной публикации данных в UI
                            withContext(Dispatchers.Main) {
                                _routeInfo.value = Pair(finalDist, finalDur)
                                _decodedRoute.value = decoded

                                // Передаємо кількість проміжних точок (waypoints.size) и автоматически рассчитываем цену
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
                _isLoading.value = false
                if (response.isSuccessful && response.body() != null) {
                    // УДАЛИЛИ stopStatusPolling() отсюда
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

    private fun stopOrderStatusService(orderId: String) { // <-- ИЗМЕНИЛИ С Long НА String
        val context = getApplication<Application>()

        // 1. Прямо і безпечно зупиняємо сам сервіс (це працює навіть з фону)
        val intent = android.content.Intent(context, com.taxiapp.client.service.OrderStatusService::class.java)
        context.stopService(intent)

        // 2. Для 100% надійності примусово прибираємо нотифікацію
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.cancel(orderId.hashCode()) // <-- Превращаем UUID строку в уникальный Int ID для шторки уведомлений
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
                        
                        // ПОЛЛИНГ БОЛЬШЕ НЕ ЗАПУСКАЕМ. Всё подхватит WebSocket подписка!
                    } else {
                        _scheduledOrderSuccess.value = order
                        updateOrderStatusService(order)
                    }
                } else {
                    // Твой текущий код обработки ошибок оставляем без изменений...
                }
            }
            override fun onFailure(call: Call<TaxiOrderDto>, t: Throwable) {
                _isLoading.value = false
                _errorMessage.value = "Помилка мережі"
            }
        })
    }

    // --- API: Отмена заказа ----
    // --- API: Отмена заказа ----
    fun cancelOrder() {
        val id = activeOrderId ?: return
        _isLoading.value = true

        ApiClient.instance.cancelOrder(id).enqueue(object : Callback<TaxiOrderDto> {
            override fun onResponse(call: Call<TaxiOrderDto>, response: Response<TaxiOrderDto>) {
                _isLoading.value = false
                if (response.isSuccessful && response.body() != null) {
                    // УДАЛИЛИ stopStatusPolling() отсюда

                    // ДОБАВЛЕНО: Передаем отмененный заказ прямо в UI!
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

    

    fun startStatusPolling() {
    
    }

    // --- API: Смена типа оплаты "на лету" ---
    fun updateActiveOrderPaymentMethod(method: String) {
    val id = activeOrderId ?: return
    _isLoading.value = true
    ApiClient.instance.updatePaymentMethod(id, method).enqueue(object : Callback<MessageResponseDto> {
        override fun onResponse(call: Call<MessageResponseDto>, response: Response<MessageResponseDto>) {
            _isLoading.value = false
            if (response.isSuccessful) {
                // 🔥 ФИКС БАГА 1: Мгновенно обновляем локальный объект заказа в LiveData
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
                    // Мгновенно обновляем локальный объект заказа новой ценой и надбавкой
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

    fun stopStatusPolling() {
    }

    // Очистка состояния
    fun clearOrderState() {
        activeOrderId?.let { stopOrderStatusService(it) }
        activeOrderId = null
        sessionManager.clearActiveOrderId()
        _activeOrder.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopOrderSocketListening()
    }
}