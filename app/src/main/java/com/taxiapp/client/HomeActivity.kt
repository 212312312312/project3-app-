package com.taxiapp.client

import android.Manifest
import android.view.Window
import android.text.Spannable
import android.view.MotionEvent
import android.transition.AutoTransition
import android.transition.TransitionManager
import com.facebook.shimmer.ShimmerFrameLayout
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan
import kotlin.math.roundToInt
import android.text.Spanned
import android.content.res.Configuration
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
import android.util.Log
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

class HomeActivity : BaseActivity() , OnMapReadyCallback {

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

    // Хранилище для данных интента, чтобы вернуться в Picker без потери текста в полях
private var lastAddressPickerIntentData: Intent? = null

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
    
    private var webSocketManager: WebSocketManager? = null
    private var driverMarker: Marker? = null
    private var customCarIcon: BitmapDescriptor? = null
    private var isDriverTrackingActive = false

    private var originMarker: Marker? = null
    private var destinationMarker: Marker? = null
    
    private val currentWaypoints = mutableListOf<Pair<LatLng, String>>()



// --- Змінні для розгортання деталей замовлення ---
    private lateinit var handleTouchZone: FrameLayout
    private lateinit var layoutExpandableDetails: LinearLayout
    private var isOrderDetailsExpanded = false

    // Переменные для анимации радара при поиске водителя
    private val radarCircles = mutableListOf<com.google.android.gms.maps.model.Circle>()
    private var radarAnimator: android.animation.ValueAnimator? = null
    private var isRadarRunning = false

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var btnMenu: CardView
    private lateinit var ivMenuIcon: ImageView

    private lateinit var profileUserName: TextView
    private lateinit var profileBtnDetails: LinearLayout 
    private lateinit var profileCityText: TextView
    private lateinit var profileBtnCity: LinearLayout 

    private lateinit var tvPaymentMethodText: TextView
    
    private lateinit var themeSwitchContainer: ConstraintLayout
    private lateinit var themeSwitchThumb: View
    private lateinit var ivThemeSun: ImageView
    private lateinit var ivThemeMoon: ImageView
    private lateinit var tvThemeLabel: TextView

    
    
    private lateinit var btnOpenPromo: CardView
    private lateinit var centerPin: ImageView
    private lateinit var pinShadow: ImageView

    private lateinit var btnDriverHealthAlert: View
    

    private lateinit var btnRecenter: CardView
    private lateinit var btnRecenterRoute: CardView

    // --- НОВЫЕ ПЕРЕМЕННЫЕ ДЛЯ РЕЖИМА ВЫБОРА НА КАРТЕ ---
private var isMapPickingMode = false
private var mapPickerIsOrigin = true // true - если выбираем точку А, false - если Б
private lateinit var layoutMapPickerPanel: View
private lateinit var btnBackMapPicker: View
private lateinit var tvMapPickerAddress: TextView
private lateinit var btnConfirmMapPicker: Button

    private lateinit var containerOrigin: LinearLayout
    private lateinit var tvOrigin: TextView
    private lateinit var containerDestination: LinearLayout
    private lateinit var tvDestination: TextView

    private var lastLocationUpdateTime = 0L
    private val LOCATION_UPDATE_INTERVAL = 7000L // Оновлення раз на 7 секунд
    private var lastLocationSent: LatLng? = null
    private val nearbyDriverMarkers = mutableMapOf<Long, Marker>()

    private lateinit var tvOverlayDestDetails: TextView
    
    private lateinit var ivMarkerOrigin: ImageView
    private lateinit var ivMarkerDest: ImageView

    private val waypointMarkers = mutableListOf<Marker>()

    private lateinit var btnFavHome: CardView
    private lateinit var ivIconHome: ImageView
    private lateinit var indicatorAddHome: ImageView
    private lateinit var btnFavWork: CardView

    private var revealAnimator: ValueAnimator? = null
    private var markerRevealAnimator: ValueAnimator? = null

    private lateinit var ivIconWork: ImageView
    private lateinit var indicatorAddWork: ImageView

    private lateinit var creationPanelCard: View
    private lateinit var addressPanel: View
    private lateinit var tariffsPanel: View
    private lateinit var tariffsShimmer: ShimmerFrameLayout
    private lateinit var btnOrderTaxi: Button
    private lateinit var tariffsRecyclerView: RecyclerView
    private lateinit var tariffAdapter: TariffAdapter

    private lateinit var layoutSearchDetails: View
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
    private var mapPickerTarget = "target_origin"
private var mapPickerWaypointIndex = -1

    private lateinit var btnChatDriver: ImageButton
    private lateinit var tvChatBadge: TextView
    private var unreadChatMessages = 0

    // --- ПЕРЕМЕННАЯ ДЛЯ КОНТРОЛЯ АНИМАЦИИ ПАНЕЛИ ---
    private var orderDetailsAnimator: ValueAnimator? = null

    private lateinit var tvCarDetailsSubtitle: TextView
    private lateinit var tvDriverFirstName: TextView
    private lateinit var tvDriverExperience: TextView
    private lateinit var tvDriverRidesCount: TextView
    private lateinit var ivDriverPhoto: ImageView
    private lateinit var btnCallDriver: ImageButton
    private lateinit var tvDriverHealthInfo: TextView


    private var waitingTimerHandler = Handler(Looper.getMainLooper())
    private var waitingTimerRunnable: Runnable? = null

    private lateinit var tvActiveOrderPrice: TextView
    private lateinit var ivActiveOrderPayment: ImageView

    private lateinit var layoutActiveOrderPrice: View

    private lateinit var mapLoadingCurtain: ImageView
    private lateinit var contentBottomSheet: View

    
    private var currentActiveLanguage: String = ""       
    
    private var loadedSectors: List<SectorDto> = emptyList()
    private var isRatingDialogVisible = false

    private lateinit var layoutPaymentCompleted: LinearLayout
    private lateinit var tvFinalPaymentPrice: TextView
    private lateinit var btnUnderstandPayment: Button
    private var lastLabelUpdateTime = 0L
    
    private var originPlace: Place? = null
    private var destinationPlace: Place? = null
    private var currentCity: CityData? = null

    private var polylineAnim: Polyline? = null
    private var webViewDialog: Dialog? = null
    private var routeAnimator: ValueAnimator? = null



private lateinit var layoutSearchControls: LinearLayout
private lateinit var layoutDriverFoundState: LinearLayout
private lateinit var btnCancelRideDriver: Button // Нижня кнопка скасування з блоку водія
    // Переменные для статус-пила
private lateinit var statusCircle1: com.google.android.material.card.MaterialCardView
private lateinit var statusIcon1: ImageView
private lateinit var statusLine1: com.google.android.material.card.MaterialCardView

private lateinit var statusCircle2: com.google.android.material.card.MaterialCardView
private lateinit var statusIcon2: ImageView
private lateinit var statusLine2: com.google.android.material.card.MaterialCardView

private lateinit var statusCircle3: com.google.android.material.card.MaterialCardView
private lateinit var statusIcon3: ImageView

private var maxDetailsHeight = 0

private lateinit var cardWaitingTimer: View
private lateinit var tvNewWaitingTimer: TextView

private lateinit var buttonsShimmer: ShimmerFrameLayout

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
            // --- Стандартний вибір адреси зі списку ---
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
            lastAddressPickerIntentData = data
            mapPickerTarget = data.getStringExtra(AddressPickerActivity.RESULT_TARGET_TYPE) ?: AddressPickerActivity.TARGET_ORIGIN
            mapPickerWaypointIndex = data.getIntExtra(AddressPickerActivity.RESULT_WAYPOINT_INDEX, -1)

            // ФИКС: Сохраняем Точку А сразу, если юзер её изменил в пикере
            if (data.hasExtra(AddressPickerActivity.RESULT_ORIGIN_LAT)) {
                val oName = data.getStringExtra(AddressPickerActivity.RESULT_ORIGIN_NAME)
                val oLat = data.getDoubleExtra(AddressPickerActivity.RESULT_ORIGIN_LAT, 0.0)
                val oLng = data.getDoubleExtra(AddressPickerActivity.RESULT_ORIGIN_LNG, 0.0)
                if (oLat != 0.0 && oLng != 0.0) {
                    originPlace = Place.builder().setName(oName).setLatLng(LatLng(oLat, oLng)).build()
                    tvOrigin.text = AddressUtils.formatAddress(oName ?: "")
                }
            }

            // Відновлюємо проміжний стан для карти
            val destName = data.getStringExtra(AddressPickerActivity.RESULT_NAME)

            val wLats = data.getDoubleArrayExtra(AddressPickerActivity.RESULT_WAYPOINTS_LATS)
            val wLngs = data.getDoubleArrayExtra(AddressPickerActivity.RESULT_WAYPOINTS_LNGS)
            val wNames = data.getStringArrayListExtra(AddressPickerActivity.RESULT_WAYPOINTS_NAMES)

            currentWaypoints.clear()
            if (wLats != null && wLngs != null) {
                for (i in wLats.indices) {
                    if (wLats[i] != 0.0 && wLngs[i] != 0.0) {
                        val wpName = if (wNames != null && i < wNames.size && wNames[i].isNotEmpty()) wNames[i] else "Зупинка"
                        currentWaypoints.add(Pair(LatLng(wLats[i], wLngs[i]), wpName))
                    }
                }
            }

            // 3. Вмикаємо UI карти
            val isOriginSelection = (mapPickerTarget == AddressPickerActivity.TARGET_ORIGIN)
            showMapPickerMode(isOriginSelection)

            // 4. Центруємо камеру на потрібній точці
            val startLatLng = when (mapPickerTarget) {
                AddressPickerActivity.TARGET_ORIGIN -> originPlace?.latLng
                AddressPickerActivity.TARGET_DESTINATION -> destinationPlace?.latLng
                AddressPickerActivity.TARGET_WAYPOINT -> {
                    if (mapPickerWaypointIndex in currentWaypoints.indices) currentWaypoints[mapPickerWaypointIndex].first else null
                }
                else -> null
            } ?: mMap?.cameraPosition?.target
            
            if (startLatLng != null) {
                // Використовуємо moveCamera замість animateCamera для миттєвого переходу
                mMap?.moveCamera(com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(startLatLng, 17f))
                
                // 5. ФІКС ЗАВИСАННЯ: Примусово запускаємо геокодинг
                tvMapPickerAddress.text = "Шукаємо адресу..."
                getAddressFromLocation(startLatLng) { address ->
                    tvMapPickerAddress.text = address ?: "Невідома адреса"
                }
            }
        }
    }
}

private fun fetchAddressAtCurrentLocation() {
    val target = mMap?.cameraPosition?.target ?: return
    
    // Просто меняем текст, кнопку визуально не трогаем
    tvMapPickerAddress.text = "Шукаємо адресу..."

    getAddressFromLocation(target) { address ->
        tvMapPickerAddress.text = address ?: "Невідома адреса"
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
            
            // Новая логика уведомлений (без цены)
            if (selectedServiceIds.isNotEmpty()) {
                showToast("Послугу додано")
            } else {
                showToast("Послугу вилучено")
            }
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

        currentActiveLanguage = sessionManager.getLanguage()

        webSocketManager = WebSocketManager(ApiClient.BASE_URL)
        fetchCustomCarIcon()
        val sessionManager = com.taxiapp.client.utils.SessionManager(this)
        val token = sessionManager.fetchAuthToken() // ИСПРАВЛЕНО ЗДЕСЬ

        if (!token.isNullOrEmpty()) {
            webSocketManager?.connect(token)
        } else {
            android.util.Log.e("WS_TAXI_DEBUG", "❌ Токен пуст, не могу подключить сокет!")
        }

        // А затем уже слушаем
        viewModel.startListeningNearbyDrivers(webSocketManager)

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
        updateThemeLabel()

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

        viewModel.startListeningNearbyDrivers(webSocketManager)

        viewModel.nearbyDrivers.observe(this) { drivers ->
        updateNearbyDriversOnMap(drivers)
    }
        
        // ДОБАВЛЕНО: Обработка системной кнопки/жеста "Назад"
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
    override fun handleOnBackPressed() {
        // 1. Найвищий пріоритет: якщо відкрито бокове меню - просто закриваємо його
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } 
        // 2. Якщо ми обираємо точку на карті
        else if (isMapPickingMode) {
            // Повертаємося до AddressPicker зі збереженням введених даних
            returnToAddressPicker()
        } 
        // 3. Якщо показана картка активного замовлення (ТУТ НАШЕ ОНОВЛЕННЯ)
        else if (activeOrderCard.visibility == View.VISIBLE) {
            if (isOrderDetailsExpanded) {
                // Якщо панель витягнута вгору (карта закрита фоном) — просто опускаємо її
                animateOrderDetailsToState(false)
            } else {
                // Якщо панель вже згорнута внизу — ховаємо замовлення як і раніше
                // (очищаємо UI та зупиняємо полінг, але НЕ скасовуємо саме замовлення)
                sessionManager.clearActiveOrderId()
                viewModel.clearOrderState()
                showAddressPanel()
            }
        } 
        // 4. Якщо ми на екрані вибору тарифів
        else if (tariffsPanel.visibility == View.VISIBLE) {
            // Повертаємося до повноекранної карти з адресами
            showAddressPanel()
        } 
        // 5. Стандартна поведінка - вихід з екрану
        else {
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
        // Когда пришли данные: выключаем шиммер и показываем список
        tariffsShimmer.stopShimmer()
        tariffsShimmer.visibility = View.GONE
        tariffsRecyclerView.visibility = View.VISIBLE
        setButtonsLoadingState(false)
        availableTariffs = tariffs
        displayTariffs() 
    }

        viewModel.cardBoundEvent.observe(this) { isBound ->
            if (isBound) {
                // 1. Закрываем браузер!
                webViewDialog?.dismiss()
                
                // 2. Автоматически переключаем метод оплаты текущего заказа на КАРТУ
                viewModel.updateActiveOrderPaymentMethod("CARD")
                currentPaymentMethod = "CARD"
                updatePaymentIcon()
                
                showToast("Картку успішно прив'язано!")
                
                // Сбрасываем ивент, чтобы он не сработал при перевороте экрана
                viewModel.resetCardBoundEvent()
            }
        }

        // 3. Активный заказ (обновление статуса)
        viewModel.activeOrder.observe(this) { order ->
            if (order != null) {
                activeOrderId = order.id
                if (order.status == "COMPLETED" || order.status == "CANCELLED") {
                    unreadChatMessages = 0
                    updateChatBadgeUI()
                } else if (order.status == "REQUESTED" || order.status == "OFFERING" || order.status == "SCHEDULED") {
                    // ФИКС: При поиске машины принудительно НЕ восстанавливаем полный маршрут на карте, оставляя радар!
                    showActiveOrderPanel(order)
                } else {
                    // Водитель принял или поездка началась (ACCEPTED, DRIVER_ARRIVED, IN_PROGRESS) — возвращаем маршрут
                    restoreOrderOnMap(order) 
                    showActiveOrderPanel(order)
                }
                updateStatusUI(order)
            }
        }

        // НОВЫЙ БЛОК: Обработка успешного предварительного заказа (на время)
        viewModel.scheduledOrderSuccess.observe(this) { order ->
            if (order != null) {
                // 1. Очищаем главный экран (чтобы при возврате назад не висела панель тарифов)
                sessionManager.clearActiveOrderId()
                viewModel.clearOrderState()
                showAddressPanel()
                
                // 2. Открываем экран "Мои поездки" (HistoryActivity)
                val intent = Intent(this@HomeActivity, HistoryActivity::class.java)
                startActivity(intent)
                
                // 3. Сбрасываем ивент (чтобы он не сработал заново при повороте экрана)
                viewModel.resetScheduledOrderEvent()
            }
        }

        // 4. Ошибки и загрузка
        viewModel.isLoading.observe(this) { loading ->
    setButtonsLoadingState(loading)
    if (loading) {
        // НОВАЯ ЛОГИКА (ИСПРАВЛЕНИЕ БАГА):
        // Проверяем: если мы прямо сейчас отправляем заказ (кнопка пишет "Обробка..."), 
        // то скелет тарифов показывать НЕ нужно, оставляем выбранный тариф на экране.
        if (btnOrderTaxi.text.toString() != "Обробка...") {
            tariffsRecyclerView.visibility = View.GONE
            tariffsShimmer.visibility = View.VISIBLE
            tariffsShimmer.startShimmer()
        }
    } else {
        // Сами данные выключат шиммер в обсервере availableTariffs
    }
}
        viewModel.errorMessage.observe(this) { msg ->
            showToast(msg)
            btnOrderTaxi.isEnabled = true
            btnOrderTaxi.text = getString(R.string.btn_order) // Сброс текста кнопки
            btnCancelOrder.isEnabled = true
            btnCancelOrder.text = "Скасувати замовлення"

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
    
    // 1. Обязательно отключаем дефолтный системный заголовок перед setContentView
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE) 
    
    dialog.setContentView(R.layout.dialog_notification_permission)
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    
    // При MATCH_PARENT убедись, что в самом XML (в корневой CardView) 
    // стоят android:layout_marginHorizontal="16dp", иначе диалог прилипнет к краям экрана!
    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    dialog.setCancelable(false)

    val btnAllow = dialog.findViewById<Button>(R.id.btn_allow)
    val btnDeny = dialog.findViewById<Button>(R.id.btn_deny)

    btnAllow.setOnClickListener {
        dialog.dismiss()
        sessionManager.setNotificationAsked(true)
        // Логика для Android 13 (Tiramisu) и выше
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // 33
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
        if (webSocketManager?.isConnected() != true) {
            Log.d("WS_TAXI_DEBUG", "📱 Приложение проснулось! Сокет мертв, пытаюсь переподключиться...")
            
            // Получаем актуальный токен
            val sessionManager = com.taxiapp.client.utils.SessionManager(this)
            val token = sessionManager.fetchAuthToken()
            
            if (!token.isNullOrEmpty()) {
                // Переподключаемся
                webSocketManager?.connect(token)
            }
        }
        val savedLanguage = sessionManager.getLanguage()
        if (currentActiveLanguage != savedLanguage) {
            currentActiveLanguage = savedLanguage
            recreate() // Пересоздаем HomeActivity с новым языком
            return // Прерываем выполнение старого onResume
        }
        updateFavoriteButtonsUI()
        updateDrawerHeader()
        updatePaymentMethodFromSession()

        if (tariffsPanel.visibility == View.VISIBLE) {
            fetchTariffsAndShowPanel()
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isDestroyed && !isFinishing) {
                fetchClientProfile()
            }
        }, 2000)
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


        statusCircle1 = findViewById(R.id.status_circle_1)
statusIcon1 = findViewById(R.id.status_icon_1)
statusLine1 = findViewById(R.id.status_line_1)

statusCircle2 = findViewById(R.id.status_circle_2)
statusIcon2 = findViewById(R.id.status_icon_2)
statusLine2 = findViewById(R.id.status_line_2)

statusCircle3 = findViewById(R.id.status_circle_3)
statusIcon3 = findViewById(R.id.status_icon_3)



        mapLoadingCurtain = findViewById(R.id.map_loading_curtain)
        contentBottomSheet = findViewById(R.id.content_bottom_sheet)
        

        tariffsShimmer = findViewById(R.id.tariffs_shimmer_container)

        btnRecenter = findViewById(R.id.btn_recenter_location)
        btnRecenterRoute = findViewById(R.id.btn_recenter_route)

        ivPaymentIcon = findViewById(R.id.iv_payment_icon)
        tvPaymentMethodText = findViewById(R.id.tv_payment_method_text) // <-- ДОБАВИТЬ ЭТО

        customToastContainer = findViewById(R.id.custom_toast_container)
        tvToastMessage = findViewById(R.id.tv_toast_message)
        ivToastIcon = findViewById(R.id.iv_toast_icon)

        profileUserName = findViewById(R.id.profile_user_name)
        profileBtnDetails = findViewById(R.id.btn_open_profile_details)

        cardWaitingTimer = findViewById(R.id.card_waiting_timer)
tvNewWaitingTimer = findViewById(R.id.tv_new_waiting_timer)


// Инициализация UI для карты
layoutMapPickerPanel = findViewById(R.id.layout_map_picker_panel)
btnBackMapPicker = findViewById(R.id.btn_back_map_picker)
tvMapPickerAddress = findViewById(R.id.tv_map_picker_address)
btnConfirmMapPicker = findViewById(R.id.btn_confirm_map_picker)

// Слушатели для новых кнопок
btnBackMapPicker.setOnClickListener {
    returnToAddressPicker() // Повертаємося до списку зі збереженням адрес
}

if (isMapPickingMode) {
    returnToAddressPicker()
}

btnConfirmMapPicker.setOnClickListener {
    confirmMapSelection()
}


        profileCityText = findViewById(R.id.profile_current_city)
        profileBtnCity = findViewById(R.id.profile_btn_change_city)

        

        tvOverlayDestDetails = findViewById(R.id.tv_overlay_dest_details)
        
        themeSwitchContainer = findViewById(R.id.theme_switch_container)
        themeSwitchThumb = findViewById(R.id.theme_switch_thumb)
        ivThemeSun = findViewById(R.id.iv_theme_sun)
        ivThemeMoon = findViewById(R.id.iv_theme_moon)
        tvThemeLabel = findViewById(R.id.tv_theme_label)



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
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val maxTariffHeight = (screenHeight * 0.45).toInt()

        tariffsPanel.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                // Если высота больше лимита и мы еще не установили жесткий размер
                if (tariffsPanel.height > maxTariffHeight && tariffsPanel.layoutParams.height != maxTariffHeight) {
                    val params = tariffsPanel.layoutParams
                    params.height = maxTariffHeight
                    tariffsPanel.layoutParams = params
                    tariffsPanel.requestLayout()
                    
                    // ВАЖНО: Возвращаем false! Это отменяет отрисовку "высокого" кадра 
                    // и полностью убирает мелькание на экране.
                    return false
                }
                return true
            }
        })
        tariffsShimmer = findViewById<ShimmerFrameLayout>(R.id.tariffs_shimmer_container)
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



        layoutExpandableDetails = findViewById(R.id.layout_expandable_details)
        handleTouchZone = findViewById(R.id.handle_touch_zone)

        var startY = 0f
        var startHeight = 0
        var lastPaddingUpdateHeight = 0

        handleTouchZone.setOnTouchListener { v, event ->
            // --- ФИНАЛЬНЫЙ ПРЕДОХРАНИТЕЛЬ ---
            val order = viewModel.activeOrder.value
            // Блокируем свайп, если заказ отменен, завершен, ИЛИ данные уже очищены (null)
            if (order == null || order.status == "CANCELLED" || order.status == "COMPLETED") {
                return@setOnTouchListener true // true означает "мы перехватили жест, но ничего не делаем"
            }
            // --------------------------------

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    v.parent.requestDisallowInterceptTouchEvent(true)

                    startHeight = if (layoutExpandableDetails.layoutParams.height <= 0) {
                        layoutExpandableDetails.height
                    } else {
                        layoutExpandableDetails.layoutParams.height
                    }

                    if (maxDetailsHeight == 0 || maxDetailsHeight < layoutExpandableDetails.height) {
                        val widthSpec = View.MeasureSpec.makeMeasureSpec(activeOrderCard.width, View.MeasureSpec.EXACTLY)
                        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                        layoutExpandableDetails.measure(widthSpec, heightSpec)
                        maxDetailsHeight = layoutExpandableDetails.measuredHeight
                    }

                    // Просто фіксуємо стартову висоту, АЛЕ НЕ РОБИМО панель відразу видимою!
                    val params = layoutExpandableDetails.layoutParams
                    params.height = startHeight
                    layoutExpandableDetails.layoutParams = params

                    lastPaddingUpdateHeight = startHeight
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val diffY = startY - event.rawY
                    var newHeight = (startHeight + diffY).toInt()

                    if (newHeight < 0) newHeight = 0
                    if (newHeight > maxDetailsHeight) newHeight = maxDetailsHeight

                    // Включаем видимость панели и нашего фона
                    if (newHeight > 0 && layoutExpandableDetails.visibility != View.VISIBLE) {
                        layoutExpandableDetails.visibility = View.VISIBLE
                        findViewById<View>(R.id.map_solid_overlay)?.visibility = View.VISIBLE
                    } else if (newHeight == 0 && layoutExpandableDetails.visibility == View.VISIBLE) {
                        layoutExpandableDetails.visibility = View.GONE
                        findViewById<View>(R.id.map_solid_overlay)?.visibility = View.GONE
                    }

                    val params = layoutExpandableDetails.layoutParams
                    params.height = newHeight
                    layoutExpandableDetails.layoutParams = params

                    // Плавно меняем прозрачность фона в зависимости от того, как высоко вытянута панель
                    if (maxDetailsHeight > 0) {
                        val progress = newHeight.toFloat() / maxDetailsHeight.toFloat()
                        findViewById<View>(R.id.map_solid_overlay)?.alpha = progress
                    }
                    
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                    
                    val currentHeight = layoutExpandableDetails.height
                    
                    // Если это просто тап (клик)
                    if (Math.abs(startY - event.rawY) < 20) {
                        v.performClick()
                        animateOrderDetailsToState(!isOrderDetailsExpanded)
                        return@setOnTouchListener true
                    }

                    // Логика доводки:
                    val movedDistance = currentHeight.toFloat() / maxDetailsHeight.toFloat()
                    
                    if (isOrderDetailsExpanded) {
                        animateOrderDetailsToState(movedDistance > 0.8f) 
                    } else {
                        animateOrderDetailsToState(movedDistance > 0.2f)
                    }
                    true
                }
                else -> false
            }
        }

        buttonsShimmer = findViewById(R.id.buttons_shimmer_container)


        layoutPaymentCompleted = findViewById(R.id.layout_payment_completed)
        tvFinalPaymentPrice = findViewById(R.id.tv_final_payment_price)
        btnUnderstandPayment = findViewById(R.id.btn_understand_payment)

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


        layoutSearchControls = findViewById(R.id.layout_search_controls)
layoutDriverFoundState = findViewById(R.id.layout_driver_found_state)
btnCancelRideDriver = findViewById(R.id.btn_cancel_ride_driver)
        
        layoutDriverDetails = findViewById(R.id.layout_driver_assigned_details)
        tvCarPlateLarge = findViewById(R.id.tv_car_plate_large)
        tvCarDetailsSubtitle = findViewById(R.id.tv_car_details_subtitle)
        tvDriverFirstName = findViewById(R.id.tv_driver_first_name)
        tvDriverExperience = findViewById(R.id.tv_driver_experience)
        tvDriverRidesCount = findViewById(R.id.tv_driver_rides_count)
        ivDriverPhoto = findViewById(R.id.iv_driver_photo)
        btnCallDriver = findViewById(R.id.btn_call_driver)
        btnDriverHealthAlert = findViewById(R.id.btn_driver_health_alert)

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

                     // ИСПРАВЛЕНИЕ: Обновляем отступ до 36dp при ручном центрировании маршрута пользователем
                     val paddingSide = convertDpToPixel(36f).toInt()

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

        findViewById<View>(R.id.map_solid_overlay)?.setOnClickListener {
            if (isOrderDetailsExpanded) {
                animateOrderDetailsToState(false)
            }
        }
        
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

        val btnChangePayment = activeOrderCard.findViewById<View>(R.id.btn_search_change_payment)
val btnChangePrice = activeOrderCard.findViewById<View>(R.id.btn_search_change_price)

// Привязываем логику
btnChangePayment.setOnClickListener {
            animateOrderDetailsToState(false) // ДОДАНО: Згортаємо деталі перед відкриттям діалогу
            showChangePaymentDialog()
        }

        btnChangePrice.setOnClickListener {
            animateOrderDetailsToState(false) // ДОДАНО: Згортаємо деталі перед відкриттям діалогу
            val order = viewModel.activeOrder.value
            if (order != null) {
                val currentPrice = order.price
                val addedValue = order.addedValue
                // Вычисляем чистую базовую цену (без надбавок ползунка)
                val basePrice = currentPrice - addedValue 
                        
                showChangePriceDialogForActiveOrder(basePrice, addedValue)
            }
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
    // ВАЖНО: Мы убрали условие if (orderComment.isNotEmpty()), 
    // чтобы иконка больше НИКОГДА не красилась в желтый цвет.

    // Получаем текущую тему устройства (светлая/темная)
    val nightModeFlags = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
    val isDarkMode = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES

    // Всегда применяем стандартный цвет (белый для темной темы, черный для светлой)
    if (isDarkMode) {
        ivCommentIcon.setColorFilter(android.graphics.Color.WHITE)
        findViewById<ImageView>(R.id.iv_services_icon)?.setColorFilter(android.graphics.Color.WHITE)
        findViewById<ImageView>(R.id.iv_price_icon)?.setColorFilter(android.graphics.Color.WHITE)
    } else {
        ivCommentIcon.setColorFilter(android.graphics.Color.BLACK)
        findViewById<ImageView>(R.id.iv_services_icon)?.setColorFilter(android.graphics.Color.BLACK)
        findViewById<ImageView>(R.id.iv_price_icon)?.setColorFilter(android.graphics.Color.BLACK)
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

    private fun toggleOrderDetails() {
        isOrderDetailsExpanded = !isOrderDetailsExpanded

        // 1. Анімація самої картки
        val autoTransition = AutoTransition()
        autoTransition.duration = 250 
        TransitionManager.beginDelayedTransition(activeOrderCard as ViewGroup, autoTransition)

        // 2. Зміна видимості деталей
        layoutExpandableDetails.visibility = if (isOrderDetailsExpanded) View.VISIBLE else View.GONE

        // 3. Плавна анімація логотипу Google (замість жорсткого Handler)
        val paddingAnimator = ValueAnimator.ofFloat(0f, 1f)
        paddingAnimator.duration = 250
        paddingAnimator.addUpdateListener {
            // Цей блок викликається на кожному мікро-кадрі анімації
            if (!isDestroyed && !isFinishing) {
                // activeOrderCard плавно змінює висоту, а updateMapPadding бере цю висоту в реальному часі!
                updateMapPadding(activeOrderCard, 0f, 20f)
            }
        }
        paddingAnimator.start()
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

        // ФИКС БАГА С ПРЫЖКОМ КАМЕРЫ:
        // Двигаем камеру к одной точке ТОЛЬКО если мы НЕ строим маршрут (одна из точек еще пустая).
        // Если есть обе точки - мы оставляем карту в покое, пока маршрут не нарисуется.
        if (originPlace == null || destinationPlace == null) {
            if (place.latLng != null) mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(place.latLng!!, 16f))
        }
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

    private fun updatePaymentMethodFromSession() {
        currentPaymentMethod = sessionManager.fetchPaymentMethod()
        val cardMask = sessionManager.getCardMask()


        // Если выбран CARD, но маски нет (например, слетела авторизация) - откатываем на CASH
        if (currentPaymentMethod == "CARD" && cardMask.isNullOrEmpty()) {
            currentPaymentMethod = "CASH"
            sessionManager.savePaymentMethod("CASH")
        }

        updatePaymentIcon()
    }

    private fun updatePaymentIcon() {
    if (!::ivPaymentIcon.isInitialized) return // Защита от краша при старте

    val mask = sessionManager.getCardMask()
    val defaultColor = ContextCompat.getColor(this, R.color.text_primary)

    // В любом случае прячем текст с маской карты
    tvPaymentMethodText.visibility = View.GONE

    if (currentPaymentMethod == "CARD" && !mask.isNullOrEmpty()) {
        // Устанавливаем иконку карты
        ivPaymentIcon.setImageResource(R.drawable.ic_card)
        ivPaymentIcon.setColorFilter(defaultColor)
    } else {
        // Устанавливаем иконку наличных
        ivPaymentIcon.setImageResource(R.drawable.ic_cash)
        ivPaymentIcon.setColorFilter(defaultColor)
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
            if (loc != null) {
                val userLatLng = LatLng(loc.latitude, loc.longitude)
                mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, 16f))
                
                // ФИКС БАГА №3: Сразу принудительно шлем координаты на сервер, 
                // чтобы машины подгрузились в сокет мгновенно, не дожидаясь остановки камеры!
                lastLocationUpdateTime = System.currentTimeMillis()
                lastLocationSent = userLatLng
                Log.d("WS_TAXI_DEBUG", "📤 МГНОВЕННАЯ ОТПРАВКА КООРДИНАТ ПРИ ВОЗВРАТЕ: lat=${userLatLng.latitude}")
                viewModel.updateClientLocation(webSocketManager, userLatLng.latitude, userLatLng.longitude)
            }
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
        
        // --- ЖЕСТКО ОТКЛЮЧАЕМ ВСЕ СИСТЕМНЫЕ КНОПКИ И ПАНЕЛИ GOOGLE MAPS ---
        mMap?.uiSettings?.apply {
            isRotateGesturesEnabled = false // Запрет вращения двумя пальцами
            isTiltGesturesEnabled = false   // Запрет наклона (3D режима) двумя пальцами
            isMapToolbarEnabled = false     // УБИРАЕТ панель при клике на маркеры
            isCompassEnabled = false          
            isZoomControlsEnabled = false     
            isMyLocationButtonEnabled = false 
            isIndoorLevelPickerEnabled = false // Убирает выбор этажей внутри ТЦ
        }

        // --- ДОБАВЛЯЕМ СЮДА: ОТКЛЮЧАЕМ 3D ЗДАНИЯ С ТЕНЯМИ И ИНДОР-ЗАТЕМНЕНИЕ ---
        mMap?.isBuildingsEnabled = false // Карта всегда будет плоской, здания не будут отбрасывать тени
        mMap?.isIndoorEnabled = false    // Карта не будет темнеть при приближении к крупным объектам (ТЦ, вокзалы)

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

        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            mMap?.isMyLocationEnabled = true
            // isMyLocationButtonEnabled = false мы уже прописали глобально в блоке выше, так надежнее
            if (currentCity != null) recenterMapOnUser()
        }

        // 1. ОПТИМИЗИРУЕМ ДВИЖЕНИЕ КАМЕРЫ (Квантование времени для стабильных 60+ FPS)
        mMap?.setOnCameraMoveListener {
            if (viewModel.currentRoutePolyline != null) {
                val currentTime = System.currentTimeMillis()
                // Выполняем тяжелую native-проекцию Google Maps не чаще чем раз в 16 мс (соответствует 60 кадрам/сек).
                // Это предотвращает удушение UI-потока на экранах 90Hz/120Hz, сохраняя идеальную плавность жеста.
                if (currentTime - lastLabelUpdateTime >= 16L) {
                    lastLabelUpdateTime = currentTime
                    updateSmartLabels()
                }
            }
        }

        // 2. ИСПРАВЛЯЕМ ПОДЛЕТ ПИНА И ВКЛЮЧАЕМ GPU HARDWARE LAYERS
        mMap?.setOnCameraMoveStartedListener { reason ->
            // При старте любого сдвига карты переводим плашки в аппаратные слои GPU.
            // Теперь перерисовка View на процессоре полностью отключается, задействуется видеопамять.
            if (viewModel.currentRoutePolyline != null) {
                if (overlayOrigin.visibility == View.VISIBLE) overlayOrigin.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                if (overlayDest.visibility == View.VISIBLE) overlayDest.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }

            if (viewModel.currentRoutePolyline == null && !isRouteMode) { 
                if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                    tvOrigin.text = "Визначення..."
                    
                    if (isInterfaceRevealed || isMapPickingMode) {
                        centerPin.animate().cancel()
                        centerPin.alpha = 1f 
                        centerPin.visibility = View.VISIBLE

                        centerPin.animate()
                            .translationY(convertDpToPixel(-48f))
                            .setStartDelay(0) 
                            .setInterpolator(AccelerateDecelerateInterpolator())
                            .setDuration(250)
                            .start()

                        try {
                            pinShadow.animate().cancel()
                            pinShadow.visibility = View.VISIBLE
                            pinShadow.animate()
                                .scaleX(0.6f)
                                .scaleY(0.6f)
                                .alpha(0.3f)
                                .setStartDelay(0) 
                                .setDuration(250)
                                .start()
                        } catch (e: Exception) {}
                    }
                }
            } else {
                if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                    btnRecenterRoute.visibility = View.VISIBLE
                }
            }
        }

        // 3. ИСПРАВЛЯЕМ ПАДЕНИЕ ПИНА И ОЧИЩАЕМ ВИДЕОПАМЯТЬ ПРИ ОСТАНОВКЕ
        mMap?.setOnCameraIdleListener {
            // Карта полностью остановилась — отключаем аппаратные слои, чтобы освободить ценную видеопамять смартфона
            if (overlayOrigin.visibility == View.VISIBLE) overlayOrigin.setLayerType(View.LAYER_TYPE_NONE, null)
            if (overlayDest.visibility == View.VISIBLE) overlayDest.setLayerType(View.LAYER_TYPE_NONE, null)

            val target = mMap!!.cameraPosition.target
            val currentTime = System.currentTimeMillis()
            val distanceMoved = if (lastLocationSent != null) {
                com.google.maps.android.SphericalUtil.computeDistanceBetween(lastLocationSent, target)
            } else {
                1000.0 
            }

            val isMainScreen = activeOrderId == null && 
                               !isRouteMode && 
                               viewModel.currentRoutePolyline == null && 
                               tariffsPanel.visibility != View.VISIBLE && 
                               !isMapPickingMode

            if (isMainScreen) {
                if (currentTime - lastLocationUpdateTime > LOCATION_UPDATE_INTERVAL || distanceMoved > 50.0) {
                    lastLocationUpdateTime = currentTime
                    lastLocationSent = target
                    Log.d("WS_TAXI_DEBUG", "📤 ОТПРАВЛЯЮ КООРДИНАТЫ НА СЕРВЕР: lat=${target.latitude}")
                    viewModel.updateClientLocation(webSocketManager, target.latitude, target.longitude)
                }
            }

            if ((isInterfaceRevealed && !isRouteMode && viewModel.currentRoutePolyline == null) || isMapPickingMode) {
                centerPin.animate().cancel()
                centerPin.alpha = 1f
                centerPin.visibility = View.VISIBLE

                centerPin.animate()
                    .translationY(convertDpToPixel(-32f))
                    .setStartDelay(0) 
                    .setInterpolator(android.view.animation.BounceInterpolator())
                    .setDuration(500)
                    .start()

                try {
                    pinShadow.animate().cancel()
                    pinShadow.visibility = View.VISIBLE
                    pinShadow.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .alpha(0.5f)
                        .setStartDelay(0)
                        .setDuration(250)
                        .start()
                } catch (e: Exception) {}
            } else if (isRouteMode || viewModel.currentRoutePolyline != null) {
                centerPin.visibility = View.GONE
                try { pinShadow.visibility = View.GONE } catch (e: Exception) {}
            }

            if (isMapPickingMode) {
                tvMapPickerAddress.text = "Шукаємо адресу..."
                getAddressFromLocation(target) { address ->
                    tvMapPickerAddress.text = address ?: "Невідома адреса"
                }
            } else {
                if (viewModel.currentRoutePolyline != null) {
                    updateSmartLabels()
                }
                if (isRouteMode || viewModel.currentRoutePolyline != null) return@setOnCameraIdleListener
                getAddressForOrigin(target)
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

        // ========================================================
        // ВАЖЛИВО: Показуємо пін ТІЛЬКИ якщо немає активного маршруту
        // ========================================================
        if (!isRouteMode && viewModel.currentRoutePolyline == null) {
            centerPin.visibility = View.VISIBLE // <-- БРОНЯ ДЛЯ УСПЕШНОГО ЗАПУСКА
            centerPin.animate()
                .alpha(1f)
                .setDuration(500)
                .setStartDelay(200)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { centerPin.alpha = 1f } 
                .start()

            // Плавно проявляем тень пина
            try {
                pinShadow.visibility = View.VISIBLE 
                pinShadow.animate()
                    .alpha(0.5f) 
                    .setDuration(500)
                    .setStartDelay(200)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { pinShadow.alpha = 0.5f } 
                    .start()
            } catch (e: Exception) {}
        } else {
            // Якщо є маршрут (замовлення) - жорстко ховаємо пін ще до того, як він почне малюватись
            centerPin.visibility = View.GONE
            centerPin.alpha = 0f
            try { 
                pinShadow.visibility = View.GONE
                pinShadow.alpha = 0f
            } catch (e: Exception) {}
        }
    }

    private fun convertDpToPixel(dp: Float): Float {
        val metrics = resources.displayMetrics
        return dp * (metrics.densityDpi / 160f)
    }

    private fun cleanAddress(fullAddress: String): String {
        return AddressUtils.formatAddress(fullAddress)
    }

    private fun updateOrderProgress(step: Int) {
    val activeColor = androidx.core.content.ContextCompat.getColor(this, R.color.text_primary)
    val inactiveColor = androidx.core.content.ContextCompat.getColor(this, R.color.divider_color)
    val bgCardColor = androidx.core.content.ContextCompat.getColor(this, R.color.card_background)

    fun setCircle(
        card: com.google.android.material.card.MaterialCardView, 
        icon: ImageView, 
        isFilled: Boolean, 
        isActiveOutline: Boolean
    ) {
        if (isFilled) {
            card.setCardBackgroundColor(activeColor)
            card.strokeWidth = 0
            icon.visibility = View.VISIBLE
        } else if (isActiveOutline) {
            card.setCardBackgroundColor(bgCardColor)
            card.strokeColor = activeColor
            card.strokeWidth = convertDpToPixel(4f).toInt()
            icon.visibility = View.GONE
        } else {
            card.setCardBackgroundColor(bgCardColor)
            card.strokeColor = inactiveColor
            card.strokeWidth = convertDpToPixel(4f).toInt()
            icon.visibility = View.GONE
        }
    }

    // Шаг 0 = Пошук (тільки контур 1-го круга)
    // Шаг 1 = Знайдений/На місці (1-й зафарбований, лінія 1 горить, 2-й контур)
    // Шаг 2 = В дорозі (1, 2 зафарбовані, обидві лінії горять, 3-й контур)
    // Шаг 3 = Завершено (все зафарбовано)

    // Круг 1
    setCircle(statusCircle1, statusIcon1, isFilled = step >= 1, isActiveOutline = step == 0)
    // Линия 1 (ведет ко второму кругу)
    statusLine1.setCardBackgroundColor(if (step >= 1) activeColor else inactiveColor)

    // Круг 2
    setCircle(statusCircle2, statusIcon2, isFilled = step >= 2, isActiveOutline = step == 1)
    // Линия 2 (ведет к третьему кругу)
    statusLine2.setCardBackgroundColor(if (step >= 2) activeColor else inactiveColor)

    // Круг 3
    setCircle(statusCircle3, statusIcon3, isFilled = step >= 3, isActiveOutline = step == 2)
}
    private fun getAddressForOrigin(latLng: LatLng) {
    Thread {
        try {
            // 1. Берем текущий язык из нашей сессии ("uk" или "en")
            val sessionManager = SessionManager(this)
            val currentLang = sessionManager.getLanguage()
            val locale = Locale(currentLang)

            // 2. Передаем эту локаль в Geocoder
            val geocoder = Geocoder(this, locale)
            
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
            
            // 3. Берем переведенный дефолтный текст
            val defaultPointText = getString(R.string.point_on_map)
            
            val addressName = if (!addresses.isNullOrEmpty()) {
                val raw = addresses[0].getAddressLine(0)
                AddressUtils.formatAddress(raw)
            } else defaultPointText
            
            Handler(Looper.getMainLooper()).post { 
                if (viewModel.currentRoutePolyline == null) {
                    tvOrigin.text = addressName
                    originPlace = Place.builder().setName(addressName).setLatLng(latLng).build()
                }
            }
        } catch (e: Exception) { 
            e.printStackTrace()
            // Если ошибка сети - тоже ставим переведенный текст
            val defaultPointText = getString(R.string.point_on_map)
            Handler(Looper.getMainLooper()).post { 
                if (viewModel.currentRoutePolyline == null) {
                    tvOrigin.text = defaultPointText 
                }
            } 
        }
    }.start()
}

// --- НОВЫЕ ФУНКЦИИ ДЛЯ РЕЖИМА ВЫБОРА НА КАРТЕ ---

private fun showMapPickerMode(isOrigin: Boolean) {
    isMapPickingMode = true
    mapPickerIsOrigin = isOrigin
    
    // МГНОВЕННЫЙ ФИКС: Принудительно стираем машинки с карты при переходе в выбор адреса
    nearbyDriverMarkers.values.forEach { it.remove() }
    nearbyDriverMarkers.clear()
    
    // Скрываем обычный интерфейс
    findViewById<View>(R.id.bottom_sheet_card).visibility = View.GONE
    findViewById<View>(R.id.btn_menu).visibility = View.GONE
    findViewById<View>(R.id.btn_open_promo).visibility = View.GONE
    findViewById<View>(R.id.btn_recenter_location).visibility = View.GONE
    
    // Показываем интерфейс выбора на карте
    layoutMapPickerPanel.visibility = View.VISIBLE
    btnBackMapPicker.visibility = View.VISIBLE
    
    tvMapPickerAddress.text = "Шукаємо адресу..."
}

private fun hideMapPickerMode() {
    isMapPickingMode = false
    
    // Возвращаем обычный интерфейс
    findViewById<View>(R.id.bottom_sheet_card).visibility = View.VISIBLE
    findViewById<View>(R.id.btn_menu).visibility = View.VISIBLE
    findViewById<View>(R.id.btn_open_promo).visibility = View.VISIBLE
    findViewById<View>(R.id.btn_recenter_location).visibility = View.VISIBLE
    
    // Скрываем интерфейс выбора на карте
    layoutMapPickerPanel.visibility = View.GONE
    btnBackMapPicker.visibility = View.GONE
}

private fun confirmMapSelection() {
    val target = mMap?.cameraPosition?.target ?: return
    val addressName = tvMapPickerAddress.text.toString()

    // Экстренная защита: если каким-то чудом клик прошел по "Шукаємо адресу"
    if (addressName == "Шукаємо адресу..." || addressName.isEmpty()) return

    val selectedPlace = com.google.android.libraries.places.api.model.Place.builder()
        .setLatLng(target)
        .setName(addressName)
        .build()

    val data = lastAddressPickerIntentData
    val isOriginOnlyMode = data?.getBooleanExtra(AddressPickerActivity.EXTRA_IS_ORIGIN, false) ?: false

    when (mapPickerTarget) {
        AddressPickerActivity.TARGET_ORIGIN -> {
            if (isOriginOnlyMode) {
                // Если мы зашли только изменить Точку А — сразу возвращаемся на главный экран
                hideMapPickerMode()
                pickerMode = MODE_ORIGIN
                handleAddressSelection(selectedPlace, addressName)
            } else {
                // Если мы на экране Откуда-Куда, меняли Точку А — возвращаемся в список для ввода Точки Б
                updateIntentDataForReturn(target, addressName)
                returnToAddressPicker()
            }
        }
        AddressPickerActivity.TARGET_DESTINATION -> {
    // 1. Проверяем, есть ли пустые зупинки в сохраненных данных от AddressPicker
    val wLats = data?.getDoubleArrayExtra(AddressPickerActivity.RESULT_WAYPOINTS_LATS)
    var hasEmptyWaypoint = false
    
    if (wLats != null) {
        for (lat in wLats) {
            if (lat == 0.0) { // Координата 0.0 означает, что поле пустое
                hasEmptyWaypoint = true
                break
            }
        }
    }

    if (hasEmptyWaypoint) {
        // 2. У нас есть пустое поле зупинки!
        // Обновляем сохраненный интент свежими координатами Точки Б, которую мы только что выбрали
        data?.putExtra(AddressPickerActivity.RESULT_NAME, addressName)
        data?.putExtra(AddressPickerActivity.RESULT_LAT, target.latitude)
        data?.putExtra(AddressPickerActivity.RESULT_LNG, target.longitude)
        
        // 3. Возвращаем пользователя обратно в экран выбора адресов (AddressPickerActivity)
        returnToAddressPicker()
    } else {
        // 4. Пустых зупинок нет. Все поля заполнены, смело строим маршрут!
        hideMapPickerMode()
        pickerMode = MODE_DESTINATION 
        handleAddressSelection(selectedPlace, addressName)
    }
}
        AddressPickerActivity.TARGET_WAYPOINT -> {
            // Если выбрали зупинку — сохраняем её и возвращаем пользователя в список
            updateIntentDataForReturn(target, addressName)
            returnToAddressPicker()
        }
    }
}

// Вспомогательная функция для обновления данных перед возвратом в список
private fun updateIntentDataForReturn(target: LatLng, addressName: String) {
    lastAddressPickerIntentData?.apply {
        when (mapPickerTarget) {
            AddressPickerActivity.TARGET_ORIGIN -> {
                putExtra(AddressPickerActivity.RESULT_ORIGIN_NAME, addressName)
                putExtra(AddressPickerActivity.RESULT_ORIGIN_LAT, target.latitude)
                putExtra(AddressPickerActivity.RESULT_ORIGIN_LNG, target.longitude)
            }
            AddressPickerActivity.TARGET_WAYPOINT -> {
                var wLats = getDoubleArrayExtra(AddressPickerActivity.RESULT_WAYPOINTS_LATS) ?: DoubleArray(0)
                var wLngs = getDoubleArrayExtra(AddressPickerActivity.RESULT_WAYPOINTS_LNGS) ?: DoubleArray(0)
                val wNames = getStringArrayListExtra(AddressPickerActivity.RESULT_WAYPOINTS_NAMES) ?: ArrayList<String>()

                if (mapPickerWaypointIndex != -1) {
                    if (mapPickerWaypointIndex >= wLats.size) {
                        val newSize = mapPickerWaypointIndex + 1
                        wLats = DoubleArray(newSize) { if (it < wLats.size) wLats[it] else 0.0 }
                        wLngs = DoubleArray(newSize) { if (it < wLngs.size) wLngs[it] else 0.0 }
                        while (wNames.size < newSize) wNames.add("")
                    }
                    wLats[mapPickerWaypointIndex] = target.latitude
                    wLngs[mapPickerWaypointIndex] = target.longitude
                    wNames[mapPickerWaypointIndex] = addressName
                }
                
                putExtra(AddressPickerActivity.RESULT_WAYPOINTS_LATS, wLats)
                putExtra(AddressPickerActivity.RESULT_WAYPOINTS_LNGS, wLngs)
                putStringArrayListExtra(AddressPickerActivity.RESULT_WAYPOINTS_NAMES, wNames)
            }
        }
    }
}

private fun returnToAddressPicker() {
    val data = lastAddressPickerIntentData
    
    // ЗАХИСТ: Якщо даних чомусь немає, просто виходимо на головний екран
    if (data == null) {
        hideMapPickerMode()
        return
    }
    
    hideMapPickerMode()

    val intent = Intent(this, AddressPickerActivity::class.java)
    
    // Передаем все накопленные данные обратно
    intent.putExtra(AddressPickerActivity.EXTRA_IS_ORIGIN, mapPickerTarget == AddressPickerActivity.TARGET_ORIGIN)
    
    // Точка А
    val oName = data.getStringExtra(AddressPickerActivity.RESULT_ORIGIN_NAME)
    intent.putExtra(AddressPickerActivity.EXTRA_CURRENT_ADDRESS, oName)
    intent.putExtra(AddressPickerActivity.EXTRA_CURRENT_LAT, data.getDoubleExtra(AddressPickerActivity.RESULT_ORIGIN_LAT, 0.0))
    intent.putExtra(AddressPickerActivity.EXTRA_CURRENT_LNG, data.getDoubleExtra(AddressPickerActivity.RESULT_ORIGIN_LNG, 0.0))
    
    // Точка Б (Prefill)
    intent.putExtra("prefill_dest_name", data.getStringExtra(AddressPickerActivity.RESULT_NAME))
    intent.putExtra("prefill_dest_lat", data.getDoubleExtra(AddressPickerActivity.RESULT_LAT, 0.0))
    intent.putExtra("prefill_dest_lng", data.getDoubleExtra(AddressPickerActivity.RESULT_LNG, 0.0))
    
    // Зупинки (Prefill)
    intent.putExtra("prefill_waypoints_lats", data.getDoubleArrayExtra(AddressPickerActivity.RESULT_WAYPOINTS_LATS))
    intent.putExtra("prefill_waypoints_lngs", data.getDoubleArrayExtra(AddressPickerActivity.RESULT_WAYPOINTS_LNGS))
    intent.putStringArrayListExtra("prefill_waypoints_names", data.getStringArrayListExtra(AddressPickerActivity.RESULT_WAYPOINTS_NAMES))
    
    addressPickerLauncher.launch(intent)
}

// Перенесенная функция геокодинга (если у тебя ее еще нет в HomeActivity)
private fun getAddressFromLocation(latLng: LatLng, callback: (String?) -> Unit) {
    Thread {
        try {
            val geocoder = android.location.Geocoder(this, java.util.Locale("uk", "UA"))
            val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
            var result: String? = null
            
            if (!addresses.isNullOrEmpty()) {
                val addressLine = addresses[0].getAddressLine(0)
                // Используем твой утилитный класс для очистки (индексы, страны)
                result = com.taxiapp.client.utils.AddressUtils.formatAddress(addressLine) 
            }
            
            runOnUiThread { callback(result) }
        } catch (e: Exception) {
            runOnUiThread { callback(null) }
        }
    }.start()
}

class RoundedBackgroundSpan(
    private val backgroundColor: Int,
    private val textColor: Int,
    private val cornerRadius: Float,
    private val horizontalPadding: Float,
    private val verticalPadding: Float // Добавили отдельный параметр для вертикали
) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        // Ширина: текст + отступы слева и справа
        return (paint.measureText(text, start, end) + 2 * horizontalPadding).roundToInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val textWidth = paint.measureText(text, start, end)
        
        // Получаем точные метрики шрифта, чтобы центрировать фон по буквам, а не по строке
        val metrics = paint.fontMetrics
        
        // Рассчитываем верх и низ прямоугольника относительно базовой линии (y)
        // ascent — это расстояние от базовой линии до верха букв (отрицательное число)
        // descent — до низа букв (положительное число)
        val rectTop = y + metrics.ascent - verticalPadding
        val rectBottom = y + metrics.descent + verticalPadding
        
        val rect = RectF(x, rectTop, x + textWidth + 2 * horizontalPadding, rectBottom)
        
        // Рисуем фон
        paint.color = backgroundColor
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

        // Рисуем текст
        paint.color = textColor
        // Центрируем текст: x + отступ, y остается базовой линией
        canvas.drawText(text!!, start, end, x + horizontalPadding, y.toFloat(), paint)
    }
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

    // Очищаем карту сразу, но маркеры пока НЕ ставим, чтобы они не мигали
    mMap?.clear() 

    // ФИКС БАГА: Мы полностью удалили предварительный animateCamera(bounds).
    // Теперь камера не будет резко "отлетать" назад к твоему местоположению.
    // Она дождется ответа от сервера и плавно отрисует маршрут через drawStylishRoute.

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
        // --- ФІКС: Очищаємо старі маркери зупинок ---
        waypointMarkers.forEach { it.remove() }
        waypointMarkers.clear()
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

    // --- ФІКС: СТВОРЮЄМО ТА ЗБЕРІГАЄМО ЗУПИНКИ ---
    val waypointIcon = BitmapHelper.vectorToBitmapDescriptor(this, R.drawable.ic_waypoint_dot)
    for (wpPair in currentWaypoints) {
        val marker = mMap?.addMarker(MarkerOptions()
            .position(wpPair.first)
            .icon(waypointIcon)
            .anchor(0.5f, 0.5f)
            .alpha(0f) // Робимо їх поки невидимими, як і інші маркери
            .zIndex(500f))
        
        if (marker != null) waypointMarkers.add(marker)
    }

    val boundsBuilder = LatLngBounds.Builder()
    if (originPlace?.latLng != null) boundsBuilder.include(originPlace!!.latLng!!)
    if (destinationPlace?.latLng != null) boundsBuilder.include(destinationPlace!!.latLng!!)
    
    // --- ФІКС: ДОДАЄМО ЗУПИНКИ У ВІДОБРАЖЕННЯ КАМЕРИ ---
    currentWaypoints.forEach { boundsBuilder.include(it.first) }
    path.forEach { boundsBuilder.include(it) }

    val visibleBottomPanel = if (activeOrderCard.visibility == View.VISIBLE) activeOrderCard else tariffsPanel

    visibleBottomPanel.post {
        try {
            val panelHeight = if (visibleBottomPanel.visibility == View.VISIBLE) visibleBottomPanel.height else 0
            
            var marginBottom = 0
            var sideMargin = 0 
            val params = visibleBottomPanel.layoutParams
            if (params is ViewGroup.MarginLayoutParams) {
                marginBottom = params.bottomMargin
                sideMargin = params.leftMargin 
            }
            
            val paddingBottom = panelHeight + marginBottom
            // ФИКС: Делаем адаптивный верхний отступ в 110dp, чтобы tv_order_route_origin и плашки всегда были в поле зрения
            val paddingTop = convertDpToPixel(110f).toInt() 
            val paddingSide = convertDpToPixel(36f).toInt() 

            mMap?.setPadding(sideMargin, paddingTop, sideMargin, paddingBottom)

            val cameraUpdate = CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), paddingSide)

            mMap?.animateCamera(cameraUpdate, 800, object : GoogleMap.CancelableCallback {
                override fun onFinish() {
                    runOnUiThread { 
                        // ПРЕДОХРАНИТЕЛЬ: рисуем только если маршрут всё ещё актуален
                        if (viewModel.currentRoutePolyline != null) {
                            startRouteRevealAnimation(colorMain, colorBorder, path) 
                        }
                    }
                }

                override fun onCancel() {
                    runOnUiThread { 
                        // ПРЕДОХРАНИТЕЛЬ: если камеру отменили (нажали Отмена), НЕ рисуем дальше
                        if (viewModel.currentRoutePolyline != null) {
                            startRouteRevealAnimation(colorMain, colorBorder, path) 
                        }
                    }
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
        if (polylineMain == null || polylineBorder == null || viewModel.currentRoutePolyline == null) return

        revealAnimator?.cancel()
        revealAnimator = ValueAnimator.ofInt(0, 255)
        revealAnimator?.duration = 1000 
        revealAnimator?.addUpdateListener { animator ->
            val alpha = animator.animatedValue as Int
            try {
                val newMainColor = Color.argb(alpha, Color.red(colorMain), Color.green(colorMain), Color.blue(colorMain))
                val newBorderColor = Color.argb(alpha, Color.red(colorBorder), Color.green(colorBorder), Color.blue(colorBorder))
                
                polylineMain?.color = newMainColor
                polylineBorder?.color = newBorderColor
            } catch (e: Exception) {}
        }

        markerRevealAnimator?.cancel()
        markerRevealAnimator = ValueAnimator.ofFloat(0f, 1f)
        markerRevealAnimator?.duration = 1000
        markerRevealAnimator?.addUpdateListener { animator ->
            val alpha = animator.animatedValue as Float
            try {
                originMarker?.alpha = alpha
                destinationMarker?.alpha = alpha
                waypointMarkers.forEach { it.alpha = alpha }
            } catch (e: Exception) {}
        }

        overlayOrigin.animate().alpha(1f).setDuration(1000).start()
        overlayDest.animate().alpha(1f).setDuration(1000).start()

        revealAnimator?.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // ГЛАВНЫЙ ПРЕДОХРАНИТЕЛЬ: Запускаем мигание, только если маршрут жив!
                if (viewModel.currentRoutePolyline != null) {
                    animateRoute(path) 
                }
            }
        })

        revealAnimator?.start()
        markerRevealAnimator?.start()
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

    private fun startSearchRadarAnimation(center: com.google.android.gms.maps.model.LatLng) {
        if (isRadarRunning) return
        isRadarRunning = true

        stopSearchRadarAnimation()

        val numCircles = 3
        val maxRadius = 100.0 // Радиус 100 метров по задаче

        // Используем встроенный taxi_yellow или fallback на системный желтый
        val strokeColorBase = androidx.core.content.ContextCompat.getColor(this, R.color.taxi_yellow)

        for (i in 0 until numCircles) {
            val circleOptions = com.google.android.gms.maps.model.CircleOptions()
                .center(center)
                .radius(0.0)
                .strokeWidth(convertDpToPixel(2f))
                .zIndex(10f)

            val circle = mMap?.addCircle(circleOptions)
            if (circle != null) {
                radarCircles.add(circle)
            }
        }

        radarAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2500
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.RESTART
            interpolator = android.view.animation.DecelerateInterpolator()

            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float

                radarCircles.forEachIndexed { index, circle ->
                    val circleProgress = (progress + (index.toFloat() / numCircles)) % 1.0f
                    circle.radius = circleProgress * maxRadius

                    val alpha = ((1.0f - circleProgress) * 255).toInt().coerceIn(0, 255)
                    
                    val strokeColorWithAlpha = android.graphics.Color.argb(
                        alpha,
                        android.graphics.Color.red(strokeColorBase),
                        android.graphics.Color.green(strokeColorBase),
                        android.graphics.Color.blue(strokeColorBase)
                    )
                    val fillColorWithAlpha = android.graphics.Color.argb(
                        (alpha * 0.15f).toInt(),
                        android.graphics.Color.red(strokeColorBase),
                        android.graphics.Color.green(strokeColorBase),
                        android.graphics.Color.blue(strokeColorBase)
                    )

                    circle.strokeColor = strokeColorWithAlpha
                    circle.fillColor = fillColorWithAlpha
                }
            }
            start()
        }
    }

    private fun stopSearchRadarAnimation() {
        isRadarRunning = false
        radarAnimator?.cancel()
        radarAnimator = null
        radarCircles.forEach { it.remove() }
        radarCircles.clear()
    }

    private fun resetOrderDetailsState() {
        // ЖЕСТКО УБИВАЕМ АНИМАЦИЮ, если она сейчас идет (это спасает от залипания фона)
        orderDetailsAnimator?.cancel()
        orderDetailsAnimator = null
        
        isOrderDetailsExpanded = false
        val overlay = findViewById<View>(R.id.map_solid_overlay)
        
        layoutExpandableDetails.visibility = View.GONE
        val params = layoutExpandableDetails.layoutParams
        params.height = 0
        layoutExpandableDetails.layoutParams = params
        
        overlay?.visibility = View.GONE
        overlay?.alpha = 0f
    }

    private fun clearMapForRoute() {
        // 1. Мгновенно останавливаем любые текущие перемещения камеры Google Maps
        mMap?.stopAnimation()

        // 2. ПЕРВЫМ ДЕЛОМ ЖЕСТКО ОСТАНАВЛИВАЕМ ВСЕ ФОНОВЫЕ АНИМАТОРЫ ЛИНИЙ И МАРКЕРОВ
        routeAnimator?.cancel()
        routeAnimator = null
        
        revealAnimator?.cancel()
        revealAnimator = null
        
        markerRevealAnimator?.cancel()
        markerRevealAnimator = null

        // 3. ТОТАЛЬНОЕ ПРЕСЕЧЕНИЕ АНИМАЦИЙ ПЛАШЕК (Убирает баг мигания адресов при отмене)
        overlayOrigin.animate().cancel()
        overlayDest.animate().cancel()
        overlayOrigin.visibility = View.GONE
        overlayDest.visibility = View.GONE
        overlayOrigin.alpha = 1f // Сбрасываем альфу к дефолту для будущих поездок
        overlayDest.alpha = 1f

        // 4. ЯВНО ВЫРЕЗАЕМ ПОЛИЛАЙНЫ ИЗ СТРУКТУРЫ КАРТЫ ДО ИХ ЗАНУЛЕНИЯ
        polylineMain?.remove()
        polylineBorder?.remove()
        polylineAnim?.remove()
        polylineMain = null
        polylineBorder = null
        polylineAnim = null

        // 5. ЯВНО УДАЛЯЕМ ФИЗИЧЕСКИЕ МАРКЕРЫ ТОЧЕК А, Б И ВСЕХ ПРОМЕЖУТОЧНЫХ ЗУПИНОК
        originMarker?.remove()
        destinationMarker?.remove()
        originMarker = null
        destinationMarker = null
        
        waypointMarkers.forEach { it.remove() }
        waypointMarkers.clear()

        // 6. Окончательно зачищаем контекст карты и сбрасываем системные отступы (Padding)
        mMap?.clear()
        mMap?.setPadding(0, 0, 0, 0)

        // 7. Безопасно возвращаем отображение синей точки пользователя (My Location слой)
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
             try {
                 mMap?.isMyLocationEnabled = true
             } catch (e: Exception) {}
        }

        // 8. Полный сброс управляющих UI-элементов главного экрана
        animHandler.removeCallbacksAndMessages(null)
        btnRecenterRoute.visibility = View.GONE

        selectedTariffItem = null
        btnOrderTaxi.isEnabled = false
        btnOrderTaxi.text = getString(R.string.btn_order)

        // 9. Обнуляем координатные массивы и сбрасываем кэш полилайна во ViewModel
        decodedRoutePoints = null
        viewModel.currentRoutePolyline = null 

        // 10. Очищаем состояние калькуляции тарифов, чтобы старая цена не «всплывала» при следующем поиске
        viewModel.loadTariffsAndCalculatePrice(null, 0)
    }
    private fun updateDrawerHeader() {
    // 1. Получаем актуальное имя из твоего хранилища (например, SessionManager)
    // Если используешь базу данных или SharedPreferences, бери данные оттуда
    val currentName = sessionManager.getUserName() // твой метод получения имени

    // 2. Находим TextView в боковом меню
    // ВАЖНО: Если у тебя стандартный NavigationView, то поиск выглядит так:
    // val headerView = binding.navigationView.getHeaderView(0)
    // val tvUserName = headerView.findViewById<TextView>(R.id.tv_user_name)
    
    // Если у тебя просто кастомная шторка (LinearLayout), то просто:
    val tvUserName = findViewById<TextView>(R.id.profile_user_name) // замени на свой ID TextView имени

    // 3. Обновляем текст
    if (!currentName.isNullOrEmpty()) {
        tvUserName?.text = currentName
    }
}

    private fun fetchTariffsAndShowPanel() {

    viewModel.stopListeningNearbyDrivers(webSocketManager)
    nearbyDriverMarkers.values.forEach { it.remove() }
    nearbyDriverMarkers.clear()
    addressPanel.visibility = View.GONE

    setButtonsLoadingState(true)
    tariffsPanel.visibility = View.VISIBLE
    
    // ВМЕСТО ProgressBar используем Shimmer
    tariffsRecyclerView.visibility = View.GONE
    tariffsShimmer.visibility = View.VISIBLE
    tariffsShimmer.startShimmer()
    
    btnRecenter.visibility = View.GONE
    setLocationButtonAnchor(R.id.tariffs_panel)

    try { btnOpenPromo.visibility = View.GONE } catch (e: Exception) {}

    tariffAdapter.submitList(emptyList(), 0)
    
    ivMenuIcon.setImageResource(R.drawable.ic_arrow_back_black)
    val adaptiveColor = ContextCompat.getColor(this, R.color.text_primary)
    ivMenuIcon.setColorFilter(adaptiveColor)

    val route = viewModel.currentRoutePolyline
    val dist = viewModel.routeInfo.value?.first ?: 0
    
    if (route != null) {
        viewModel.loadTariffsAndCalculatePrice(route, dist)
    }
}

    private fun showCustomScheduleDialog() {
    val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
    val view = layoutInflater.inflate(R.layout.dialog_schedule_ride, null)
    dialog.setContentView(view)

    dialog.window?.let { window ->
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        val controller = WindowInsetsControllerCompat(window, view)
        
        if (sessionManager.isDarkMode()) {
            window.navigationBarColor = Color.BLACK
            controller.isAppearanceLightNavigationBars = false // Светлые иконки
        } else {
            window.navigationBarColor = Color.WHITE
            controller.isAppearanceLightNavigationBars = true  // Темные иконки
        }
    }

    // ==========================================
    // 1. НАХОДИМ ВСЕ ЭЛЕМЕНТЫ ИНТЕРФЕЙСА ЗДЕСЬ
    // ==========================================
    val tabDate = view.findViewById<TextView>(R.id.tab_date)
    val tabTime = view.findViewById<TextView>(R.id.tab_time)
    val btnConfirm = view.findViewById<Button>(R.id.btn_confirm_schedule)

    // Сами барабаны (нужны для получения выбранных значений)
    val datePicker = view.findViewById<NumberPicker>(R.id.date_picker_widget)
    val timePicker = view.findViewById<TimePicker>(R.id.time_picker_widget)
    
    // Наши новые обертки (нужны только для переключения видимости экранов)
    val dateContainer = view.findViewById<FrameLayout>(R.id.date_picker_container)
    val timeContainer = view.findViewById<FrameLayout>(R.id.time_picker_container)


    // ==========================================
    // 2. НАСТРОЙКА БАРАБАНОВ И ГЕНЕРАЦИЯ ДАТ
    // ==========================================
    val availableDates = mutableListOf<Calendar>()
    val displayStrings = mutableListOf<String>()
    val dateFormat = java.text.SimpleDateFormat("dd MMM", java.util.Locale("uk", "UA"))

    for (i in 0..6) {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_MONTH, i)
        availableDates.add(cal)
        
        when (i) {
            0 -> displayStrings.add("Сьогодні, ${dateFormat.format(cal.time)}")
            1 -> displayStrings.add("Завтра, ${dateFormat.format(cal.time)}")
            else -> displayStrings.add(dateFormat.format(cal.time))
        }
    }

    // Настраиваем барабан дат
    datePicker.minValue = 0
    datePicker.maxValue = displayStrings.size - 1
    datePicker.displayedValues = displayStrings.toTypedArray()
    datePicker.wrapSelectorWheel = false 
    datePicker.descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS 

    // Настраиваем барабан времени
    timePicker.setIs24HourView(true)


    // ==========================================
    // 3. ЛОГИКА ПЕРЕКЛЮЧЕНИЯ ВКЛАДОК
    // ==========================================
    fun updateTabs(isDateSelected: Boolean) {
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

            // Прячем/показываем КОНТЕЙНЕРЫ, а не сами пикеры
            dateContainer.visibility = View.VISIBLE
            timeContainer.visibility = View.GONE
        } else {
            tabTime.setBackgroundResource(R.drawable.bg_button_primary)
            tabTime.backgroundTintList = android.content.res.ColorStateList.valueOf(colorPrimary)
            tabTime.setTextColor(colorBg)

            tabDate.background = null
            tabDate.backgroundTintList = null
            tabDate.setTextColor(colorSecondary)

            // Прячем/показываем КОНТЕЙНЕРЫ, а не сами пикеры
            dateContainer.visibility = View.GONE
            timeContainer.visibility = View.VISIBLE
        }
    }

    // Слушатели нажатий на вкладки (по умолчанию открываем вкладку даты)
    tabDate.setOnClickListener { updateTabs(true) }
    tabTime.setOnClickListener { updateTabs(false) }


    // ==========================================
    // 4. ЛОГИКА КНОПКИ ПОДТВЕРЖДЕНИЯ
    // ==========================================
    btnConfirm.setOnClickListener {
    // Берем готовый день из нашего массива
    val selectedIndex = datePicker.value
    val selectedCalendar = availableDates[selectedIndex].clone() as Calendar

    // Добавляем к нему время
    val hour = if (Build.VERSION.SDK_INT >= 23) timePicker.hour else timePicker.currentHour
    val minute = if (Build.VERSION.SDK_INT >= 23) timePicker.minute else timePicker.currentMinute

    selectedCalendar.set(Calendar.HOUR_OF_DAY, hour)
    selectedCalendar.set(Calendar.MINUTE, minute)
    selectedCalendar.set(Calendar.SECOND, 0)
    selectedCalendar.set(Calendar.MILLISECOND, 0)

    // --- НОВАЯ ЛОГИКА ПРОВЕРКИ 40 МИНУТ ---
    val minAllowedTime = Calendar.getInstance()
    minAllowedTime.add(Calendar.MINUTE, 40) // Добавляем 40 минут к текущему времени

    if (selectedCalendar.before(minAllowedTime)) {
        showToast("Попереднє замовлення можливе мінімум за 40 хвилин")
        updateTabs(false) // Автоматически перекидываем на вкладку времени для исправления
    } else {
        scheduledDate = selectedCalendar // Сохраняем выбранную дату в глобальную переменную
        updateOrderButtonWithTime()      // Обновляем UI главной кнопки
        dialog.dismiss()
    }
}

    // Запускаем диалог
    dialog.show()
}


private fun showDriverHealthDialog(issues: List<String>) {
    val dialog = Dialog(this)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setContentView(R.layout.dialog_driver_health)
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    val tvIssues = dialog.findViewById<TextView>(R.id.tv_health_issues_list)
    val btnOk = dialog.findViewById<Button>(R.id.btn_understand_health)

    // Красиво форматируем список с буллитами (точками)
    val formattedIssues = issues.joinToString(separator = "\n") { "• $it" }
    tvIssues.text = formattedIssues

    btnOk.setOnClickListener {
        dialog.dismiss()
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

        // Завжди тільки "Замовити"
        val topText = getString(R.string.btn_order)

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
        // --- ТЕПЕРЬ ЦЕНЫ НЕТ И ЗДЕСЬ ---
        // Якщо час не обрано - просто "Замовити"
        if (selectedTariffItem != null) {
            btnOrderTaxi.text = getString(R.string.btn_order)
        } else {
            btnOrderTaxi.text = "Оберіть тариф"
        }
    }
    
    // Завжди фарбуємо іконку календаря у фірмовий темний колір
btnSchedule.setColorFilter(android.graphics.Color.parseColor("#454754"))
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

    private fun openLiqPayInApp(url: String) {
        // Создаем диалог на весь экран
        webViewDialog = Dialog(this, android.R.style.Theme_Light_NoTitleBar_Fullscreen)
        val view = layoutInflater.inflate(R.layout.dialog_liqpay_webview, null)
        webViewDialog?.setContentView(view)

        val webView = view.findViewById<android.webkit.WebView>(R.id.liqpay_webview)
        val btnClose = view.findViewById<ImageView>(R.id.btn_close_webview)

        // ВАЖНО: Включаем JavaScript и DOM Storage. Без этого банковские 3DS страницы не работают!
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = android.webkit.WebViewClient() // Чтобы ссылки не выкидывали в Chrome

        webView.loadUrl(url)

        btnClose.setOnClickListener {
            webViewDialog?.dismiss()
        }

        // Если пользователь сам закрыл браузер, останавливаем проверку во ViewModel
        webViewDialog?.setOnDismissListener {
            viewModel.stopCheckingCardBinding()
        }

        webViewDialog?.show()

        // ЗАПУСКАЕМ НАШ УМНЫЙ ПОЛЛИНГ!
        viewModel.startCheckingCardBinding()
    }

    private fun stopDriverTracking() {
        isDriverTrackingActive = false
        webSocketManager?.disconnect()
        driverMarker?.remove()
        driverMarker = null
    }

    private fun animateRoute(path: List<LatLng>) {
        if (path.isEmpty() || mMap == null || viewModel.currentRoutePolyline == null) return

        val isDark = sessionManager.isDarkMode()
        val colorBase = ContextCompat.getColor(this, R.color.route_main)
        
        // Для темной темы — почти белый (240)
        // Для светлой темы — мягкий черный / графитовый (50)
        val colorGlow = if (isDark) {
            Color.rgb(240, 240, 240) 
        } else {
            Color.rgb(112, 112, 112) 
        }

        val animOpts = PolylineOptions()
            .addAll(path)
            .width(8f) 
            .color(colorBase) 
            .zIndex(2.5f) 
            .startCap(RoundCap())
            .endCap(RoundCap())
            .jointType(JointType.ROUND) 

        polylineAnim = mMap?.addPolyline(animOpts)

        // Итоговый тайминг: 750мс (вспышка) + 500мс (удержание) + 2500мс (спад) + 1750мс (пауза) = 5500 мс
        val totalDurationMs = 5500f
        val argbEvaluator = android.animation.ArgbEvaluator() 

        routeAnimator = ValueAnimator.ofFloat(0f, 1f)
        routeAnimator?.duration = totalDurationMs.toLong()
        routeAnimator?.interpolator = LinearInterpolator()
        routeAnimator?.repeatCount = ValueAnimator.INFINITE
        routeAnimator?.repeatMode = ValueAnimator.RESTART

        routeAnimator?.addUpdateListener { animator ->
            try {
                val progress = animator.animatedValue as Float
                val timeMs = progress * totalDurationMs
                
                val fraction = when {
                    timeMs <= 750f -> {
                        // Этап 1: Вспышка (0.75 сек)
                        timeMs / 750f
                    }
                    timeMs <= 1250f -> {
                        // Этап 2: Удержание (0.5 сек)
                        1f
                    }
                    timeMs <= 3750f -> {
                        // Этап 3: Затухание (2.5 сек)
                        1f - ((timeMs - 1250f) / 2500f)
                    }
                    else -> 0f // Этап 4: Пауза (1.75 сек)
                }

                val currentColor = argbEvaluator.evaluate(fraction, colorBase, colorGlow) as Int
                polylineAnim?.color = currentColor
                
            } catch (_: Exception) {}
        }
        
        routeAnimator?.start()
    }

    private fun showChangePaymentDialog() {
        // Создаем красивый BottomSheetDialog
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.dialog_change_payment, null)
        dialog.setContentView(view)

        // Настройка статус баров под тему
        dialog.window?.let { window ->
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = Color.TRANSPARENT

            val isNightMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            if (isNightMode) {
                window.navigationBarColor = androidx.core.content.ContextCompat.getColor(this, android.R.color.black)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    var flags = window.decorView.systemUiVisibility
                    flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                    flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                    window.decorView.systemUiVisibility = flags
                }
            } else {
                window.navigationBarColor = androidx.core.content.ContextCompat.getColor(this, android.R.color.white)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    var flags = window.decorView.systemUiVisibility
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    window.decorView.systemUiVisibility = flags
                }
            }
        }

        val rbCash = view.findViewById<RadioButton>(R.id.rb_cash)
        val rbCard = view.findViewById<RadioButton>(R.id.rb_card)
        val btnSave = view.findViewById<Button>(R.id.btn_save_payment)
        val btnClose = view.findViewById<ImageView>(R.id.btn_close_dialog)

        // Получаем текущий метод оплаты и маску из локального хранилища
        val currentMethod = viewModel.activeOrder.value?.paymentMethod ?: "CASH"
        val cardMask = sessionManager.getCardMask()
        

        // 1. ПОКАЗЫВАЕМ МАСКУ КАРТЫ В ДИАЛОГЕ
if (!cardMask.isNullOrEmpty()) {
    val fullText = getString(R.string.payment_card_format, cardMask)
    val spannable = SpannableString(fullText)
    
    val startIndex = fullText.indexOf(cardMask)
    if (startIndex != -1) {
    val bgColor = ContextCompat.getColor(this, R.color.menu_background_color)
    val textColor = ContextCompat.getColor(this, R.color.text_primary)
    
    spannable.setSpan(
        RoundedBackgroundSpan(
            backgroundColor = bgColor,
            textColor = textColor,
            cornerRadius = 12f,      // Радиус скругления
            horizontalPadding = 20f,  // Отступ слева и справа (сделали чуть больше)
            verticalPadding = 8f      // Отступ сверху и снизу (теперь будет одинаково!)
        ),
        startIndex,
        startIndex + cardMask.length,
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
    )
}
    
    rbCard.text = spannable
} else {
    rbCard.text = getString(R.string.payment_card)
}

        // Выставляем правильную галочку
        if (currentMethod == "CARD") {
            rbCard.isChecked = true
        } else {
            rbCash.isChecked = true
        }

        // Кнопка сохранения
        btnSave.setOnClickListener {
            val selectedMethod = if (rbCard.isChecked) "CARD" else "CASH"

            // 2. ПРЯМАЯ ИНТЕГРАЦИЯ LIQPAY
            if (selectedMethod == "CARD" && cardMask.isNullOrEmpty()) {
                btnSave.isEnabled = false
                btnSave.text = "Зачекайте..."

                val token = "Bearer ${sessionManager.fetchAuthToken()}"

                ApiClient.instance.initBindCard().enqueue(object : retrofit2.Callback<com.taxiapp.client.network.InitBindCardResponse> {
                    override fun onResponse(call: retrofit2.Call<com.taxiapp.client.network.InitBindCardResponse>, response: retrofit2.Response<com.taxiapp.client.network.InitBindCardResponse>) {
                        dialog.dismiss()
                        if (response.isSuccessful && response.body() != null) {

                            // === ИЗМЕНЕНИЕ ЗДЕСЬ ===
                            val url = response.body()!!.paymentUrl
                            openLiqPayInApp(url) // Вызываем встроенный браузер!
                            // =======================

                        } else {
                            showToast("Помилка підключення карти")
                        }
                    }

                    override fun onFailure(call: retrofit2.Call<com.taxiapp.client.network.InitBindCardResponse>, t: Throwable) {
                        dialog.dismiss()
                        showToast("Помилка мережі")
                    }
                })
                
                return@setOnClickListener
            }

            // Если карта уже есть или выбрали "Готівка", отправляем запрос на обновление в заказе
            viewModel.updateActiveOrderPaymentMethod(selectedMethod)
            
            // Обновляем UI локально
            currentPaymentMethod = selectedMethod
            updatePaymentIcon()

            dialog.dismiss()
        }

        // Кнопка закрытия
        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun fetchClientProfile() {
        val token = sessionManager.fetchAuthToken()
        if (token.isNullOrEmpty()) return

        ApiClient.instance.getClientProfile().enqueue(object : retrofit2.Callback<com.taxiapp.client.network.ClientProfileResponse> {
            override fun onResponse(
                call: retrofit2.Call<com.taxiapp.client.network.ClientProfileResponse>,
                response: retrofit2.Response<com.taxiapp.client.network.ClientProfileResponse>
            ) {
                if (response.isSuccessful && response.body() != null) {
                    val profile = response.body()!!

                    // Сохраняем новую маску (или null, если карту удалили)
                    sessionManager.saveCardMask(profile.cardMask)

                    // Если карты нет, а способ оплаты стоит CARD — сбрасываем на наличку
                    if (profile.cardMask.isNullOrEmpty() && sessionManager.fetchPaymentMethod() == "CARD") {
                        sessionManager.savePaymentMethod("CASH")

                        // Если есть активный заказ, отправляем запрос на смену оплаты на сервере
                        if (activeOrderId != null) {
                            viewModel.updateActiveOrderPaymentMethod("CASH")
                        }
                    }

                    // Обновляем иконку на главном экране
                    updatePaymentIcon()
                }
            }

            override fun onFailure(call: retrofit2.Call<com.taxiapp.client.network.ClientProfileResponse>, t: Throwable) {
                // Игнорируем ошибку сети в фоне, пользователь этого не заметит
            }
        })
    }

    private fun showChangePriceDialogForActiveOrder(basePrice: Double, currentAddedValue: Double) {
        val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.dialog_change_price, null)
        dialog.setContentView(view)

        // Настройка статус баров (оставляем твой код)
        dialog.window?.let { window ->
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = Color.TRANSPARENT 

            val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            if (isNightMode) {
                window.navigationBarColor = ContextCompat.getColor(this, android.R.color.black)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    var flags = window.decorView.systemUiVisibility
                    flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                    flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                    window.decorView.systemUiVisibility = flags
                }
            } else {
                window.navigationBarColor = ContextCompat.getColor(this, android.R.color.white)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    var flags = window.decorView.systemUiVisibility
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    window.decorView.systemUiVisibility = flags
                }
            }
        }

        val tvPrice = view.findViewById<TextView>(R.id.tv_dialog_price)
        val btnMinus = view.findViewById<View>(R.id.btn_price_minus)
        val btnPlus = view.findViewById<View>(R.id.btn_price_plus)
        val seekBar = view.findViewById<SeekBar>(R.id.seekbar_price)
        val btnSave = view.findViewById<Button>(R.id.btn_save_price)
        val btnClose = view.findViewById<View>(R.id.btn_close_dialog)

        val minPrice = basePrice.toInt()
        val maxPrice = (basePrice * 3).toInt() // Умножаем на 3, как ты и хотел!
        
        // Текущее положение ползунка — это базовая цена + текущая надбавка
        var currentPrice = minPrice + currentAddedValue.toInt()

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
            } else {
                currentPrice = maxPrice
            }
            updateUI()
        }

        btnMinus.setOnClickListener {
            if (currentPrice - 10 >= minPrice) {
                currentPrice -= 10
            } else {
                currentPrice = minPrice
            }
            updateUI()
        }

        btnSave.setOnClickListener {
            val newAddedValue = (currentPrice - minPrice).toDouble()
            viewModel.updateActiveOrderPrice(newAddedValue)
            dialog.dismiss()
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
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
    val startPosition = marker.position
    val startRotation = marker.rotation

    val valueAnimator = ValueAnimator.ofFloat(0f, 1f)
    valueAnimator.duration = 2000 
    valueAnimator.interpolator = LinearInterpolator()
    valueAnimator.addUpdateListener { animation ->
        val v = animation.animatedFraction
        val lng = v * toPosition.longitude + (1 - v) * startPosition.longitude
        val lat = v * toPosition.latitude + (1 - v) * startPosition.latitude
        marker.position = LatLng(lat, lng)
        
        var delta = toRotation - startRotation
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360
        marker.rotation = startRotation + (v * delta)
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
        centerPin.animate().cancel() // Зупиняємо будь-які можливі анімації піна
        try { 
            pinShadow.visibility = View.GONE 
            pinShadow.animate().cancel()
        } catch (e: Exception) {}

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

        // ========================================================
        // ВАЖЛИВО: Очищаємо і ВІДНОВЛЮЄМО масив зупинок з сервера
        // ========================================================
        currentWaypoints.clear()
        if (!order.stops.isNullOrEmpty()) {
            order.stops.forEach { stop ->
                currentWaypoints.add(Pair(LatLng(stop.lat, stop.lng), stop.address))
            }
        }

        val polyline = order.googleRoutePolyline
        if (!polyline.isNullOrEmpty()) {
            viewModel.currentRoutePolyline = polyline 
            val points = com.google.maps.android.PolyUtil.decode(polyline)
            decodedRoutePoints = points
            
            // Гарантуємо правильні відступи перед відмальовуванням
            creationPanelCard.visibility = View.GONE
            addressPanel.visibility = View.GONE
            
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
        var isRouteGoingRight = false
        var deltaLng = 0.0

        val baseLatLng = if (isStartPoint) originPlace?.latLng else destinationPlace?.latLng

        if (baseLatLng != null && decodedRoutePoints != null && decodedRoutePoints!!.isNotEmpty()) {
            val routePoints = decodedRoutePoints!!
            
            val compareLatLng = if (isStartPoint) {
                if (routePoints.size > 1) routePoints[1] else routePoints[0]
            } else {
                if (routePoints.size > 1) routePoints[routePoints.size - 2] else routePoints[0]
            }

            // Вертикальный вектор
            if (compareLatLng.latitude > baseLatLng.latitude) {
                isRouteGoingUp = true
            }
            // Горизонтальный вектор
            if (compareLatLng.longitude > baseLatLng.longitude) {
                isRouteGoingRight = true
            }
            // Сила отклонения маршрута по горизонтали
            deltaLng = Math.abs(compareLatLng.longitude - baseLatLng.longitude)
        }

        val verticalPadding = convertDpToPixel(8f)
        val horizontalPadding = convertDpToPixel(12f)
        
        // НОВЫЙ КОМПОНЕНТ: Воздушный зазор радиуса маркера (16dp).
        // Гарантирует, что плашка не наедет на саму текстуру значка А или Б.
        val markerRadiusOffset = convertDpToPixel(4f)

        // 1. Рассчитываем базовую идеальную позицию по Y с учетом радиуса маркера
        var finalY = if (isRouteGoingUp) {
            targetY + markerRadiusOffset + verticalPadding // Маршрут идет вверх -> плашку уводим вниз за пределы пина
        } else {
            targetY - markerRadiusOffset - viewHeight - verticalPadding // Маршрут идет вниз -> плашку уводим вверх за пределы пина
        }

        // По умолчанию центрируем плашку горизонтально над маркером
        var finalX = targetX - (viewWidth / 2)

        val topSafeArea = convertDpToPixel(50f) 
        val bottomSafeArea = resources.displayMetrics.heightPixels - convertDpToPixel(150f) 
        val margin = convertDpToPixel(16f)

        // Маркер жесткого конфликта: зажало краем экрана и плашка вынуждена лечь на маршрут
        var isForcedToOverlapRoute = false

        // Проверяем сжатие по границам SafeArea экрана (учитываем маркерный сдвиг)
        if (finalY < topSafeArea) {
            finalY = targetY + markerRadiusOffset + verticalPadding
            if (!isRouteGoingUp) {
                isForcedToOverlapRoute = true // Конфликт: маршрут уходит вниз, и плашка прижата вниз дисплея
            }
        } else if (finalY + viewHeight > bottomSafeArea) {
            finalY = targetY - markerRadiusOffset - viewHeight - verticalPadding
            if (isRouteGoingUp) {
                isForcedToOverlapRoute = true // Конфликт: маршрут уходит вверх, и плашка прижата вверх дисплея
            }
        }

        // 2. ИНТЕЛЛЕКТУАЛЬНЫЙ ДИАГОНАЛЬНЫЙ ОБХОД ЛИНИИ МАРШРУТА
        if (isForcedToOverlapRoute) {
            // Если маршрут имеет явный наклон в сторону, уходим в противоположный бок
            if (deltaLng > 0.0001) { 
                if (isRouteGoingRight) {
                    // Линия уходит вправо -> плашку изящно смещаем влево от пина + учитываем радиус пина!
                    finalX = targetX - viewWidth - horizontalPadding - markerRadiusOffset
                } else {
                    // Линия уходит влево -> плашку изящно смещаем вправо от пина + учитываем радиус пина!
                    finalX = targetX + horizontalPadding + markerRadiusOffset
                }
            } else {
                // Если маршрут идет строго вертикально, выбираем сторону на основе положения пина на экране
                if (targetX > screenWidth / 2) {
                    finalX = targetX - viewWidth - horizontalPadding - markerRadiusOffset
                } else {
                    finalX = targetX + horizontalPadding + markerRadiusOffset
                }
            }

            // Магия диагонали: корректируем высоту Y с зазором, чтобы плашка встала 
            // под красивым наклоном «обтекая» маркер сбоку, а не перекрывала его.
            if (finalY < targetY) {
                // Находимся чуть выше уровня маркера, но смещены вбок
                finalY = targetY - markerRadiusOffset - (viewHeight * 0.3f)
            } else {
                // Находимся чуть ниже уровня маркера, но смещены вбок
                finalY = targetY + markerRadiusOffset - (viewHeight * 0.7f)
            }
        }

        // 3. Финальный жесткий клэмп-предохранитель от вылета элементов за физический дисплей
        if (finalX < margin) {
            finalX = margin
        }
        if (finalX + viewWidth > screenWidth - margin) {
            finalX = screenWidth - margin - viewWidth
        }

        if (finalY < topSafeArea) {
            finalY = topSafeArea
        }
        if (finalY + viewHeight > bottomSafeArea) {
            finalY = bottomSafeArea - viewHeight
        }

        // Применяем через высокопроизводительную GPU-трансляцию
        view.translationX = finalX
        view.translationY = finalY
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
    stopWaitingTimer()

    if (order.arrivedAt == null) {
        cardWaitingTimer.visibility = View.GONE
        return
    }

    cardWaitingTimer.visibility = View.VISIBLE

    val cleanArrivedAt = order.arrivedAt.substringBefore(".").substringBefore("Z")
    val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
    
    val arrivedTime = try {
        format.parse(cleanArrivedAt)?.time ?: return
    } catch (e: Exception) {
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
                // 1. БЕЗКОШТОВНЕ ОЧІКУВАННЯ (Зворотний відлік)
                val remainingMs = (freeMins * 60 * 1000) - diffMs
                val remMin = (remainingMs / (1000 * 60)).toInt()
                val remSec = ((remainingMs / 1000) % 60).toInt()
                
                tvNewWaitingTimer.text = String.format("%02d:%02d", remMin, remSec)
                tvNewWaitingTimer.setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.text_secondary))
            } else {
                // 2. ПЛАТНЕ ОЧІКУВАННЯ (Відлік вперед: +00:01, +00:02...)
                val paidMs = diffMs - (freeMins * 60 * 1000) // Час, що пройшов понад безкоштовний
                val paidMin = (paidMs / (1000 * 60)).toInt()
                val paidSec = ((paidMs / 1000) % 60).toInt()
                
                tvNewWaitingTimer.text = String.format("+%02d:%02d", paidMin, paidSec)
                // ИЗМЕНЕНО: Берем цвет taxi_red_cancel из ресурсов
                tvNewWaitingTimer.setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.taxi_red_cancel))
            }
            waitingTimerHandler.postDelayed(this, 1000)
        }
    }
    waitingTimerHandler.post(waitingTimerRunnable!!)
}

private fun stopWaitingTimer() {
    waitingTimerRunnable?.let { waitingTimerHandler.removeCallbacks(it) }
    waitingTimerRunnable = null
    cardWaitingTimer.visibility = View.GONE // Скрываем табло
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
    
    // Умная настройка системных баров в зависимости от темы
    dialog.window?.let { window ->
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT 

        // Проверяем, включена ли сейчас темная тема на устройстве
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        if (isNightMode) {
            // ДЛЯ ТЕМНОЙ ТЕМЫ: Тотально черный бар и светлые иконки
            window.navigationBarColor = ContextCompat.getColor(this, android.R.color.black)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                var flags = window.decorView.systemUiVisibility
                // Убираем флаги "светлого" фона, чтобы иконки стали белыми
                flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                window.decorView.systemUiVisibility = flags
            }
        } else {
            // ДЛЯ СВЕТЛОЙ ТЕМЫ: Белый бар и темные иконки
            window.navigationBarColor = ContextCompat.getColor(this, android.R.color.white)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                var flags = window.decorView.systemUiVisibility
                // Добавляем флаги "светлого" фона, чтобы иконки стали черными/серыми
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                window.decorView.systemUiVisibility = flags
            }
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

            // 1. УДАЛИЛИ: servicesExtraCost = newAddedValue
            // Теперь чаевые не затирают стоимость дополнительных услуг!

            // 2. Отправляем новую цену в адаптер для визуальной отрисовки
            tariffAdapter.setCustomPrice(selectedItem.tariff.id, newAddedValue)

            // 3. ФИКС ДЛЯ СЕРВЕРА: Принудительно обновляем выбранный тариф в памяти Activity,
            // чтобы при нажатии "Замовити" на сервер ушел правильный addedValue.
            selectedTariffItem = selectedTariffItem?.copy(addedValue = newAddedValue) ?: selectedItem.copy(addedValue = newAddedValue)

            dialog.dismiss()
        }

    btnClose.setOnClickListener { dialog.dismiss() }
    dialog.show()
}
    
    private fun cancelCurrentOrder() {
        val token = sessionManager.fetchAuthToken() ?: return

        ApiClient.instance.getCancellationReasons("CLIENT").enqueue(object : Callback<List<CancellationReasonDto>> {
            override fun onResponse(call: Call<List<CancellationReasonDto>>, response: Response<List<CancellationReasonDto>>) {
                if (response.isSuccessful) {
                    val reasons = response.body()?.filter { it.isActive } ?: emptyList()

                    if (reasons.isNotEmpty()) {
                        // Викликаємо наш новий красивий кастомний діалог!
                        showCustomCancelDialog(reasons)
                    } else {
                        // Якщо список порожній — скасовуємо без причини
                        viewModel.cancelOrder(null)
                    }
                } else {
                    android.util.Log.e("CancelOrder", "Failed to load reasons: ${response.code()}")
                    viewModel.cancelOrder(null)
                }
            }
            override fun onFailure(call: Call<List<CancellationReasonDto>>, t: Throwable) {
                android.util.Log.e("CancelOrder", "Network error", t)
                viewModel.cancelOrder(null)
            }
        })
    }

    private fun animateOrderDetailsToState(expand: Boolean) {
        val overlay = findViewById<View>(R.id.map_solid_overlay)
        if (maxDetailsHeight == 0) {
            val widthSpec = View.MeasureSpec.makeMeasureSpec(activeOrderCard.width, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            layoutExpandableDetails.measure(widthSpec, heightSpec)
            maxDetailsHeight = layoutExpandableDetails.measuredHeight
        }

        val startHeight = layoutExpandableDetails.layoutParams.height
        val targetHeight = if (expand) maxDetailsHeight else 0

        isOrderDetailsExpanded = expand

        if (expand) {
            layoutExpandableDetails.visibility = View.VISIBLE
            overlay?.visibility = View.VISIBLE
        }

        // --- ИСПРАВЛЕНИЕ: Отменяем предыдущую анимацию, чтобы они не конфликтовали ---
        orderDetailsAnimator?.cancel()

        val animator = android.animation.ValueAnimator.ofInt(startHeight, targetHeight)
        orderDetailsAnimator = animator // Сохраняем ссылку на текущую анимацию
        
        animator.duration = 250
        animator.addUpdateListener { animation ->
            val h = animation.animatedValue as Int
            val params = layoutExpandableDetails.layoutParams
            params.height = h
            layoutExpandableDetails.layoutParams = params

            if (maxDetailsHeight > 0) {
                overlay?.alpha = h.toFloat() / maxDetailsHeight.toFloat()
            }
        }

        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (!expand) {
                    layoutExpandableDetails.visibility = View.GONE
                    overlay?.visibility = View.GONE
                }
            }
        })
        animator.start()
    }

    // НОВИЙ МЕТОД: Малює красивий діалог
    private fun showCustomCancelDialog(reasons: List<CancellationReasonDto>) {
    val dialog = Dialog(this)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setContentView(R.layout.dialog_cancel_reason)
    
    // Робимо фон вікна прозорим, щоб спрацювали закруглення від CardView
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    val rgReasons = dialog.findViewById<android.widget.RadioGroup>(R.id.rg_cancel_reasons)
    val btnConfirm = dialog.findViewById<android.widget.Button>(R.id.btn_confirm_cancel)

    // --- НАЛАШТУВАННЯ КОЛЬОРУ ДЛЯ КРУЖЕЧКІВ ---
    val yellowColor = androidx.core.content.ContextCompat.getColor(this, R.color.taxi_yellow)
    val colorStateList = android.content.res.ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_checked), // Стан: не обрано
            intArrayOf(android.R.attr.state_checked)    // Стан: обрано
        ),
        intArrayOf(
            android.graphics.Color.GRAY, // Сірий контур для неактивного стану
            yellowColor                  // Ваш жовтий для активного стану
        )
    )

    // Динамічно створюємо кнопки (RadioButton) для кожної причини
    for ((index, reason) in reasons.withIndex()) {
        val rb = android.widget.RadioButton(this)
        rb.id = index // Встановлюємо ID як індекс у списку
        rb.text = reason.reasonText
        rb.textSize = 16f
        rb.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_primary))
        
        // ЗАСТОСОВУЄМО КОЛІР ТІНТУ
        rb.buttonTintList = colorStateList
        
        // Додаємо відступи між варіантами для краси
        val params = android.widget.RadioGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 12, 0, 12)
        rb.layoutParams = params
        rb.setPadding(16, 0, 0, 0) // Відступ тексту від кружечка

        rgReasons.addView(rb)
    }

    // За замовчуванням обираємо перший пункт (щоб кнопка підтвердити не відправляла null)
    if (reasons.isNotEmpty()) {
        rgReasons.check(0)
    }

    // Обробка натискання на кнопку "Підтвердити"
    btnConfirm.setOnClickListener {
        // ДОДАНО: Миттєво згортаємо панель і прибираємо фон перед відправкою запиту!
        animateOrderDetailsToState(false)

        val checkedId = rgReasons.checkedRadioButtonId
        if (checkedId != -1) {
            // Оскільки ID дорівнює індексу масиву, легко дістаємо текст
            val selectedReason = reasons[checkedId].reasonText
            viewModel.cancelOrder(selectedReason)
        } else {
            viewModel.cancelOrder(null)
        }
        dialog.dismiss() // Закриваємо діалог
    }

    dialog.show()
}

    private fun showActiveOrderPanel(order: TaxiOrderDto) {
    viewModel.stopListeningNearbyDrivers(webSocketManager)
    nearbyDriverMarkers.values.forEach { it.remove() }
    nearbyDriverMarkers.clear()
    // 1. Проверяем, была ли панель скрыта до этого
    val isFirstShow = activeOrderCard.visibility != View.VISIBLE || ivMenuIcon.tag != "order_mode"

    findViewById<TextView>(R.id.tv_order_route_origin).text = cleanAddress(order.fromAddress ?: "А")
    findViewById<TextView>(R.id.tv_order_route_dest).text = cleanAddress(order.toAddress ?: "Б")
    
    activeOrderCard.visibility = View.VISIBLE
    tariffsPanel.visibility = View.GONE
    
    // Вирішення 5: Приховуємо панель вводу А і Б, щоб не було накладання
    creationPanelCard.visibility = View.GONE
    addressPanel.visibility = View.GONE

    // Вирішення 3: Приховуємо кнопку "Моє місцезнаходження"
    btnRecenter.visibility = View.GONE  

    ivMenuIcon.tag = "order_mode"
    if (isFirstShow) {
        setLocationButtonAnchor(R.id.active_order_card)
        updateMapPadding(activeOrderCard, 0f, 20f)
    }

    // Вирішення 4: Робимо кнопку меню видимою і перетворюємо её на кнопку "Назад"
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
    
    tvActiveOrderPrice.text = String.format("%.0f ₴", order.price)
    val tvPaymentText = findViewById<TextView>(R.id.tv_active_order_payment_text)
    if (order.paymentMethod == "CARD") {
        ivActiveOrderPayment.setImageResource(R.drawable.ic_card)
        tvPaymentText.text = "Картка"
    } else {
        ivActiveOrderPayment.setImageResource(R.drawable.ic_cash)
        tvPaymentText.text = "Готівка"
    }

    // ==========================================
    // НАЧАЛО ОБНОВЛЕННОГО БЛОКА: 2. Маршрут
    // ==========================================
    
    // Находим главные TextView для адресов А и Б
    findViewById<TextView>(R.id.tv_order_route_origin).text = cleanAddress(order.fromAddress ?: "А")
    findViewById<TextView>(R.id.tv_order_route_dest).text = cleanAddress(order.toAddress ?: "Б")

    // Получаем контейнер как LinearLayout, чтобы динамически управлять вложенными элементами
    val llRouteWaypoints = findViewById<LinearLayout>(R.id.ll_route_waypoints)
    
    // Обязательно очищаем контейнер от старых точек (чтобы адреса не дублировались при обновлении статуса заказа)
    llRouteWaypoints.removeAllViews()

    // Проверяем, пришел ли от сервера массив дополнительных остановок
    if (!order.stops.isNullOrEmpty()) {
        llRouteWaypoints.visibility = View.VISIBLE
        
        // Проходимся по каждой промежуточной точке маршрута
        order.stops.forEach { stop ->
            // Инфлейтим (надуваем) кастомный XML-шаблон для одной точки
            val stopView = layoutInflater.inflate(R.layout.item_active_order_waypoint, llRouteWaypoints, false)
            
            // Находим TextView внутри шаблона и сетим туда чистый адрес без обрезаний
            val tvAddress = stopView.findViewById<TextView>(R.id.tv_waypoint_address)
            tvAddress.text = cleanAddress(stop.address)
            
            // Добавляем готовую строчку с адресом в наш вертикальный контейнер
            llRouteWaypoints.addView(stopView)
        }
    } else {
        // Если дополнительных остановок в заказе нет, полностью скрываем контейнер
        llRouteWaypoints.visibility = View.GONE
    }
    
    // ==========================================
    // КОНЕЦ ОБНОВЛЕННОГО БЛОКА
    // ==========================================

    // 3. Тариф и Иконка (Загрузка из диспетчерской)
    val expandableDetails = findViewById<View>(R.id.layout_expandable_details)
    val ivOrderTariffIcon = expandableDetails.findViewById<ImageView>(R.id.iv_order_tariff_icon)
    val tvOrderTariffName = expandableDetails.findViewById<TextView>(R.id.tv_order_tariff_name)

    tvOrderTariffName.text = order.tariffName

    val matchingTariff = availableTariffs.find { it.name == order.tariffName }
    val rawUrl = matchingTariff?.imageUrl

    // Очищаем фильтры, чтобы Glide нарисовал цветную иконку, а не серую тень
    ivOrderTariffIcon.imageTintList = null
    ivOrderTariffIcon.clearColorFilter()

    if (!rawUrl.isNullOrEmpty()) {
        val SERVER_IP = "192.168.0.107" // Правильный IP сервера!
        val SERVER_PORT = "8080"

        val fullUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else {
            var cleanPath = rawUrl.replace("\\", "/")
                .replace(Regex("/{2,}"), "/")
                .trimStart('/')

            if (!cleanPath.startsWith("uploads/")) {
                cleanPath = "uploads/$cleanPath"
            }
            "http://$SERVER_IP:$SERVER_PORT/$cleanPath"
        }

        Glide.with(this)
            .load(fullUrl)
            .placeholder(R.drawable.ic_taxi_model_standard)
            .error(R.drawable.ic_taxi_model_standard)
            .into(ivOrderTariffIcon)
    } else {
        ivOrderTariffIcon.setImageResource(R.drawable.ic_taxi_model_standard)
    }

    // Доп. услуги
    if (order.services.isNotEmpty()) {
        tvOrderServices.visibility = View.VISIBLE
        tvOrderServices.text = "+ " + order.services.joinToString(", ") { it.name }
    } else {
        tvOrderServices.visibility = View.GONE
    }

    // Комментарий
    val tvCommentTitle = findViewById<View>(R.id.tv_comment_title)
    val layoutOrderCommentCard = findViewById<View>(R.id.layout_order_comment_card)
    val tvOrderCommentText = findViewById<TextView>(R.id.tv_order_comment)

    if (!order.comment.isNullOrBlank()) {
        tvCommentTitle.visibility = View.VISIBLE
        layoutOrderCommentCard.visibility = View.VISIBLE
        tvOrderCommentText.text = order.comment 
    } else {
        tvCommentTitle.visibility = View.GONE
        layoutOrderCommentCard.visibility = View.GONE
    }

    updateStatusUI(order)
}
    
    private fun checkOrderStatus() {
        // Оставлен как заглушка, если вдруг где-то вызывается,
        // но основная логика теперь в ViewModel
    }

    private fun setButtonsLoadingState(isLoading: Boolean) {
        // Проверяем, вызвана ли загрузка именно нажатием на кнопку "Замовити"
        val isOrdering = btnOrderTaxi.text.toString() == "Обробка..."

        // 1. Управляем мерцанием самих кнопок
        // Включаем шиммер ТОЛЬКО если это обычная загрузка (не отправка заказа)
        if (isLoading && !isOrdering) {
            buttonsShimmer.showShimmer(true) // Возвращаем маску, если она была скрыта
            buttonsShimmer.startShimmer()    // Запускаем анимацию
        } else {
            buttonsShimmer.stopShimmer()     // Останавливаем анимацию
            buttonsShimmer.hideShimmer()     // Полностью удаляем градиент!
        }

        // 2. Прячем или показываем иконки
        // Прячем иконки ТОЛЬКО если это обычная загрузка
        val iconVisibility = if (isLoading && !isOrdering) View.INVISIBLE else View.VISIBLE
        
        findViewById<View>(R.id.iv_comment_icon).visibility = iconVisibility
        findViewById<View>(R.id.iv_payment_icon).visibility = iconVisibility
        findViewById<View>(R.id.iv_services_icon).visibility = iconVisibility
        findViewById<View>(R.id.iv_price_icon).visibility = iconVisibility

        // 3. БРОНЕБОЙНАЯ логика для текста оплаты
        val paymentText = findViewById<TextView>(R.id.tv_payment_method_text)
        if (isLoading && !isOrdering) {
            // Если текст сейчас видим, прячем и запоминаем
            if (paymentText.visibility == View.VISIBLE) {
                paymentText.tag = "was_visible"
                paymentText.visibility = View.INVISIBLE
            }
        } else {
            // Загрузка закончилась (ИЛИ мы сейчас отправляем заказ - тогда текст не трогаем)
            val hasActualText = paymentText.text?.toString()?.isNotBlank() == true
            
            if (paymentText.tag == "was_visible" || hasActualText) {
                paymentText.visibility = View.VISIBLE
                paymentText.tag = null // очищаем память
            } else {
                paymentText.visibility = View.GONE
            }
        }
        
        // 4. Отключаем кликабельность
        // ВАЖНО: А вот блокировать кнопки от нажатий мы должны ВСЕГДА, пока идет любая загрузка (isLoading),
        // чтобы пользователь не начал менять цену или писать комментарий во время отправки заказа на сервер.
        findViewById<View>(R.id.btn_open_comment).isEnabled = !isLoading
        findViewById<View>(R.id.btn_open_payment).isEnabled = !isLoading
        findViewById<View>(R.id.btn_open_services).isEnabled = !isLoading
        findViewById<View>(R.id.btn_change_price).isEnabled = !isLoading
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
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE) 
    dialog.setContentView(R.layout.dialog_rate_driver)
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    dialog.setCancelable(false)

    val etComment = dialog.findViewById<EditText>(R.id.et_comment)
    val btnSubmit = dialog.findViewById<Button>(R.id.btn_submit_rating)
    
    // TextView для заголовка більше не чіпаємо, він автоматично покаже "Оцініть поїздку" з XML

    val stars = listOf(
        dialog.findViewById<ImageView>(R.id.star_1),
        dialog.findViewById<ImageView>(R.id.star_2),
        dialog.findViewById<ImageView>(R.id.star_3),
        dialog.findViewById<ImageView>(R.id.star_4),
        dialog.findViewById<ImageView>(R.id.star_5)
    )

    var currentRating = 0 

    // Дістаємо твій колір taxi_yellow з ресурсів
    val activeStarColor = ContextCompat.getColor(this, R.color.taxi_yellow)
    val inactiveStarColor = Color.parseColor("#808080") // Залишаємо сірий для неактивних

    stars.forEachIndexed { index, imageView ->
        imageView.setOnClickListener {
            currentRating = index + 1 
            
            stars.forEachIndexed { i, star ->
                if (i < currentRating) {
                    star.setColorFilter(activeStarColor) 
                } else {
                    star.setColorFilter(inactiveStarColor) 
                }
            }
        }
    }

    btnSubmit.setOnClickListener {
        if (currentRating == 0) {
            showToast("Будь ласка, поставте оцінку")
            return@setOnClickListener
        }

        btnSubmit.isEnabled = false
        btnSubmit.text = "Відправка..."

        val comment = etComment.text.toString()
        sendRating(orderId, currentRating, comment, dialog)
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

        ApiClient.instance.rateDriver(request).enqueue(object : Callback<MessageResponse> {
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
        // 1. НАША НОВАЯ ПЛАВНАЯ АНИМАЦИЯ
        (activeOrderCard as? ViewGroup)?.let { cardGroup ->
            val transition = android.transition.AutoTransition().apply {
                duration = 300 
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            }
            android.transition.TransitionManager.beginDelayedTransition(cardGroup, transition)
        }

        orderStatusText.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_primary))
        
        btnCancelOrder.isEnabled = true
        btnCancelOrder.text = getString(R.string.btn_cancel_order)

        // 2. БЛОК ЗАПЛАНИРОВАННОГО ВРЕМЕНИ (ОСТАВЬ ТОЛЬКО ОДИН ЭКЗЕМПЛЯР ЭТОГО КОДА!)
        val tvScheduledTime = findViewById<TextView>(R.id.tv_scheduled_time)
        if (!order.scheduledAt.isNullOrEmpty()) {
            tvScheduledTime?.visibility = View.VISIBLE

            val timeStr = try {
                if (order.scheduledAt.contains("T")) {
                    order.scheduledAt.substringAfter("T").take(5)
                } else {
                    order.scheduledAt
                }
            } catch (e: Exception) {
                order.scheduledAt
            }
            tvScheduledTime?.text = "Орієнтовний час подачі $timeStr"
        } else {
            tvScheduledTime?.visibility = View.GONE
        }

        // 3. ДАЛЬШЕ СРАЗУ ИДЕТ ПРОВЕРКА СТАТУСОВ
        when(order.status) {
            "SCHEDULED" -> {
                updateOrderProgress(0)
                orderStatusText.text = "Заплановано"
                stopStatusBlinking()
                
                layoutSearchControls.visibility = View.VISIBLE
                layoutDriverFoundState.visibility = View.GONE
                
                layoutSearchDetails.visibility = View.VISIBLE
                findViewById<TextView>(R.id.tv_order_tariff_title)?.visibility = View.VISIBLE
                layoutDriverDetails.visibility = View.GONE
                layoutPaymentCompleted.visibility = View.GONE 
                
                btnCancelOrder.visibility = View.VISIBLE
                btnCancelRideDriver.visibility = View.GONE

                // Показываем адреса для запланированных заказов
                findViewById<View>(R.id.tv_order_route_origin)?.parent?.let { (it as? View)?.visibility = View.VISIBLE }

                stopDriverTracking()
                stopWaitingTimer()
                
                updateMapPadding(activeOrderCard, 0f, 20f)
            }

            "REQUESTED", "OFFERING" -> {
                updateOrderProgress(0)
                orderStatusText.text = getString(R.string.status_searching_driver)
                startStatusBlinking()

                // 1. Жестко запрещаем пользователю двигать и зумить карту во время поиска
                mMap?.uiSettings?.isScrollGesturesEnabled = false
                mMap?.uiSettings?.isZoomGesturesEnabled = false

                // 2. Глушим старые анимации маршрута, чтобы они не мигали
                routeAnimator?.cancel()
                routeAnimator = null
                revealAnimator?.cancel()
                revealAnimator = null
                markerRevealAnimator?.cancel()
                markerRevealAnimator = null
                polylineMain?.remove()
                polylineBorder?.remove()
                polylineAnim?.remove()
                polylineMain = null
                polylineBorder = null
                polylineAnim = null

                mMap?.clear() // Чистим карту под чистый радар

                // Скрываем элементы ввода адреса и лишние кнопки на главном экране
                addressPanel.visibility = View.GONE
                btnRecenter.visibility = View.GONE 

                // 3. ТОЧЕЧНЫЙ ФИКС: Скрываем весь родительский LinearLayout адресов маршрута при поиске
                findViewById<View>(R.id.tv_order_route_origin)?.parent?.let { (it as? View)?.visibility = View.GONE }

                // 4. Возвращаем кнопку "Назад" (стрелочку отмены) на экран поиска
                btnMenu.visibility = View.VISIBLE
                ivMenuIcon.setImageResource(R.drawable.ic_arrow_back_black)
                ivMenuIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_primary))

                // Управляем видимостью внутренних слоев карточки активного заказа
                activeOrderCard.visibility = View.VISIBLE
                layoutSearchDetails.visibility = View.VISIBLE 
                layoutSearchControls.visibility = View.VISIBLE 
                layoutDriverDetails.visibility = View.GONE 
                layoutDriverFoundState.visibility = View.GONE
                
                btnCancelOrder.visibility = View.VISIBLE
                btnCancelRideDriver.visibility = View.GONE

                // Получаем координаты точки отправления (Точки А)
                val originLatLng = originPlace?.latLng ?: com.google.android.gms.maps.model.LatLng(
                    order.originLat ?: 0.0, 
                    order.originLng ?: 0.0
                )

                // 5. Отрисовываем маркер Точки А, чтобы было визуально понятно, где нас ждет авто
                originMarker = mMap?.addMarker(MarkerOptions()
                    .position(originLatLng)
                    .icon(getBitmapDescriptor(R.drawable.ic_marker_base_yellow))
                    .anchor(0.5f, 0.5f)
                    .zIndex(1000f))

                // 6. Строго концентрируем камеру по Точке А без прыжков
                if (!isRadarRunning) {
                    mMap?.moveCamera(com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(originLatLng, 16.5f))
                }
                
                // Запускаем волновой радар волновой пульсации вокруг Точки А
                startSearchRadarAnimation(originLatLng)
                
                updateMapPadding(activeOrderCard, 0f, 20f, recenterMap = false)
            }
            
            "ACCEPTED" -> {
                stopSearchRadarAnimation()
                
                // Возвращаем управление картой клиенту, так как водитель найден
                mMap?.uiSettings?.isScrollGesturesEnabled = true
                mMap?.uiSettings?.isZoomGesturesEnabled = true

                // Показываем блок адресов маршрута обратно, когда водитель принял заказ
                findViewById<View>(R.id.tv_order_route_origin)?.parent?.let { (it as? View)?.visibility = View.VISIBLE }

                updateOrderProgress(1)
                stopStatusBlinking()
                orderStatusText.text = getString(R.string.status_driver_coming)
                
                layoutSearchControls.visibility = View.GONE
                layoutDriverFoundState.visibility = View.VISIBLE
                
                layoutSearchDetails.visibility = View.GONE
                findViewById<TextView>(R.id.tv_order_tariff_title)?.visibility = View.GONE
                layoutDriverDetails.visibility = View.VISIBLE
                layoutPaymentCompleted.visibility = View.GONE 
                
                btnCancelOrder.visibility = View.GONE        
                btnCancelRideDriver.visibility = View.VISIBLE 
                
                updateDriverInfo(order)
                stopWaitingTimer()

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
                
                updateMapPadding(activeOrderCard, 0f, 20f)
            }

            "DRIVER_ARRIVED" -> {
                updateOrderProgress(1)
                stopStatusBlinking()
                orderStatusText.text = getString(R.string.status_driver_arrived) 
                
                layoutSearchControls.visibility = View.GONE
                layoutDriverFoundState.visibility = View.VISIBLE
                
                layoutSearchDetails.visibility = View.GONE
                findViewById<TextView>(R.id.tv_order_tariff_title)?.visibility = View.GONE
                layoutDriverDetails.visibility = View.VISIBLE
                layoutPaymentCompleted.visibility = View.GONE 
                
                btnCancelOrder.visibility = View.GONE        
                btnCancelRideDriver.visibility = View.VISIBLE 

                // Убеждаемся, что адреса видимы
                findViewById<View>(R.id.tv_order_route_origin)?.parent?.let { (it as? View)?.visibility = View.VISIBLE }
                
                updateDriverInfo(order)
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
                updateMapPadding(activeOrderCard, 0f, 20f)
            }

            "IN_PROGRESS" -> {
                updateOrderProgress(2)
                stopStatusBlinking()
                orderStatusText.text = getString(R.string.status_in_progress)
                
                layoutSearchControls.visibility = View.GONE
                layoutDriverFoundState.visibility = View.VISIBLE
                
                layoutSearchDetails.visibility = View.GONE
                findViewById<TextView>(R.id.tv_order_tariff_title)?.visibility = View.GONE
                layoutDriverDetails.visibility = View.VISIBLE
                layoutPaymentCompleted.visibility = View.GONE 
                
                btnCancelOrder.visibility = View.GONE
                btnCancelRideDriver.visibility = View.GONE

                // Убеждаемся, что адреса видимы в поездке
                findViewById<View>(R.id.tv_order_route_origin)?.parent?.let { (it as? View)?.visibility = View.VISIBLE }
                
                updateDriverInfo(order)
                stopWaitingTimer()

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
                updateMapPadding(activeOrderCard, 0f, 20f)
            }
            
            "COMPLETED" -> {
                updateOrderProgress(3)
                stopStatusBlinking()
                resetOrderDetailsState()
                orderStatusText.text = getString(R.string.status_completed)
                
                layoutSearchControls.visibility = View.GONE
                layoutDriverFoundState.visibility = View.GONE
                
                layoutSearchDetails.visibility = View.GONE
                findViewById<View>(R.id.tv_order_tariff_title)?.visibility = View.GONE 
                layoutDriverDetails.visibility = View.GONE
                
                btnCancelOrder.visibility = View.GONE
                btnCancelRideDriver.visibility = View.GONE
                
                stopDriverTracking()
                stopWaitingTimer()

                layoutPaymentCompleted.visibility = View.VISIBLE
                tvFinalPaymentPrice.text = String.format("%.0f ₴", order.price)
                
                val targetBottomPaddingPx = convertDpToPixel(20f).toInt()
                layoutPaymentCompleted.setPadding(
                    layoutPaymentCompleted.paddingLeft,
                    layoutPaymentCompleted.paddingTop,
                    layoutPaymentCompleted.paddingRight,
                    targetBottomPaddingPx
                )
                
                updateMapPadding(activeOrderCard, 0f, 20f)

                btnUnderstandPayment.setOnClickListener {
                    layoutPaymentCompleted.visibility = View.GONE
                    
                    if (!order.isRatedByClient) {
                        val driverName = order.driver?.fullName ?: getString(R.string.default_driver_name)
                        showRatingDialog(order.id, driverName)
                    } else {
                        viewModel.clearOrderState()
                        activeOrderId = null
                        showAddressPanel()
                    }
                }
            }
            
            "CANCELLED" -> {
                stopStatusBlinking()
                resetOrderDetailsState()
                orderStatusText.text = getString(R.string.status_cancelled)
                orderStatusText.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.taxi_red_cancel))
                
                layoutSearchControls.visibility = View.GONE
                layoutDriverFoundState.visibility = View.GONE
                
                layoutSearchDetails.visibility = View.GONE
                findViewById<TextView>(R.id.tv_order_tariff_title)?.visibility = View.GONE
                layoutDriverDetails.visibility = View.GONE
                layoutPaymentCompleted.visibility = View.GONE
                
                btnCancelOrder.visibility = View.GONE
                btnCancelRideDriver.visibility = View.GONE
                
                stopDriverTracking()
                stopWaitingTimer()

                updateMapPadding(activeOrderCard, 0f, 20f)
                
                viewModel.clearOrderState()
                
                Handler(Looper.getMainLooper()).postDelayed({ showAddressPanel() }, 3000)
            }
        }
    layoutExpandableDetails.post {
        // Сбрасываем старую огромную высоту
        maxDetailsHeight = 0 
        
        // Замеряем актуальную высоту с учетом скрытого тарифа
        val widthSpec = View.MeasureSpec.makeMeasureSpec(activeOrderCard.width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        layoutExpandableDetails.measure(widthSpec, heightSpec)
        maxDetailsHeight = layoutExpandableDetails.measuredHeight
        
        // Если панель в данный момент вытянута вверх - мгновенно подтягиваем дно, убирая пустоту
        if (isOrderDetailsExpanded) {
            val params = layoutExpandableDetails.layoutParams
            params.height = maxDetailsHeight
            layoutExpandableDetails.layoutParams = params
        } else {
            // Если свернута - убеждаемся, что ее высота 0
            val params = layoutExpandableDetails.layoutParams
            params.height = 0
            layoutExpandableDetails.layoutParams = params
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
if (drv.hasMovementIssue) healthIssues.add("Порушення опорно-рухового апарату")
if (drv.hasHearingIssue) healthIssues.add("Порушення слуху")
if (drv.isDeaf) healthIssues.add("Водій не чує (глухий)")
if (drv.hasSpeechIssue) healthIssues.add("Порушення мовлення")

if (healthIssues.isNotEmpty()) {
    btnDriverHealthAlert.visibility = View.VISIBLE
    btnDriverHealthAlert.setOnClickListener {
        showDriverHealthDialog(healthIssues)
    }
} else {
    btnDriverHealthAlert.visibility = View.GONE
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

    private fun stopRouteAnimations() {
        routeAnimator?.cancel()
        routeAnimator = null
        revealAnimator?.cancel()
        revealAnimator = null
        markerRevealAnimator?.cancel()
        markerRevealAnimator = null

        polylineMain?.remove()
        polylineBorder?.remove()
        polylineAnim?.remove()
        polylineMain = null
        polylineBorder = null
        polylineAnim = null
    }
    
    // ДОБАВИЛИ ПАРАМЕТР recenterMap: Boolean = true по умолчанию
private fun updateMapPadding(bottomPanel: View, extraBottomDp: Float = 20f, topPaddingDp: Float = 20f, recenterMap: Boolean = true) {
    bottomPanel.requestLayout()

    bottomPanel.post {
        if (mMap != null) {
            if (bottomPanel.visibility != View.VISIBLE) {
                mMap?.setPadding(0, 0, 0, 0)
                return@post
            }

            var panelHeight = bottomPanel.height
            if (panelHeight == 0) return@post

            // --- НОВАЯ МАГИЯ: Обманываем карту ---
            // Если это панель активного заказа, мы вычитаем высоту выезжающих деталей.
            // Теперь карта ВСЕГДА будет думать, что панель свернута, и логотип Google 
            // будет стоять как влитой!
            if (bottomPanel.id == R.id.active_order_card) {
                val detailsView = bottomPanel.findViewById<View>(R.id.layout_expandable_details)
                if (detailsView != null && detailsView.visibility == View.VISIBLE) {
                    panelHeight -= detailsView.height
                }
            }

            var marginBottom = 0
            var sideMargin = 0

            val params = bottomPanel.layoutParams
            if (params is ViewGroup.MarginLayoutParams) {
                marginBottom = params.bottomMargin
                sideMargin = params.leftMargin
            }

            val extraBuffer = convertDpToPixel(extraBottomDp).toInt()
            val totalBottomPadding = panelHeight + marginBottom + extraBuffer
            val topPadding = convertDpToPixel(topPaddingDp).toInt()

            // Двигаем элементы Google (логотип, кнопки), чтобы они не перекрывались панелью
            mMap?.setPadding(sideMargin, topPadding, sideMargin, totalBottomPadding)

            // --- ИЗМЕНЕНИЕ ЗДЕСЬ: Центрируем маршрут ТОЛЬКО если recenterMap == true ---
            if (recenterMap && viewModel.currentRoutePolyline != null) {
                try {
                    val boundsBuilder = LatLngBounds.Builder()
                    if (originPlace != null && destinationPlace != null) {
                        boundsBuilder.include(originPlace!!.latLng!!)
                        boundsBuilder.include(destinationPlace!!.latLng!!)
                        currentWaypoints.forEach { boundsBuilder.include(it.first) }
                        decodedRoutePoints?.forEach { boundsBuilder.include(it) }

                        // ИСПРАВЛЕНИЕ: Синхронизируем падинг до 36dp для динамического кадрирования шторки
                        val labelSafePadding = convertDpToPixel(36f).toInt()
                        mMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), labelSafePadding))
                    }
                } catch (e: Exception) {}
            }
        }
    }
}

private fun updateNearbyDriversOnMap(drivers: List<DriverLocationDto>) {
        if (mMap == null) return
        
        val mapCenter = mMap!!.cameraPosition.target

        // ФИКС БАГА: Если открыт заказ, тарифы ИЛИ режим выбора адреса на карте — тотально чистим маркеры и выходим
        if (activeOrderId != null || isRouteMode || viewModel.currentRoutePolyline != null || 
            tariffsPanel.visibility == View.VISIBLE || isMapPickingMode) {
            nearbyDriverMarkers.values.forEach { it.remove() }
            nearbyDriverMarkers.clear()
            return
        }

        // Жесткий фильтр на 0.5 км
        val SEARCH_RADIUS_METERS = 500.0
        val driversInRange = drivers.filter { driver ->
            val driverPos = LatLng(driver.lat, driver.lng)
            val distance = com.google.maps.android.SphericalUtil.computeDistanceBetween(mapCenter, driverPos)
            distance <= SEARCH_RADIUS_METERS
        }

        val newDriverIds = driversInRange.map { it.driverId }
        
        // УДАЛЯЕМ МАРКЕРЫ: Если водитель вышел за радиус 1.5 км или пропал из списка бэкенда — удаляем маркер
        val iterator = nearbyDriverMarkers.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!newDriverIds.contains(entry.key)) {
                entry.value.remove()
                iterator.remove()
            }
        }

        // ДОБАВЛЯЕМ / ОБНОВЛЯЕМ МАРКЕРЫ НА КАРТЕ:
        for (driver in driversInRange) {
            val position = LatLng(driver.lat, driver.lng)
            if (nearbyDriverMarkers.containsKey(driver.driverId)) {
                val marker = nearbyDriverMarkers[driver.driverId]!!
                animateMarker(marker, position, driver.bearing)
            } else {
                val carWidth = 40
                val carHeight = 40

                // Масштабируем локальный PNG-автомобиль под нужный размер
                val fallbackIcon = BitmapHelper.getScaledBitmapDescriptor(
                    this, 
                    R.drawable.ic_car_icon, 
                    carWidth, 
                    carHeight
                )

                val carIcon = customCarIcon ?: fallbackIcon
                
                val markerOpts = MarkerOptions()
                    .position(position)
                    .icon(carIcon)
                    .anchor(0.5f, 0.5f)
                    .rotation(driver.bearing)
                    .zIndex(50f)
                
                val marker = mMap?.addMarker(markerOpts)
                if (marker != null) {
                    nearbyDriverMarkers[driver.driverId] = marker
                }
            }
        }
    }

    

    private fun showAddressPanel() {
        isRouteMode = false
        mMap?.uiSettings?.isScrollGesturesEnabled = true
        mMap?.uiSettings?.isZoomGesturesEnabled = true
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

        // --- ВАЖНО: ОСТАНАВЛИВАЕМ И СКРЫВАЕМ ТАЙМЕР ОЖИДАНИЯ ---
        stopWaitingTimer()

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
            // Відновлюємо правильний колір або просто скидаємо фільтр (XML tint зробить свою справу)
            btnSchedule.clearColorFilter() 
        } catch (e: Exception){}

        tvOrigin.text = getString(R.string.hint_where_from)
        tvDestination.text = getString(R.string.hint_where_to)
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
        viewModel.startListeningNearbyDrivers(webSocketManager)
    }

    private fun updateThemeLabel() {
    val tvThemeLabel = findViewById<TextView>(R.id.tv_theme_label)
    
    if (sessionManager.isDarkMode()) {
        // Берем строку из ресурсов (она автоматически будет на нужном языке)
        tvThemeLabel.text = getString(R.string.theme_label_dark)
    } else {
        tvThemeLabel.text = getString(R.string.theme_label_light)
    }
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