package com.taxiapp.client

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.maps.android.SphericalUtil
import com.taxiapp.client.analytics.AnalyticsManager
import com.taxiapp.client.controllers.ActiveOrderUiController
import com.taxiapp.client.controllers.HomeUiController
import com.taxiapp.client.controllers.MapRouteController
import com.taxiapp.client.controllers.MarkerAnimationController
import com.taxiapp.client.controllers.OrderRatingController
import com.taxiapp.client.controllers.OrderTimersController
import com.taxiapp.client.controllers.SearchRadarController
import com.taxiapp.client.databinding.ActivityHomeBinding
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.WebSocketManager
import com.taxiapp.client.network.dto.CancellationReasonDto
import com.taxiapp.client.network.dto.CarTariffDto
import com.taxiapp.client.network.dto.TaxiOrderDto
import com.taxiapp.client.network.dto.TrackingLocationDto
import com.taxiapp.client.ui.TariffAdapter
import com.taxiapp.client.ui.TariffItem
import com.taxiapp.client.ui.dialogs.BoostPriceBottomSheet
import com.taxiapp.client.ui.dialogs.CancelOrderBottomSheet
import com.taxiapp.client.ui.dialogs.ChangePaymentBottomSheet
import com.taxiapp.client.ui.dialogs.ChangePriceBottomSheet
import com.taxiapp.client.ui.dialogs.DriverHealthDialog
import com.taxiapp.client.ui.dialogs.LiqPayWebViewDialog
import com.taxiapp.client.ui.dialogs.RateDriverBottomSheet
import com.taxiapp.client.ui.dialogs.ScheduleRideBottomSheet
import com.taxiapp.client.utils.AddressUtils
import com.taxiapp.client.ChatEventBus
import com.taxiapp.client.utils.CityData
import com.taxiapp.client.utils.CityDatabase
import com.taxiapp.client.utils.LocationValidator
import com.taxiapp.client.NetworkUtils
import com.taxiapp.client.utils.ReverseGeocoderHelper
import com.taxiapp.client.utils.SessionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

class HomeActivity : BaseActivity(), OnMapReadyCallback {

    companion object {
        var lastScreenshot: Bitmap? = null
        private const val MODE_ORIGIN = 1
        private const val MODE_DESTINATION = 2
        private const val MODE_ADD_HOME = 3
        private const val MODE_ADD_WORK = 4
        private const val LOCATION_UPDATE_INTERVAL = 7000L
    }

    private lateinit var binding: ActivityHomeBinding
    private val viewModel: HomeViewModel by viewModels()

    private lateinit var sessionManager: SessionManager
    private var mMap: GoogleMap? = null
    private var webSocketManager: WebSocketManager? = null

    private lateinit var mapRouteController: MapRouteController
    private lateinit var activeOrderUiController: ActiveOrderUiController
    private lateinit var homeUiController: HomeUiController
    private lateinit var orderTimersController: OrderTimersController
    private val searchRadarController = SearchRadarController()
    private val markerAnimationController = MarkerAnimationController()
    private lateinit var tariffAdapter: TariffAdapter

    private var decodedRoutePoints: List<LatLng>? = null
    private var routeDistanceMeters: Int = 0
    private var routeDurationSeconds: Int = 0
    private var activeOrderId: String? = null
    private var availableTariffs: List<CarTariffDto> = emptyList()

    private var lastAddressPickerIntentData: Intent? = null
    private val currentWaypoints = mutableListOf<Pair<LatLng, String>>()

    private var isDriverTrackingActive = false
    private var subscribedChatOrderId: String? = null

    private var isMapPickingMode = false
    private var mapPickerIsOrigin = true
    private var mapPickerTarget = "target_origin"
    private var mapPickerWaypointIndex = -1

    private val nearbyDriversHandler = Handler(Looper.getMainLooper())
    private var nearbyDriversRunnable: Runnable? = null
    private var lastLocationUpdateTime = 0L
    private var lastLocationSent: LatLng? = null

    private var boostDialogFragment: BoostPriceBottomSheet? = null
    private var webViewDialogFragment: LiqPayWebViewDialog? = null
    private var rateDriverDialogFragment: RateDriverBottomSheet? = null
    private var isRatingDialogVisible = false

    private var orderComment: String = ""
    private var currentPaymentMethod: String = "CASH"
    private var hasTrackedTariffsView = false
    private var currentActiveLanguage: String = ""

    private var originPlace: Place? = null
    private var destinationPlace: Place? = null
    private var currentCity: CityData? = null
    private var scheduledDate: Calendar? = null
    private var isRouteMode = false

    private var pickerMode = MODE_ORIGIN
    private var isSelectingOrigin = true
    private var isSuppressingGeocode = false
    private var geocodeJob: Job? = null
    private var mapPickerGeocodeJob: Job? = null

    private var selectedTariffItem: TariffItem? = null
    private var selectedServiceIds = ArrayList<Long>()
    private var servicesExtraCost: Double = 0.0
    private val tariffCustomPrices = mutableMapOf<Long, Double>()

    private val statusCallbacks = object : ActiveOrderUiController.StatusCallbacks {
        override val map: GoogleMap? get() = mMap
        override val originPlace: Place? get() = this@HomeActivity.originPlace
        override val routeDurationSeconds: Int get() = this@HomeActivity.routeDurationSeconds
        override val isDarkMode: Boolean get() = sessionManager.isDarkMode()

        override fun onSetDecodedRoutePoints(points: List<LatLng>?) { decodedRoutePoints = points }
        override fun onDrawStylishRoute(points: List<LatLng>, status: String) { drawStylishRoute(points, status) }
        override fun onDrawPickupRoute(polyline: String?) { drawPickupRoute(polyline) }
        override fun onFocusOnPickupRoute(carLatLng: LatLng) { focusOnPickupRoute(carLatLng) }
        override fun onUpdateDriverMarker(loc: TrackingLocationDto) { updateDriverMarker(loc) }
        override fun onStartDriverTracking(orderId: String) { startDriverTracking(orderId) }
        override fun onStopDriverTracking() { stopDriverTracking() }
        override fun onCheckAndScheduleBoost(order: TaxiOrderDto) { checkAndScheduleBoostPrompt(order) }
        override fun onStopBoostTimer() { stopBoostTimer() }
        override fun onShowDriverHealth(issues: List<String>) { showDriverHealthDialog(issues) }
        override fun onSetRecenterRouteAnchor(anchorId: Int, bottomMarginDp: Float) {
            homeUiController.setRecenterRouteButtonAnchor(anchorId, convertDpToPixel(bottomMarginDp).toInt())
        }
        override fun onAnimateMapPadding(fromHeight: Int, toHeight: Int, recenterMap: Boolean) {
            animateMapPadding(fromHeight, toHeight, recenterMap)
        }
        override fun onCompletedUnderstandClicked(order: TaxiOrderDto) {
            viewModel.dismissCompletedOrder(order.id)
            if (!order.isRatedByClient) {
                val driverName = order.driver?.fullName ?: getString(R.string.default_driver_name)
                showRatingDialog(order.id, driverName)
            } else {
                viewModel.clearOrderState()
                activeOrderId = null
                showAddressPanel()
            }
        }
        override fun onOrderCancelledReset() {
            mMap?.clear()
            mapRouteController.clearAllRouteElements()
            homeUiController.removeDriverMarker()
            currentWaypoints.clear()
            isRouteMode = false
            viewModel.currentRoutePolyline = null
            decodedRoutePoints = null
            binding.centerPin.translationY = 0f
            binding.centerPin.animate().cancel()
            binding.centerPin.alpha = 1f
            binding.centerPin.visibility = View.VISIBLE
            try { binding.pinShadow.visibility = View.VISIBLE } catch (_: Exception) {}
            showAddressPanel()
            recenterMapOnUser()
            viewModel.clearOrderState()
        }
    }

    private val commentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            orderComment = result.data!!.getStringExtra("EXTRA_COMMENT") ?: ""
            homeUiController.updateCommentIconState()
        }
    }

    private val addressPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val data = result.data!!
            val action = data.getStringExtra(AddressPickerActivity.RESULT_ACTION)
            parseWaypointsFromPickerIntent(data)

            if (data.hasExtra(AddressPickerActivity.RESULT_ORIGIN_LAT)) {
                val oName = data.getStringExtra(AddressPickerActivity.RESULT_ORIGIN_NAME)
                val oLat = data.getDoubleExtra(AddressPickerActivity.RESULT_ORIGIN_LAT, 0.0)
                val oLng = data.getDoubleExtra(AddressPickerActivity.RESULT_ORIGIN_LNG, 0.0)
                if (oLat != 0.0 && oLng != 0.0) {
                    originPlace = Place.builder().setName(oName).setLatLng(LatLng(oLat, oLng)).build()
                    binding.textViewOrigin.text = AddressUtils.formatAddress(oName ?: "")
                }
            }

            if (action == "place") {
                val name = data.getStringExtra(AddressPickerActivity.RESULT_NAME)
                val lat = data.getDoubleExtra(AddressPickerActivity.RESULT_LAT, 0.0)
                val lng = data.getDoubleExtra(AddressPickerActivity.RESULT_LNG, 0.0)
                val place = Place.builder().setName(name).setLatLng(LatLng(lat, lng)).build()
                handleAddressSelection(place, name)
            } else if (action == "map_click") {
                lastAddressPickerIntentData = data
                mapPickerTarget = data.getStringExtra(AddressPickerActivity.RESULT_TARGET_TYPE) ?: AddressPickerActivity.TARGET_ORIGIN
                mapPickerWaypointIndex = data.getIntExtra(AddressPickerActivity.RESULT_WAYPOINT_INDEX, -1)

                showMapPickerMode(mapPickerTarget == AddressPickerActivity.TARGET_ORIGIN)

                val startLatLng = when (mapPickerTarget) {
                    AddressPickerActivity.TARGET_ORIGIN -> originPlace?.latLng
                    AddressPickerActivity.TARGET_DESTINATION -> destinationPlace?.latLng
                    AddressPickerActivity.TARGET_WAYPOINT -> currentWaypoints.getOrNull(mapPickerWaypointIndex)?.first
                    else -> null
                } ?: mMap?.cameraPosition?.target

                if (startLatLng != null) {
                    mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(startLatLng, 16f))
                    binding.tvMapPickerAddress.text = "Шукаємо адресу..."
                    getAddressFromLocation(startLatLng) { address ->
                        binding.tvMapPickerAddress.text = address ?: "Невідома адреса"
                    }
                }
            }
        }
    }

    private fun parseWaypointsFromPickerIntent(data: Intent) {
        val wLats = data.getDoubleArrayExtra(AddressPickerActivity.RESULT_WAYPOINTS_LATS)
        val wLngs = data.getDoubleArrayExtra(AddressPickerActivity.RESULT_WAYPOINTS_LNGS)
        val wNames = data.getStringArrayListExtra(AddressPickerActivity.RESULT_WAYPOINTS_NAMES)
        currentWaypoints.clear()
        if (wLats != null && wLngs != null) {
            for (i in wLats.indices) {
                if (wLats[i] != 0.0 && wLngs[i] != 0.0) {
                    val wpName = if (wNames != null && i < wNames.size && wNames[i].isNotEmpty()) wNames[i] else "Зупинка ${i + 1}"
                    currentWaypoints.add(Pair(LatLng(wLats[i], wLngs[i]), wpName))
                }
            }
        }
    }

    private val paymentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val method = result.data?.getStringExtra("EXTRA_PAYMENT_METHOD")
            if (method != null) {
                currentPaymentMethod = method
                homeUiController.updatePaymentIcon(currentPaymentMethod)
            }
        }
    }

    private val cityPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val cityName = result.data!!.getStringExtra(CityPickerActivity.RESULT_CITY_NAME)
            if (cityName != null) {
                val regionData = CityDatabase.regions[cityName]!!
                currentCity = CityData(cityName, regionData.center.latitude, regionData.center.longitude, regionData.zoom)
                sessionManager.saveUserCity(currentCity!!)
                homeUiController.updateCityUI(cityName)
                val latLng = LatLng(regionData.center.latitude, regionData.center.longitude)
                mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, regionData.zoom))
                resetUI()
            }
        }
    }

    private val servicesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val data = result.data!!
            servicesExtraCost = data.getDoubleExtra("EXTRA_COST", 0.0)
            selectedServiceIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                data.getSerializableExtra("SELECTED_IDS", ArrayList::class.java) as? ArrayList<Long>
            } else {
                @Suppress("DEPRECATION")
                data.getSerializableExtra("SELECTED_IDS") as? ArrayList<Long>
            } ?: ArrayList()

            tariffAdapter.updateExtraCost(servicesExtraCost)
            binding.btnOrderTaxi.isEnabled = false
            binding.btnOrderTaxi.text = "Оберіть тариф"
            selectedTariffItem = null
            showToast(if (selectedServiceIds.isNotEmpty()) "Послугу додано" else "Послугу вилучено")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            try {
                mMap?.isMyLocationEnabled = true
                detectCityAndMove()
            } catch (_: SecurityException) {}
        } else {
            if (currentCity == null) showCitySelectorDialog() else showToast("Увімкніть геолокацію в налаштуваннях")
        }
        checkAndShowNotificationDialogWithDelay()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!NetworkUtils.isInternetAvailable(this)) {
            startActivity(Intent(this, NoInternetActivity::class.java))
            finish()
            return
        }

        sessionManager = SessionManager(applicationContext)
        currentActiveLanguage = sessionManager.getLanguage()
        webSocketManager = WebSocketManager(ApiClient.BASE_URL, this)

        val token = sessionManager.fetchAuthToken()
        if (!token.isNullOrEmpty()) {
            webSocketManager?.connect(token)
        } else {
            Log.e("WS_TAXI_DEBUG", "❌ Токен пуст, не могу подключить сокет!")
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val targetMode = if (!sessionManager.isManualThemeSelected()) {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        } else {
            if (sessionManager.isDarkMode()) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        }
        if (AppCompatDelegate.getDefaultNightMode() != targetMode) {
            AppCompatDelegate.setDefaultNightMode(targetMode)
        }

        val isDark = sessionManager.isDarkMode()
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, BuildConfig.GOOGLE_PLACES_API_KEY, Locale("uk", "UA"))
        }

        currentCity = sessionManager.fetchUserCity()
        orderTimersController = OrderTimersController(this)
        mapRouteController = MapRouteController(this, sessionManager, supportFragmentManager)
        homeUiController = HomeUiController(this, binding, sessionManager, markerAnimationController)
        activeOrderUiController = ActiveOrderUiController(
            activity = this,
            binding = binding,
            sessionManager = sessionManager,
            viewModel = viewModel,
            mapRouteController = mapRouteController,
            orderTimersController = orderTimersController,
            searchRadarController = searchRadarController
        )

        homeUiController.setupSystemBars(isDark)
        homeUiController.fetchCustomCarIcon()

        initUI()
        homeUiController.setupProfileLogic(currentCity?.name)
        setupTariffAdapter()
        homeUiController.updateFavoriteButtonsUI()
        homeUiController.updateThemeLabel()

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        if (currentCity == null) {
            checkPermissionsAndAutoDetectCity()
        }

        homeUiController.renderScreenshotTransitionIfPresent()
        setupViewModelObservers()
        viewModel.startListeningNearbyDrivers(webSocketManager)

        viewModel.nearbyDrivers.observe(this) { drivers ->
            val shouldHide = activeOrderId != null ||
                    isRouteMode ||
                    viewModel.currentRoutePolyline != null ||
                    binding.tariffsPanel.visibility == View.VISIBLE ||
                    isMapPickingMode
            homeUiController.updateNearbyDriversOnMap(mMap, drivers, shouldHide)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.drawerLayout.isDrawerOpen(GravityCompat.START) -> binding.drawerLayout.closeDrawer(GravityCompat.START)
                    isMapPickingMode -> returnToAddressPicker()
                    binding.activeOrderCard.visibility == View.VISIBLE -> {
                        val currentOrder = viewModel.activeOrder.value
                        val targetOrderId = currentOrder?.id ?: activeOrderId
                        if (activeOrderUiController.isOrderDetailsExpanded) {
                            activeOrderUiController.animateOrderDetailsToState(false)
                        } else if (currentOrder?.status == "COMPLETED" && targetOrderId != null) {
                            autoRateAndDismissCompletedOrder(targetOrderId)
                        } else {
                            viewModel.hideOrderFromMainScreen()
                            activeOrderId = null
                            showAddressPanel()
                        }
                    }
                    binding.tariffsPanel.visibility == View.VISIBLE -> showAddressPanel()
                    else -> finish()
                }
            }
        })

        viewModel.loadTariffsAndCalculatePrice(null, 0)
    }

    override fun onResume() {
        super.onResume()
        if (webSocketManager?.isConnected() != true) {
            val token = sessionManager.fetchAuthToken()
            if (!token.isNullOrEmpty()) webSocketManager?.connect(token)
        }

        viewModel.startOrderSocketListening(webSocketManager)
        viewModel.syncActiveOrder()

        val currentStatus = viewModel.activeOrder.value?.status
        if (currentStatus != null && currentStatus != "REQUESTED" && currentStatus != "OFFERING") {
            mapRouteController.resetCameraTilt(mMap)
        }

        val savedLanguage = sessionManager.getLanguage()
        if (currentActiveLanguage != savedLanguage) {
            currentActiveLanguage = savedLanguage
            recreate()
            return
        }

        homeUiController.updateFavoriteButtonsUI()
        homeUiController.updateDrawerHeader()
        updatePaymentMethodFromSession()
        startPeriodicNearbyDriversRefresh()

        if (binding.tariffsPanel.visibility == View.VISIBLE) {
            fetchTariffsAndShowPanel()
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isDestroyed && !isFinishing && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                homeUiController.fetchClientProfile(activeOrderId, currentPaymentMethod, viewModel, webSocketManager)
            }
        }, 2000)
    }

    override fun onStop() {
        super.onStop()
        val currentOrder = viewModel.activeOrder.value
        if (currentOrder?.status == "COMPLETED") {
            val targetOrderId = currentOrder.id ?: activeOrderId
            if (targetOrderId != null) autoRateAndDismissCompletedOrder(targetOrderId)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        geocodeJob?.cancel()
        mapPickerGeocodeJob?.cancel()
        orderTimersController.stopWaitingTimer(binding.cardWaitingTimer)
        stopDriverTracking()
        stopBoostTimer()
        searchRadarController.stop()
        markerAnimationController.cancelAll()
        stopPeriodicNearbyDriversRefresh()
        viewModel.stopOrderSocketListening()
        lastScreenshot?.let { if (!it.isRecycled) it.recycle() }
        lastScreenshot = null
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val savedId = sessionManager.fetchActiveOrderId()
        if (!savedId.isNullOrEmpty()) {
            activeOrderId = savedId
            viewModel.activeOrderId = savedId
            viewModel.checkOrderStatusOnce(savedId)
        }
    }

    private fun initUI() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            params.topMargin = insets.top
            params.bottomMargin = insets.bottom
            view.layoutParams = params
            WindowInsetsCompat.CONSUMED
        }

        binding.drawerLayout.setStatusBarBackground(null)
        binding.centerPin.alpha = 0f
        try { binding.pinShadow.alpha = 0f } catch (_: Exception) {}

        binding.btnBackMapPicker.setOnClickListener { returnToAddressPicker() }
        if (isMapPickingMode) returnToAddressPicker()
        binding.btnConfirmMapPicker.setOnClickListener { confirmMapSelection() }
        binding.btnChangePrice.setOnClickListener { showChangePriceDialog() }
        binding.btnOpenPromo.setOnClickListener { startActivity(Intent(this, PromoActivity::class.java)) }

        val maxTariffHeight = (resources.displayMetrics.heightPixels * 0.45).toInt()
        binding.tariffsPanel.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (binding.tariffsPanel.height > maxTariffHeight && binding.tariffsPanel.layoutParams.height != maxTariffHeight) {
                    val params = binding.tariffsPanel.layoutParams
                    params.height = maxTariffHeight
                    binding.tariffsPanel.layoutParams = params
                    binding.tariffsPanel.requestLayout()
                    return false
                }
                return true
            }
        })

        binding.btnOrderTaxi.setOnClickListener {
            if (selectedTariffItem != null) {
                AnalyticsManager.trackCustomEvent("click_order", selectedTariffItem!!.tariff.name)
                val currentItem = tariffAdapter.getSelectedTariff() ?: selectedTariffItem!!
                selectedTariffItem = currentItem
                createOrder(currentItem.tariff, currentItem.priceValue)
            } else {
                showToast("Оберіть тариф")
            }
        }

        binding.btnAddNewOrder.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Нове замовлення")
                .setMessage("Бажаєте створити ще одне замовлення?")
                .setPositiveButton("Так") { dialog, _ ->
                    dialog.dismiss()
                    viewModel.hideOrderFromMainScreen()
                    activeOrderId = null
                    showAddressPanel()
                }
                .setNegativeButton("Ні") { dialog, _ -> dialog.dismiss() }
                .show()
        }

        binding.btnSchedule.setOnClickListener {
            AnalyticsManager.trackCustomEvent("click_schedule")
            showCustomScheduleDialog()
        }

        binding.btnOpenComment.setOnClickListener {
            AnalyticsManager.trackCustomEvent("click_comment")
            val intent = Intent(this, CommentActivity::class.java)
            intent.putExtra("EXTRA_COMMENT", orderComment)
            commentLauncher.launch(intent)
        }

        activeOrderUiController.setupExpandableDetailsTouchListener()

        binding.activeOrderCard.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, oldTop, oldBottom ->
            val newHeight = bottom - top
            val oldHeight = oldBottom - oldTop
            if (binding.activeOrderCard.visibility == View.VISIBLE && newHeight > 0 && newHeight != oldHeight) {
                if (mMap != null && !isDestroyed && !isFinishing) {
                    updateMapPadding(binding.activeOrderCard, extraBottomDp = 4f, topPaddingDp = 20f, recenterMap = false)
                }
            }
        }

        binding.btnMenu.setOnClickListener {
            when {
                binding.activeOrderCard.visibility == View.VISIBLE -> {
                    val currentOrder = viewModel.activeOrder.value
                    val targetOrderId = currentOrder?.id ?: activeOrderId
                    if (currentOrder?.status == "COMPLETED" && targetOrderId != null) {
                        autoRateAndDismissCompletedOrder(targetOrderId)
                    } else {
                        viewModel.hideOrderFromMainScreen()
                        activeOrderId = null
                        showAddressPanel()
                    }
                }
                binding.tariffsPanel.visibility == View.VISIBLE -> showAddressPanel()
                else -> binding.drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        binding.btnRecenterLocation.setOnClickListener {
            if (currentCity == null) checkPermissionsAndAutoDetectCity() else recenterMapOnUser()
        }

        binding.btnRecenterRoute.setOnClickListener {
            if (viewModel.currentRoutePolyline != null && originPlace != null && destinationPlace != null) {
                try {
                    mapRouteController.resetCameraTilt(mMap)
                    val boundsBuilder = LatLngBounds.Builder()
                    boundsBuilder.include(originPlace!!.latLng!!)
                    boundsBuilder.include(destinationPlace!!.latLng!!)
                    currentWaypoints.forEach { boundsBuilder.include(it.first) }
                    decodedRoutePoints?.forEach { boundsBuilder.include(it) }
                    val paddingSide = convertDpToPixel(80f).toInt()
                    mMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), paddingSide))
                    binding.btnRecenterRoute.visibility = View.GONE
                } catch (_: Exception) {}
            }
        }

        val openOrigin = View.OnClickListener {
            if (binding.textViewOrigin.text.toString() == "Визначення...") return@OnClickListener
            pickerMode = MODE_ORIGIN
            isSelectingOrigin = true
            openAddressPicker(true)
        }
        binding.containerOrigin.setOnClickListener(openOrigin)
        binding.textViewOrigin.setOnClickListener(openOrigin)

        val openDest = View.OnClickListener {
            if (binding.textViewOrigin.text.toString() == "Визначення...") return@OnClickListener
            pickerMode = MODE_DESTINATION
            isSelectingOrigin = false
            openAddressPicker(false)
        }
        binding.containerDestination.setOnClickListener(openDest)
        binding.textViewDestination.setOnClickListener(openDest)

        binding.btnFavHome.setOnClickListener { onFavoriteAddressClick(true) }
        binding.btnFavWork.setOnClickListener { onFavoriteAddressClick(false) }

        binding.btnCallDriver.setOnClickListener {
            val driverPhone = binding.activeOrderCard.tag as? String
            if (!driverPhone.isNullOrEmpty()) {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$driverPhone")))
            } else {
                showToast("Номер недоступний")
            }
        }

        binding.btnCancelOrder.setOnClickListener { activeOrderUiController.cancelCurrentOrder(::showCustomCancelDialog) }
        binding.btnCancelRideDriver.setOnClickListener { activeOrderUiController.cancelCurrentOrder(::showCustomCancelDialog) }

        setupMenuLogic()
    }

    private fun setupMenuLogic() {
        homeUiController.setupDrawerNavigationListeners(
            onOpenCitySelector = { showCitySelectorDialog() },
            onOpenPaymentScreen = {
                val intent = Intent(this, PaymentActivity::class.java)
                intent.putExtra("EXTRA_PAYMENT_METHOD", currentPaymentMethod)
                paymentLauncher.launch(intent)
            }
        )

        binding.btnSearchChangePayment.setOnClickListener {
            activeOrderUiController.animateOrderDetailsToState(false)
            showChangePaymentDialog()
        }

        binding.btnSearchChangePrice.setOnClickListener {
            activeOrderUiController.animateOrderDetailsToState(false)
            val order = viewModel.activeOrder.value
            if (order != null) {
                val basePrice = (order.effectiveClientPrice - order.addedValue).coerceAtLeast(1.0)
                showChangePriceDialogForActiveOrder(basePrice, order.addedValue)
            }
        }

        binding.btnOpenPayment.setOnClickListener {
            val intent = Intent(this, PaymentActivity::class.java)
            intent.putExtra("EXTRA_PAYMENT_METHOD", currentPaymentMethod)
            paymentLauncher.launch(intent)
        }

        binding.btnOpenServices.setOnClickListener {
            val intent = Intent(this, ServicesActivity::class.java)
            intent.putExtra("SELECTED_IDS", selectedServiceIds)
            servicesLauncher.launch(intent)
        }

        binding.btnChatDriver.setOnClickListener {
            val order = viewModel.activeOrder.value
            if (order?.driver?.id == -1L) {
                showToast(getString(R.string.toast_chat_unavailable_partner))
                return@setOnClickListener
            }

            activeOrderId?.let { orderId ->
                activeOrderUiController.unreadChatMessages = 0
                ChatEventBus.clearUnreadCount()
                activeOrderUiController.updateChatBadgeUI()
                val intent = Intent(this, ChatActivity::class.java)
                intent.putExtra("ORDER_ID", orderId)
                startActivity(intent)
            }
        }
    }

    private fun setupViewModelObservers() {
        viewModel.decodedRoute.observe(this) { points ->
            if (!points.isNullOrEmpty()) {
                val origin = originPlace?.latLng
                val dest = destinationPlace?.latLng
                if (origin != null && dest != null) {
                    val startDist = SphericalUtil.computeDistanceBetween(points.first(), origin)
                    val endDist = SphericalUtil.computeDistanceBetween(points.last(), dest)
                    if (startDist > 2000.0 || endDist > 2000.0) return@observe
                }
                decodedRoutePoints = points
                drawStylishRoute(points, viewModel.activeOrder.value?.status ?: "")
            }
        }

        viewModel.routeInfo.observe(this) { (dist, dur) ->
            routeDistanceMeters = dist
            routeDurationSeconds = dur
        }

        viewModel.availableTariffs.observe(this) { tariffs ->
            availableTariffs = tariffs
            displayTariffs()
            viewModel.activeOrder.value?.let { order ->
                activeOrderUiController.updateActiveOrderTariffIcon(order, availableTariffs)
            }
        }

        viewModel.cardBoundEvent.observe(this) { isBound ->
            if (isBound) {
                webViewDialogFragment?.dismissAllowingStateLoss()
                webViewDialogFragment = null
                viewModel.updateActiveOrderPaymentMethod("CARD")
                currentPaymentMethod = "CARD"
                homeUiController.updatePaymentIcon(currentPaymentMethod)
                showToast("Картку успішно прив'язано!")
                viewModel.resetCardBoundEvent()
            }
        }

        lifecycleScope.launch {
            ChatEventBus.unreadCount.collect { count ->
                activeOrderUiController.unreadChatMessages = count
                activeOrderUiController.updateChatBadgeUI()
            }
        }

        viewModel.activeOrder.observe(this) { order ->
            if (order != null) {
                activeOrderId = order.id
                if (order.status != "REQUESTED" && order.status != "OFFERING") {
                    mapRouteController.resetCameraTilt(mMap)
                }

                if (order.status == "COMPLETED" || order.status == "CANCELLED") {
                    activeOrderUiController.unreadChatMessages = 0
                    ChatEventBus.clearUnreadCount()
                    activeOrderUiController.updateChatBadgeUI()
                    subscribedChatOrderId = null
                    if (order.status == "COMPLETED") {
                        showActiveOrderPanel(order)
                    } else {
                        activeOrderUiController.updateStatusUI(order, statusCallbacks)
                    }
                } else {
                    if (order.driver != null && order.driver.id != -1L && subscribedChatOrderId != order.id) {
                        subscribedChatOrderId = order.id
                        webSocketManager?.subscribeToChat(order.id) { msg ->
                            if (msg.senderRole != "CLIENT" && !ChatEventBus.isChatScreenOpen) {
                                runOnUiThread { incrementUnreadMessages() }
                            }
                        }
                    }
                    restoreOrderOnMap(order)
                    showActiveOrderPanel(order)

                    binding.activeOrderCard.post {
                        if (!isDestroyed && !isFinishing) {
                            val shouldRecenter = (order.status == "REQUESTED" || order.status == "OFFERING")
                            updateMapPadding(binding.activeOrderCard, extraBottomDp = 4f, topPaddingDp = 20f, recenterMap = shouldRecenter)
                        }
                    }
                }
            } else {
                activeOrderId = null
                showAddressPanel()
                mMap?.clear()
            }
        }

        viewModel.isLoading.observe(this) { loading ->
            homeUiController.setButtonsLoadingState(loading)
            if (loading) {
                if (activeOrderId == null && availableTariffs.isEmpty()) {
                    binding.tariffsRecyclerView.visibility = View.GONE
                    binding.tariffsShimmerContainer.visibility = View.VISIBLE
                    binding.tariffsShimmerContainer.startShimmer()
                }
            } else {
                binding.tariffsShimmerContainer.stopShimmer()
                binding.tariffsShimmerContainer.visibility = View.GONE
                if (availableTariffs.isNotEmpty()) binding.tariffsRecyclerView.visibility = View.VISIBLE
            }
        }

        viewModel.errorMessage.observe(this) { msg ->
            showToast(msg)
            binding.btnOrderTaxi.isEnabled = true
            binding.btnOrderTaxi.text = getString(R.string.btn_order)
            binding.btnCancelOrder.isEnabled = true
            binding.btnCancelOrder.text = "Скасувати замовлення"
            binding.tariffsShimmerContainer.stopShimmer()
            binding.tariffsShimmerContainer.visibility = View.GONE
            if (availableTariffs.isNotEmpty()) binding.tariffsRecyclerView.visibility = View.VISIBLE
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap?.uiSettings?.apply {
            isRotateGesturesEnabled = false
            isTiltGesturesEnabled = false
            isMapToolbarEnabled = false
            isCompassEnabled = false
            isZoomControlsEnabled = false
            isMyLocationButtonEnabled = false
            isIndoorLevelPickerEnabled = false
        }
        mMap?.isBuildingsEnabled = false
        mMap?.isIndoorEnabled = false

        val styleRes = if (sessionManager.isDarkMode()) R.raw.map_style_dark else R.raw.map_style_standard
        try {
            mMap?.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, styleRes))
        } catch (_: Exception) {
            if (!sessionManager.isDarkMode()) mMap?.setMapStyle(null)
        }

        val activeOrder = viewModel.activeOrder.value
        if (activeOrder == null) {
            val cityToLoad = currentCity ?: sessionManager.fetchUserCity()
            val cityCenter = cityToLoad?.let { LatLng(it.lat, it.lng) } ?: LatLng(50.4501, 30.5234)
            val cityZoom = cityToLoad?.zoom ?: 11f
            mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(cityCenter, cityZoom))

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                mMap?.isMyLocationEnabled = true
                if (currentCity != null) recenterMapOnUser()
                updateMapPadding(binding.bottomSheetCard, extraBottomDp = 2f, topPaddingDp = 20f, recenterMap = false)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                mMap?.isMyLocationEnabled = true
            }
            if (activeOrder.status != "REQUESTED" && activeOrder.status != "OFFERING") {
                mapRouteController.resetCameraTilt(mMap)
            }
            restoreOrderOnMap(activeOrder)
            showActiveOrderPanel(activeOrder)

            binding.activeOrderCard.post {
                if (!isDestroyed && !isFinishing) {
                    updateMapPadding(binding.activeOrderCard, extraBottomDp = 2f, topPaddingDp = 20f, recenterMap = true)
                }
            }
        }

        mMap?.setOnCameraMoveListener {
            if (viewModel.currentRoutePolyline != null) updateSmartLabels()
        }

        mMap?.setOnCameraMoveStartedListener { reason ->
            if (viewModel.currentRoutePolyline == null && !isRouteMode) {
                if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                    geocodeJob?.cancel()
                    if (isMapPickingMode) {
                        mapPickerGeocodeJob?.cancel()
                        binding.tvMapPickerAddress.text = "Шукаємо адресу..."
                    } else {
                        binding.textViewOrigin.text = "Визначення..."
                    }
                    if (homeUiController.isInterfaceRevealed || isMapPickingMode) {
                        homeUiController.animatePinLift(::convertDpToPixel)
                    }
                }
            } else if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                val currentStatus = viewModel.activeOrder.value?.status
                if (currentStatus == "IN_PROGRESS" || (activeOrderId == null && binding.tariffsPanel.visibility == View.VISIBLE)) {
                    binding.btnRecenterRoute.visibility = View.VISIBLE
                }
            }
        }

        mMap?.setOnCameraIdleListener {
            val currentStatus = viewModel.activeOrder.value?.status
            if (currentStatus != "REQUESTED" && currentStatus != "OFFERING" && (mMap?.cameraPosition?.tilt ?: 0f) > 0.1f) {
                mapRouteController.resetCameraTilt(mMap)
            }

            val target = mMap!!.cameraPosition.target
            val currentTime = System.currentTimeMillis()
            val distanceMoved = lastLocationSent?.let { SphericalUtil.computeDistanceBetween(it, target) } ?: 1000.0

            val isMainScreen = activeOrderId == null &&
                    !isRouteMode &&
                    viewModel.currentRoutePolyline == null &&
                    binding.tariffsPanel.visibility != View.VISIBLE &&
                    !isMapPickingMode

            if (isMainScreen && (currentTime - lastLocationUpdateTime > LOCATION_UPDATE_INTERVAL || distanceMoved > 50.0)) {
                lastLocationUpdateTime = currentTime
                lastLocationSent = target
                viewModel.updateClientLocation(webSocketManager, target.latitude, target.longitude)
            }

            if ((homeUiController.isInterfaceRevealed && !isRouteMode && viewModel.currentRoutePolyline == null) || isMapPickingMode) {
                homeUiController.animatePinDrop(::convertDpToPixel)
            } else if (isRouteMode || viewModel.currentRoutePolyline != null) {
                homeUiController.hidePin()
            }

            if (isMapPickingMode) {
                binding.tvMapPickerAddress.text = "Шукаємо адресу..."
                getAddressFromLocation(target, debounceMs = 400L) { address ->
                    binding.tvMapPickerAddress.text = address ?: "Невідома адреса"
                }
            } else {
                if (viewModel.currentRoutePolyline != null) updateSmartLabels()
                if (isRouteMode || viewModel.currentRoutePolyline != null) return@setOnCameraIdleListener
                if (isSuppressingGeocode) {
                    isSuppressingGeocode = false
                    return@setOnCameraIdleListener
                }
                getAddressForOrigin(target)
            }
        }

        mMap?.setOnMapLoadedCallback {
            homeUiController.revealInterface(isRouteMode || viewModel.currentRoutePolyline != null)
        }
    }

    private fun setupTariffAdapter() {
        tariffAdapter = TariffAdapter(
            onTariffSelected = { item ->
                selectedTariffItem = item
                binding.btnOrderTaxi.isEnabled = true
                binding.btnOrderTaxi.text = "Замовити"
            },
            onImagesLoaded = {
                runOnUiThread {
                    binding.tariffsShimmerContainer.stopShimmer()
                    binding.tariffsShimmerContainer.visibility = View.GONE
                    binding.tariffsRecyclerView.visibility = View.VISIBLE
                    homeUiController.setButtonsLoadingState(false)
                }
            }
        )
        homeUiController.setupTariffsRecyclerView(tariffAdapter)
    }

    private fun getAddressForOrigin(latLng: LatLng, debounceMs: Long = 400L) {
        geocodeJob?.cancel()
        geocodeJob = lifecycleScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val addressName = ReverseGeocoderHelper.resolveAddress(
                context = this@HomeActivity,
                latLng = latLng,
                languageCode = sessionManager.getLanguage()
            ) ?: getString(R.string.point_on_map)

            if (viewModel.currentRoutePolyline == null) {
                binding.textViewOrigin.text = addressName
                originPlace = Place.builder().setName(addressName).setLatLng(latLng).build()
            }
        }
    }

    private fun getAddressFromLocation(latLng: LatLng, debounceMs: Long = 0L, callback: (String?) -> Unit) {
        mapPickerGeocodeJob?.cancel()
        mapPickerGeocodeJob = lifecycleScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            callback(ReverseGeocoderHelper.resolveAddress(this@HomeActivity, latLng, sessionManager.getLanguage()))
        }
    }

    private fun onFavoriteAddressClick(isHome: Boolean) {
        currentWaypoints.clear()
        val place = if (isHome) sessionManager.getHomeAddress() else sessionManager.getWorkAddress()
        if (place != null) {
            setDestination(place)
        } else {
            pickerMode = if (isHome) MODE_ADD_HOME else MODE_ADD_WORK
            openAddressPicker(false, true)
        }
    }

    private fun setDestination(place: Place) {
        destinationPlace = place
        binding.textViewDestination.text = AddressUtils.formatAddress(place.name ?: "")
        tryDrawRoute()
    }

    private fun handleAddressSelection(place: Place, name: String?) {
        val cleanName = AddressUtils.formatAddress(name ?: "")
        when (pickerMode) {
            MODE_ORIGIN -> {
                originPlace = place
                binding.textViewOrigin.text = cleanName
                tryDrawRoute()
            }
            MODE_DESTINATION -> {
                destinationPlace = place
                binding.textViewDestination.text = cleanName
                tryDrawRoute()
            }
            MODE_ADD_HOME -> {
                sessionManager.saveHomeAddress(place)
                homeUiController.updateFavoriteButtonsUI()
                showToast("Дім збережено")
                setDestination(place)
            }
            MODE_ADD_WORK -> {
                sessionManager.saveWorkAddress(place)
                homeUiController.updateFavoriteButtonsUI()
                showToast("Робота збережена")
                setDestination(place)
            }
        }
        if (originPlace == null || destinationPlace == null) {
            place.latLng?.let { mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 16f)) }
        }
    }

    private fun checkPermissionsAndAutoDetectCity() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            mMap?.isMyLocationEnabled = true
            detectCityAndMove()
            checkAndShowNotificationDialogWithDelay()
        }
    }

    private fun checkAndShowNotificationDialogWithDelay() {
        homeUiController.checkAndShowNotificationDialogWithDelay {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun detectCityAndMove() {
        try {
            val client = LocationServices.getFusedLocationProviderClient(this)
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                client.lastLocation.addOnSuccessListener { location ->
                    if (LocationValidator.isValidLocation(location)) {
                        val userLatLng = LatLng(location!!.latitude, location.longitude)
                        if (activeOrderId == null && !isRouteMode && originPlace == null) {
                            originPlace = Place.builder().setName("Визначення...").setLatLng(userLatLng).build()
                            getAddressForOrigin(userLatLng)
                        }
                        val foundCityName = homeUiController.findClosestCity(location.latitude, location.longitude)
                        if (foundCityName != null) {
                            val regionData = CityDatabase.regions[foundCityName]!!
                            currentCity = CityData(foundCityName, regionData.center.latitude, regionData.center.longitude, regionData.zoom)
                            sessionManager.saveUserCity(currentCity!!)
                            homeUiController.updateCityUI(foundCityName)
                            mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, 16f))
                        } else {
                            showCitySelectorDialog()
                        }
                    } else {
                        currentCity?.let { city ->
                            mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(city.lat, city.lng), city.zoom))
                        } ?: showCitySelectorDialog()
                    }
                }
            }
        } catch (_: Exception) {
            showCitySelectorDialog()
        }
    }

    private fun updatePaymentMethodFromSession() {
        currentPaymentMethod = sessionManager.fetchPaymentMethod()
        if (currentPaymentMethod == "CARD" && sessionManager.getCardMask().isNullOrEmpty()) {
            currentPaymentMethod = "CASH"
            sessionManager.savePaymentMethod("CASH")
        }
        homeUiController.updatePaymentIcon(currentPaymentMethod)
    }

    private fun recenterMapOnUser() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        LocationServices.getFusedLocationProviderClient(this).lastLocation.addOnSuccessListener { loc ->
            if (LocationValidator.isValidLocation(loc)) {
                val userLatLng = LatLng(loc!!.latitude, loc.longitude)
                mapRouteController.animateCameraFlatZoom(mMap, userLatLng, 16f)
                lastLocationUpdateTime = System.currentTimeMillis()
                lastLocationSent = userLatLng
                viewModel.updateClientLocation(webSocketManager, userLatLng.latitude, userLatLng.longitude)
            } else {
                showToast("Неточний GPS (можливий РЕБ). Вкажіть точку на карті")
                currentCity?.let { city ->
                    mapRouteController.animateCameraFlatZoom(mMap, LatLng(city.lat, city.lng), city.zoom)
                }
            }
        }
    }

    private fun openAddressPicker(isOrigin: Boolean, hideMyLocation: Boolean = false) {
        addressPickerLauncher.launch(
            homeUiController.buildOpenAddressPickerIntent(isOrigin, hideMyLocation, mMap, originPlace, currentCity)
        )
    }

    private fun showMapPickerMode(isOrigin: Boolean) {
        isMapPickingMode = true
        mapPickerIsOrigin = isOrigin
        homeUiController.clearNearbyDrivers()
        binding.bottomSheetCard.visibility = View.GONE
        binding.btnMenu.visibility = View.GONE
        binding.btnOpenPromo.visibility = View.GONE
        binding.btnRecenterLocation.visibility = View.GONE
        binding.layoutMapPickerPanel.visibility = View.VISIBLE
        binding.btnBackMapPicker.visibility = View.VISIBLE
        binding.tvMapPickerAddress.text = "Шукаємо адресу..."
        updateMapPadding(binding.layoutMapPickerPanel, extraBottomDp = 2f, topPaddingDp = 20f, recenterMap = false)
    }

    private fun hideMapPickerMode() {
        isMapPickingMode = false
        binding.bottomSheetCard.visibility = View.VISIBLE
        binding.btnMenu.visibility = View.VISIBLE
        binding.btnOpenPromo.visibility = View.VISIBLE
        binding.btnRecenterLocation.visibility = View.VISIBLE
        binding.layoutMapPickerPanel.visibility = View.GONE
        binding.btnBackMapPicker.visibility = View.GONE
        updateMapPadding(binding.bottomSheetCard, extraBottomDp = 2f, topPaddingDp = 20f, recenterMap = false)
    }

    private fun confirmMapSelection() {
        val target = mMap?.cameraPosition?.target ?: return
        val addressName = binding.tvMapPickerAddress.text.toString()
        if (addressName == "Шукаємо адресу..." || addressName.isEmpty()) return

        val selectedPlace = Place.builder().setLatLng(target).setName(addressName).build()
        val data = lastAddressPickerIntentData
        val isOriginOnlyMode = data?.getBooleanExtra(AddressPickerActivity.EXTRA_IS_ORIGIN, false) ?: false

        when (mapPickerTarget) {
            AddressPickerActivity.TARGET_ORIGIN -> {
                if (isOriginOnlyMode) {
                    hideMapPickerMode()
                    pickerMode = MODE_ORIGIN
                    handleAddressSelection(selectedPlace, addressName)
                } else {
                    homeUiController.updateIntentDataForReturn(data, mapPickerTarget, mapPickerWaypointIndex, target, addressName)
                    returnToAddressPicker()
                }
            }
            AddressPickerActivity.TARGET_DESTINATION -> {
                val wLats = data?.getDoubleArrayExtra(AddressPickerActivity.RESULT_WAYPOINTS_LATS)
                if (wLats?.any { it == 0.0 } == true) {
                    data.putExtra(AddressPickerActivity.RESULT_NAME, addressName)
                    data.putExtra(AddressPickerActivity.RESULT_LAT, target.latitude)
                    data.putExtra(AddressPickerActivity.RESULT_LNG, target.longitude)
                    returnToAddressPicker()
                } else {
                    hideMapPickerMode()
                    if (pickerMode != MODE_ADD_HOME && pickerMode != MODE_ADD_WORK) pickerMode = MODE_DESTINATION
                    handleAddressSelection(selectedPlace, addressName)
                }
            }
            AddressPickerActivity.TARGET_WAYPOINT -> {
                homeUiController.updateIntentDataForReturn(data, mapPickerTarget, mapPickerWaypointIndex, target, addressName)
                returnToAddressPicker()
            }
        }
    }

    private fun returnToAddressPicker() {
        val data = lastAddressPickerIntentData
        hideMapPickerMode()
        if (data != null) {
            addressPickerLauncher.launch(homeUiController.buildReturnToAddressPickerIntent(data, mapPickerTarget))
        }
    }

    private fun autoRateAndDismissCompletedOrder(orderId: String) {
        try { rateDriverDialogFragment?.dismissAllowingStateLoss() } catch (_: Exception) {}
        rateDriverDialogFragment = null
        isRatingDialogVisible = false
        OrderRatingController.submitRatingAndDismiss(orderId, 5, null, sessionManager, viewModel) {
            activeOrderId = null
            showAddressPanel()
        }
    }

    private fun tryDrawRoute() {
        if (originPlace == null || destinationPlace == null) return
        isRouteMode = true
        homeUiController.hidePin()
        binding.tvOverlayOrigin.text = AddressUtils.formatAddress(originPlace!!.name ?: "А")
        binding.tvOverlayDest.text = AddressUtils.formatAddress(destinationPlace!!.name ?: "Б")
        binding.overlayOrigin.visibility = View.GONE
        binding.overlayDest.visibility = View.GONE
        mMap?.clear()
        viewModel.fetchDirections(originPlace!!.latLng!!, destinationPlace!!.latLng!!, currentWaypoints)
        fetchTariffsAndShowPanel()
    }

    private fun drawStylishRoute(path: List<LatLng>, orderStatus: String) {
        mapRouteController.drawStylishRoute(
            map = mMap,
            path = path,
            orderStatus = orderStatus,
            originPlace = originPlace,
            destinationPlace = destinationPlace,
            currentWaypoints = currentWaypoints,
            routeDurationSeconds = routeDurationSeconds,
            tvOriginText = binding.textViewOrigin.text.toString(),
            tvDestinationText = binding.textViewDestination.text.toString(),
            centerPin = binding.centerPin,
            pinShadow = binding.pinShadow,
            overlayOrigin = binding.overlayOrigin,
            tvOverlayOrigin = binding.tvOverlayOrigin,
            overlayDest = binding.overlayDest,
            tvOverlayDest = binding.tvOverlayDest,
            tvOverlayDestDetails = binding.tvOverlayDestDetails,
            btnRecenterRoute = binding.btnRecenterRoute,
            btnRecenter = binding.btnRecenterLocation,
            activeOrderCard = binding.activeOrderCard,
            tariffsPanel = binding.tariffsPanel,
            contentBottomSheet = binding.contentBottomSheet,
            currentRoutePolylineProvider = { viewModel.currentRoutePolyline },
            activeOrderProvider = { viewModel.activeOrder.value },
            onClearDriverMarker = { homeUiController.driverMarker = null },
            onRevealInterface = { homeUiController.revealInterface(isRouteMode || viewModel.currentRoutePolyline != null) },
            onUpdateStatusUI = { currentOrder -> activeOrderUiController.updateStatusUI(currentOrder, statusCallbacks) }
        )
    }

    fun incrementUnreadMessages() {
        ChatEventBus.triggerUpdateAsync()
        showToast("Нове повідомлення від водія!")
    }

    private fun clearMapForRoute() {
        mMap?.stopAnimation()
        mMap?.clear()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try { mMap?.isMyLocationEnabled = true } catch (_: Exception) {}
        }
        mapRouteController.clearAllRouteElements()
        if (binding.bottomSheetCard.visibility == View.VISIBLE) {
            updateMapPadding(binding.bottomSheetCard, extraBottomDp = 2f, topPaddingDp = 20f, recenterMap = false)
        } else {
            mMap?.setPadding(0, 0, 0, 0)
        }
        binding.btnRecenterRoute.visibility = View.GONE
        viewModel.currentRoutePolyline = null
        homeUiController.hidePin()
        selectedTariffItem = null
        binding.btnOrderTaxi.isEnabled = false
        binding.btnOrderTaxi.text = getString(R.string.btn_order)
        binding.overlayOrigin.visibility = View.GONE
        binding.overlayDest.visibility = View.GONE
        decodedRoutePoints = null
        viewModel.loadTariffsAndCalculatePrice(null, 0)
    }

    private fun fetchTariffsAndShowPanel() {
        viewModel.stopListeningNearbyDrivers(webSocketManager)
        homeUiController.clearNearbyDrivers()
        stopDriverTracking()
        binding.addressPanel.visibility = View.GONE
        homeUiController.setButtonsLoadingState(true)
        binding.tariffsPanel.layoutParams.height = (resources.displayMetrics.heightPixels * 0.45).toInt()
        binding.tariffsPanel.visibility = View.VISIBLE
        hasTrackedTariffsView = false
        binding.tariffsRecyclerView.visibility = View.GONE
        binding.tariffsShimmerContainer.visibility = View.VISIBLE
        binding.tariffsShimmerContainer.startShimmer()
        binding.btnRecenterLocation.visibility = View.GONE
        homeUiController.setRecenterRouteButtonAnchor(R.id.tariffs_panel, convertDpToPixel(16f).toInt())
        binding.btnRecenterRoute.visibility = View.GONE
        try { binding.btnOpenPromo.visibility = View.GONE } catch (_: Exception) {}
        binding.btnMenuIcon.setImageResource(R.drawable.ic_arrow_back_black)
        binding.btnMenuIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_primary))

        viewModel.currentRoutePolyline?.let { route ->
            viewModel.loadTariffsAndCalculatePrice(route, viewModel.routeInfo.value?.first ?: 0)
        }
        stopPeriodicNearbyDriversRefresh()
    }

    private fun displayTariffs() {
        if (availableTariffs.isEmpty()) {
            showToast("Немає тарифів")
            return
        }
        if (!hasTrackedTariffsView) {
            AnalyticsManager.trackCustomEvent("tariffs_view", "${availableTariffs.size} тарифи(ів)")
            hasTrackedTariffsView = true
        }
        selectedTariffItem = homeUiController.bindTariffsList(
            availableTariffs = availableTariffs,
            routeDistanceMeters = routeDistanceMeters,
            tariffCustomPrices = tariffCustomPrices,
            tariffAdapter = tariffAdapter,
            currentSelectedItem = selectedTariffItem,
            scheduledDate = scheduledDate
        )
        binding.tariffsPanel.post { updateMapPadding(binding.tariffsPanel, 0f, 10f) }
    }

    private fun createOrder(tariff: CarTariffDto, price: Double) {
        binding.btnOrderTaxi.isEnabled = false
        binding.btnOrderTaxi.text = "Обробка..."
        val request = homeUiController.buildCreateOrderRequest(
            tariff = tariff,
            price = price,
            originPlace = originPlace!!,
            destinationPlace = destinationPlace!!,
            currentWaypoints = currentWaypoints,
            selectedTariffItem = selectedTariffItem,
            scheduledDate = scheduledDate,
            currentRoutePolyline = viewModel.currentRoutePolyline,
            routeDistanceMeters = routeDistanceMeters,
            routeDurationSeconds = routeDurationSeconds,
            orderComment = orderComment,
            currentPaymentMethod = currentPaymentMethod,
            selectedServiceIds = selectedServiceIds
        )
        viewModel.createOrder(request)
    }

    private fun showActiveOrderPanel(order: TaxiOrderDto) {
        activeOrderUiController.showActiveOrderPanel(
            order = order,
            availableTariffs = availableTariffs,
            onStopNearbyDrivers = {
                viewModel.stopListeningNearbyDrivers(webSocketManager)
                homeUiController.clearNearbyDrivers()
                stopPeriodicNearbyDriversRefresh()
            },
            onUpdateMapPaddingFirstShow = { updateMapPadding(binding.activeOrderCard, 0f, 20f) },
            callbacks = statusCallbacks
        )
    }

    private fun restoreOrderOnMap(order: TaxiOrderDto) {
        isRouteMode = activeOrderUiController.restoreOrderOnMap(
            order = order,
            map = mMap,
            isRouteMode = isRouteMode,
            activeOrderId = activeOrderId,
            currentWaypoints = currentWaypoints,
            onHidePin = { homeUiController.hidePin() },
            onClearDriverMarker = { homeUiController.driverMarker = null },
            onSetPlacesAndPoints = { orig, dest, pts ->
                originPlace = orig
                destinationPlace = dest
                if (pts != null) decodedRoutePoints = pts
            },
            callbacks = statusCallbacks
        )
    }

    private fun showAddressPanel() {
        mMap?.clear()
        isRouteMode = false
        startPeriodicNearbyDriversRefresh()
        activeOrderUiController.lastKnownDriverLatLng = null
        activeOrderUiController.lastKnownDriverBearing = 0f
        activeOrderUiController.isCameraFocusedOnSearch = false

        mMap?.uiSettings?.isScrollGesturesEnabled = true
        mMap?.uiSettings?.isZoomGesturesEnabled = true
        mapRouteController.resetCameraTilt(mMap, forceStopAnimation = true)

        binding.bottomSheetCard.visibility = View.VISIBLE
        binding.activeOrderCard.visibility = View.GONE
        binding.addressPanel.visibility = View.VISIBLE
        binding.tariffsPanel.visibility = View.GONE
        binding.btnRecenterLocation.visibility = View.VISIBLE

        clearMapForRoute()
        homeUiController.setLocationButtonAnchor(R.id.bottom_sheet_card)
        binding.btnRecenterLocation.visibility = View.VISIBLE
        binding.btnMenu.visibility = View.VISIBLE
        try { binding.btnOpenPromo.visibility = View.VISIBLE } catch (_: Exception) {}
        binding.btnMenuIcon.setImageResource(R.drawable.ic_menu_hamburger)

        orderTimersController.stopWaitingTimer(binding.cardWaitingTimer)
        sessionManager.clearActiveOrderId()
        tariffAdapter.submitList(emptyList(), 0)
        tariffCustomPrices.clear()
        tariffAdapter.clearCustomPrices()
        selectedServiceIds.clear()
        servicesExtraCost = 0.0
        tariffAdapter.updateExtraCost(0.0)

        binding.layoutDriverFoundState.visibility = View.GONE
        binding.layoutDriverAssignedDetails.visibility = View.GONE
        binding.layoutSearchControls.visibility = View.GONE
        binding.layoutSearchDetails.visibility = View.GONE
        binding.tvCarPlateLarge.text = ""
        binding.tvDriverFirstName.text = ""
        binding.tvCarDetailsSubtitle.text = ""

        orderComment = ""
        homeUiController.updateCommentIconState()
        scheduledDate = null
        try { binding.btnSchedule.clearColorFilter() } catch (_: Exception) {}

        binding.textViewOrigin.text = "Визначення..."
        binding.textViewDestination.text = getString(R.string.hint_where_to)
        originPlace = null
        destinationPlace = null

        homeUiController.resetPinForAddressPanel(::convertDpToPixel)
        searchRadarController.stop()
        binding.animatedBorderHeader.stopBorderAnimation()

        isSuppressingGeocode = true
        updateMapPadding(binding.bottomSheetCard, extraBottomDp = 2f, topPaddingDp = 20f, recenterMap = false)
        binding.bottomSheetCard.post { recenterMapOnUser() }
        viewModel.startListeningNearbyDrivers(webSocketManager)
    }

    private fun startDriverTracking(orderId: String) {
        isDriverTrackingActive = true
        val token = sessionManager.fetchAuthToken()
        if (webSocketManager?.isConnected() != true) webSocketManager?.connect(token)
        webSocketManager?.subscribeToDriverLocation(orderId) { locationDto ->
            runOnUiThread { updateDriverMarker(locationDto) }
        }
    }

    private fun stopDriverTracking() {
        if (!isDriverTrackingActive) return
        isDriverTrackingActive = false
        webSocketManager?.unsubscribeFromDriverLocation()
        homeUiController.removeDriverMarker()
    }

    private fun updateDriverMarker(loc: TrackingLocationDto) {
        val currentStatus = viewModel.activeOrder.value?.status
        if (currentStatus != "REQUESTED" && currentStatus != "OFFERING" && (mMap?.cameraPosition?.tilt ?: 0f) > 0.1f) {
            mapRouteController.resetCameraTilt(mMap)
        }
        homeUiController.updateDriverMarker(
            map = mMap,
            loc = loc,
            isMapPickingMode = isMapPickingMode,
            currentOrderStatus = currentStatus,
            onTrimPickupRoute = { latLng ->
                if (mapRouteController.pickupPolyline != null && mapRouteController.pickupRoutePoints.isNotEmpty()) {
                    mapRouteController.trimPickupRoute(latLng)
                }
            },
            onFocusOnPickupRoute = { latLng -> focusOnPickupRoute(latLng) }
        )
    }

    private fun startPeriodicNearbyDriversRefresh() {
        stopPeriodicNearbyDriversRefresh()
        nearbyDriversRunnable = object : Runnable {
            override fun run() {
                val isMainScreen = activeOrderId == null &&
                        !isRouteMode &&
                        viewModel.currentRoutePolyline == null &&
                        binding.tariffsPanel.visibility != View.VISIBLE &&
                        !isMapPickingMode
                if (isMainScreen && mMap != null) {
                    val target = mMap!!.cameraPosition.target
                    viewModel.updateClientLocation(webSocketManager, target.latitude, target.longitude)
                }
                nearbyDriversHandler.postDelayed(this, 10000L)
            }
        }
        nearbyDriversHandler.postDelayed(nearbyDriversRunnable!!, 10000L)
    }

    private fun stopPeriodicNearbyDriversRefresh() {
        nearbyDriversRunnable?.let { nearbyDriversHandler.removeCallbacks(it) }
        nearbyDriversRunnable = null
    }

    private fun drawPickupRoute(polylineStr: String?) {
        mapRouteController.drawPickupRoute(mMap, polylineStr, originPlace, viewModel.activeOrder.value, homeUiController.driverMarker?.position)
    }

    private fun updateSmartLabels() {
        mapRouteController.updateSmartLabels(mMap, viewModel.activeOrder.value?.status, originPlace, destinationPlace, decodedRoutePoints, binding.overlayOrigin, binding.overlayDest)
    }

    private fun focusOnPickupRoute(driverLatLng: LatLng? = null) {
        mapRouteController.focusOnPickupRoute(mMap, driverLatLng, originPlace, viewModel.activeOrder.value, homeUiController.driverMarker?.position)
    }

    private fun updateMapPadding(bottomPanel: View, extraBottomDp: Float = 20f, topPaddingDp: Float = 20f, recenterMap: Boolean = true) {
        mapRouteController.updateMapPadding(
            map = mMap,
            bottomPanel = bottomPanel,
            extraBottomDp = extraBottomDp,
            topPaddingDp = topPaddingDp,
            recenterMap = recenterMap,
            creationPanelCard = binding.bottomSheetCard,
            pinContainer = binding.pinContainer,
            activeOrder = viewModel.activeOrder.value,
            activeOrderId = activeOrderId,
            originPlace = originPlace,
            destinationPlace = destinationPlace,
            currentWaypoints = currentWaypoints,
            decodedRoutePoints = decodedRoutePoints,
            driverMarkerPos = homeUiController.driverMarker?.position
        )
    }

    private fun animateMapPadding(fromHeight: Int, toHeight: Int, recenterMap: Boolean) {
        mapRouteController.animateMapPadding(
            map = mMap,
            activeOrderCard = binding.activeOrderCard,
            fromHeight = fromHeight,
            toHeight = toHeight,
            recenterMap = recenterMap,
            currentRoutePolyline = viewModel.currentRoutePolyline,
            activeOrder = viewModel.activeOrder.value,
            originPlace = originPlace,
            destinationPlace = destinationPlace,
            currentWaypoints = currentWaypoints,
            decodedRoutePoints = decodedRoutePoints,
            driverMarkerPos = homeUiController.driverMarker?.position
        )
    }

    private fun showCustomScheduleDialog() {
        if (isFinishing || isDestroyed) return
        ScheduleRideBottomSheet(
            isDarkMode = sessionManager.isDarkMode(),
            onDateScheduled = { selectedCalendar ->
                scheduledDate = selectedCalendar
                homeUiController.updateOrderButtonWithTime(scheduledDate, selectedTariffItem != null)
            },
            onValidationError = { msg -> showToast(msg) }
        ).show(supportFragmentManager, "ScheduleRideBottomSheet")
    }

    private fun showDriverHealthDialog(issues: List<String>) {
        if (isFinishing || isDestroyed) return
        DriverHealthDialog(issues).show(supportFragmentManager, "DriverHealthDialog")
    }

    private fun openLiqPayInApp(url: String) {
        val host = Uri.parse(url).host
        if (host == null || !host.contains("liqpay.ua", ignoreCase = true)) {
            showToast("Недозволене посилання для оплати")
            return
        }
        webViewDialogFragment?.dismissAllowingStateLoss()
        val dialog = LiqPayWebViewDialog(url) {
            viewModel.stopCheckingCardBinding()
            webViewDialogFragment = null
        }
        webViewDialogFragment = dialog
        dialog.show(supportFragmentManager, "LiqPayWebViewDialog")
        viewModel.startCheckingCardBinding(webSocketManager)
    }

    private fun showChangePaymentDialog() {
        if (isFinishing || isDestroyed) return
        val currentMethod = viewModel.activeOrder.value?.paymentMethod ?: sessionManager.fetchPaymentMethod()
        ChangePaymentBottomSheet(
            currentMethod = currentMethod,
            cardMask = sessionManager.getCardMask(),
            onPaymentMethodSelected = { selectedMethod ->
                if (viewModel.activeOrder.value != null) {
                    viewModel.updateActiveOrderPaymentMethod(selectedMethod)
                } else {
                    sessionManager.savePaymentMethod(selectedMethod)
                    currentPaymentMethod = selectedMethod
                    homeUiController.updatePaymentIcon(currentPaymentMethod)
                }
            },
            onOpenLiqPayUrl = ::openLiqPayInApp,
            onMessage = ::showToast
        ).show(supportFragmentManager, "ChangePaymentBottomSheet")
    }

    private fun showChangePriceDialogForActiveOrder(basePrice: Double, currentAddedValue: Double) {
        if (isFinishing || isDestroyed) return
        ChangePriceBottomSheet(basePrice, currentAddedValue) { newAddedValue ->
            viewModel.activeOrder.value?.let { order ->
                AnalyticsManager.trackBoostClicked(order.id, newAddedValue, 30L)
            }
            viewModel.updateActiveOrderPrice(newAddedValue)
        }.show(supportFragmentManager, "ChangePriceActiveOrderBottomSheet")
    }

    private fun showChangePriceDialog() {
        val selectedItem = tariffAdapter.getSelectedTariff() ?: run {
            showToast("Спочатку оберіть тариф")
            return
        }
        val basePriceWithServices = selectedItem.priceValue - selectedItem.addedValue
        ChangePriceBottomSheet(basePriceWithServices, selectedItem.addedValue) { newAddedValue ->
            tariffAdapter.setCustomPrice(selectedItem.tariff.id, newAddedValue)
            selectedTariffItem = selectedTariffItem?.copy(addedValue = newAddedValue) ?: selectedItem.copy(addedValue = newAddedValue)
        }.show(supportFragmentManager, "ChangePricePreOrderBottomSheet")
    }

    private fun showCustomCancelDialog(reasons: List<CancellationReasonDto>) {
        if (isFinishing || isDestroyed) return
        CancelOrderBottomSheet(reasons) { selectedReason ->
            activeOrderUiController.animateOrderDetailsToState(false)
            viewModel.cancelOrder(selectedReason)
        }.show(supportFragmentManager, "CancelOrderBottomSheet")
    }

    private fun showRatingDialog(orderId: String, driverName: String?) {
        if (isRatingDialogVisible || isFinishing || isDestroyed) return
        isRatingDialogVisible = true
        val dialog = RateDriverBottomSheet(
            orderId = orderId,
            driverName = driverName,
            onRatingFinished = {
                isRatingDialogVisible = false
                rateDriverDialogFragment = null
                viewModel.dismissCompletedOrder(orderId)
                viewModel.clearOrderState()
                activeOrderId = null
                showAddressPanel()
            },
            onCancelledByBack = {
                isRatingDialogVisible = false
                rateDriverDialogFragment = null
                autoRateAndDismissCompletedOrder(orderId)
            },
            onMessage = ::showToast
        )
        rateDriverDialogFragment = dialog
        dialog.show(supportFragmentManager, "RateDriverBottomSheet")
    }

    private fun checkAndScheduleBoostPrompt(order: TaxiOrderDto) {
        if (!::orderTimersController.isInitialized) return
        orderTimersController.checkAndScheduleBoostPrompt(
            order = order,
            isBoostPromptShown = viewModel.isBoostPromptShown,
            canShowBoostNow = {
                !isFinishing && !isDestroyed &&
                        (viewModel.activeOrder.value?.status == "REQUESTED" || viewModel.activeOrder.value?.status == "OFFERING")
            },
            onTriggerBoostDialog = ::showBoostPriceDialog
        )
    }

    private fun stopBoostTimer() {
        if (::orderTimersController.isInitialized) orderTimersController.stopBoostTimer()
        boostDialogFragment?.dismissAllowingStateLoss()
        boostDialogFragment = null
    }

    private fun showBoostPriceDialog() {
        if (isFinishing || isDestroyed || viewModel.isBoostPromptShown) return
        viewModel.isBoostPromptShown = true
        val dialog = BoostPriceBottomSheet {
            viewModel.applyBoostPrice(20.0)
            showToast(getString(R.string.toast_boost_added))
            boostDialogFragment = null
        }
        boostDialogFragment = dialog
        dialog.show(supportFragmentManager, "BoostPriceBottomSheet")
    }

    private fun showToast(msg: String) = homeUiController.showToast(msg)
    private fun convertDpToPixel(dp: Float): Float = dp * (resources.displayMetrics.densityDpi / 160f)
    private fun resetUI() {
        tariffCustomPrices.clear()
        tariffAdapter.clearCustomPrices()
    }
    private fun showCitySelectorDialog() = cityPickerLauncher.launch(Intent(this, CityPickerActivity::class.java))
    override fun onCreateOptionsMenu(menu: Menu?): Boolean = true
    override fun onOptionsItemSelected(item: MenuItem): Boolean = true
}