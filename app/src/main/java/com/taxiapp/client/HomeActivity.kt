package com.taxiapp.client

import android.Manifest
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.content.res.ColorStateList
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.TimeZone
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels // <--- НОВЫЙ ИМПОРТ
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.maps.android.PolyUtil
import com.google.maps.android.SphericalUtil
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.MessageResponse
import com.taxiapp.client.network.RateDriverRequest
import com.taxiapp.client.network.WebSocketManager
import com.taxiapp.client.network.dto.*
import com.taxiapp.client.ui.TariffAdapter
import com.taxiapp.client.ui.TariffItem
import com.taxiapp.client.utils.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil

class HomeActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val REQUEST_CODE_CITY = 101
        var lastScreenshot: android.graphics.Bitmap? = null
        private var lastSwitchTime: Long = 0
    }

    // --- ПОДКЛЮЧАЕМ VIEWMODEL ---
    private val viewModel: HomeViewModel by viewModels()

    // Остальные переменные UI и карты
    private var currentTariffPrice: Double = 0.0 
    private lateinit var tvPrice: TextView

    // Эти переменные теперь обновляются из ViewModel
    private var decodedRoutePoints: List<LatLng>? = null
    private var routeDistanceMeters: Int = 0
    private var routeDurationSeconds: Int = 0
    private var currentRoutePolyline: String? = null
    private var activeOrderId: Long? = null
    private var availableTariffs: List<CarTariffDto> = emptyList()

    private lateinit var sessionManager: SessionManager
    private var mMap: GoogleMap? = null

    private var isChoosingDestination = false

    private val animHandler = Handler(Looper.getMainLooper())
    // statusHandler удален, так как поллинг теперь в ViewModel

    private lateinit var customToastContainer: CardView
    private lateinit var tvToastMessage: TextView
    private lateinit var ivToastIcon: ImageView
    private val toastHandler = Handler(Looper.getMainLooper())
    private val hideToastRunnable = Runnable { hideTopMessage() }

    private lateinit var overlayOrigin: View
    private lateinit var tvOverlayOrigin: TextView
    private lateinit var overlayDest: View
    private lateinit var tvOverlayDest: TextView

    private var polylineBorder: Polyline? = null
    private var polylineMain: Polyline? = null
    private var polylineAnim: Polyline? = null
    private var routeAnimator: ValueAnimator? = null

    private var webSocketManager: WebSocketManager? = null
    private var driverMarker: Marker? = null
    private var customCarIcon: BitmapDescriptor? = null
    private var isDriverTrackingActive = false

    private var originMarker: Marker? = null
    private var destinationMarker: Marker? = null
    
    private val currentWaypoints = mutableListOf<Pair<LatLng, String>>()

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var btnMenu: CardView
    private lateinit var ivMenuIcon: ImageView

    private lateinit var profileUserName: TextView
    private lateinit var profileBtnDetails: LinearLayout 
    private lateinit var profileCityText: TextView
    private lateinit var profileBtnCity: LinearLayout 
    
    private lateinit var themeSwitchContainer: ConstraintLayout
    private lateinit var themeSwitchThumb: View
    private lateinit var ivThemeSun: ImageView
    private lateinit var ivThemeMoon: ImageView
    private lateinit var tvThemeLabel: TextView
    
    private lateinit var btnOpenPromo: CardView
    private lateinit var centerPin: ImageView
    private lateinit var pinShadow: ImageView
    private lateinit var btnRecenter: CardView
    private lateinit var btnRecenterRoute: CardView

    private lateinit var containerOrigin: LinearLayout
    private lateinit var tvOrigin: TextView
    private lateinit var containerDestination: LinearLayout
    private lateinit var tvDestination: TextView

    private lateinit var tvOverlayDestDetails: TextView
    
    private lateinit var ivMarkerOrigin: ImageView
    private lateinit var ivMarkerDest: ImageView

    private lateinit var btnFavHome: CardView
    private lateinit var ivIconHome: ImageView
    private lateinit var indicatorAddHome: ImageView
    private lateinit var btnFavWork: CardView
    private lateinit var ivIconWork: ImageView
    private lateinit var indicatorAddWork: ImageView

    private lateinit var creationPanelCard: View
    private lateinit var addressPanel: View
    private lateinit var tariffsPanel: View
    private lateinit var tariffsProgressBar: ProgressBar
    private lateinit var btnOrderTaxi: Button
    private lateinit var tariffsRecyclerView: RecyclerView
    private lateinit var tariffAdapter: TariffAdapter

    private lateinit var layoutSearchDetails: LinearLayout
    private lateinit var tvOrderTariffName: TextView
    private lateinit var tvOrderServices: TextView
    private lateinit var tvOrderComment: TextView
    private lateinit var statusBlinkAnimator: ObjectAnimator

    private lateinit var activeOrderCard: View
    private lateinit var orderStatusText: TextView
    private lateinit var statusProgressBar: ProgressBar
    private lateinit var btnCancelOrder: Button

    private var orderComment: String = "" 
    private lateinit var btnOpenComment: View
    private lateinit var ivCommentIcon: ImageView

    private var currentPaymentMethod: String = "CASH" 
    private lateinit var ivPaymentIcon: ImageView
    
    private lateinit var layoutDriverDetails: LinearLayout
    private lateinit var tvCarPlateLarge: TextView

    private lateinit var btnChatDriver: ImageButton
    private lateinit var tvChatBadge: TextView
    private var unreadChatMessages = 0

    private lateinit var tvCarDetailsSubtitle: TextView
    private lateinit var tvDriverFirstName: TextView
    private lateinit var tvDriverExperience: TextView
    private lateinit var tvDriverRidesCount: TextView
    private lateinit var ivDriverPhoto: ImageView
    private lateinit var btnCallDriver: ImageButton
    private lateinit var tvDriverHealthInfo: TextView

    private lateinit var layoutWaitingInfo: LinearLayout
    private lateinit var tvWaitingTimer: TextView
    private var waitingTimerHandler = Handler(Looper.getMainLooper())
    private var waitingTimerRunnable: Runnable? = null

    private lateinit var tvActiveOrderPrice: TextView
    private lateinit var ivActiveOrderPayment: ImageView

    private lateinit var layoutActiveOrderPrice: View

    private lateinit var mapLoadingCurtain: ImageView
    private lateinit var contentBottomSheet: View
    
    private var loadedSectors: List<SectorDto> = emptyList()
    private var isRatingDialogVisible = false
    
    private var originPlace: Place? = null
    private var destinationPlace: Place? = null
    private var currentCity: CityData? = null

    private lateinit var btnSchedule: ImageButton 
    private var scheduledDate: Calendar? = null

    private var isRouteMode = false

    private lateinit var btnAddNewOrder: ImageButton

    private val MODE_ORIGIN = 1
    private val MODE_DESTINATION = 2
    private val MODE_ADD_HOME = 3
    private val MODE_ADD_WORK = 4
    private var pickerMode = MODE_ORIGIN
    private var isSelectingOrigin = true

    private var selectedTariffItem: TariffItem? = null

    private var selectedServiceIds = ArrayList<Long>()
    private var servicesExtraCost: Double = 0.0

    private val tariffCustomPrices = mutableMapOf<Long, Double>()
    private lateinit var btnChangePrice: View

    // Лаунчеры
    private val userDetailsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            profileUserName.text = sessionManager.getUserName()
            setupProfileLogic() 
        }
    }

    private val commentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val comment = result.data!!.getStringExtra("EXTRA_COMMENT") ?: ""
            orderComment = comment
            updateCommentIconState()
        }
    }

    private val addressPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val data = result.data!!
            val action = data.getStringExtra(AddressPickerActivity.RESULT_ACTION)

            if (action == "place") {
                val name = data.getStringExtra(AddressPickerActivity.RESULT_NAME)
                val lat = data.getDoubleExtra(AddressPickerActivity.RESULT_LAT, 0.0)
                val lng = data.getDoubleExtra(AddressPickerActivity.RESULT_LNG, 0.0)
                val place = Place.builder().setName(name).setLatLng(LatLng(lat, lng)).build()

                if (data.hasExtra(AddressPickerActivity.RESULT_ORIGIN_LAT)) {
                    val originName = data.getStringExtra(AddressPickerActivity.RESULT_ORIGIN_NAME)
                    val originLat = data.getDoubleExtra(AddressPickerActivity.RESULT_ORIGIN_LAT, 0.0)
                    val originLng = data.getDoubleExtra(AddressPickerActivity.RESULT_ORIGIN_LNG, 0.0)
                    originPlace = Place.builder().setName(originName).setLatLng(LatLng(originLat, originLng)).build()
                    tvOrigin.text = AddressUtils.formatAddress(originName ?: "")
                }

                val wLats = data.getDoubleArrayExtra(AddressPickerActivity.RESULT_WAYPOINTS_LATS)
                val wLngs = data.getDoubleArrayExtra(AddressPickerActivity.RESULT_WAYPOINTS_LNGS)
                val wNames = data.getStringArrayListExtra(AddressPickerActivity.RESULT_WAYPOINTS_NAMES)

                currentWaypoints.clear()
                if (wLats != null && wLngs != null) {
                    for (i in wLats.indices) {
                        if (wLats[i] != 0.0 && wLngs[i] != 0.0) {
                            val wpName = if (wNames != null && i < wNames.size) wNames[i] else "Зупинка"
                            currentWaypoints.add(Pair(LatLng(wLats[i], wLngs[i]), wpName))
                        }
                    }
                }
                handleAddressSelection(place, name)
            }
            else if (action == "map_click") {
                val intent = Intent(this, MapPickerActivity::class.java)
                val startLatLng = if (pickerMode == MODE_ORIGIN) {
                    originPlace?.latLng ?: mMap?.cameraPosition?.target
                } else {
                    destinationPlace?.latLng ?: mMap?.cameraPosition?.target
                }

                if (startLatLng != null) {
                    intent.putExtra("start_lat", startLatLng.latitude)
                    intent.putExtra("start_lng", startLatLng.longitude)
                }
                mapPickerLauncher.launch(intent)
            }
        }
    }

    private val paymentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val method = result.data?.getStringExtra("EXTRA_PAYMENT_METHOD")
            if (method != null) {
                currentPaymentMethod = method
                updatePaymentIcon()
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
                updateCityUI(cityName)
                val latLng = LatLng(regionData.center.latitude, regionData.center.longitude)
                mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, regionData.zoom))
                resetUI()
            }
        }
    }

    private val mapPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data!!
            val lat = data.getDoubleExtra("picked_lat", 0.0)
            val lng = data.getDoubleExtra("picked_lng", 0.0)
            val addressName = data.getStringExtra("picked_name") ?: "Точка на карті"

            val place = Place.builder()
                .setName(addressName)
                .setLatLng(LatLng(lat, lng))
                .build()

            handleAddressSelection(place, addressName)
        }
    }

    private val servicesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val data = result.data!!
            servicesExtraCost = data.getDoubleExtra("EXTRA_COST", 0.0)
            selectedServiceIds = data.getSerializableExtra("SELECTED_IDS") as? ArrayList<Long> ?: ArrayList()

            tariffAdapter.updateExtraCost(servicesExtraCost)

            btnOrderTaxi.isEnabled = false
            btnOrderTaxi.text = "Оберіть тариф"
            selectedTariffItem = null
            
            showToast("Послуги додано: +${servicesExtraCost.toInt()} грн")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean -> }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                try {
                    mMap?.isMyLocationEnabled = true
                    detectCityAndMove()
                } catch (e: SecurityException) {}
            } else {
                if (currentCity == null) {
                    showCitySelectorDialog()
                } else {
                    showToast("Увімкніть геолокацію в налаштуваннях")
                }
            }
            checkAndShowNotificationDialogWithDelay() 
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!NetworkUtils.isInternetAvailable(this)) {
            val intent = Intent(this, NoInternetActivity::class.java)
            startActivity(intent)
            finish() 
            return 
        }

        sessionManager = SessionManager(applicationContext)

        webSocketManager = WebSocketManager(ApiClient.BASE_URL)
        fetchCustomCarIcon()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val isDark = sessionManager.isDarkMode()
        val mode = if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        setContentView(R.layout.activity_home)

        setupSystemBars(isDark)

        val myApiKey = "AIzaSyCcKH30fg81bqdUs62QzOBhmpy8hCOHNkI" 
        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, myApiKey, Locale("uk", "UA"))
        }

        currentCity = sessionManager.fetchUserCity()

        initUI()
        setupProfileLogic()
        setupTariffAdapter()
        updateFavoriteButtonsUI()

        loadSectors()
        // loadTariffs() - теперь вызывается через ViewModel или по требованию

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        if (currentCity == null) {
            checkPermissionsAndAutoDetectCity()
        }

        if (lastScreenshot != null) {
            val coverImage = ImageView(this)
            coverImage.setImageBitmap(lastScreenshot)
            coverImage.scaleType = ImageView.ScaleType.FIT_XY
            coverImage.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            (window.decorView as ViewGroup).addView(coverImage)
            coverImage.animate()
                .alpha(0f)
                .setDuration(600)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    (window.decorView as ViewGroup).removeView(coverImage)
                    lastScreenshot?.recycle()
                    lastScreenshot = null
                }
                .start()
        }

        // --- ПОДПИСКА НА VIEWMODEL ---
        setupViewModelObservers()
        
        // ДОБАВЛЕНО: Обработка системной кнопки/жеста "Назад"
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (activeOrderCard.visibility == View.VISIBLE) {
                    // Згортаємо замовлення: очищаємо UI та зупиняємо полінг, 
                    // але НЕ скасовуємо саме замовлення на сервері!
                    sessionManager.clearActiveOrderId()
                    viewModel.clearOrderState()
                    showAddressPanel()
                } else if (tariffsPanel.visibility == View.VISIBLE) {
                    // Если мы в тарифах — возвращаемся к полноэкранной карте с адресами
                    showAddressPanel()
                } else if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    finish()
                }
            }
        })
        
        // Первичная загрузка тарифов (базовая)
        viewModel.loadTariffsAndCalculatePrice(null, 0)
    }

    // =========================================================
    // НОВАЯ ФУНКЦИЯ: СВЯЗЬ С VIEWMODEL
    // =========================================================
    private fun setupViewModelObservers() {
        // 1. Маршрут
        viewModel.decodedRoute.observe(this) { points ->
            if (points != null && points.isNotEmpty()) {
                // ДОБАВЛЕНО: Защита от кэша LiveData. Проверяем, наш ли это маршрут
                val origin = originPlace?.latLng
                val dest = destinationPlace?.latLng
                
                if (origin != null && dest != null) {
                    val startDist = SphericalUtil.computeDistanceBetween(points.first(), origin)
                    val endDist = SphericalUtil.computeDistanceBetween(points.last(), dest)
                    
                    // Если маршрут начинается/заканчивается дальше чем в 2 км от наших точек — это старый кэш, игнорируем!
                    if (startDist > 2000.0 || endDist > 2000.0) {
                        return@observe 
                    }
                }

                decodedRoutePoints = points
                drawStylishRoute(points)
            }
        }
        
        viewModel.routeInfo.observe(this) { (dist, dur) ->
            routeDistanceMeters = dist
            routeDurationSeconds = dur
        }
        
       
        
        // 2. Тарифы
        viewModel.availableTariffs.observe(this) { tariffs ->
            tariffsProgressBar.visibility = View.GONE
            availableTariffs = tariffs
            displayTariffs() 
        }

        // 3. Активный заказ (обновление статуса)
        viewModel.activeOrder.observe(this) { order ->
            if (order != null) {
                activeOrderId = order.id
                if (order.status == "COMPLETED" || order.status == "CANCELLED") {
                    unreadChatMessages = 0
                    updateChatBadgeUI()
                    // Статус обработается внутри updateStatusUI
                } else {
                    // ВІДНОВЛЮЄМО МАРШРУТ ПЕРЕД ПОКАЗОМ ПАНЕЛІ
                    restoreOrderOnMap(order) 
                    showActiveOrderPanel(order)
                }
                updateStatusUI(order)
            }
        }

        // 4. Ошибки и загрузка
        viewModel.isLoading.observe(this) { loading ->
            if (loading) {
               // Можно показать общий лоадер, если нужно
            }
        }
        viewModel.errorMessage.observe(this) { msg ->
            showToast(msg)
            btnOrderTaxi.isEnabled = true
            btnOrderTaxi.text = "Замовити" // Сброс текста кнопки
            btnCancelOrder.isEnabled = true
            btnCancelOrder.text = "Скасувати"
            tariffsProgressBar.visibility = View.GONE
        }
    }

    private fun checkAndShowNotificationDialogWithDelay() {
        if (shouldShowNotificationDialog()) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed) {
                    showNotificationPermissionDialog()
                }
            }, 1000)
        }
    }

    private fun shouldShowNotificationDialog(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val isGranted = ContextCompat.checkSelfPermission(
                this, 
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            
            val alreadyAsked = sessionManager.isNotificationAsked()
            
            return !isGranted && !alreadyAsked
        }
        return false
    }

    private fun showNotificationPermissionDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_notification_permission)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.setCancelable(false)

        val btnAllow = dialog.findViewById<Button>(R.id.btn_allow)
        val btnDeny = dialog.findViewById<Button>(R.id.btn_deny)

        btnAllow.setOnClickListener {
            dialog.dismiss()
            sessionManager.setNotificationAsked(true)
            if (Build.VERSION.SDK_INT >= 33) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        btnDeny.setOnClickListener {
            dialog.dismiss()
            sessionManager.setNotificationAsked(true)
        }
        dialog.show()
    }

    private fun setupSystemBars(isDark: Boolean) {
        val window = window
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        if (isDark) {
            val solidBlack = Color.BLACK 
            window.statusBarColor = solidBlack
            window.navigationBarColor = solidBlack
            
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        } else {
            val solidWhite = Color.WHITE
            window.statusBarColor = solidWhite
            window.navigationBarColor = solidWhite
            
            controller.isAppearanceLightStatusBars = true
            controller.isAppearanceLightNavigationBars = true
        }
    }

    private fun applyTheme(isDark: Boolean) {
        try {
            val rootView = window.decorView.rootView
            val bitmap = android.graphics.Bitmap.createBitmap(
                rootView.width, 
                rootView.height, 
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            rootView.draw(canvas)
            lastScreenshot = bitmap
        } catch (e: Exception) {
            lastScreenshot = null
        }

        val mode = if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    override fun onResume() {
        super.onResume()
        updateFavoriteButtonsUI()
        
        if (tariffsPanel.visibility == View.VISIBLE) {
            fetchTariffsAndShowPanel()
        }
    }

    private fun initUI() {
        drawerLayout = findViewById(R.id.drawer_layout)
        
        ViewCompat.setOnApplyWindowInsetsListener(drawerLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            
            params.topMargin = insets.top
            params.bottomMargin = insets.bottom
            view.layoutParams = params
            
            WindowInsetsCompat.CONSUMED
        }

        drawerLayout.setStatusBarBackground(null)

        btnMenu = findViewById(R.id.btn_menu)
        ivMenuIcon = findViewById(R.id.btn_menu_icon)

        overlayOrigin = findViewById(R.id.overlay_origin)
        tvOverlayOrigin = findViewById(R.id.tv_overlay_origin)
        overlayDest = findViewById(R.id.overlay_dest)
        tvOverlayDest = findViewById(R.id.tv_overlay_dest)

        centerPin = findViewById(R.id.center_pin)
        val shadowView = findViewById<ImageView>(R.id.pin_shadow)
        pinShadow = shadowView ?: centerPin 
        centerPin.alpha = 0f
        try { pinShadow.alpha = 0f } catch (e: Exception) {}

        mapLoadingCurtain = findViewById(R.id.map_loading_curtain)
        contentBottomSheet = findViewById(R.id.content_bottom_sheet)
        
        btnRecenter = findViewById(R.id.btn_recenter_location)
        btnRecenterRoute = findViewById(R.id.btn_recenter_route)

        customToastContainer = findViewById(R.id.custom_toast_container)
        tvToastMessage = findViewById(R.id.tv_toast_message)
        ivToastIcon = findViewById(R.id.iv_toast_icon)

        profileUserName = findViewById(R.id.profile_user_name)
        profileBtnDetails = findViewById(R.id.btn_open_profile_details)
        profileCityText = findViewById(R.id.profile_current_city)
        profileBtnCity = findViewById(R.id.profile_btn_change_city)

        tvOverlayDestDetails = findViewById(R.id.tv_overlay_dest_details)
        
        themeSwitchContainer = findViewById(R.id.theme_switch_container)
        themeSwitchThumb = findViewById(R.id.theme_switch_thumb)
        ivThemeSun = findViewById(R.id.iv_theme_sun)
        ivThemeMoon = findViewById(R.id.iv_theme_moon)
        tvThemeLabel = findViewById(R.id.tv_theme_label)

        layoutWaitingInfo = findViewById(R.id.layout_waiting_info)
        tvWaitingTimer = findViewById(R.id.tv_waiting_timer)

        btnChangePrice = findViewById(R.id.btn_change_price)
        btnChangePrice.setOnClickListener {
            showChangePriceDialog() 
        }
        
        try {
            btnOpenPromo = findViewById(R.id.btn_open_promo)
            btnOpenPromo.setOnClickListener { startActivity(Intent(this, PromoActivity::class.java)) }
        } catch (e: Exception) { }

        creationPanelCard = findViewById(R.id.bottom_sheet_card)
        addressPanel = findViewById(R.id.address_panel)
        tariffsPanel = findViewById(R.id.tariffs_panel)
        tariffsProgressBar = findViewById(R.id.tariffs_progress_bar)
        btnOrderTaxi = findViewById(R.id.btn_order_taxi)
        btnOrderTaxi.setOnClickListener {
            if (selectedTariffItem != null) {
                // Если есть custom price (из ползунка), берем его, иначе из тарифа
                val finalPrice = selectedTariffItem!!.priceValue + selectedTariffItem!!.addedValue
                createOrder(selectedTariffItem!!.tariff, finalPrice)
            } else {
                showToast("Оберіть тариф")
            }
        }


        btnAddNewOrder = findViewById(R.id.btn_add_new_order)
        btnAddNewOrder.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("Нове замовлення")
                .setMessage("Бажаєте створити ще одне замовлення?")
                .setPositiveButton("Так") { dialog, _ ->
                    dialog.dismiss()
                    // Очищаємо локальний стан, щоб сховати поточне замовлення
                    sessionManager.clearActiveOrderId()
                    viewModel.clearOrderState()
                    // Повертаємо інтерфейс до початкового стану (вибір адреси)
                    showAddressPanel()
                }
                .setNegativeButton("Ні") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }

        btnSchedule = findViewById(R.id.btn_schedule)
        btnSchedule.setOnClickListener {
            showCustomScheduleDialog()
        }

        btnOpenComment = findViewById(R.id.btn_open_comment)
        ivCommentIcon = findViewById(R.id.iv_comment_icon)
        
        btnOpenComment.setOnClickListener {
            val intent = Intent(this, CommentActivity::class.java)
            intent.putExtra("EXTRA_COMMENT", orderComment) 
            commentLauncher.launch(intent)
        }

        containerOrigin = findViewById(R.id.container_origin)
        tvOrigin = findViewById(R.id.text_view_origin)
        containerDestination = findViewById(R.id.container_destination)
        tvDestination = findViewById(R.id.text_view_destination)

        tariffsRecyclerView = findViewById(R.id.tariffs_recycler_view)

        btnFavHome = findViewById(R.id.btn_fav_home)
        ivIconHome = findViewById(R.id.iv_icon_home)
        indicatorAddHome = findViewById(R.id.indicator_add_home)

        ivMarkerOrigin = findViewById(R.id.iv_marker_origin)
        ivMarkerDest = findViewById(R.id.iv_marker_dest)
        ivIconHome = findViewById(R.id.iv_icon_home)
        ivIconWork = findViewById(R.id.iv_icon_work)
        indicatorAddHome = findViewById(R.id.indicator_add_home)
        indicatorAddWork = findViewById(R.id.indicator_add_work)
        
        btnFavWork = findViewById(R.id.btn_fav_work)
        ivIconWork = findViewById(R.id.iv_icon_work)
        indicatorAddWork = findViewById(R.id.indicator_add_work)
        ivPaymentIcon = findViewById(R.id.iv_payment_icon)

        activeOrderCard = findViewById(R.id.active_order_card)
        layoutActiveOrderPrice = findViewById(R.id.layout_active_order_price)
        orderStatusText = findViewById(R.id.order_status_text)
        
        btnCancelOrder = findViewById(R.id.btn_cancel_order)

        layoutActiveOrderPrice = findViewById(R.id.layout_active_order_price)

        layoutSearchDetails = findViewById(R.id.layout_search_details)
        tvOrderTariffName = findViewById(R.id.tv_order_tariff_name)
        tvOrderServices = findViewById(R.id.tv_order_services)
        tvOrderComment = findViewById(R.id.tv_order_comment)
        
        layoutDriverDetails = findViewById(R.id.layout_driver_assigned_details)
        tvCarPlateLarge = findViewById(R.id.tv_car_plate_large)
        tvCarDetailsSubtitle = findViewById(R.id.tv_car_details_subtitle)
        tvDriverFirstName = findViewById(R.id.tv_driver_first_name)
        tvDriverExperience = findViewById(R.id.tv_driver_experience)
        tvDriverRidesCount = findViewById(R.id.tv_driver_rides_count)
        ivDriverPhoto = findViewById(R.id.iv_driver_photo)
        btnCallDriver = findViewById(R.id.btn_call_driver)
        tvDriverHealthInfo = findViewById(R.id.tv_driver_health_info)

        tvActiveOrderPrice = findViewById(R.id.tv_active_order_price)
        ivActiveOrderPayment = findViewById(R.id.iv_active_order_payment)

        btnMenu.setOnClickListener {
            if (activeOrderCard.visibility == View.VISIBLE) {
                // Вирішення 4: Працює як кнопка НАЗАД (Згортає активне замовлення)
                sessionManager.clearActiveOrderId()
                viewModel.clearOrderState()
                showAddressPanel()
            } else if (tariffsPanel.visibility == View.VISIBLE) {
                // Працює як кнопка НАЗАД (Виходить з тариФів)
                showAddressPanel()
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        btnRecenter.setOnClickListener {
            if (currentCity == null) {
                checkPermissionsAndAutoDetectCity()
            } else {
                recenterMapOnUser()
            }
        }

        btnRecenterRoute.setOnClickListener {
            // Перевіряємо, чи є маршрут і точки
            if (viewModel.currentRoutePolyline != null && originPlace != null && destinationPlace != null) {
                 try {
                     val boundsBuilder = LatLngBounds.Builder()
                     boundsBuilder.include(originPlace!!.latLng!!)
                     boundsBuilder.include(destinationPlace!!.latLng!!)
                     
                     currentWaypoints.forEach { boundsBuilder.include(it.first) }
                     decodedRoutePoints?.forEach { boundsBuilder.include(it) }

                     // Використовуємо універсальний відступ 80dp для боків, як при першому малюванні
                     val paddingSide = convertDpToPixel(80f).toInt()

                     mMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), paddingSide))
                     
                     btnRecenterRoute.visibility = View.GONE
                 } catch (e: Exception) {}
            }
        }

        val openOrigin = View.OnClickListener {
            // БЛОКИРУЕМ КЛИК, если адрес еще определяется
            if (tvOrigin.text.toString() == "Визначення...") return@OnClickListener

            pickerMode = MODE_ORIGIN
            isSelectingOrigin = true
            openAddressPicker(true)
        }
        containerOrigin.setOnClickListener(openOrigin)
        tvOrigin.setOnClickListener(openOrigin)

        val openDest = View.OnClickListener {
            // БЛОКИРУЕМ КЛИК, если адрес еще определяется
            if (tvOrigin.text.toString() == "Визначення...") return@OnClickListener

            pickerMode = MODE_DESTINATION
            isSelectingOrigin = false
            openAddressPicker(false)
        }
        containerDestination.setOnClickListener(openDest)
        tvDestination.setOnClickListener(openDest)

        btnFavHome.setOnClickListener { onFavoriteAddressClick(true) }
        btnFavWork.setOnClickListener { onFavoriteAddressClick(false) }

        btnCallDriver.setOnClickListener {
            val driverPhone = activeOrderCard.tag as? String
            if (!driverPhone.isNullOrEmpty()) {
                val intent = Intent(Intent.ACTION_DIAL); intent.data = Uri.parse("tel:$driverPhone"); startActivity(intent)
            } else { showToast("Номер недоступний") }
        }

        btnOrderTaxi.setOnClickListener {
            if (selectedTariffItem != null) {
                createOrder(selectedTariffItem!!.tariff, selectedTariffItem!!.priceValue)
            } else {
                showToast("Оберіть тариф")
            }
        }

        btnCancelOrder.setOnClickListener { cancelCurrentOrder() }
        
        setupMenuLogic()
    }
    
    private fun setupMenuLogic() {
        findViewById<View>(R.id.btn_open_profile_details).setOnClickListener {
            startActivity(Intent(this, UserDetailsActivity::class.java))
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        findViewById<View>(R.id.btn_menu_stats).setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        findViewById<View>(R.id.profile_btn_change_city).setOnClickListener {
            showCitySelectorDialog()
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        findViewById<View>(R.id.btn_menu_history).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        findViewById<View>(R.id.btn_open_favorites).setOnClickListener {
             startActivity(Intent(this, FavoriteAddressesActivity::class.java))
             drawerLayout.closeDrawer(GravityCompat.START)
        }
        findViewById<View>(R.id.btn_menu_payment).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            val intent = Intent(this, PaymentActivity::class.java)
            intent.putExtra("EXTRA_PAYMENT_METHOD", currentPaymentMethod)
            paymentLauncher.launch(intent)
        }
        findViewById<View>(R.id.btn_menu_discounts).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            val intent = Intent(this, HelpDiscountsActivity::class.java)
            startActivity(intent)
        }
        findViewById<View>(R.id.btn_menu_news).setOnClickListener {
        startActivity(Intent(this, NewsActivity::class.java))
        }
        findViewById<View>(R.id.btn_menu_help).setOnClickListener {
            val intent = Intent(this, HelpActivity::class.java)
            startActivity(intent)
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        findViewById<View>(R.id.btn_open_payment).setOnClickListener {
            val intent = Intent(this, PaymentActivity::class.java)
            intent.putExtra("EXTRA_PAYMENT_METHOD", currentPaymentMethod)
            paymentLauncher.launch(intent)
        }

        findViewById<View>(R.id.btn_open_services).setOnClickListener {
            val intent = Intent(this, ServicesActivity::class.java)
            intent.putExtra("SELECTED_IDS", selectedServiceIds)
            servicesLauncher.launch(intent)
        }

        btnChatDriver = findViewById(R.id.btn_chat_driver)
        tvChatBadge = findViewById(R.id.tv_chat_badge)

        btnChatDriver.setOnClickListener {
            activeOrderId?.let { orderId ->
                unreadChatMessages = 0
                updateChatBadgeUI()
                
                // Открываем экран чата
                val intent = Intent(this@HomeActivity, ChatActivity::class.java)
                intent.putExtra("ORDER_ID", orderId)
                startActivity(intent)
            }
        }
    }

    private fun setupProfileLogic() {
        val name = sessionManager.getUserName() ?: "User"
        profileUserName.text = name

        val firstLetter = if (name.isNotEmpty()) name.first().toString().uppercase() else "U"
        findViewById<TextView>(R.id.tv_avatar_letter).text = firstLetter

        profileCityText.text = currentCity?.name ?: "Не обрано"

        try {
            findViewById<TextView>(R.id.tv_user_rating).text = "5.0"
        } catch (e: Exception) { }

        val isDark = sessionManager.isDarkMode()
        updateThemeSwitchUI(isDark, animate = false, updateColors = true)

        themeSwitchContainer.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastSwitchTime < 1000) return@setOnClickListener
            lastSwitchTime = currentTime

            val currentMode = sessionManager.isDarkMode()
            val newMode = !currentMode
            sessionManager.saveThemeMode(newMode)

            updateThemeSwitchUI(newMode, animate = true, updateColors = false) {
                applyTheme(newMode)
            }
        }
    }
    
    private fun updateThemeSwitchUI(
        isDarkMode: Boolean, 
        animate: Boolean = true, 
        updateColors: Boolean = true,
        onAnimationEnd: (() -> Unit)? = null
    ) {
        val duration = if (animate) 200L else 0L

        themeSwitchContainer.post {
            val containerWidth = themeSwitchContainer.width - themeSwitchContainer.paddingStart - themeSwitchContainer.paddingEnd
            val translationX = if (isDarkMode) (containerWidth / 2f) else 0f
            
            themeSwitchThumb.animate()
                .translationX(translationX)
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { 
                    onAnimationEnd?.invoke() 
                }
                .start()
        }

        if (updateColors) {
            tvThemeLabel.text = if (isDarkMode) "Темна тема" else "Світла тема"

            val sunColor = ContextCompat.getColor(this, if (isDarkMode) R.color.switch_icon_inactive_dark else R.color.switch_icon_active_light)
            val moonColor = ContextCompat.getColor(this, if (isDarkMode) R.color.switch_icon_active_dark else R.color.switch_icon_inactive_light)
            ivThemeSun.setColorFilter(sunColor)
            ivThemeMoon.setColorFilter(moonColor)

            val trackColor = ContextCompat.getColor(this, if (isDarkMode) R.color.switch_track_dark else R.color.switch_track_light)
            val thumbColor = ContextCompat.getColor(this, if (isDarkMode) R.color.switch_thumb_dark else R.color.switch_thumb_light)
            
            themeSwitchContainer.background.setTint(trackColor)
            themeSwitchThumb.background.setTint(thumbColor)
        }
    }

    private fun showTopMessage(message: String, isError: Boolean = false) {
        if (!::customToastContainer.isInitialized) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); return }
        
        toastHandler.removeCallbacks(hideToastRunnable)
        tvToastMessage.text = message
        if (isError) {
            ivToastIcon.setColorFilter(Color.parseColor("#FF5252"))
        } else {
            ivToastIcon.setColorFilter(Color.parseColor("#FFD600"))
        }
        customToastContainer.visibility = View.VISIBLE
        customToastContainer.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(400)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        toastHandler.postDelayed(hideToastRunnable, 3500)
    }

    private fun hideTopMessage() {
        customToastContainer.animate()
            .translationY(-200f)
            .alpha(0f)
            .setDuration(300)
            .withEndAction { customToastContainer.visibility = View.INVISIBLE }
            .start()
    }

    private fun showToast(msg: String) {
        val isError = msg.contains("Помилка", true) || 
                      msg.contains("Error", true) || 
                      msg.contains("недоступний") || 
                      msg.contains("геолокацію")
                      
        showTopMessage(msg, isError)
    }

    private fun updateCommentIconState() {
        if (orderComment.isNotEmpty()) {
            ivCommentIcon.setColorFilter(ContextCompat.getColor(this, R.color.taxi_yellow))
        } else {
            val nightModeFlags = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            val isDarkMode = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES

            if (isDarkMode) {
                ivCommentIcon.setColorFilter(android.graphics.Color.WHITE)
            } else {
                ivCommentIcon.setColorFilter(android.graphics.Color.BLACK)
            }
        }
    }

    private fun onFavoriteAddressClick(isHome: Boolean) {
        currentWaypoints.clear()
        if (isHome) {
            val home = sessionManager.getHomeAddress()
            if (home != null) setDestination(home) else {
                pickerMode = MODE_ADD_HOME
                openAddressPicker(false, true)
            }
        } else {
            val work = sessionManager.getWorkAddress()
            if (work != null) setDestination(work) else {
                pickerMode = MODE_ADD_WORK
                openAddressPicker(false, true)
            }
        }
    }

    private fun setDestination(place: Place) {
        destinationPlace = place
        tvDestination.text = AddressUtils.formatAddress(place.name ?: "")
        tryDrawRoute()
    }

    private fun updateFavoriteButtonsUI() {
        val home = sessionManager.getHomeAddress()
        val work = sessionManager.getWorkAddress()

        if (home != null) {
            indicatorAddHome.visibility = View.GONE
            ivIconHome.setColorFilter(ContextCompat.getColor(this, R.color.text_primary))
        } else {
            indicatorAddHome.visibility = View.VISIBLE
            ivIconHome.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
        }

        if (work != null) {
            indicatorAddWork.visibility = View.GONE
            ivIconWork.setColorFilter(ContextCompat.getColor(this, R.color.text_primary))
        } else {
            indicatorAddWork.visibility = View.VISIBLE
            ivIconWork.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun handleAddressSelection(place: Place, name: String?) {
        val cleanName = AddressUtils.formatAddress(name ?: "")
        
        when (pickerMode) {
            MODE_ORIGIN -> {
                originPlace = place
                tvOrigin.text = cleanName
                tryDrawRoute()
            }
            MODE_DESTINATION -> {
                destinationPlace = place
                tvDestination.text = cleanName
                tryDrawRoute()
            }
            MODE_ADD_HOME -> {
                sessionManager.saveHomeAddress(place)
                updateFavoriteButtonsUI()
                showToast("Дім збережено")
                setDestination(place)
            }
            MODE_ADD_WORK -> {
                sessionManager.saveWorkAddress(place)
                updateFavoriteButtonsUI()
                showToast("Робота збережена")
                setDestination(place)
            }
        }
        if (place.latLng != null) mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(place.latLng!!, 16f))
    }

    private fun checkPermissionsAndAutoDetectCity() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            mMap?.isMyLocationEnabled = true
            detectCityAndMove()
            
            checkAndShowNotificationDialogWithDelay() 
        }
    }

    private fun detectCityAndMove() {
        try {
            val client = LocationServices.getFusedLocationProviderClient(this)
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                client.lastLocation.addOnSuccessListener { location ->
                    if (location != null) {
                        val foundCityName = findClosestCity(location.latitude, location.longitude)
                        if (foundCityName != null) {
                            val regionData = CityDatabase.regions[foundCityName]!!
                            currentCity = CityData(foundCityName, regionData.center.latitude, regionData.center.longitude, regionData.zoom)
                            sessionManager.saveUserCity(currentCity!!)
                            updateCityUI(foundCityName)
                            showToast("Ваш регіон: $foundCityName")
                            val userLatLng = LatLng(location.latitude, location.longitude)
                            mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, 16f))
                        } else {
                            showCitySelectorDialog()
                        }
                    } else {
                        showCitySelectorDialog()
                    }
                }
            }
        } catch (e: Exception) {
            showCitySelectorDialog()
        }
    }

    private fun updatePaymentIcon() {
        if (currentPaymentMethod == "CARD") {
            ivPaymentIcon.setImageResource(R.drawable.ic_card)
        } else {
            ivPaymentIcon.setImageResource(R.drawable.ic_cash)
        }
    }

    private fun findClosestCity(lat: Double, lng: Double): String? {
        var closestCity: String? = null
        var minDistance = Float.MAX_VALUE
        for ((name, data) in CityDatabase.regions) {
            val results = FloatArray(1)
            Location.distanceBetween(lat, lng, data.center.latitude, data.center.longitude, results)
            val distance = results[0]
            if (distance < 100000) {
                if (distance < minDistance) {
                    minDistance = distance
                    closestCity = name
                }
            }
        }
        return closestCity
    }

    private fun updateCityUI(name: String) {
        profileCityText.text = name
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDriverTracking()
    }

    private fun recenterMapOnUser() {
        if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        val client = LocationServices.getFusedLocationProviderClient(this)
        client.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 16f))
            else showToast("Місцезнаходження не знайдено")
        }
    }

    private fun recalculateTotalPrice() {
        val finalPrice = currentTariffPrice + servicesExtraCost
        tvPrice.text = "${finalPrice.toInt()} ₴" 
    }

    private fun openAddressPicker(isOrigin: Boolean, hideMyLocation: Boolean = false) {
        val intent = Intent(this, AddressPickerActivity::class.java)
        intent.putExtra(AddressPickerActivity.EXTRA_IS_ORIGIN, isOrigin)
        intent.putExtra(AddressPickerActivity.EXTRA_CURRENT_ADDRESS, tvOrigin.text.toString())
        
        var latToSend = 0.0
        var lngToSend = 0.0
        
        if (isOrigin) {
            try {
                val myLoc = mMap?.myLocation
                if (myLoc != null) {
                    latToSend = myLoc.latitude
                    lngToSend = myLoc.longitude
                }
            } catch (e: SecurityException) { }
            
            if (latToSend == 0.0 && originPlace?.latLng != null) {
                latToSend = originPlace!!.latLng!!.latitude
                lngToSend = originPlace!!.latLng!!.longitude
            }
        } else {
            if (originPlace != null && originPlace!!.latLng != null) {
                latToSend = originPlace!!.latLng!!.latitude
                lngToSend = originPlace!!.latLng!!.longitude
            } else {
                try {
                    val myLoc = mMap?.myLocation
                    if (myLoc != null) {
                        latToSend = myLoc.latitude
                        lngToSend = myLoc.longitude
                    }
                } catch (e: SecurityException) { }
            }
        }

        if (latToSend != 0.0 && lngToSend != 0.0) {
            intent.putExtra(AddressPickerActivity.EXTRA_CURRENT_LAT, latToSend)
            intent.putExtra(AddressPickerActivity.EXTRA_CURRENT_LNG, lngToSend)
        }
        
        if (hideMyLocation) intent.putExtra(AddressPickerActivity.EXTRA_HIDE_MY_LOCATION, true)
        
        val mapTarget = mMap?.cameraPosition?.target
        if (mapTarget != null) {
            intent.putExtra("city_lat", mapTarget.latitude)
            intent.putExtra("city_lng", mapTarget.longitude)
        } else if (currentCity != null) {
            intent.putExtra("city_lat", currentCity!!.lat)
            intent.putExtra("city_lng", currentCity!!.lng)
        }
        
        addressPickerLauncher.launch(intent)
    }

    private fun setupTariffAdapter() {
        tariffAdapter = TariffAdapter { item ->
            selectedTariffItem = item
            btnOrderTaxi.isEnabled = true
            btnOrderTaxi.text = "Замовити"
        }
        tariffsRecyclerView.adapter = tariffAdapter
        tariffsRecyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.LinearLayoutManager.VERTICAL, false)

        while (tariffsRecyclerView.itemDecorationCount > 0) {
            tariffsRecyclerView.removeItemDecorationAt(0)
        }

        tariffsRecyclerView.addItemDecoration(object : androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
            val divider = androidx.core.content.ContextCompat.getDrawable(this@HomeActivity, R.drawable.divider_horizontal)

            override fun getItemOffsets(outRect: android.graphics.Rect, view: android.view.View, parent: androidx.recyclerview.widget.RecyclerView, state: androidx.recyclerview.widget.RecyclerView.State) {
                val position = parent.getChildAdapterPosition(view)
                if (position != androidx.recyclerview.widget.RecyclerView.NO_POSITION && position < state.itemCount - 1) {
                    outRect.bottom = divider?.intrinsicHeight ?: 0
                } else {
                    outRect.bottom = 0
                }
            }

            override fun onDraw(c: android.graphics.Canvas, parent: androidx.recyclerview.widget.RecyclerView, state: androidx.recyclerview.widget.RecyclerView.State) {
                divider?.let {
                    val left = parent.paddingLeft
                    val right = parent.width - parent.paddingRight
                    
                    val childCount = parent.childCount
                    for (i in 0 until childCount) {
                        val child = parent.getChildAt(i)
                        val position = parent.getChildAdapterPosition(child)
                        
                        if (position != androidx.recyclerview.widget.RecyclerView.NO_POSITION && position < state.itemCount - 1) {
                            val params = child.layoutParams as androidx.recyclerview.widget.RecyclerView.LayoutParams
                            val top = child.bottom + params.bottomMargin
                            val bottom = top + it.intrinsicHeight
                            
                            it.setBounds(left, top, right, bottom)
                            it.draw(c)
                        }
                    }
                }
            }
        })
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap?.uiSettings?.isCompassEnabled = false
        if (sessionManager.isDarkMode()) {
            try {
                mMap?.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style_dark))
            } catch (e: Exception) {}
        } else {
            try {
                mMap?.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style_standard))
            } catch (e: Exception) {
                mMap?.setMapStyle(null)
            }
        }

        val cityToLoad = currentCity ?: sessionManager.fetchUserCity()
        val cityCenter = cityToLoad?.let { LatLng(it.lat, it.lng) } ?: LatLng(50.4501, 30.5234)
        val cityZoom = cityToLoad?.zoom ?: 11f
        mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(cityCenter, cityZoom))

        mMap?.uiSettings?.isZoomControlsEnabled = false

        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            mMap?.isMyLocationEnabled = true
            mMap?.uiSettings?.isMyLocationButtonEnabled = false 
            if (currentCity != null) recenterMapOnUser()
        }

        mMap?.setOnCameraMoveListener {
            updateSmartLabels()
        }

        mMap?.setOnCameraMoveStartedListener { reason ->
    if (viewModel.currentRoutePolyline == null) {
        
        // 1. Оборачиваем смену текста в post, чтобы не блокировать старт анимации!
        tvOrigin.post {
            tvOrigin.text = "Визначення..."
        }
        
        if (isInterfaceRevealed) {
            // 2. ЖЕСТКО отменяем предыдущую анимацию падения
            centerPin.animate().cancel() 
            
            centerPin.animate()
                .translationY(convertDpToPixel(-48f))
                .setInterpolator(DecelerateInterpolator())
                .setDuration(120)
                .start()
                
            try {
                pinShadow.animate().cancel() // Отменяем тень
                pinShadow.animate()
                    .scaleX(0.6f)
                    .scaleY(0.6f)
                    .alpha(0.3f)
                    .setDuration(120)
                    .start()
            } catch (e: Exception) {}
        }
    } else {
        if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
            btnRecenterRoute.visibility = View.VISIBLE
        }
    }
}

        mMap?.setOnCameraIdleListener {
            updateSmartLabels()

            // ЖЕЛЕЗНАЯ БЛОКИРОВКА:
            if (isRouteMode || viewModel.currentRoutePolyline != null) return@setOnCameraIdleListener

            // Логика выбора адреса (работает только когда нет маршрута)
            val center = mMap!!.cameraPosition.target
            getAddressForOrigin(center)

            if (isInterfaceRevealed) {
                // ДОБАВЛЕНО: Отменяем анимацию взлета, чтобы плавно начать падение
                centerPin.animate().cancel() 
                
                centerPin.animate()
                    .translationY(convertDpToPixel(-32f))
                    .setInterpolator(BounceInterpolator())
                    .setDuration(500)
                    .start()
                    
                try {
                    pinShadow.animate().cancel() // И для тени тоже
                    pinShadow.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .alpha(0.5f)
                        .setDuration(250)
                        .start()
                } catch (e: Exception) {}
            }
        }

        mMap?.setOnMapLoadedCallback {
            revealInterface()
        }

        Handler(Looper.getMainLooper()).postDelayed({
            revealInterface()
        }, 2500)
    }

    private var isInterfaceRevealed = false

    private fun revealInterface() {
        if (isInterfaceRevealed) return
        isInterfaceRevealed = true

        if (::mapLoadingCurtain.isInitialized) {
            mapLoadingCurtain.animate()
                .alpha(0f)
                .setDuration(800) 
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    mapLoadingCurtain.visibility = View.GONE
                }
                .start()
        }

        val skeletonViews = listOf(
            tvOrigin, tvDestination,            
            ivMarkerOrigin, ivMarkerDest,       
            ivIconHome, ivIconWork,             
            indicatorAddHome, indicatorAddWork 
        )

        skeletonViews.forEach { view ->
            try {
                view.animate()
                    .alpha(1f)
                    .setDuration(500)
                    .setStartDelay(200) 
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            } catch (e: Exception) {}
        }

        // ДОБАВЛЕНО: Плавно проявляем центральный пин
        centerPin.animate()
            .alpha(1f)
            .setDuration(500)
            .setStartDelay(200)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // ДОБАВЛЕНО: Плавно проявляем тень пина (тень должна быть полупрозрачной)
        try {
            pinShadow.animate()
                .alpha(0.5f) 
                .setDuration(500)
                .setStartDelay(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } catch (e: Exception) {}
    }

    private fun convertDpToPixel(dp: Float): Float {
        val metrics = resources.displayMetrics
        return dp * (metrics.densityDpi / 160f)
    }

    private fun cleanAddress(fullAddress: String): String {
        return AddressUtils.formatAddress(fullAddress)
    }

    private fun getAddressForOrigin(latLng: LatLng) {
        Thread {
            try {
                val geocoder = Geocoder(this, Locale("uk", "UA"))
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
                
                val addressName = if (!addresses.isNullOrEmpty()) {
                    val raw = addresses[0].getAddressLine(0)
                    AddressUtils.formatAddress(raw)
                } else "Точка на карті"
                
                Handler(Looper.getMainLooper()).post { 
                    if (viewModel.currentRoutePolyline == null) {
                        tvOrigin.text = addressName
                        originPlace = Place.builder().setName(addressName).setLatLng(latLng).build()
                    }
                }
            } catch (e: Exception) { 
                Handler(Looper.getMainLooper()).post { 
                    if (viewModel.currentRoutePolyline == null) {
                        tvOrigin.text = "Точка на карті" 
                    }
                } 
            }
        }.start()
    }

    private fun tryDrawRoute() {
        if (originPlace == null || destinationPlace == null) return

        isRouteMode = true 
        centerPin.visibility = View.GONE
        centerPin.animate().cancel() 
        try { pinShadow.visibility = View.GONE } catch (e: Exception) {}

        val originLatLng = originPlace!!.latLng!!
        val destinationLatLng = destinationPlace!!.latLng!!

        tvOverlayOrigin.text = cleanAddress(originPlace!!.name ?: "А")
        tvOverlayDest.text = cleanAddress(destinationPlace!!.name ?: "Б")

        // Скрываем оверлеи до тех пор, пока маршрут не начнет плавно рисоваться
        overlayOrigin.visibility = View.GONE
        overlayDest.visibility = View.GONE

        // Очищаем карту сразу, но маркеры пока НЕ ставим, чтобы они не мигали перед анимацией
        mMap?.clear() 

        // Запускаем предварительный зум камеры, чтобы не было задержки
        val builder = LatLngBounds.Builder()
        builder.include(originLatLng)
        builder.include(destinationLatLng)
        currentWaypoints.forEach { builder.include(it.first) }
        
        try { mMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 100)) } catch (e: Exception){}

        // ДЕЛЕГИРУЕМ В VIEWMODEL
        viewModel.fetchDirections(
            originLatLng, 
            destinationLatLng, 
            currentWaypoints
        )
        
        // Показываем панель тарифов (загрузка начнется, когда придет маршрут)
        fetchTariffsAndShowPanel()
    }
    
    private fun showTariffsPanel() {
       fetchTariffsAndShowPanel()
    }
    
    private fun drawStylishRoute(path: List<LatLng>) {
        if (mMap == null) return

        try {
            polylineMain?.remove()
            polylineBorder?.remove()
            originMarker?.remove()
            destinationMarker?.remove()
        } catch (e: Exception) {}

        centerPin.visibility = View.GONE
        try { pinShadow.visibility = View.GONE } catch (e: Exception) {}
        try { mMap?.isMyLocationEnabled = false } catch (e: SecurityException) { }
        mMap?.clear()

        val colorMain = ContextCompat.getColor(this, R.color.route_main)
        val colorBorder = ContextCompat.getColor(this, R.color.route_border)

        val transparentMain = Color.argb(0, Color.red(colorMain), Color.green(colorMain), Color.blue(colorMain))
        val transparentBorder = Color.argb(0, Color.red(colorBorder), Color.green(colorBorder), Color.blue(colorBorder))

        val borderOpts = PolylineOptions().addAll(path).width(20f).color(transparentBorder).startCap(RoundCap()).endCap(RoundCap()).zIndex(1f)
        polylineBorder = mMap?.addPolyline(borderOpts)

        val mainOpts = PolylineOptions().addAll(path).width(14f).color(transparentMain).startCap(RoundCap()).endCap(RoundCap()).zIndex(2f)
        polylineMain = mMap?.addPolyline(mainOpts)

        if (originPlace != null && originPlace!!.latLng != null) {
            originMarker = mMap?.addMarker(MarkerOptions()
                .position(originPlace!!.latLng!!)
                .icon(getBitmapDescriptor(R.drawable.ic_marker_base_yellow))
                .anchor(0.5f, 0.5f)
                .alpha(0f) 
                .zIndex(1000f))

            val uiText = tvOrigin.text.toString()
            val finalOriginText = if (uiText.contains("...") || uiText.isBlank()) (originPlace?.name ?: "А") else uiText
            tvOverlayOrigin.text = finalOriginText
            overlayOrigin.alpha = 0f
            overlayOrigin.visibility = View.VISIBLE
        }

        if (destinationPlace != null && destinationPlace!!.latLng != null) {
            destinationMarker = mMap?.addMarker(MarkerOptions()
                .position(destinationPlace!!.latLng!!)
                .icon(getBitmapDescriptor(R.drawable.ic_marker_base_white))
                .anchor(0.5f, 0.5f)
                .alpha(0f)
                .zIndex(1000f))

            val uiText = tvDestination.text.toString()
            val finalDestText = if (uiText.contains("...") || uiText.isBlank()) (destinationPlace?.name ?: "Б") else uiText
            tvOverlayDest.text = finalDestText

            val calendar = java.util.Calendar.getInstance()
            calendar.add(java.util.Calendar.SECOND, routeDurationSeconds)
            val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            val arrivalTime = sdf.format(calendar.time)
            tvOverlayDestDetails.text = "Приїдемо о $arrivalTime"

            overlayDest.alpha = 0f
            overlayDest.visibility = View.VISIBLE
        }

        val waypointIcon = BitmapHelper.vectorToBitmap(this, R.drawable.ic_waypoint_dot)
        for (wpPair in currentWaypoints) {
            mMap?.addMarker(MarkerOptions().position(wpPair.first).icon(waypointIcon).anchor(0.5f, 0.5f).alpha(0f).zIndex(500f))
        }

        val boundsBuilder = LatLngBounds.Builder()
        if (originPlace?.latLng != null) boundsBuilder.include(originPlace!!.latLng!!)
        if (destinationPlace?.latLng != null) boundsBuilder.include(destinationPlace!!.latLng!!)
        path.forEach { boundsBuilder.include(it) }

        val visibleBottomPanel = if (activeOrderCard.visibility == View.VISIBLE) activeOrderCard else tariffsPanel

        visibleBottomPanel.post {
            try {
                val panelHeight = if (visibleBottomPanel.visibility == View.VISIBLE) visibleBottomPanel.height else 0
                
                val paddingBottom = panelHeight 
                val paddingTop = convertDpToPixel(10f).toInt() 
                val paddingSide = convertDpToPixel(80f).toInt() 

                mMap?.setPadding(0, paddingTop, 0, paddingBottom) 

                val cameraUpdate = CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), paddingSide)

                mMap?.animateCamera(cameraUpdate, 800, object : GoogleMap.CancelableCallback {
                    override fun onFinish() {
                        runOnUiThread { startRouteRevealAnimation(colorMain, colorBorder, path) }
                    }

                    override fun onCancel() {
                        runOnUiThread { startRouteRevealAnimation(colorMain, colorBorder, path) }
                    }
                })
            } catch (e: Exception) {
                runOnUiThread { startRouteRevealAnimation(colorMain, colorBorder, path) }
            }
        }
        
        btnRecenterRoute.visibility = View.GONE
        contentBottomSheet.post { updateSmartLabels() }
    }

    private fun startRouteRevealAnimation(colorMain: Int, colorBorder: Int, path: List<LatLng>) {
        if (polylineMain == null || polylineBorder == null) return

        val polylineAnimator = ValueAnimator.ofInt(0, 255)
        polylineAnimator.duration = 1000 
        polylineAnimator.addUpdateListener { animator ->
            val alpha = animator.animatedValue as Int
            try {
                val newMainColor = Color.argb(alpha, Color.red(colorMain), Color.green(colorMain), Color.blue(colorMain))
                val newBorderColor = Color.argb(alpha, Color.red(colorBorder), Color.green(colorBorder), Color.blue(colorBorder))
                
                polylineMain?.color = newMainColor
                polylineBorder?.color = newBorderColor
            } catch (e: Exception) {}
        }

        val markerAnimator = ValueAnimator.ofFloat(0f, 1f)
        markerAnimator.duration = 1000
        markerAnimator.addUpdateListener { animator ->
            val alpha = animator.animatedValue as Float
            try {
                originMarker?.alpha = alpha
                destinationMarker?.alpha = alpha
            } catch (e: Exception) {}
        }

        overlayOrigin.animate().alpha(1f).setDuration(1000).start()
        overlayDest.animate().alpha(1f).setDuration(1000).start()

        polylineAnimator.start()
        markerAnimator.start()

        polylineAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                super.onAnimationEnd(animation)
                animateRoute(path)
            }
        })
    }
    
    private fun animateRoute(path: List<LatLng>) {
        if (path.isEmpty()) return
        val animOpts = PolylineOptions().width(14f).color(Color.WHITE).zIndex(3f).startCap(RoundCap()).endCap(RoundCap())
        polylineAnim = mMap?.addPolyline(animOpts)

        routeAnimator = ValueAnimator.ofInt(0, 200)
        routeAnimator?.duration = 4000
        routeAnimator?.interpolator = LinearInterpolator()

        routeAnimator?.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                animHandler.postDelayed({
                    if (polylineAnim != null && routeAnimator != null) {
                        routeAnimator?.start()
                    }
                }, 2000)
            }
        })

        routeAnimator?.addUpdateListener { animator ->
            try {
                val progress = animator.animatedValue as Int
                if (path.isNotEmpty()) {
                    val totalPoints = path.size
                    val endRaw = (totalPoints * progress) / 100
                    val startRaw = Math.max(0, endRaw - (totalPoints / 3))
                    val end = Math.min(endRaw, totalPoints)
                    val start = Math.min(startRaw, end)

                    if (end > start) {
                        val subList = path.subList(start, end)
                        polylineAnim?.points = subList
                        val gradientSpan = StyleSpan(StrokeStyle.gradientBuilder(Color.TRANSPARENT, Color.WHITE).build())
                        polylineAnim?.spans = listOf(gradientSpan)
                    } else {
                        polylineAnim?.points = emptyList()
                    }
                }
            } catch (e: Exception) {}
        }
        routeAnimator?.start()
    }

    fun incrementUnreadMessages() {
        unreadChatMessages++
        updateChatBadgeUI()
        showToast("Нове повідомлення від водія!")
    }

    private fun updateChatBadgeUI() {
        runOnUiThread {
            if (unreadChatMessages > 0) {
                tvChatBadge.visibility = View.VISIBLE
                tvChatBadge.text = unreadChatMessages.toString()
            } else {
                tvChatBadge.visibility = View.GONE
            }
        }
    }

    private fun clearMapForRoute() {
        mMap?.clear()

        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
             try {
                 mMap?.isMyLocationEnabled = true
             } catch (e: Exception) {}
        }

        originMarker = null 
        destinationMarker = null
        mMap?.setPadding(0, 0, 0, 0)
        routeAnimator?.removeAllListeners()
        routeAnimator?.cancel()
        routeAnimator = null
        animHandler.removeCallbacksAndMessages(null)
        btnRecenterRoute.visibility = View.GONE

        polylineBorder = null
        polylineMain = null
        polylineAnim = null
        viewModel.currentRoutePolyline = null // Сброс в VM
        centerPin.visibility = View.GONE
        try { pinShadow.visibility = View.GONE } catch (e: Exception) {}

        selectedTariffItem = null
        btnOrderTaxi.isEnabled = false
        btnOrderTaxi.text = "Замовити"

        overlayOrigin.visibility = View.GONE
        overlayDest.visibility = View.GONE

        decodedRoutePoints = null
        // Сброс в VM состояния маршрута, чтобы не пересчитывался
        viewModel.loadTariffsAndCalculatePrice(null, 0)
    }

    private fun fetchTariffsAndShowPanel() {
        addressPanel.visibility = View.GONE
        tariffsPanel.visibility = View.VISIBLE
        tariffsProgressBar.visibility = View.VISIBLE
        btnRecenter.visibility = View.GONE
        setLocationButtonAnchor(R.id.tariffs_panel)

        try { btnOpenPromo.visibility = View.GONE } catch (e: Exception) {}

        tariffAdapter.submitList(emptyList(), 0)
        
        ivMenuIcon.setImageResource(R.drawable.ic_arrow_back_black)
        val adaptiveColor = ContextCompat.getColor(this, R.color.text_primary)
        ivMenuIcon.setColorFilter(adaptiveColor)

        // Логику тарифов делегируем в ViewModel
        // Маршрут уже лежит в ViewModel (currentRoutePolyline), расстояние тоже
        val route = viewModel.currentRoutePolyline
        val dist = viewModel.routeInfo.value?.first ?: 0
        
        viewModel.loadTariffsAndCalculatePrice(route, dist)
    }

    private fun showCustomScheduleDialog() {
        val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.dialog_schedule_ride, null)
        dialog.setContentView(view)

        val tabDate = view.findViewById<TextView>(R.id.tab_date)
        val tabTime = view.findViewById<TextView>(R.id.tab_time)
        val datePicker = view.findViewById<DatePicker>(R.id.date_picker_widget)
        val timePicker = view.findViewById<TimePicker>(R.id.time_picker_widget)
        val btnConfirm = view.findViewById<Button>(R.id.btn_confirm_schedule)

        // --- ФИКС БАГА 2: Исправляем глюк DatePicker со скроллом прошлых месяцев ---
        val today = Calendar.getInstance()
        // Строго обнуляем время до начала дня
        today.set(Calendar.HOUR_OF_DAY, 0)
        today.set(Calendar.MINUTE, 0)
        today.set(Calendar.SECOND, 0)
        today.set(Calendar.MILLISECOND, 0)

        // Отнимаем 1 секунду (надежный хак, чтобы барабан не сходил с ума при расчетах)
        datePicker.minDate = today.timeInMillis - 1000

        val maxDate = Calendar.getInstance()
        maxDate.add(Calendar.DAY_OF_MONTH, 7)
        datePicker.maxDate = maxDate.timeInMillis

        timePicker.setIs24HourView(true)

        // --- ФИКС БАГА 1: Правильные цвета для вкладок ---
        fun updateTabs(isDateSelected: Boolean) {
            // Берем цвета из темы
            val colorPrimary = ContextCompat.getColor(this@HomeActivity, R.color.text_primary)
            val colorBg = ContextCompat.getColor(this@HomeActivity, R.color.card_background)
            val colorSecondary = ContextCompat.getColor(this@HomeActivity, R.color.text_secondary)

            if (isDateSelected) {
                tabDate.setBackgroundResource(R.drawable.bg_button_primary)
                tabDate.backgroundTintList = android.content.res.ColorStateList.valueOf(colorPrimary)
                tabDate.setTextColor(colorBg)

                tabTime.background = null
                tabTime.backgroundTintList = null
                tabTime.setTextColor(colorSecondary)

                datePicker.visibility = View.VISIBLE
                timePicker.visibility = View.GONE
            } else {
                tabTime.setBackgroundResource(R.drawable.bg_button_primary)
                tabTime.backgroundTintList = android.content.res.ColorStateList.valueOf(colorPrimary)
                tabTime.setTextColor(colorBg)

                tabDate.background = null
                tabDate.backgroundTintList = null
                tabDate.setTextColor(colorSecondary)

                datePicker.visibility = View.GONE
                timePicker.visibility = View.VISIBLE
            }
        }

        // Инициализация стартового состояния
        tabDate.setOnClickListener { updateTabs(true) }
        tabTime.setOnClickListener { updateTabs(false) }

        btnConfirm.setOnClickListener {
            val selectedCalendar = Calendar.getInstance()
            selectedCalendar.set(Calendar.YEAR, datePicker.year)
            selectedCalendar.set(Calendar.MONTH, datePicker.month)
            selectedCalendar.set(Calendar.DAY_OF_MONTH, datePicker.dayOfMonth)

            val hour = if (Build.VERSION.SDK_INT >= 23) timePicker.hour else timePicker.currentHour
            val minute = if (Build.VERSION.SDK_INT >= 23) timePicker.minute else timePicker.currentMinute

            selectedCalendar.set(Calendar.HOUR_OF_DAY, hour)
            selectedCalendar.set(Calendar.MINUTE, minute)
            selectedCalendar.set(Calendar.SECOND, 0)

            val now = Calendar.getInstance()
            // Если выбрано прошлое время
            if (selectedCalendar.before(now)) {
                showToast("Не можна обрати час у минулому")
                // Вызываем updateTabs и теперь текст "Час" не пропадет!
                updateTabs(false)
            } else {
                scheduledDate = selectedCalendar
                updateOrderButtonWithTime()
                dialog.dismiss()
            }
        }

        dialog.show()
    }
    private fun updateOrderButtonWithTime() {
    if (scheduledDate != null) {
        val now = Calendar.getInstance()
        
        // Форматуємо час (наприклад, "15:30")
        val timeSdf = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeStr = timeSdf.format(scheduledDate!!.time)

        // Визначаємо день (Сьогодні, Завтра або конкретна дата)
        val dateStr = when {
            isSameDay(scheduledDate!!, now) -> "Сьогодні, $timeStr"
            isTomorrow(scheduledDate!!, now) -> "Завтра, $timeStr"
            else -> {
                // Для української локалі (наприклад, "24 лют")
                val dayMonthSdf = java.text.SimpleDateFormat("d MMM", Locale("uk", "UA"))
                "${dayMonthSdf.format(scheduledDate!!.time)}, $timeStr"
            }
        }

        // Формуємо верхній рядок з ціною
        val topText = if (selectedTariffItem != null) {
            val price = selectedTariffItem!!.priceValue + selectedTariffItem!!.addedValue
            "Замовити ${price.toInt()} ₴"
        } else {
            "Замовити"
        }

        // Об'єднуємо з переносом рядка
        val fullText = "$topText\n$dateStr"
        val spannable = SpannableString(fullText)
        
        // Робимо нижній рядок (дату і час) трохи меншим (75% від основного розміру)
        spannable.setSpan(
            RelativeSizeSpan(0.75f), 
            topText.length + 1, 
            fullText.length, 
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Застосовуємо текст до кнопки
        btnOrderTaxi.text = spannable
        
    } else {
        // Якщо час не обрано - стандартний вигляд
        if (selectedTariffItem != null) {
            val price = selectedTariffItem!!.priceValue + selectedTariffItem!!.addedValue
            btnOrderTaxi.text = "Замовити ${price.toInt()} ₴"
        } else {
            btnOrderTaxi.text = "Оберіть тариф"
        }
    }
    
    // Завжди фарбуємо іконку календаря в text_primary незалежно від того, обрано час чи ні
    btnSchedule.setColorFilter(ContextCompat.getColor(this, R.color.text_primary))
}

private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
           cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}

private fun isTomorrow(target: Calendar, now: Calendar): Boolean {
    val tomorrow = Calendar.getInstance()
    tomorrow.timeInMillis = now.timeInMillis
    tomorrow.add(Calendar.DAY_OF_YEAR, 1)
    return isSameDay(target, tomorrow)
}

    private fun loadSectors() {
        ApiClient.instance.getSectors().enqueue(object : retrofit2.Callback<List<SectorDto>> {
            override fun onResponse(call: Call<List<SectorDto>>, response: Response<List<SectorDto>>) {
                if (response.isSuccessful && response.body() != null) {
                    loadedSectors = response.body()!!
                }
            }
            override fun onFailure(call: Call<List<SectorDto>>, t: Throwable) { }
        })
    }

    private fun fetchCustomCarIcon() {
        ApiClient.instance.getCarIconUrl().enqueue(object : Callback<Map<String, String>> {
            override fun onResponse(call: Call<Map<String, String>>, response: Response<Map<String, String>>) {
                var url = response.body()?.get("url")
                
                if (!url.isNullOrEmpty()) {
                    if (url!!.contains("localhost")) {
                        val myIp = "192.168.0.104"
                        url = url!!.replace("localhost", myIp)
                    }

                    Glide.with(this@HomeActivity)
                        .asBitmap()
                        .load(url)
                        .into(object : CustomTarget<Bitmap>() {
                            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                val scaled = Bitmap.createScaledBitmap(resource, 130, 130, false)
                                customCarIcon = BitmapDescriptorFactory.fromBitmap(scaled)
                                
                                if (driverMarker != null) {
                                    driverMarker?.setIcon(customCarIcon)
                                }
                            }
                            override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
                        })
                }
            }
            override fun onFailure(call: Call<Map<String, String>>, t: Throwable) {}
        })
    }

    private fun startDriverTracking(orderId: Long) {
        if (isDriverTrackingActive) return
        isDriverTrackingActive = true

        val token = sessionManager.fetchAuthToken()
        webSocketManager?.connect(token)
        
        webSocketManager?.subscribeToDriverLocation(orderId) { locationDto ->
            runOnUiThread {
                updateDriverMarker(locationDto)
            }
        }
    }

    private fun stopDriverTracking() {
        isDriverTrackingActive = false
        webSocketManager?.disconnect()
        driverMarker?.remove()
        driverMarker = null
    }

    private fun updateDriverMarker(loc: TrackingLocationDto) {
        var targetPos = LatLng(loc.lat, loc.lng)
        
        if (decodedRoutePoints != null && decodedRoutePoints!!.isNotEmpty()) {
            val (snappedPoint, distance) = getSnapPointAndDistance(targetPos, decodedRoutePoints!!)
            if (distance < 50.0) {
                targetPos = snappedPoint
            }
        }

        if (driverMarker == null) {
            val icon = customCarIcon ?: BitmapHelper.vectorToBitmapDescriptor(this, R.drawable.ic_sun)
            val initialBearing = loc.bearing ?: 0f
            
            driverMarker = mMap?.addMarker(MarkerOptions()
                .position(targetPos)
                .icon(icon)
                .anchor(0.5f, 0.5f)
                .rotation(initialBearing)
                .flat(true)) 
        } else {
            val oldPos = driverMarker!!.position
            val distanceMoved = com.google.maps.android.SphericalUtil.computeDistanceBetween(oldPos, targetPos)
            var newBearing = driverMarker!!.rotation
            
            if (distanceMoved > 2.0) {
                newBearing = com.google.maps.android.SphericalUtil.computeHeading(oldPos, targetPos).toFloat()
            }
            animateMarker(driverMarker!!, targetPos, newBearing)
        }
    }

    private fun animateMarker(marker: Marker, toPosition: LatLng, toRotation: Float) {
        val startPos = marker.position
        val startRotation = marker.rotation
        
        val valueAnimator = ValueAnimator.ofFloat(0f, 1f)
        valueAnimator.duration = 2000
        valueAnimator.interpolator = LinearInterpolator()
        
        valueAnimator.addUpdateListener { animation ->
            val v = animation.animatedFraction
            val lng = v * toPosition.longitude + (1 - v) * startPos.longitude
            val lat = v * toPosition.latitude + (1 - v) * startPos.latitude
            marker.position = LatLng(lat, lng)
            
            var rot = toRotation - startRotation
            while (rot < -180) rot += 360
            while (rot > 180) rot -= 360
            marker.rotation = startRotation + rot * v
        }
        valueAnimator.start()
    }
    
    private fun startStatusBlinking() {
        if (::statusBlinkAnimator.isInitialized && statusBlinkAnimator.isRunning) return

        statusBlinkAnimator = ObjectAnimator.ofFloat(orderStatusText, "alpha", 1f, 0.4f, 1f)
        statusBlinkAnimator.duration = 1500 
        statusBlinkAnimator.repeatCount = ValueAnimator.INFINITE
        statusBlinkAnimator.repeatMode = ValueAnimator.REVERSE
        statusBlinkAnimator.start()
    }

    private fun restoreOrderOnMap(order: TaxiOrderDto) {
        if (isRouteMode && activeOrderId == order.id) return // Вже відмальовано

        isRouteMode = true
        centerPin.visibility = View.GONE
        try { pinShadow.visibility = View.GONE } catch (e: Exception) {}

        val originLat = order.originLat ?: return
        val originLng = order.originLng ?: return
        val destLat = order.destLat ?: return
        val destLng = order.destLng ?: return

        val originLoc = LatLng(originLat, originLng)
        val destLoc = LatLng(destLat, destLng)

        // Вирішення 1: Відновлюємо логіку точок А та Б для правильного фокусу камери
        originPlace = Place.builder().setName(order.fromAddress).setLatLng(originLoc).build()
        destinationPlace = Place.builder().setName(order.toAddress).setLatLng(destLoc).build()

        tvOrigin.text = cleanAddress(order.fromAddress ?: "А")
        tvDestination.text = cleanAddress(order.toAddress ?: "Б")

        currentWaypoints.clear()

        val polyline = order.googleRoutePolyline
        if (!polyline.isNullOrEmpty()) {
            viewModel.currentRoutePolyline = polyline 
            val points = com.google.maps.android.PolyUtil.decode(polyline)
            decodedRoutePoints = points
            
            // Гарантуємо правильні відступи перед відмальовуванням
            creationPanelCard.visibility = View.GONE
            addressPanel.visibility = View.GONE
            activeOrderCard.visibility = View.VISIBLE
            
            drawStylishRoute(points)
        }
    }

    private fun stopStatusBlinking() {
        if (::statusBlinkAnimator.isInitialized) {
            statusBlinkAnimator.cancel()
            orderStatusText.alpha = 1f 
        }
    }

    private fun updateSmartLabels() {
        if (mMap == null) return
        
        val originLoc = originPlace?.latLng
        val destLoc = destinationPlace?.latLng
        val projection = mMap!!.projection

        if (originLoc != null && overlayOrigin.visibility == View.VISIBLE) {
            val screenPt = projection.toScreenLocation(originLoc)
            moveViewIdeally(overlayOrigin, screenPt.x.toFloat(), screenPt.y.toFloat(), isStartPoint = true)
        }

        if (destLoc != null && overlayDest.visibility == View.VISIBLE) {
            val screenPt = projection.toScreenLocation(destLoc)
            moveViewIdeally(overlayDest, screenPt.x.toFloat(), screenPt.y.toFloat(), isStartPoint = false)
        }
    }

    private fun moveViewIdeally(view: View, targetX: Float, targetY: Float, isStartPoint: Boolean) {
        val viewWidth = view.width
        val viewHeight = view.height
        val screenWidth = resources.displayMetrics.widthPixels
        
        var isRouteGoingUp = false 

        if (decodedRoutePoints != null && decodedRoutePoints!!.isNotEmpty()) {
            val projection = mMap!!.projection
            val routePoints = decodedRoutePoints!!
            
            val compareLatLng = if (isStartPoint) {
                if (routePoints.size > 1) routePoints[1] else routePoints[0]
            } else {
                if (routePoints.size > 1) routePoints[routePoints.size - 2] else routePoints[0]
            }

            val compareScreenPt = projection.toScreenLocation(compareLatLng)

            if (compareScreenPt.y < targetY) {
                isRouteGoingUp = true
            }
        }

        var finalY: Float
        val verticalPadding = convertDpToPixel(8f)

        if (isRouteGoingUp) {
            finalY = targetY + verticalPadding
        } else {
            finalY = targetY - viewHeight - verticalPadding
        }

        var finalX = targetX - (viewWidth / 2)

        val margin = convertDpToPixel(16f)

        if (finalX < margin) {
            finalX = margin
        }
        if (finalX + viewWidth > screenWidth - margin) {
            finalX = screenWidth - margin - viewWidth
        }

        val topSafeArea = convertDpToPixel(50f) 
        val bottomSafeArea = resources.displayMetrics.heightPixels - convertDpToPixel(150f) 

        if (finalY < topSafeArea) {
            finalY = targetY + verticalPadding
        }
        else if (finalY + viewHeight > bottomSafeArea) {
             finalY = targetY - viewHeight - verticalPadding
        }

        view.x = finalX
        view.y = finalY
    }

    private fun getBitmapDescriptor(id: Int): BitmapDescriptor? {
        val vectorDrawable = androidx.core.content.ContextCompat.getDrawable(this, id) ?: return null
        vectorDrawable.setBounds(0, 0, vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight)
        val bitmap = Bitmap.createBitmap(vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        vectorDrawable.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun startWaitingTimer(order: TaxiOrderDto) {
    stopWaitingTimer() // Останавливаем старый, если был

    if (order.arrivedAt == null) return
    layoutWaitingInfo.visibility = View.VISIBLE

    // 1. Очищаем время от миллисекунд и "Z" (иначе Android не сможет спарсить строку)
    val cleanArrivedAt = order.arrivedAt.substringBefore(".").substringBefore("Z")

    val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
    // format.timeZone = TimeZone.getTimeZone("UTC") <-- Убрали, чтобы использовать локальное время телефона
    
    val arrivedTime = try {
        format.parse(cleanArrivedAt)?.time ?: return
    } catch (e: Exception) {
        e.printStackTrace()
        return
    }

    waitingTimerRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val diffMs = now - arrivedTime
            
            if (diffMs < 0) {
                waitingTimerHandler.postDelayed(this, 1000)
                return
            }

            val diffMinutesFull = diffMs / (1000 * 60).toDouble()
            val freeMins = order.freeWaitingMinutes

            if (diffMinutesFull <= freeMins) {
                // Бесплатное ожидание (Зеленый цвет)
                val remainingMs = (freeMins * 60 * 1000) - diffMs
                val remMin = (remainingMs / (1000 * 60)).toInt()
                val remSec = ((remainingMs / 1000) % 60).toInt()
                
                layoutWaitingInfo.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#EBFBEE"))
                tvWaitingTimer.setTextColor(android.graphics.Color.parseColor("#2B8A3E"))
                tvWaitingTimer.text = String.format("⏱ Безкоштовне очікування: %02d:%02d", remMin, remSec)
            } else {
                // Платное ожидание (Красный цвет)
                val paidMins = Math.floor(diffMinutesFull - freeMins).toInt()
                val extraCost = paidMins * order.pricePerWaitingMinute
                
                layoutWaitingInfo.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FFF5F5"))
                tvWaitingTimer.setTextColor(android.graphics.Color.parseColor("#C92A2A"))
                tvWaitingTimer.text = String.format("⏳ Платне очікування: %d хв (+%.2f грн)", paidMins, extraCost)
            }
            waitingTimerHandler.postDelayed(this, 1000)
        }
    }
    waitingTimerHandler.post(waitingTimerRunnable!!)
}

private fun stopWaitingTimer() {
    waitingTimerRunnable?.let { waitingTimerHandler.removeCallbacks(it) }
    waitingTimerRunnable = null
}    

    private fun displayTariffs() {
        if (availableTariffs.isEmpty()) {
            showToast("Немає тарифів")
            return
        }

        // 1. Оновлюємо мапу БАЗОВИХ цін (tariffCustomPrices)
        // Логику расчета "billableKm" можно тоже вынести, но пока оставим здесь как UI-логику
        val INCLUDED_KM = 3.0
        val totalKm = routeDistanceMeters / 1000.0
        val billableKm = if (totalKm > INCLUDED_KM) totalKm - INCLUDED_KM else 0.0

        availableTariffs.forEach { tariff ->
            // ПРІОРИТЕТ 1: Якщо сервер прислав точну ціну
            if (tariff.calculatedPrice != null && tariff.calculatedPrice!! > 0) {
                tariffCustomPrices[tariff.id] = tariff.calculatedPrice!!
            } 
            // ПРІОРИТЕТ 2: Рахуємо самі (Фолбек)
            else {
                val localPrice = tariff.basePrice + (billableKm * tariff.pricePerKm)
                tariffCustomPrices[tariff.id] = ceil(localPrice)
            }
        }

        // 2. Оновлюємо список в адаптері
        tariffAdapter.submitList(availableTariffs, routeDistanceMeters)
        tariffAdapter.updatePrices(tariffCustomPrices)

        tariffsPanel.post {
            updateMapPadding(tariffsPanel, 0f, 10f)
        }

        // Логіка вибору тарифу за замовчуванням
        if (selectedTariffItem == null) {
            val defaultTariff = availableTariffs.find { it.name.contains("Standard", ignoreCase = true) } 
                ?: availableTariffs.firstOrNull()

            if (defaultTariff != null) {
                val finalPrice = tariffCustomPrices[defaultTariff.id] ?: defaultTariff.basePrice
                
                val item = TariffItem(
                    tariff = defaultTariff, 
                    priceString = String.format("%.0f", finalPrice), 
                    priceValue = finalPrice, 
                    addedValue = 0.0
                ) 
                
                selectedTariffItem = item
                tariffAdapter.setSelectedTariffId(defaultTariff.id)
                
                btnOrderTaxi.isEnabled = true
                
                // ВАЖЛИВО: Викликаємо метод оновлення кнопки, щоб врахувати час (календар)
                updateOrderButtonWithTime()
            }
        } else {
            // Если тариф уже был выбран, обновляем цену
            val item = selectedTariffItem!!
            val newBasePrice = tariffCustomPrices[item.tariff.id]
            
            if (newBasePrice != null) {
                selectedTariffItem = item.copy(
                    priceValue = newBasePrice,
                    priceString = String.format("%.0f", newBasePrice)
                )
            }
            
            btnOrderTaxi.isEnabled = true
            updateOrderButtonWithTime()
        }
    }

    private fun setLocationButtonAnchor(anchorId: Int) {
        val btnLocation = findViewById<View>(R.id.btn_recenter_location)
        val params = btnLocation.layoutParams as RelativeLayout.LayoutParams
        
        params.removeRule(RelativeLayout.ABOVE)
        params.addRule(RelativeLayout.ABOVE, anchorId)
        
        btnLocation.layoutParams = params
    }
    
    private fun createOrder(tariff: CarTariffDto, price: Double) {
        btnOrderTaxi.isEnabled = false
        btnOrderTaxi.text = "Обробка..."

        val waypointsDto = currentWaypoints.map { pair ->
            WaypointDto(
                address = pair.second,
                lat = pair.first.latitude,
                lng = pair.first.longitude
            )
        }

        val myAddedValue = selectedTariffItem?.addedValue ?: 0.0

        var scheduledAtString: String? = null
        if (scheduledDate != null) {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:00", Locale.getDefault())
            scheduledAtString = sdf.format(scheduledDate!!.time)
        }

        val request = CreateOrderRequestDto(
            fromAddress = originPlace!!.name ?: "А",
            toAddress = destinationPlace!!.name ?: "Б",
            tariffId = tariff.id,
            price = price,
            originLat = originPlace!!.latLng?.latitude,
            originLng = originPlace!!.latLng?.longitude,
            destLat = destinationPlace!!.latLng?.latitude,
            destLng = destinationPlace!!.latLng?.longitude,
            googleRoutePolyline = viewModel.currentRoutePolyline, // Берем из VM
            waypoints = if (waypointsDto.isNotEmpty()) waypointsDto else null,
            distanceMeters = routeDistanceMeters,
            durationSeconds = routeDurationSeconds,
            comment = if (orderComment.isBlank()) null else orderComment,
            paymentMethod = currentPaymentMethod,
            serviceIds = selectedServiceIds,
            addedValue = myAddedValue,
            scheduledAt = scheduledAtString
        )

        viewModel.createOrder(request)
    }

    private fun showChangePriceDialog() {
        val selectedItem = tariffAdapter.getSelectedTariff()
        if (selectedItem == null) {
            showToast("Спочатку оберіть тариф")
            return
        }

        val currentTotalInAdapter = selectedItem.priceValue
        val oldAddedValue = selectedItem.addedValue
        val basePriceWithServices = currentTotalInAdapter - oldAddedValue

        val minPrice = basePriceWithServices.toInt()
        val maxPrice = (basePriceWithServices * 3).toInt()
        
        var currentPrice = (basePriceWithServices + oldAddedValue).toInt()

        val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.dialog_change_price, null)
        dialog.setContentView(view)
        
        dialog.window?.let { window ->
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.navigationBarColor = ContextCompat.getColor(this, android.R.color.black)
            window.statusBarColor = Color.TRANSPARENT 

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val decorView = window.decorView
                var flags = decorView.systemUiVisibility
                flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                decorView.systemUiVisibility = flags
            }
        }

        val tvPrice = view.findViewById<TextView>(R.id.tv_dialog_price)
        val btnMinus = view.findViewById<View>(R.id.btn_price_minus)
        val btnPlus = view.findViewById<View>(R.id.btn_price_plus)
        val seekBar = view.findViewById<SeekBar>(R.id.seekbar_price)
        val btnSave = view.findViewById<Button>(R.id.btn_save_price)
        val btnClose = view.findViewById<View>(R.id.btn_close_dialog)

        val range = maxPrice - minPrice
        seekBar.max = range
        
        fun updateUI() {
            tvPrice.text = "$currentPrice ₴"
            val progress = currentPrice - minPrice
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                seekBar.setProgress(progress, true)
            } else {
                seekBar.progress = progress
            }
        }

        updateUI()

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentPrice = minPrice + progress
                    tvPrice.text = "$currentPrice ₴"
                }
            }
            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        })

        btnPlus.setOnClickListener {
            if (currentPrice + 10 <= maxPrice) {
                currentPrice += 10
                updateUI()
            } else {
                currentPrice = maxPrice
                updateUI()
            }
        }

        btnMinus.setOnClickListener {
            if (currentPrice - 10 >= minPrice) {
                currentPrice -= 10
                updateUI()
            } else {
                currentPrice = minPrice
                updateUI()
            }
        }

        btnSave.setOnClickListener {
            val newAddedValue = (currentPrice - minPrice).toDouble()
            servicesExtraCost = newAddedValue 
            tariffAdapter.setCustomPrice(selectedItem.tariff.id, newAddedValue)
            dialog.dismiss()
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
    
    private fun cancelCurrentOrder() {
        btnCancelOrder.isEnabled = false
        btnCancelOrder.text = "Скасування..."
        viewModel.cancelOrder()
    }

    private fun showActiveOrderPanel(order: TaxiOrderDto) {
    // 1. Проверяем, была ли панель скрыта до этого
    val isFirstShow = activeOrderCard.visibility != View.VISIBLE

    activeOrderCard.visibility = View.VISIBLE
    tariffsPanel.visibility = View.GONE
    
    // Вирішення 5: Приховуємо панель вводу А і Б, щоб не було накладання
    creationPanelCard.visibility = View.GONE
    addressPanel.visibility = View.GONE

    // Вирішення 3: Приховуємо кнопку "Моє місцезнаходження"
    btnRecenter.visibility = View.GONE

    // Вирішення 4: Робимо кнопку меню видимою і перетворюємо її на кнопку "Назад"
    btnMenu.visibility = View.VISIBLE
    ivMenuIcon.setImageResource(R.drawable.ic_arrow_back_black)
    val adaptiveColor = ContextCompat.getColor(this, R.color.text_primary)
    ivMenuIcon.setColorFilter(adaptiveColor)

    // 2. Делаем отступы и центрируем камеру ТОЛЬКО при первом показе!
    if (isFirstShow) {
        setLocationButtonAnchor(R.id.active_order_card)
        updateMapPadding(activeOrderCard, 0f, 20f)
    }

    try { btnOpenPromo.visibility = View.GONE } catch (e: Exception) {}
    

        // Далі йде твій код без змін...
        tvActiveOrderPrice.text = String.format("%.0f ₴", order.price)

        if (order.paymentMethod == "CARD") {
            ivActiveOrderPayment.setImageResource(R.drawable.ic_card)
        } else {
            ivActiveOrderPayment.setImageResource(R.drawable.ic_cash)
        }

        tvOrderTariffName.text = order.tariffName

        val ivOrderTariffIcon = findViewById<ImageView>(R.id.iv_order_tariff_icon)

        val matchingTariff = availableTariffs.find { it.name == order.tariffName }
        val imageFileName = matchingTariff?.imageUrl

        if (!imageFileName.isNullOrEmpty()) {
            ivOrderTariffIcon.imageTintList = null
            val fullUrl = "http://192.168.0.104:8080/uploads/$imageFileName"

            Glide.with(this)
                .load(fullUrl)
                .placeholder(R.drawable.ic_taxi_model_standard)
                .error(R.drawable.ic_taxi_model_standard)
                .into(ivOrderTariffIcon)
        } else {
            ivOrderTariffIcon.setImageResource(R.drawable.ic_taxi_model_standard)
            ivOrderTariffIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
        }

        if (order.services.isNotEmpty()) {
            tvOrderServices.visibility = View.VISIBLE
            val servicesText = order.services.joinToString(separator = ", ") { it.name }
            tvOrderServices.text = "+ $servicesText"
        } else {
            tvOrderServices.visibility = View.GONE
        }

        if (order.comment != null && order.comment.isNotEmpty()) {
            tvOrderComment.visibility = View.VISIBLE
            tvOrderComment.text = "Коментар: ${order.comment}"
        } else {
            tvOrderComment.visibility = View.GONE
        }

        updateStatusUI(order)
    }
    
    private fun checkOrderStatus() {
        // Оставлен как заглушка, если вдруг где-то вызывается,
        // но основная логика теперь в ViewModel
    }

    private fun getSnapPointAndDistance(rawLocation: LatLng, route: List<LatLng>): Pair<LatLng, Double> {
        if (route.size < 2) return Pair(rawLocation, 0.0)

        var closestPoint = rawLocation
        var minDistance = Double.MAX_VALUE

        for (i in 0 until route.size - 1) {
            val start = route[i]
            val end = route[i + 1]

            val pointOnSegment = findNearestPointOnSegment(rawLocation, start, end)
            
            val distance = SphericalUtil.computeDistanceBetween(rawLocation, pointOnSegment)

            if (distance < minDistance) {
                minDistance = distance
                closestPoint = pointOnSegment
            }
        }

        return Pair(closestPoint, minDistance)
    }

    private fun findNearestPointOnSegment(p: LatLng, start: LatLng, end: LatLng): LatLng {
        if (start == end) return start

        val sLat = Math.toRadians(start.latitude)
        val sLng = Math.toRadians(start.longitude)
        val eLat = Math.toRadians(end.latitude)
        val eLng = Math.toRadians(end.longitude)
        val pLat = Math.toRadians(p.latitude)
        val pLng = Math.toRadians(p.longitude)

        val sinSLat = Math.sin(sLat)
        val cosSLat = Math.cos(sLat)
        val sinELat = Math.sin(eLat)
        val cosELat = Math.cos(eLat)
        val sinPLat = Math.sin(pLat)
        val cosPLat = Math.cos(pLat)

        val x1 = cosSLat * Math.cos(sLng)
        val y1 = cosSLat * Math.sin(sLng)
        val z1 = sinSLat

        val x2 = cosELat * Math.cos(eLng)
        val y2 = cosELat * Math.sin(eLng)
        val z2 = sinELat

        val x = cosPLat * Math.cos(pLng)
        val y = cosPLat * Math.sin(pLng)
        val z = sinPLat

        val t = ((x - x1) * (x2 - x1) + (y - y1) * (y2 - y1) + (z - z1) * (z2 - z1)) /
                ((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1))

        return if (t <= 0) {
            start
        } else if (t >= 1) {
            end
        } else {
            val xRes = x1 + t * (x2 - x1)
            val yRes = y1 + t * (y2 - y1)
            val zRes = z1 + t * (z2 - z1)

            val latRes = Math.atan2(zRes, Math.sqrt(xRes * xRes + yRes * yRes))
            val lngRes = Math.atan2(yRes, xRes)
            LatLng(Math.toDegrees(latRes), Math.toDegrees(lngRes))
        }
    }
    
    private fun showRatingDialog(orderId: Long, driverName: String?) {
        if (isRatingDialogVisible) return
        isRatingDialogVisible = true 

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_rate_driver)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(false)

        val ratingBar = dialog.findViewById<RatingBar>(R.id.rating_bar)
        val etComment = dialog.findViewById<EditText>(R.id.et_comment)
        val btnSubmit = dialog.findViewById<Button>(R.id.btn_submit_rating)
        val tvTitle = dialog.findViewById<TextView>(R.id.tv_rating_title)

        tvTitle.text = "Оцініть поїздку з $driverName"

        btnSubmit.setOnClickListener {
            val score = ratingBar.rating.toInt()
            if (score == 0) {
                showToast("Будь ласка, поставте оцінку")
                return@setOnClickListener
            }

            btnSubmit.isEnabled = false
            btnSubmit.text = "Відправка..."

            val comment = etComment.text.toString()
            sendRating(orderId, score, comment, dialog)
        }

        dialog.setOnDismissListener {
            isRatingDialogVisible = false
        }

        dialog.show()
    }

    private fun sendRating(orderId: Long, score: Int, comment: String, dialog: Dialog) {
        val token = sessionManager.fetchAuthToken()
        if (token == null) {
            showToast("Помилка авторизації")
            isRatingDialogVisible = false
            dialog.dismiss()
            return
        }
        
        val request = RateDriverRequest(orderId, score, comment)

        ApiClient.instance.rateDriver("Bearer $token", request).enqueue(object : Callback<MessageResponse> {
            override fun onResponse(call: Call<MessageResponse>, response: Response<MessageResponse>) {
                fun finishRatingProcess() {
                    showToast("Дякуємо за відгук!")
                    try { dialog.dismiss() } catch (e: Exception) {}
                    isRatingDialogVisible = false
                    
                    // Очистка состояния через ViewModel
                    viewModel.clearOrderState()
                    activeOrderId = null
                    showAddressPanel()
                }

                if (response.isSuccessful) {
                    finishRatingProcess()
                } else {
                    val code = response.code()
                    val errorBody = try { response.errorBody()?.string() ?: "" } catch (e: Exception) { "" }

                    if (code == 500 || code == 400) {
                        if (errorBody.contains("вже оцінили") || errorBody.contains("RuntimeException")) {
                            finishRatingProcess()
                            return
                        }
                    }
                    
                    showToast("Помилка: $code")
                    try {
                        val btn = dialog.findViewById<Button>(R.id.btn_submit_rating)
                        btn.isEnabled = true
                        btn.text = "Відправити"
                    } catch (e: Exception) {}
                }
            }

            override fun onFailure(call: Call<MessageResponse>, t: Throwable) {
                showToast("Помилка мережі")
                try {
                    val btn = dialog.findViewById<Button>(R.id.btn_submit_rating)
                    btn.isEnabled = true
                    btn.text = "Відправити"
                } catch (e: Exception) {}
            }
        })
    }

    private fun updateStatusUI(order: TaxiOrderDto) {
        orderStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        
        btnCancelOrder.visibility = View.VISIBLE
        btnCancelOrder.isEnabled = true
        btnCancelOrder.text = "Скасувати"

        layoutActiveOrderPrice.visibility = View.VISIBLE 

        when(order.status) {
            "REQUESTED", "OFFERING" -> {
                orderStatusText.text = "Пошук водія..."
                startStatusBlinking()
                layoutSearchDetails.visibility = View.VISIBLE
                layoutDriverDetails.visibility = View.GONE
                stopDriverTracking()
                
                // Захист: ховаємо таймер, якщо статус змінився
                stopWaitingTimer()
                layoutWaitingInfo.visibility = View.GONE
            }
            
            "ACCEPTED" -> {
                stopStatusBlinking()
                orderStatusText.text = "Водій їде до вас"
                layoutSearchDetails.visibility = View.GONE
                layoutDriverDetails.visibility = View.VISIBLE
                updateDriverInfo(order)
                
                // Захист: ховаємо таймер
                stopWaitingTimer()
                layoutWaitingInfo.visibility = View.GONE

                order.driver?.let { drv ->
                    val lat = drv.latitude
                    val lng = drv.longitude
                    
                    if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
                        val initialLoc = TrackingLocationDto(
                            lat = lat,
                            lng = lng,
                            bearing = drv.bearing ?: 0f 
                        )
                        updateDriverMarker(initialLoc)
                    }
                }
                startDriverTracking(order.id)
            }

            "DRIVER_ARRIVED" -> {
                stopStatusBlinking()
                orderStatusText.text = "Водій на місці" 
                layoutSearchDetails.visibility = View.GONE
                layoutDriverDetails.visibility = View.VISIBLE
                btnCancelOrder.visibility = View.GONE 
                updateDriverInfo(order)
                
                // ---> НОВЕ: Запускаємо відлік очікування <---
                startWaitingTimer(order)
                
                order.driver?.let { drv ->
                    val lat = drv.latitude
                    val lng = drv.longitude
                    if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
                        val initialLoc = TrackingLocationDto(
                            lat = lat, 
                            lng = lng, 
                            bearing = drv.bearing ?: 0f
                        )
                        updateDriverMarker(initialLoc)
                    }
                }

                startDriverTracking(order.id)
            }

            "IN_PROGRESS" -> {
                stopStatusBlinking()
                orderStatusText.text = "В дорозі"
                layoutActiveOrderPrice.visibility = View.GONE 
                layoutSearchDetails.visibility = View.GONE
                layoutDriverDetails.visibility = View.VISIBLE
                btnCancelOrder.visibility = View.GONE 
                updateDriverInfo(order)
                
                // ---> НОВЕ: Зупиняємо таймер та показуємо фінальну надбавку <---
                stopWaitingTimer()
                if (order.waitingPrice > 0) {
                    layoutWaitingInfo.visibility = View.VISIBLE
                    layoutWaitingInfo.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FFF4E6"))
                    tvWaitingTimer.setTextColor(android.graphics.Color.parseColor("#D9480F"))
                    // Форматуємо до 2 знаків після коми
                    tvWaitingTimer.text = String.format("💰 Додано за очікування: %.2f грн", order.waitingPrice)
                } else {
                    layoutWaitingInfo.visibility = View.GONE
                }

                order.driver?.let { drv ->
                    val lat = drv.latitude
                    val lng = drv.longitude
                    if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
                        val initialLoc = TrackingLocationDto(
                            lat = lat, 
                            lng = lng, 
                            bearing = drv.bearing ?: 0f
                        )
                        updateDriverMarker(initialLoc)
                    }
                }

                startDriverTracking(order.id)
            }
            
            "COMPLETED" -> {
                stopStatusBlinking()
                orderStatusText.text = "Поїздку завершено"
                layoutActiveOrderPrice.visibility = View.GONE
                layoutSearchDetails.visibility = View.GONE
                layoutDriverDetails.visibility = View.GONE
                btnCancelOrder.visibility = View.GONE
                stopDriverTracking()
                
                // ---> НОВЕ: Ховаємо таймер <---
                stopWaitingTimer()
                layoutWaitingInfo.visibility = View.GONE
                
                // Таймер опроса уже остановлен ViewModel

                if (!order.isRatedByClient) {
                    showRatingDialog(order.id, order.driver?.fullName ?: "водієм")
                } else {
                    viewModel.clearOrderState()
                    activeOrderId = null
                    showAddressPanel()
                }
            }
            
            "CANCELLED" -> {
                stopStatusBlinking()
                orderStatusText.text = "Скасовано"
                orderStatusText.setTextColor(Color.RED)
                layoutActiveOrderPrice.visibility = View.GONE
                layoutSearchDetails.visibility = View.GONE
                layoutDriverDetails.visibility = View.GONE
                btnCancelOrder.visibility = View.GONE
                stopDriverTracking()
                
                // ---> НОВЕ: Ховаємо таймер <---
                stopWaitingTimer()
                layoutWaitingInfo.visibility = View.GONE

                // ДОБАВЛЕНО: Заставляем карту пересчитать отступы для похудевшей карточки!
                // Теперь логотип Google моментально опустится вниз.
                updateMapPadding(activeOrderCard, 0f, 20f)
                
                viewModel.clearOrderState()
                
                Handler(Looper.getMainLooper()).postDelayed({ showAddressPanel() }, 3000)
            }
        }
    }
    
    private fun updateDriverInfo(order: TaxiOrderDto) {
        order.driver?.let { drv ->
            tvCarPlateLarge.text = drv.carPlateNumber ?: "---"

            val colorPart = drv.carColor ?: ""
            val modelPart = drv.carModel ?: "Авто"
            val tariffPart = order.tariffName ?: "Standard"

            val subtitle = buildString {
                if (colorPart.isNotEmpty()) append("$colorPart ")
                append(modelPart)
                append(" • ")
                append(tariffPart)
            }
            tvCarDetailsSubtitle.text = subtitle

            val fullName = drv.fullName
            val firstName = fullName.split(" ").firstOrNull() ?: fullName
            tvDriverFirstName.text = firstName

            val months = drv.monthsInService
            val expText = if (months < 1) "В службі < 1 міс." else "В службі $months міс."
            tvDriverExperience.text = expText

            tvDriverRidesCount.text = "Поїздок: ${drv.completedRides}"

            activeOrderCard.tag = drv.phoneNumber

            // Блок мед. предупреждений
            val healthIssues = mutableListOf<String>()
            if (drv.hasMovementIssue) healthIssues.add("порушення опорно-рухового апарату")
            if (drv.hasHearingIssue) healthIssues.add("порушення слуху")
            if (drv.isDeaf) healthIssues.add("водій не чує (глухий)")
            if (drv.hasSpeechIssue) healthIssues.add("порушення мовлення")

            if (healthIssues.isNotEmpty()) {
                tvDriverHealthInfo.visibility = View.VISIBLE
                tvDriverHealthInfo.text = "ℹ️ Увага! Особливості водія: ${healthIssues.joinToString(", ")}"
            } else {
                tvDriverHealthInfo.visibility = View.GONE
            }

            if (!drv.photoUrl.isNullOrEmpty()) {
                var finalUrl = drv.photoUrl!!
                if (finalUrl.contains("localhost")) finalUrl = finalUrl.replace("localhost", "10.0.2.2")
                if (finalUrl.contains("localhost")) finalUrl = finalUrl.replace("localhost", "192.168.0.104")

                Glide.with(this@HomeActivity)
                    .load(finalUrl)
                    .placeholder(R.drawable.ic_avatar_placeholder)
                    .circleCrop()
                    .into(ivDriverPhoto)
            } else {
                ivDriverPhoto.setImageResource(R.drawable.ic_avatar_placeholder)
            }
        }
    }
    
    private fun updateMapPadding(bottomPanel: View, extraBottomDp: Float = 20f, topPaddingDp: Float = 20f) {
    bottomPanel.post {
        if (mMap != null) {
            // ДОБАВЛЕНО: Если панель была скрыта до окончания расчетов, сбрасываем отступы
            if (bottomPanel.visibility != View.VISIBLE) {
                mMap?.setPadding(0, 0, 0, 0)
                return@post
            }

            val panelHeight = bottomPanel.height
            if (panelHeight == 0) return@post

            val extraBuffer = convertDpToPixel(extraBottomDp).toInt()
            val totalBottomPadding = panelHeight + extraBuffer
            val topPadding = convertDpToPixel(topPaddingDp).toInt()

            mMap?.setPadding(0, topPadding, 0, totalBottomPadding)

            if (viewModel.currentRoutePolyline != null) {
                try {
                    val boundsBuilder = LatLngBounds.Builder()
                    if (originPlace != null && destinationPlace != null) {
                        boundsBuilder.include(originPlace!!.latLng!!)
                        boundsBuilder.include(destinationPlace!!.latLng!!)
                        
                        currentWaypoints.forEach { boundsBuilder.include(it.first) }
                        decodedRoutePoints?.forEach { boundsBuilder.include(it) }

                        val labelSafePadding = convertDpToPixel(80f).toInt()

                        mMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), labelSafePadding))
                        
                        btnRecenterRoute.visibility = View.GONE
                    }
                } catch (e: Exception) {}
            }
        }
    }
}

    private fun showAddressPanel() {
    isRouteMode = false
    creationPanelCard.visibility = View.VISIBLE
    activeOrderCard.visibility = View.GONE
    addressPanel.visibility = View.VISIBLE
    tariffsPanel.visibility = View.GONE
    btnRecenter.visibility = View.VISIBLE

    setLocationButtonAnchor(R.id.bottom_sheet_card)
    btnMenu.visibility = View.VISIBLE

    // ДОБАВЛЕНО: Сброс отступов на всю ширину завернут в post, чтобы перекрыть старые расчеты
    tariffsPanel.post {
        mMap?.setPadding(0, 0, 0, 0)
    }

    try { btnOpenPromo.visibility = View.VISIBLE } catch (e: Exception) {}

    ivMenuIcon.setImageResource(R.drawable.ic_menu_hamburger)

    clearMapForRoute()

        sessionManager.clearActiveOrderId()
        tariffAdapter.submitList(emptyList(), 0)

        tariffCustomPrices.clear()
        tariffAdapter.clearCustomPrices()

        selectedServiceIds.clear()
        servicesExtraCost = 0.0
        tariffAdapter.updateExtraCost(0.0)

        orderComment = ""
        updateCommentIconState()
        
        scheduledDate = null
        try {
            val isDark = sessionManager.isDarkMode()
            val color = if (isDark) Color.WHITE else Color.BLACK
            btnSchedule.setColorFilter(color)
        } catch (e: Exception){}

        tvOrigin.text = "Звідки?"
        tvDestination.text = "Куди?"
        originPlace = null
        destinationPlace = null

        centerPin.visibility = View.VISIBLE
        centerPin.alpha = 1f // ДОБАВЛЕНО: подстраховка прозрачности
        centerPin.translationY = convertDpToPixel(-48f)
        try {
            pinShadow.visibility = View.VISIBLE
            pinShadow.alpha = 0.3f
            pinShadow.scaleX = 0.6f
            pinShadow.scaleY = 0.6f
        } catch (e: Exception) {}

        centerPin.animate()
            .translationY(convertDpToPixel(-32f))
            .setInterpolator(BounceInterpolator())
            .setDuration(500)
            .start()

        try {
            pinShadow.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .alpha(0.5f)
                .setDuration(250)
                .start()
        } catch (e: Exception) {}

        recenterMapOnUser()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        // Перевіряємо, чи з'явився новий активний ID (наприклад, після кліку в Історії)
        val savedId = sessionManager.fetchActiveOrderId()
        if (savedId != -1L) {
            viewModel.activeOrderId = savedId
            viewModel.startStatusPolling() // Це запустить запит до сервера і розгорне замовлення
        }
    }
    
    private fun resetUI() {
        tariffCustomPrices.clear()
        tariffAdapter.clearCustomPrices()
    }
    private fun showCitySelectorDialog() { val intent = Intent(this, CityPickerActivity::class.java); cityPickerLauncher.launch(intent) }
    override fun onCreateOptionsMenu(menu: Menu?): Boolean { return true }
    override fun onOptionsItemSelected(item: MenuItem): Boolean { return true }
}