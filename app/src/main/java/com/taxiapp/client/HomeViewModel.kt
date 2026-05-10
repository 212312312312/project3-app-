package com.taxiapp.client

import android.app.Application
import android.os.Handler
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

    private val _cardBoundEvent = MutableLiveData<Boolean>()
    val cardBoundEvent: LiveData<Boolean> get() = _cardBoundEvent

    private var profilePollingHandler = Handler(Looper.getMainLooper())
    private var profilePollingRunnable: Runnable? = null

    private val _orderStatus = MutableLiveData<String>()
    val orderStatus: LiveData<String> get() = _orderStatus

    private val _routeInfo = MutableLiveData<Pair<Int, Int>>() // DistanceMeters, DurationSeconds
    val routeInfo: LiveData<Pair<Int, Int>> get() = _routeInfo

    private val _decodedRoute = MutableLiveData<List<LatLng>>()
    val decodedRoute: LiveData<List<LatLng>> get() = _decodedRoute

    // --- Состояние ---
    var activeOrderId: Long? = null
    var currentCity: CityData? = null
    var currentRoutePolyline: String? = null

    // Таймер для опроса статуса
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRunnable = object : Runnable {
        override fun run() {
            checkOrderStatus()
            statusHandler.postDelayed(this, 3000)
        }
    }

    init {
        // При старте проверяем, есть ли активный заказ
        val savedId = sessionManager.fetchActiveOrderId()
        if (savedId != -1L) {
            activeOrderId = savedId
            startStatusPolling()
        }
        currentCity = sessionManager.fetchUserCity()
    }

    // --- API: Тарифы и Цена ---
    fun loadTariffsAndCalculatePrice(routePolyline: String?, distanceMeters: Int) {
        _isLoading.value = true

        // 1. Проверяем промокод (логику можно расширить)
        val promoPercent = sessionManager.fetchPromoDiscount()

        // 2. Если есть маршрут - считаем цену на сервере
        if (routePolyline != null && distanceMeters > 0) {
            val request = CalculatePriceRequestDto(routePolyline, distanceMeters)
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
                val token = sessionManager.fetchAuthToken() ?: return
                
                ApiClient.instance.getClientProfile("Bearer $token").enqueue(object : Callback<com.taxiapp.client.network.ClientProfileResponse> {
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
                    currentRoutePolyline = route.overviewPolyline.points

                    var dist = 0L
                    var dur = 0L
                    route.legs.forEach {
                        dist += it.distance.meters
                        dur += it.duration.seconds
                    }

                    val finalDist = dist.toInt()
                    val finalDur = dur.toInt()

                    // 2. СНАЧАЛА оновлюємо дистанцію в UI (щоб локальний бекап-розрахунок знав точні км)
                    _routeInfo.value = Pair(finalDist, finalDur)
                    val decoded = com.google.maps.android.PolyUtil.decode(currentRoutePolyline)
                    _decodedRoute.value = decoded

                    // 3. І ТІЛЬКИ ПОТІМ автоматично запитуємо точну ціну у нашого сервера!
                    loadTariffsAndCalculatePrice(currentRoutePolyline, finalDist)
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

    fun cancelOrder(reasonText: String? = null) { // <-- ДОДАНО ПАРАМЕТР
        val id = activeOrderId ?: return
        val token = sessionManager.fetchAuthToken() ?: return
        _isLoading.value = true

        // Передаємо reasonText в ApiClient
        ApiClient.instance.cancelOrder("Bearer $token", id, reasonText).enqueue(object : Callback<TaxiOrderDto> {
            override fun onResponse(call: Call<TaxiOrderDto>, response: Response<TaxiOrderDto>) {
                _isLoading.value = false
                if (response.isSuccessful && response.body() != null) {
                    stopStatusPolling()
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

    private fun stopOrderStatusService(orderId: Long) {
        val context = getApplication<Application>()

        // 1. Прямо і безпечно зупиняємо сам сервіс (це працює навіть з фону)
        val intent = android.content.Intent(context, com.taxiapp.client.service.OrderStatusService::class.java)
        context.stopService(intent)

        // 2. Для 100% надійності примусово прибираємо нотифікацію
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.cancel(orderId.toInt())
    }

    // --- API: Создание заказа ---
    fun createOrder(request: CreateOrderRequestDto) {
        val token = sessionManager.fetchAuthToken() ?: return
        _isLoading.value = true

        ApiClient.instance.createOrder("Bearer $token", request).enqueue(object : Callback<TaxiOrderDto> {
            override fun onResponse(call: Call<TaxiOrderDto>, response: Response<TaxiOrderDto>) {
                _isLoading.value = false
                if (response.isSuccessful) {
                    val order = response.body()

                    if (order != null) { // Безпечна перевірка замість небезпечного !!
                        if (order.status != "SCHEDULED") {
                            activeOrderId = order.id
                            sessionManager.saveActiveOrderId(order.id)
                            _activeOrder.value = order

                            // Запускаємо віджет!
                            updateOrderStatusService(order)

                            startStatusPolling()
                        } else {
                            _errorMessage.value = "Замовлення заплановано на ${order.scheduledAt}"
                            // Для запланированого також показуємо віджет
                            updateOrderStatusService(order)
                        }
                    } else {
                        _errorMessage.value = "Помилка: сервер повернув порожню відповідь"
                    }
                } else {
                    _errorMessage.value = "Помилка створення: ${response.message()}"
                }
            }
            override fun onFailure(call: Call<TaxiOrderDto>, t: Throwable) {
                _isLoading.value = false
                _errorMessage.value = "Помилка мережі"
            }
        })
    }

    // --- API: Отмена заказа ----
    fun cancelOrder() {
        val id = activeOrderId ?: return
        val token = sessionManager.fetchAuthToken() ?: return
        _isLoading.value = true

        ApiClient.instance.cancelOrder("Bearer $token", id).enqueue(object : Callback<TaxiOrderDto> {
            override fun onResponse(call: Call<TaxiOrderDto>, response: Response<TaxiOrderDto>) {
                _isLoading.value = false
                if (response.isSuccessful && response.body() != null) {
                    stopStatusPolling()

                    // ДОБАВЛЕНО: Передаем отмененный заказ прямо в UI!
                    // HomeActivity сама увидит статус "CANCELLED", покажет его на 3 секунды
                    // и затем вызовет clearOrderState() для очистки ID.
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

    // --- Логика опроса статуса ---
    private fun checkOrderStatus() {
        val id = activeOrderId ?: return
        val token = sessionManager.fetchAuthToken() ?: return

        ApiClient.instance.getOrder("Bearer $token", id).enqueue(object : Callback<TaxiOrderDto> {
            override fun onResponse(call: Call<TaxiOrderDto>, response: Response<TaxiOrderDto>) {
                if (response.isSuccessful) {
                    val order = response.body()
                    if (order != null) {
                        _activeOrder.value = order

                        // ДОБАВЛЕНО: Синхронизируем статус с виджетом
                        updateOrderStatusService(order)

                        if (order.status == "COMPLETED" || order.status == "CANCELLED") {
                            // ДОБАВЛЕНО: Убиваем виджет, если заказ всё
                            stopOrderStatusService(order.id)
                            stopStatusPolling()
                        }
                    }
                }
            }
            override fun onFailure(call: Call<TaxiOrderDto>, t: Throwable) {}
        })
    }

    fun startStatusPolling() {
        statusHandler.removeCallbacks(statusRunnable)
        statusHandler.post(statusRunnable)
    }

    // --- API: Смена типа оплаты "на лету" ---
    fun updateActiveOrderPaymentMethod(method: String) {
        val id = activeOrderId ?: return
        val token = sessionManager.fetchAuthToken() ?: return
        _isLoading.value = true

        ApiClient.instance.updatePaymentMethod("Bearer $token", id, method).enqueue(object : Callback<MessageResponseDto> {
            override fun onResponse(call: Call<MessageResponseDto>, response: Response<MessageResponseDto>) {
                _isLoading.value = false
                if (response.isSuccessful) {
                    // Сервер сам пришлет WebSocket обновление,
                    // но мы можем пнуть поллинг для надежности
                    startStatusPolling()
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
        val token = sessionManager.fetchAuthToken() ?: return
        _isLoading.value = true

        ApiClient.instance.updateOrderPrice("Bearer $token", id, addedValue).enqueue(object : Callback<MessageResponseDto> {
            override fun onResponse(call: Call<MessageResponseDto>, response: Response<MessageResponseDto>) {
                _isLoading.value = false
                if (response.isSuccessful) {
                    // Форсируем обновление заказа
                    startStatusPolling()
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
        statusHandler.removeCallbacks(statusRunnable)
    }

    fun clearOrderState() {
        activeOrderId?.let { stopOrderStatusService(it) }
        activeOrderId = null
        sessionManager.clearActiveOrderId()
        stopStatusPolling()

        // ДОБАВЛЕНА ЭТА СТРОКА:
        // Очищаем LiveData, чтобы при пересоздании Activity (например, при смене темы)
        // обзервер не получил старый "призрачный" заказ и не показал карточку.
        _activeOrder.value = null
    }


    override fun onCleared() {
        super.onCleared()
        stopStatusPolling()
    }
}