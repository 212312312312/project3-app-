package com.taxiapp.client

import com.google.maps.android.SphericalUtil
import android.widget.RelativeLayout
import android.app.Activity
import android.Manifest // <--- ВАЖЛИВИЙ ІМПОРТ
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Dialog // <--- ВАЖЛИВИЙ ІМПОРТ
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable // <--- ВАЖЛИВИЙ ІМПОРТ
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.graphics.Bitmap
import android.graphics.Canvas
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Marker
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
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.RoundCap
import com.google.android.gms.maps.model.StrokeStyle
import com.google.android.gms.maps.model.StyleSpan
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.maps.android.PolyUtil
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.DirectionsApiClient
import com.taxiapp.client.network.dto.ActiveDiscountDto
import com.taxiapp.client.network.dto.CarTariffDto
import com.taxiapp.client.network.dto.CreateOrderRequestDto
import com.taxiapp.client.network.dto.DirectionsResponse
import com.taxiapp.client.network.dto.TaxiOrderDto
import com.taxiapp.client.network.dto.WaypointDto
import com.taxiapp.client.ui.TariffAdapter
import com.taxiapp.client.ui.TariffItem
import com.taxiapp.client.utils.AddressUtils
import com.taxiapp.client.utils.BitmapHelper
import com.taxiapp.client.utils.CityData
import com.taxiapp.client.utils.CityDatabase
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils // <--- Не забудь імпорт ViewUtils
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Locale

class HomeActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val REQUEST_CODE_CITY = 101
        var lastScreenshot: android.graphics.Bitmap? = null
        private var lastSwitchTime: Long = 0
    }

    private var currentTariffPrice: Double = 0.0 // Базовая цена поездки
    private lateinit var tvPrice: TextView

    private var decodedRoutePoints: List<LatLng>? = null

    private lateinit var sessionManager: SessionManager
    private var mMap: GoogleMap? = null

    private var isChoosingDestination = false

    private val animHandler = Handler(Looper.getMainLooper())
    private val statusHandler = Handler(Looper.getMainLooper())

    private lateinit var customToastContainer: CardView
    private lateinit var tvToastMessage: TextView
    private lateinit var ivToastIcon: ImageView
    private val toastHandler = Handler(Looper.getMainLooper())
    private val hideToastRunnable = Runnable { hideTopMessage() }

    // Views для умных меток
    private lateinit var overlayOrigin: View
    private lateinit var tvOverlayOrigin: TextView
    private lateinit var overlayDest: View
    private lateinit var tvOverlayDest: TextView

    private var polylineBorder: Polyline? = null
    private var polylineMain: Polyline? = null
    private var polylineAnim: Polyline? = null
    private var routeAnimator: ValueAnimator? = null


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
    private lateinit var statusBlinkAnimator: android.animation.ObjectAnimator

    private lateinit var activeOrderCard: View
    private lateinit var orderStatusText: TextView
    private lateinit var statusProgressBar: ProgressBar
    private lateinit var btnCancelOrder: Button

    private var orderComment: String = "" 
    private lateinit var btnOpenComment: View
    private lateinit var ivCommentIcon: ImageView

    private var currentPaymentMethod: String = "CASH" 
    
    // View для иконки
    private lateinit var ivPaymentIcon: ImageView
    
    private lateinit var layoutDriverDetails: LinearLayout
    private lateinit var tvCarPlateLarge: TextView
    private lateinit var tvCarDetailsSubtitle: TextView
    private lateinit var tvDriverFirstName: TextView
    private lateinit var tvDriverExperience: TextView
    private lateinit var tvDriverRidesCount: TextView
    private lateinit var ivDriverPhoto: ImageView
    private lateinit var btnCallDriver: ImageButton

    private lateinit var tvActiveOrderPrice: TextView
    private lateinit var ivActiveOrderPayment: ImageView

    private lateinit var layoutActiveOrderPrice: View

    private lateinit var mapLoadingCurtain: ImageView
    private lateinit var contentBottomSheet: View
    
    private var originPlace: Place? = null
    private var destinationPlace: Place? = null
    private var routeDistanceMeters: Int = 0
    private var routeDurationSeconds: Int = 0
    private var currentRoutePolyline: String? = null

    private var availableTariffs: List<CarTariffDto> = emptyList()
    private var currentCity: CityData? = null

    private val MODE_ORIGIN = 1
    private val MODE_DESTINATION = 2
    private val MODE_ADD_HOME = 3
    private val MODE_ADD_WORK = 4
    private var pickerMode = MODE_ORIGIN
    private var isSelectingOrigin = true

    private var selectedTariffItem: TariffItem? = null
    private var activeOrderId: Long? = null

    private var selectedServiceIds = ArrayList<Long>()
    private var servicesExtraCost: Double = 0.0

    private val tariffCustomPrices = mutableMapOf<Long, Double>()
    private lateinit var btnChangePrice: View

    private val statusRunnable = object : Runnable {
        override fun run() {
            checkOrderStatus()
            statusHandler.postDelayed(this, 3000)
        }
    }

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

            // 1. ВАРІАНТ: Юзер обрав адресу зі списку (текстовий пошук)
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

            // 2. ВАРІАНТ: Юзер натиснув кнопку "Вказати на карті"
            else if (action == "map_click") {
                val intent = Intent(this, MapPickerActivity::class.java)

                // Визначаємо, де відкрити карту:
                // Якщо обираємо Точку А (Start) -> центруємо на Origin або на поточному центрі
                // Якщо обираємо Точку Б (Dest) -> центруємо на Destination або на поточному центрі
                val startLatLng = if (pickerMode == MODE_ORIGIN) {
                    originPlace?.latLng ?: mMap?.cameraPosition?.target
                } else {
                    destinationPlace?.latLng ?: mMap?.cameraPosition?.target
                }

                if (startLatLng != null) {
                    intent.putExtra("start_lat", startLatLng.latitude)
                    intent.putExtra("start_lng", startLatLng.longitude)
                }

                // Запускаємо окремий екран вибору на карті
                mapPickerLauncher.launch(intent)
            }
        }
    }

    private val paymentLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
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
            
            // Получаем координаты, которые выбрал юзер в MapPickerActivity
            val lat = data.getDoubleExtra("picked_lat", 0.0)
            val lng = data.getDoubleExtra("picked_lng", 0.0)
            val addressName = data.getStringExtra("picked_name") ?: "Точка на карті"

            // Создаем объект Place
            val place = Place.builder()
                .setName(addressName)
                .setLatLng(LatLng(lat, lng))
                .build()

            // Отправляем в твою готовую функцию обработки
            // Она сама поймет (по pickerMode), куда подставить этот адрес (в А или в Б)
            handleAddressSelection(place, addressName)
        }
    }

    private val servicesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val data = result.data!!
            
            // 1. Отримуємо вартість послуг
            servicesExtraCost = data.getDoubleExtra("EXTRA_COST", 0.0)
            selectedServiceIds = data.getSerializableExtra("SELECTED_IDS") as? ArrayList<Long> ?: ArrayList()

            // 2. !!! ОНОВЛЮЄМО АДАПТЕР !!!
            // Це перерахує ціни у всіх картках (Тариф + Послуги)
            tariffAdapter.updateExtraCost(servicesExtraCost)

            // 3. Оновлюємо кнопку, якщо тариф вже був обраний (опціонально, бо адаптер скине виділення)
            // Якщо хочеш, щоб виділення злітало (щоб юзер клікнув на тариф заново і побачив нову ціну):
            btnOrderTaxi.isEnabled = false
            btnOrderTaxi.text = "Оберіть тариф"
            selectedTariffItem = null
            
            // АБО (якщо хочеш зберегти вибір):
            /*
            selectedTariffItem?.let { item ->
               // Треба вручну оновити item.priceValue, бо старий об'єкт item має стару ціну
               val newPrice = item.priceValue + servicesExtraCost // Це грубо, краще нехай юзер переобере
               btnOrderTaxi.text = "Замовити ${newPrice.toInt()} ₴"
            }
            */
            
            showToast("Послуги додано: +${servicesExtraCost.toInt()} грн")
        }
    }

    // --- 1. ЛАУНЧЕР ДЛЯ ПОВІДОМЛЕНЬ (Перейменований, щоб не було конфлікту) ---
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Тут нічого не робимо, бо ми вже на карті
    }

    // --- 2. ЛАУНЧЕР ДЛЯ ГЕОЛОКАЦІЇ (Залишається старий) ---
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            
            // 1. Логіка Геолокації
            if (isGranted) {
                // ДОЗВОЛЕНО
                try {
                    mMap?.isMyLocationEnabled = true
                    detectCityAndMove()
                } catch (e: SecurityException) {}
            } else {
                // ВІДМОВЛЕНО
                if (currentCity == null) {
                    showCitySelectorDialog()
                } else {
                    showToast("Увімкніть геолокацію в налаштуваннях")
                }
            }

            // 2. ВАЖЛИВО: Тільки тепер, коли ми розібралися з локацією, запускаємо таймер для Сповіщень
            checkAndShowNotificationDialogWithDelay() 
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!NetworkUtils.isInternetAvailable(this)) {
            val intent = Intent(this, NoInternetActivity::class.java)
            startActivity(intent)
            finish() // Закрываем HomeActivity, чтобы она не пыталась грузить карту
            return // Останавливаем выполнение кода
        }

        sessionManager = SessionManager(applicationContext)

        // Настройка темы
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val isDark = sessionManager.isDarkMode()
        val mode = if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        setContentView(R.layout.activity_home)

        // !!! ВАЖНО: Инициализируем новые переменные интерфейса здесь (или в initUI) !!!
        // Это исправит ошибки "Unresolved reference"


        setupSystemBars(isDark)

        // API KEY
        val myApiKey = "AIzaSyDp1blRHORukZ08uYYpvh52fN0mGe7Rnu4" 
        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, myApiKey, Locale("uk", "UA"))
        }

        currentCity = sessionManager.fetchUserCity()

        initUI()
        setupProfileLogic()
        setupTariffAdapter()
        updateFavoriteButtonsUI()

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Логика восстановления заказа
        val savedOrderId = sessionManager.fetchActiveOrderId()
        if (savedOrderId != -1L) {
            activeOrderId = savedOrderId    
            
            // showActiveOrderPanel() // <--- УДАЛЯЕМ ЭТУ СТРОКУ
            // Мы не можем вызвать её здесь, так как у нас еще нет объекта order, только ID.
            // startStatusPolling() сделает запрос на сервер, получит order и сам вызовет showActiveOrderPanel(order).
            
            startStatusPolling() 
            
            // Чтобы панель тарифов не перекрывала карту пока грузится заказ, можно временно скрыть её:
             findViewById<View>(R.id.tariffs_panel).visibility = View.GONE
        } else if (currentCity == null) {
            checkPermissionsAndAutoDetectCity()
        }

        // Анимация перехода (скриншот)
        if (lastScreenshot != null) {
            val coverImage = ImageView(this)
            coverImage.setImageBitmap(lastScreenshot)
            coverImage.scaleType = ImageView.ScaleType.FIT_XY
            coverImage.layoutParams = android.view.ViewGroup.LayoutParams(
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
    }

    // ... (ТУТ МАЮТЬ БУТИ ВАШІ МЕТОДИ: initUI, setupSystemBars, і т.д.) ...
    // ... Якщо ви їх не видаляли, вони тут ...

    // --- НОВІ МЕТОДИ ДЛЯ ДІАЛОГУ ---

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

        // 1. Убираем стандартный белый фон окна (чтобы скругления CardView работали и не было белых углов)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // 2. ВАЖНО: Растягиваем само окно диалога на всю ширину (MATCH_PARENT).
        // Теперь ширина ограничивается ТОЛЬКО твоими отступами в XML (24dp).
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialog.setCancelable(false)

        val btnAllow = dialog.findViewById<Button>(R.id.btn_allow)
        val btnDeny = dialog.findViewById<Button>(R.id.btn_deny)

        btnAllow.setOnClickListener {
            dialog.dismiss()
            sessionManager.setNotificationAsked(true)

            if (Build.VERSION.SDK_INT >= 33) {
                // Используем наш НОВЫЙ лаунчер
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
        
        // Якщо панель тарифів відкрита, перезавантажуємо ціни, 
        // щоб відобразити нову знижку з промокоду, якщо вона з'явилася
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

        // Было: R.id.tv_overlay_origin_text -> Стало: R.id.tv_overlay_origin
        tvOverlayOrigin = findViewById(R.id.tv_overlay_origin)

        // Было: R.id.overlay_marker_dest -> Стало: R.id.overlay_dest
        overlayDest = findViewById(R.id.overlay_dest)

        // Было: R.id.tv_overlay_dest_text -> Стало: R.id.tv_overlay_dest
        tvOverlayDest = findViewById(R.id.tv_overlay_dest)

        centerPin = findViewById(R.id.center_pin)
        val shadowView = findViewById<ImageView>(R.id.pin_shadow)
        pinShadow = shadowView ?: centerPin 

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

        btnChangePrice = findViewById(R.id.btn_change_price)
        btnChangePrice.setOnClickListener {
        showPriceAdjustmentDialog()
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

        try {
            tvPrice = findViewById(R.id.tv_active_order_price) // <-- ЗАМЕНИ R.id.tv_estimated_price НА ТВОЙ ID ИЗ XML
        } catch (e: Exception) {
            // Если ID не найден, чтобы не падало
            e.printStackTrace()
        }

        // --- 1. ЗНАХОДИМО НОВІ КНОПКИ ДЛЯ КОМЕНТАРЯ ---
        btnOpenComment = findViewById(R.id.btn_open_comment)
        ivCommentIcon = findViewById(R.id.iv_comment_icon)
        
        // --- 2. ДОДАЄМО ОБРОБНИК КЛІКУ ---
        btnOpenComment.setOnClickListener {
            val intent = Intent(this, CommentActivity::class.java)
            intent.putExtra("EXTRA_COMMENT", orderComment) // Передаємо поточний текст, щоб редагувати
            commentLauncher.launch(intent)
        }
        // ----------------------------------------------

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

        tvActiveOrderPrice = findViewById(R.id.tv_active_order_price)
        ivActiveOrderPayment = findViewById(R.id.iv_active_order_payment)

        btnMenu.setOnClickListener {
            if (tariffsPanel.visibility == View.VISIBLE) {
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
            // Перевіряємо, яка панель зараз активна (Тарифи або Активне замовлення)
            val visiblePanel = if (activeOrderCard.visibility == View.VISIBLE) {
                activeOrderCard
            } else if (tariffsPanel.visibility == View.VISIBLE) {
                tariffsPanel
            } else {
                null
            }

            // Якщо панель знайдена - використовуємо нашу нову логіку
            if (visiblePanel != null) {
                // 0f знизу (бо ми це враховуємо в функції), 10f зверху
                updateMapPadding(visiblePanel, 0f, 10f)
            } else {
                // Якщо панелей немає (рідкісний кейс), просто центруємо маршрут стандартно
                if (currentRoutePolyline != null) {
                     val boundsBuilder = LatLngBounds.Builder()
                     // ... (тут можна залишити спрощену логіку або просто нічого не робити)
                     try {
                         boundsBuilder.include(originPlace!!.latLng!!)
                         boundsBuilder.include(destinationPlace!!.latLng!!)
                         decodedRoutePoints?.forEach { boundsBuilder.include(it) }
                         mMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 100))
                         btnRecenterRoute.visibility = View.GONE
                     } catch (e: Exception) {}
                }
            }
        }

        val openOrigin = View.OnClickListener {
            pickerMode = MODE_ORIGIN
            isSelectingOrigin = true
            openAddressPicker(true)
        }
        containerOrigin.setOnClickListener(openOrigin)
        tvOrigin.setOnClickListener(openOrigin)

        val openDest = View.OnClickListener {
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
        // ✅ ПРАВИЛЬНО:
        createOrder(selectedTariffItem!!.tariff, selectedTariffItem!!.priceValue)

        // ❌ ОШИБКА (если было так):
        // createOrder(selectedTariffItem!!.tariff, selectedTariffItem!!.priceValue + servicesExtraCost)
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
            // Закриваємо меню
            drawerLayout.closeDrawer(GravityCompat.START)
            
            // Створюємо інтент для переходу
            val intent = Intent(this, PaymentActivity::class.java)
            
            // Передаємо поточний метод, щоб там стояла правильна галочка
            intent.putExtra("EXTRA_PAYMENT_METHOD", currentPaymentMethod)
            
            // Запускаємо через лаунчер, щоб отримати результат назад
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
            // Передаем текущий метод, чтобы там поставить галочку
            intent.putExtra("EXTRA_PAYMENT_METHOD", currentPaymentMethod)
            paymentLauncher.launch(intent)
        }

        findViewById<View>(R.id.btn_open_services).setOnClickListener {
            val intent = Intent(this, ServicesActivity::class.java)
            // Передаємо вже обрані послуги, щоб там стояли галочки
            intent.putExtra("SELECTED_IDS", selectedServiceIds)
            servicesLauncher.launch(intent)
        }
    }

    private fun setupProfileLogic() {
        val name = sessionManager.getUserName() ?: "User"
        profileUserName.text = name
        
        val firstLetter = if (name.isNotEmpty()) name.first().toString().uppercase() else "U"
        findViewById<TextView>(R.id.tv_avatar_letter).text = firstLetter

        profileCityText.text = currentCity?.name ?: "Не обрано"
        
        val isDark = sessionManager.isDarkMode()
        updateThemeSwitchUI(isDark, animate = false, updateColors = true)

        themeSwitchContainer.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastSwitchTime < 1000) {
                return@setOnClickListener
            }
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
        // Додаємо "геолокацію" до списку слів, які викликають червоний стиль
        val isError = msg.contains("Помилка", true) || 
                      msg.contains("Error", true) || 
                      msg.contains("недоступний") || 
                      msg.contains("геолокацію") // <-- Додай це
                      
        showTopMessage(msg, isError)
    }

    private fun updateCommentIconState() {
        if (orderComment.isNotEmpty()) {
            // Якщо є коментар — фарбуємо в акцентний колір (жовтий)
            ivCommentIcon.setColorFilter(ContextCompat.getColor(this, R.color.taxi_yellow))
        } else {
            // Якщо коментаря немає — перевіряємо, яка зараз тема системи
            val nightModeFlags = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            val isDarkMode = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES

            if (isDarkMode) {
                // У темній темі іконка має бути БІЛОЮ
                ivCommentIcon.setColorFilter(android.graphics.Color.WHITE)
            } else {
                // У світлій темі іконка має бути ЧОРНОЮ
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
            // Права НЕМАЄ -> Запитуємо (Лаунчер спрацює пізніше і викличе сповіщення)
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            // Права ВЖЕ Є -> Робимо справи з локацією І запускаємо сповіщення
            mMap?.isMyLocationEnabled = true
            detectCityAndMove()
            
            // <-- ДОДАЄМО ЦЕЙ РЯДОК
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
        statusHandler.removeCallbacks(statusRunnable)
    }

    private fun recenterMapOnUser() {
        if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Це запустить requestPermissionLauncher. Якщо місто є -> покаже тост.
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
        // currentTariffPrice - це ціна, яку вирахував Google Maps або сервер за маршрут
        // servicesExtraCost - це ціна обраних послуг
        
        val finalPrice = currentTariffPrice + servicesExtraCost
        
        // Оновлюємо TextView з ціною
        tvPrice.text = "${finalPrice.toInt()} ₴" 
    }

    private fun openAddressPicker(isOrigin: Boolean, hideMyLocation: Boolean = false) {
        val intent = Intent(this, AddressPickerActivity::class.java)
        intent.putExtra(AddressPickerActivity.EXTRA_IS_ORIGIN, isOrigin)
        intent.putExtra(AddressPickerActivity.EXTRA_CURRENT_ADDRESS, tvOrigin.text.toString())
        
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

        // 1. Очищаем старые декораторы (чтобы линии не дублировались)
        while (tariffsRecyclerView.itemDecorationCount > 0) {
            tariffsRecyclerView.removeItemDecorationAt(0)
        }

        // 2. Добавляем "Умный" декоратор, который пропускает последний элемент
        tariffsRecyclerView.addItemDecoration(object : androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
            val divider = androidx.core.content.ContextCompat.getDrawable(this@HomeActivity, R.drawable.divider_horizontal)

            // Этот метод создает место (отступ) для линии
            override fun getItemOffsets(outRect: android.graphics.Rect, view: android.view.View, parent: androidx.recyclerview.widget.RecyclerView, state: androidx.recyclerview.widget.RecyclerView.State) {
                val position = parent.getChildAdapterPosition(view)
                // Если это НЕ последний элемент -> добавляем отступ снизу
                if (position != androidx.recyclerview.widget.RecyclerView.NO_POSITION && position < state.itemCount - 1) {
                    outRect.bottom = divider?.intrinsicHeight ?: 0
                } else {
                    outRect.bottom = 0
                }
            }

            // Этот метод рисует саму линию
            override fun onDraw(c: android.graphics.Canvas, parent: androidx.recyclerview.widget.RecyclerView, state: androidx.recyclerview.widget.RecyclerView.State) {
                divider?.let {
                    val left = parent.paddingLeft
                    val right = parent.width - parent.paddingRight
                    
                    val childCount = parent.childCount
                    for (i in 0 until childCount) {
                        val child = parent.getChildAt(i)
                        val position = parent.getChildAdapterPosition(child)
                        
                        // Рисуем линию только если это НЕ последний элемент
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
        // --- 1. Налаштування стилю (Темна/Світла тема) ---
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

        // --- 2. Початкове позиціонування камери ---
        val cityToLoad = currentCity ?: sessionManager.fetchUserCity()
        val cityCenter = cityToLoad?.let { LatLng(it.lat, it.lng) } ?: LatLng(50.4501, 30.5234)
        val cityZoom = cityToLoad?.zoom ?: 11f
        mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(cityCenter, cityZoom))

        // --- 3. Налаштування UI карти ---
        mMap?.uiSettings?.isZoomControlsEnabled = false

        // Перевірка дозволів на геолокацію
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            mMap?.isMyLocationEnabled = true
            mMap?.uiSettings?.isMyLocationButtonEnabled = false // Ми використовуємо свою кнопку
            if (currentCity != null) recenterMapOnUser()
        }

        // --- 4. ВАЖЛИВО: Слухач руху камери ---
        mMap?.setOnCameraMoveListener {
            updateSmartLabels()
        }

        // --- 5. Початок руху камери (Анімація піна) ---
        mMap?.setOnCameraMoveStartedListener { reason ->
            if (currentRoutePolyline == null) {
                // Режим вибору адреси: Піднімаємо пін
                tvOrigin.text = "Визначення..."
                centerPin.animate()
                    .translationY(convertDpToPixel(-48f))
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .setDuration(250)
                    .start()
                try {
                    pinShadow.animate().scaleX(0.6f).scaleY(0.6f).alpha(0.3f).setDuration(250).start()
                } catch (e: Exception) {}
            } else {
                // Режим маршруту: Показуємо кнопку повернення
                if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                    btnRecenterRoute.visibility = View.VISIBLE
                }
            }
        }

        // --- 6. Кінець руху камери (Фіксація) ---
        mMap?.setOnCameraIdleListener {
            updateSmartLabels()

            // Если маршрут уже есть - ничего не меняем
            if (currentRoutePolyline != null) return@setOnCameraIdleListener

            // Если маршрута нет - мы считаем, что юзер двигает карту, чтобы уточнить Точку А
            val center = mMap!!.cameraPosition.target
            getAddressForOrigin(center)

            // Анимация пина
            centerPin.animate()
                .translationY(convertDpToPixel(-32f))
                .setInterpolator(BounceInterpolator())
                .setDuration(500)
                .start()
             try {
                 pinShadow.animate().scaleX(1.0f).scaleY(1.0f).alpha(0.5f).setDuration(250).start()
             } catch (e: Exception) {}
        }

        // ================================================================
        // --- 7. НОВЕ: ПЛАВНЕ ЗАВАНТАЖЕННЯ (Seamless Transition) ---
        // ================================================================

        // Встановлюємо слухач завантаження тайлів
        mMap?.setOnMapLoadedCallback {
            revealInterface()
        }

        // ЗАХИСТ: Якщо інтернет повільний і тайли не завантажились за 2.5 сек,
        // все одно показуємо інтерфейс, щоб юзер не чекав вічно.
        Handler(Looper.getMainLooper()).postDelayed({
            revealInterface()
        }, 2500)
    }

    // Допоміжна змінна, щоб анімація не спрацювала двічі
    private var isInterfaceRevealed = false

    // Функція анімації відкриття
    private fun revealInterface() {
        if (isInterfaceRevealed) return
        isInterfaceRevealed = true

        // 1. Плавно убираем шторку-картинку
        if (::mapLoadingCurtain.isInitialized) {
            mapLoadingCurtain.animate()
                .alpha(0f)
                .setDuration(800) // Чуть дольше, чтобы было эпичнее
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    mapLoadingCurtain.visibility = View.GONE
                }
                .start()
        }

        // 2. Список элементов, которые нужно "проявить" (текст и иконки)
        val skeletonViews = listOf(
            tvOrigin, tvDestination,           // Тексты
            ivMarkerOrigin, ivMarkerDest,      // Маркеры в полях
            ivIconHome, ivIconWork,            // Иконки Дом/Работа
            indicatorAddHome, indicatorAddWork // Плюсики
        )

        // 3. Анимируем их появление
        skeletonViews.forEach { view ->
            // Проверка на всякий случай, если view не инициализирована
            try {
                view.animate()
                    .alpha(1f)
                    .setDuration(500)
                    .setStartDelay(200) // Небольшая задержка после начала ухода шторки
                    .setInterpolator(DecelerateInterpolator())
                    .start()
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
                    if (currentRoutePolyline == null) {
                        tvOrigin.text = addressName
                        originPlace = Place.builder().setName(addressName).setLatLng(latLng).build()
                    }
                }
            } catch (e: Exception) { 
                Handler(Looper.getMainLooper()).post { 
                    if (currentRoutePolyline == null) {
                        tvOrigin.text = "Точка на карті" 
                    }
                } 
            }
        }.start()
    }

    private fun tryDrawRoute() {
        if (originPlace == null || destinationPlace == null) return
        clearMapForRoute() // Очищаємо старе
        
        val originLatLng = originPlace!!.latLng!!
        val destinationLatLng = destinationPlace!!.latLng!!
        
        // --- 1. ЗАПОВНЮЄМО І ПОКАЗУЄМО РОЗУМНІ МІТКИ (ТЕКСТ) ---
        // Ці мітки "літають" поверх карти і не обрізаються
        tvOverlayOrigin.text = cleanAddress(originPlace!!.name ?: "А")
        tvOverlayDest.text = cleanAddress(destinationPlace!!.name ?: "Б")
        
        overlayOrigin.visibility = View.VISIBLE
        overlayDest.visibility = View.VISIBLE
        
        // Даємо час на отрисовку, щоб коректно розрахувати позицію
        overlayOrigin.post { updateSmartLabels() }

        // --- 2. МАЛЮЄМО НА КАРТІ ТІЛЬКИ ТОЧКИ (БЕЗ ТЕКСТУ) ---
        
        // !!! ДОДАНО: Малюємо Точку А (щоб вона не зникала) !!!
        val iconA = BitmapHelper.vectorToBitmap(this, R.drawable.ic_waypoint_dot) 
        mMap?.addMarker(MarkerOptions()
            .position(originLatLng)
            .icon(iconA)
            .anchor(0.5f, 0.5f)
            .zIndex(100f)) 

        // Для Точки Б
        val iconB = BitmapHelper.vectorToBitmap(this, R.drawable.ic_waypoint_dot) 
        mMap?.addMarker(MarkerOptions()
            .position(destinationLatLng)
            .icon(iconB)
            .anchor(0.5f, 0.5f)
            .zIndex(100f))
        
        // Проміжні точки
        val waypointIcon = BitmapHelper.vectorToBitmap(this, R.drawable.ic_waypoint_dot)
        for (wpPair in currentWaypoints) {
            mMap?.addMarker(MarkerOptions()
                .position(wpPair.first)
                .icon(waypointIcon)
                .anchor(0.5f, 0.5f)
                .title(wpPair.second))
        }
        
        // --- 3. ЗАПИТ МАРШРУТУ ---
        val originApiString = "${originLatLng.latitude},${originLatLng.longitude}"
        val destApiString = "${destinationLatLng.latitude},${destinationLatLng.longitude}"
        val waypointsString = if (currentWaypoints.isNotEmpty()) {
            "optimize:false|" + currentWaypoints.joinToString("%7C") { "${it.first.latitude},${it.first.longitude}" }
        } else {
            null
        }
        
        val myApiKey = "AIzaSyDp1blRHORukZ08uYYpvh52fN0mGe7Rnu4"

        DirectionsApiClient.instance.getDirections(originApiString, destApiString, waypointsString, myApiKey).enqueue(object : Callback<DirectionsResponse> {
            override fun onResponse(call: Call<DirectionsResponse>, response: Response<DirectionsResponse>) {
                if (response.isSuccessful && response.body()?.routes?.isNotEmpty() == true) {
                    val route = response.body()!!.routes[0]
                    currentRoutePolyline = route.overviewPolyline.points

                    // 1. Присвоюємо значення ГЛОБАЛЬНІЙ змінній
                    decodedRoutePoints = PolyUtil.decode(currentRoutePolyline)

                    // 2. Викликаємо drawStylishRoute з глобальною змінною
                    drawStylishRoute(decodedRoutePoints!!)

                    // 3. НАЛАШТУВАННЯ КАМЕРИ
                    val boundsBuilder = LatLngBounds.Builder()
                    boundsBuilder.include(originLatLng)
                    boundsBuilder.include(destinationLatLng)
                    currentWaypoints.forEach { boundsBuilder.include(it.first) }

                    // Використовуємо decodedRoutePoints для меж
                    decodedRoutePoints!!.forEach { point -> boundsBuilder.include(point) }

                    val extraRoutePadding = convertDpToPixel(80f).toInt() // Використовуємо наш новий padding

                    // Анімуємо камеру
                    mMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), extraRoutePadding), 600, null)

                    btnRecenterRoute.visibility = View.GONE

                    var totalDistance = 0L
                    var totalSeconds = 0L

                    for (leg in route.legs) {
                        totalDistance += leg.distance.meters
                        totalSeconds += leg.duration.seconds
                    }

                    routeDistanceMeters = totalDistance.toInt()
                    routeDurationSeconds = totalSeconds.toInt()

                    fetchTariffsAndShowPanel()
                } else showToast("Маршрут не знайдено")
            }
            override fun onFailure(call: Call<DirectionsResponse>, t: Throwable) { showToast("Помилка мережі") }
        })
    }

    private fun drawStylishRoute(path: List<LatLng>) {
        if (mMap == null) return

        try {
            mMap?.isMyLocationEnabled = false
        } catch (e: SecurityException) { }

        // 1. Очистка старых маркеров (тех, что мы нарисовали в tryDrawRoute)
        originMarker?.remove()
        destinationMarker?.remove()
        mMap?.clear() // Для надійності очищаємо все і малюємо заново

        // 2. Малюємо лінії
        val borderOpts = PolylineOptions().addAll(path).width(20f).color(ContextCompat.getColor(this, R.color.route_border)).startCap(RoundCap()).endCap(RoundCap()).zIndex(1f)
        polylineBorder = mMap?.addPolyline(borderOpts)

        val mainOpts = PolylineOptions().addAll(path).width(14f).color(ContextCompat.getColor(this, R.color.route_main)).startCap(RoundCap()).endCap(RoundCap()).zIndex(2f)
        polylineMain = mMap?.addPolyline(mainOpts)

        // --- 3. ТОЧКА А ---
        if (originPlace != null) {
            // Малюємо основний маркер
            originMarker = mMap?.addMarker(MarkerOptions()
                .position(originPlace!!.latLng!!)
                .icon(getBitmapDescriptor(R.drawable.ic_marker_base_yellow))
                .anchor(0.5f, 0.5f)
                .zIndex(1000f)) 

            // Оновлюємо текст оверлею
            val uiText = tvOrigin.text.toString()
            val isBadText = uiText.contains("Визначення", true) || uiText.contains("Звідки", true) || uiText.contains("...", true) || uiText.isBlank()
            val finalOriginText = if (!isBadText) uiText else (originPlace?.address ?: originPlace?.name ?: "Точка А")
            tvOverlayOrigin.text = finalOriginText
            overlayOrigin.visibility = View.VISIBLE
        }

        // --- 4. ТОЧКА Б ---
        if (destinationPlace != null) {
            destinationMarker = mMap?.addMarker(MarkerOptions()
                .position(destinationPlace!!.latLng!!)
                .icon(getBitmapDescriptor(R.drawable.ic_marker_base_white))
                .anchor(0.5f, 0.5f)
                .zIndex(1000f)) 

            val uiDestText = tvDestination.text.toString()
            val isBadDest = uiDestText.contains("Куди", true) || uiDestText.contains("Визначення", true) || uiDestText.isBlank()
            val finalDestText = if (!isBadDest) uiDestText else (destinationPlace?.address ?: destinationPlace?.name ?: "Точка Б")
            tvOverlayDest.text = finalDestText

            var calculatedDistanceMeters = 0.0
            if (path.size > 1) {
                for (i in 0 until path.size - 1) {
                    calculatedDistanceMeters += SphericalUtil.computeDistanceBetween(path[i], path[i + 1])
                }
            } else {
                calculatedDistanceMeters = routeDistanceMeters.toDouble()
            }
            val km = calculatedDistanceMeters / 1000.0
            val detailsText = String.format("%.1f км", km)
            tvOverlayDestDetails.text = detailsText

            overlayDest.visibility = View.VISIBLE
        }
        
        // Проміжні точки (якщо є)
        val waypointIcon = BitmapHelper.vectorToBitmap(this, R.drawable.ic_waypoint_dot)
        for (wpPair in currentWaypoints) {
            mMap?.addMarker(MarkerOptions()
                .position(wpPair.first)
                .icon(waypointIcon)
                .anchor(0.5f, 0.5f)
                .zIndex(500f)
                .title(wpPair.second))
        }

        // 5. Анимация
        animateRoute(path)
        
        // !!! ВАЖЛИВО: Примусово оновлюємо положення міток після малювання !!!
        // Це виправляє баг, коли мітки не з'являються, якщо карта не рухається
        contentBottomSheet.post { updateSmartLabels() }
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

    private fun clearMapForRoute() {
        mMap?.clear()

        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
             try {
                 mMap?.isMyLocationEnabled = true
             } catch (e: Exception) {}
        }

        originMarker = null // Ссылка сбрасывается
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
        currentRoutePolyline = null
        centerPin.visibility = View.GONE
        try { pinShadow.visibility = View.GONE } catch (e: Exception) {}

        selectedTariffItem = null
        btnOrderTaxi.isEnabled = false
        btnOrderTaxi.text = "Замовити"

        overlayOrigin.visibility = View.GONE
        overlayDest.visibility = View.GONE

        decodedRoutePoints = null
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

        val promoPercent = sessionManager.fetchPromoDiscount()
        val promoLimit = sessionManager.fetchPromoLimit()

        val token = sessionManager.fetchAuthToken()
        if (token != null) {
            ApiClient.instance.getActiveDiscount("Bearer $token").enqueue(object : Callback<ActiveDiscountDto> {
                override fun onResponse(call: Call<ActiveDiscountDto>, response: Response<ActiveDiscountDto>) {
                    var finalPercent = promoPercent
                    var finalLimit = promoLimit
                    
                    if (response.isSuccessful && response.body() != null) {
                        val taskDiscount = response.body()!!
                        if (taskDiscount.percent > finalPercent) {
                            finalPercent = taskDiscount.percent
                            finalLimit = taskDiscount.maxAmount ?: 0.0
                        }
                    }

                    // --- ТЕПЕРЬ ПРОСТО ПЕРЕДАЕМ В АДАПТЕР (Без управления баннером) ---
                    tariffAdapter.setDiscount(finalPercent, finalLimit)
                    loadTariffs()
                }

                override fun onFailure(call: Call<ActiveDiscountDto>, t: Throwable) {
                    handleLocalPromoFallback(promoPercent, promoLimit)
                }
            })
        } else {
            handleLocalPromoFallback(promoPercent, promoLimit)
        }
    }

    // Этот метод тоже обновляем, чтобы стиль был единым
    private fun handleLocalPromoFallback(percent: Double, limit: Double) {
        // Просто передаем данные в адаптер.
        // Если percent > 0, адаптер сам нарисует значки на тарифах.
        tariffAdapter.setDiscount(percent, limit)
        loadTariffs()
    }
    
    private fun loadTariffs() {
        ApiClient.instance.getActiveTariffs().enqueue(object : Callback<List<CarTariffDto>> {
            override fun onResponse(call: Call<List<CarTariffDto>>, response: Response<List<CarTariffDto>>) {
                tariffsProgressBar.visibility = View.GONE
                if (response.isSuccessful) {
                    availableTariffs = response.body() ?: emptyList()
                    displayTariffs()
                } else {
                    showToast("Помилка тарифів")
                }
            }
            override fun onFailure(call: Call<List<CarTariffDto>>, t: Throwable) {
                tariffsProgressBar.visibility = View.GONE
                showToast("Помилка мережі")
            }
        })
    }

    private fun startStatusBlinking() {
        // Если анимация уже есть и работает - не трогаем
        if (::statusBlinkAnimator.isInitialized && statusBlinkAnimator.isRunning) return

        statusBlinkAnimator = android.animation.ObjectAnimator.ofFloat(orderStatusText, "alpha", 1f, 0.4f, 1f)
        statusBlinkAnimator.duration = 1500 // 1.5 секунды на цикл
        statusBlinkAnimator.repeatCount = android.animation.ValueAnimator.INFINITE
        statusBlinkAnimator.repeatMode = android.animation.ValueAnimator.REVERSE
        statusBlinkAnimator.start()
    }

    private fun stopStatusBlinking() {
        if (::statusBlinkAnimator.isInitialized) {
            statusBlinkAnimator.cancel()
            orderStatusText.alpha = 1f // Возвращаем полную видимость
        }
    }

    private fun updateSmartLabels() {
        if (mMap == null) return
        
        val originLoc = originPlace?.latLng
        val destLoc = destinationPlace?.latLng
        val projection = mMap!!.projection

        // Логика для Точки А (isStart = true)
        if (originLoc != null && overlayOrigin.visibility == View.VISIBLE) {
            val screenPt = projection.toScreenLocation(originLoc)
            // Передаем true (это старт)
            moveViewIdeally(overlayOrigin, screenPt.x.toFloat(), screenPt.y.toFloat(), isStartPoint = true)
        }

        // Логика для Точки Б (isStart = false)
        if (destLoc != null && overlayDest.visibility == View.VISIBLE) {
            val screenPt = projection.toScreenLocation(destLoc)
            // Передаем false (это финиш)
            moveViewIdeally(overlayDest, screenPt.x.toFloat(), screenPt.y.toFloat(), isStartPoint = false)
        }
    }

    private fun moveViewIdeally(view: View, targetX: Float, targetY: Float, isStartPoint: Boolean) {
        val viewWidth = view.width
        val viewHeight = view.height
        val screenWidth = resources.displayMetrics.widthPixels
        
        // 1. Определяем "Соседнюю точку" маршрута, чтобы понять направление линии
        var isRouteGoingUp = false // По умолчанию считаем, что маршрут не мешает сверху

        if (decodedRoutePoints != null && decodedRoutePoints!!.isNotEmpty()) {
            val projection = mMap!!.projection
            val routePoints = decodedRoutePoints!!
            
            // Точка, с которой сравниваем
            val compareLatLng = if (isStartPoint) {
                // Для старта: берем вторую точку маршрута (если есть)
                if (routePoints.size > 1) routePoints[1] else routePoints[0]
            } else {
                // Для финиша: берем предпоследнюю точку
                if (routePoints.size > 1) routePoints[routePoints.size - 2] else routePoints[0]
            }

            // Переводим её в пиксели экрана
            val compareScreenPt = projection.toScreenLocation(compareLatLng)

            // ПРОВЕРКА: Если Y соседней точки МЕНЬШЕ Y нашей точки, значит линия идет ВВЕРХ
            // (в Android координаты Y растут вниз: 0 - это верх экрана)
            if (compareScreenPt.y < targetY) {
                isRouteGoingUp = true
            }
        }

        // 2. Выбираем базовую позицию Y
        // Если маршрут уходит ВВЕРХ -> Ставим метку ПОД точкой
        // Иначе -> Ставим метку НАД точкой (стандарт)
        
        var finalY: Float
        val verticalPadding = convertDpToPixel(8f)

        if (isRouteGoingUp) {
            // Маршрут занимает верх, ставим метку СНИЗУ
            finalY = targetY + verticalPadding
        } else {
            // Маршрут внизу или его нет, ставим метку СВЕРХУ
            finalY = targetY - viewHeight - verticalPadding
        }

        // 3. Вычисляем X (Центрируем)
        var finalX = targetX - (viewWidth / 2)

        // 4. ANTI-CLIP (Защита от краев экрана)
        val margin = convertDpToPixel(16f)

        // Левый край
        if (finalX < margin) {
            finalX = margin
        }
        // Правый край
        if (finalX + viewWidth > screenWidth - margin) {
            finalX = screenWidth - margin - viewWidth
        }

        // 5. Защита ВЕРХА и НИЗА экрана (если мы вытеснили метку за экран)
        val topSafeArea = convertDpToPixel(50f) // Статусбар
        val bottomSafeArea = resources.displayMetrics.heightPixels - convertDpToPixel(150f) // Примерная высота нижней панели

        // Если мы поставили метку сверху, а она вылезла за верх экрана -> ПРИНУДИТЕЛЬНО ВНИЗ
        if (finalY < topSafeArea) {
            finalY = targetY + verticalPadding
        }
        // Если мы поставили метку снизу, а она вылезла под панель -> ПРИНУДИТЕЛЬНО ВВЕРХ
        else if (finalY + viewHeight > bottomSafeArea) {
             finalY = targetY - viewHeight - verticalPadding
        }

        // 6. Применяем
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

    private fun displayTariffs() {
        if (availableTariffs.isEmpty()) {
            showToast("Немає тарифів")
            return
        }

        // --- ВИПРАВЛЕННЯ: НЕ ОЧИЩАЄМО ЦІНИ ТУТ ---
        // Ми видалили рядки tariffCustomPrices.clear() та tariffAdapter.clearCustomPrices()
        // Тому що цей метод викликається в onResume, і він збивав ваші налаштування.

        // Оновлюємо список тарифів
        tariffAdapter.submitList(availableTariffs, routeDistanceMeters)

        // --- ДОДАНО: ВІДНОВЛЮЄМО ЗБЕРЕЖЕНІ НАДБАВКИ ---
        // Якщо у нас вже є збережені зміни цін, ми застосовуємо їх назад до адаптера
        tariffCustomPrices.forEach { (tariffId, addedValue) ->
            tariffAdapter.setCustomPrice(tariffId, addedValue)
        }

    
        // Оновлюємо відступи карти
        tariffsPanel.post {
            updateMapPadding(tariffsPanel, 0f, 10f)
        }

        // Логіка вибору тарифу за замовчуванням (якщо ще не обрано)
        if (selectedTariffItem == null) {
            val defaultTariff = availableTariffs.find { it.name.contains("Standard", ignoreCase = true) } 
                ?: availableTariffs.firstOrNull()

            if (defaultTariff != null) {
                val distKm = routeDistanceMeters / 1000.0
                val priceValue = defaultTariff.basePrice + (distKm * defaultTariff.pricePerKm)
                
                // Перевіряємо, чи є надбавка для дефолтного тарифу
                val added = tariffCustomPrices[defaultTariff.id] ?: 0.0
                val finalPrice = priceValue + added
                val finalPriceString = String.format("%.0f", finalPrice)
                
                val item = TariffItem(defaultTariff, finalPriceString, finalPrice, added) 
                
                selectedTariffItem = item
                tariffAdapter.setSelectedTariffId(defaultTariff.id)
                
                btnOrderTaxi.isEnabled = true
                btnOrderTaxi.text = "Замовити"
            }
        } else {
            // Якщо тариф вже був обраний (ми повернулися з іншого екрану), оновлюємо кнопку
            val item = selectedTariffItem!!
            btnOrderTaxi.isEnabled = true
            btnOrderTaxi.text = "Замовити ${item.priceValue.toInt()} ₴"
        }
    }

    private fun setLocationButtonAnchor(anchorId: Int) {
        val btnLocation = findViewById<View>(R.id.btn_recenter_location)
        val params = btnLocation.layoutParams as RelativeLayout.LayoutParams
        
        // Удаляем старые правила (на всякий случай)
        params.removeRule(RelativeLayout.ABOVE)
        
        // Добавляем новое правило
        params.addRule(RelativeLayout.ABOVE, anchorId)
        
        btnLocation.layoutParams = params
    }
    
    private fun createOrder(tariff: CarTariffDto, price: Double) { 
        val token = sessionManager.fetchAuthToken() ?: return
        btnOrderTaxi.isEnabled = false
        btnOrderTaxi.text = "Замовлення..."
        
        val waypointsDto = currentWaypoints.map { pair ->
            WaypointDto(
                address = pair.second,
                lat = pair.first.latitude,
                lng = pair.first.longitude
            )
        }
        
        // 1. !!! ДОДАНО: Дістаємо збережену надбавку для цього тарифу !!!
        val myAddedValue = tariffCustomPrices[tariff.id] ?: 0.0
        
        val request = CreateOrderRequestDto(
            fromAddress = originPlace!!.name ?: "А",
            toAddress = destinationPlace!!.name ?: "Б",
            tariffId = tariff.id,
            price = price, // Це вже фінальна сума (Тариф + Послуги + Надбавка)
            originLat = originPlace!!.latLng?.latitude,
            originLng = originPlace!!.latLng?.longitude,
            destLat = destinationPlace!!.latLng?.latitude,
            destLng = destinationPlace!!.latLng?.longitude,
            googleRoutePolyline = currentRoutePolyline,
            waypoints = if (waypointsDto.isNotEmpty()) waypointsDto else null,
            distanceMeters = routeDistanceMeters,
            durationSeconds = routeDurationSeconds,
            
            // Передаємо коментар
            comment = if (orderComment.isBlank()) null else orderComment,

            // Передаємо метод оплати
            paymentMethod = currentPaymentMethod,
            serviceIds = selectedServiceIds,
            
            // 2. !!! ДОДАНО: Передаємо надбавку на сервер !!!
            addedValue = myAddedValue
        )
        
        ApiClient.instance.createOrder("Bearer $token", request).enqueue(object : Callback<TaxiOrderDto> {
            override fun onResponse(call: Call<TaxiOrderDto>, response: Response<TaxiOrderDto>) {
                if (response.isSuccessful) {
                    val order = response.body()!!
                    activeOrderId = order.id
                    sessionManager.saveActiveOrderId(order.id)

                    showActiveOrderPanel(order)

                    startStatusPolling()
                } else {
                    showToast("Помилка: ${response.message()}")
                    btnOrderTaxi.isEnabled = true
                    btnOrderTaxi.text = "Спробувати ще раз"
                }
            }
             override fun onFailure(call: Call<TaxiOrderDto>, t: Throwable) {
                 showToast("Помилка мережі")
                 btnOrderTaxi.isEnabled = true
             }
         })
    }

    private fun showPriceAdjustmentDialog() {
        if (selectedTariffItem == null) {
            showToast("Спочатку оберіть тариф")
            return
        }

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.dialog_change_price, null)
        dialog.setContentView(view)

        // Элементы UI
        val tvPrice = view.findViewById<TextView>(R.id.tv_dialog_price)
        val btnMinus = view.findViewById<View>(R.id.btn_price_minus)
        val btnPlus = view.findViewById<View>(R.id.btn_price_plus)
        val seekBar = view.findViewById<android.widget.SeekBar>(R.id.seekbar_price)
        val btnSave = view.findViewById<Button>(R.id.btn_save_price)
        val btnClose = view.findViewById<View>(R.id.btn_close_dialog)

        // --- ГЛАВНАЯ МАТЕМАТИКА ---
        
        val currentTariffId = selectedTariffItem!!.tariff.id
        
        // 1. Получаем полную цену из выбранного тарифа (Тариф + Услуги + УЖЕ СУЩЕСТВУЮЩАЯ НАДБАВКА)
        val currentTotalPrice = selectedTariffItem!!.priceValue
        
        // 2. Получаем старую надбавку, которую мы сохраняли ранее (или 0.0)
        val savedAddedValue = tariffCustomPrices[currentTariffId] ?: 0.0
        
        // 3. Вычисляем ИСТИННУЮ БАЗУ (Чистая цена тарифа с услугами, без торга)
        // Формула: ТекущаяЦена - СтараяНадбавка
        val trueBasePrice = currentTotalPrice - savedAddedValue
        
        // 4. Считаем лимит надбавки (чтобы цена была максимум x3 от базы)
        // Если база 100, макс цена 300. Значит макс надбавка = 200 (это 2 * база)
        val maxAddition = trueBasePrice * 2.0 

        // Функция обновления UI внутри диалога
        fun updateDialogUI(currentAddition: Double) {
            val finalPrice = trueBasePrice + currentAddition
            tvPrice.text = "${finalPrice.toInt()} ₴"
            
            // Расчет позиции ползунка
            // Если currentAddition = 200, а maxAddition = 200 -> progress = 100%
            val progress = if (maxAddition > 0) {
                ((currentAddition / maxAddition) * 100).toInt()
            } else 0
            
            seekBar.progress = progress
            
            // Кнопка минус активна, только если есть надбавка
            btnMinus.isEnabled = currentAddition > 0
            btnMinus.alpha = if (currentAddition > 0) 1.0f else 0.5f
            
            // Кнопка плюс активна, если не достигли максимума
            btnPlus.isEnabled = currentAddition < maxAddition
            btnPlus.alpha = if (currentAddition < maxAddition) 1.0f else 0.5f
        }

        // Инициализация при открытии (показываем текущее состояние)
        updateDialogUI(savedAddedValue)

        // --- СЛУШАТЕЛИ ---

        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Обратная математика: из процентов в деньги
                    val newAddition = (progress / 100.0) * maxAddition
                    
                    // Округляем до 10 грн для красоты
                    val roundedAddition = (Math.round(newAddition / 10.0) * 10).toDouble()
                    
                    // Обновляем только текст, не двигая слайдер (чтобы не дёргался)
                    val finalPrice = trueBasePrice + roundedAddition
                    tvPrice.text = "${finalPrice.toInt()} ₴"
                    
                    // Кнопки обновляем вручную
                    btnMinus.isEnabled = roundedAddition > 0
                    btnMinus.alpha = if (roundedAddition > 0) 1.0f else 0.5f
                }
            }
            override fun onStartTrackingTouch(p0: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(p0: android.widget.SeekBar?) {
                // Когда отпустили палец - жестко синхронизируем UI с округленным значением
                val currentPrice = tvPrice.text.toString().replace(" ₴", "").toDouble()
                val addition = currentPrice - trueBasePrice
                updateDialogUI(addition)
            }
        })

        btnPlus.setOnClickListener {
            val currentPrice = tvPrice.text.toString().replace(" ₴", "").toDouble()
            val currentAddition = currentPrice - trueBasePrice
            
            var newAddition = currentAddition + 10.0
            if (newAddition > maxAddition) newAddition = maxAddition
            
            updateDialogUI(newAddition)
        }

        btnMinus.setOnClickListener {
            val currentPrice = tvPrice.text.toString().replace(" ₴", "").toDouble()
            val currentAddition = currentPrice - trueBasePrice
            
            var newAddition = currentAddition - 10.0
            if (newAddition < 0) newAddition = 0.0
            
            updateDialogUI(newAddition)
        }

        btnSave.setOnClickListener {
            // 1. Берем итоговую цену из текста
            val finalPrice = tvPrice.text.toString().replace(" ₴", "").toDouble()
            
            // 2. Считаем, сколько пользователь надбавил
            val addedValue = finalPrice - trueBasePrice
            
            // 3. Сохраняем в Map
            tariffCustomPrices[currentTariffId] = addedValue
            
            // 4. Обновляем Адаптер
            tariffAdapter.setCustomPrice(currentTariffId, addedValue)
            
            // 5. Обновляем текущий selectedTariffItem в Activity
            // !!! ВИПРАВЛЕННЯ ТУТ: priceString замість price !!!
            selectedTariffItem = selectedTariffItem?.copy(
                priceString = finalPrice.toInt().toString(), // <-- ТУТ БУЛА ПОМИЛКА
                priceValue = finalPrice,
                addedValue = addedValue
            )
            
            // 6. Обновляем кнопку заказа
            btnOrderTaxi.text = "Замовити"
            
            dialog.dismiss()
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }
    
    private fun cancelCurrentOrder() {
        val orderId = activeOrderId ?: return
        val token = sessionManager.fetchAuthToken() ?: return
        btnCancelOrder.isEnabled = false
        btnCancelOrder.text = "Скасування..."
        ApiClient.instance.cancelOrder("Bearer $token", orderId).enqueue(object : Callback<TaxiOrderDto> {
            override fun onResponse(call: Call<TaxiOrderDto>, response: Response<TaxiOrderDto>) {
                if (response.isSuccessful) {
                    showToast("Замовлення скасовано")
                    sessionManager.clearActiveOrderId()
                    activeOrderId = null
                    statusHandler.removeCallbacks(statusRunnable)
                    showAddressPanel()
                    btnCancelOrder.isEnabled = true
                    btnCancelOrder.text = "Скасувати"
                } else {
                    showToast("Помилка скасування")
                    btnCancelOrder.isEnabled = true
                    btnCancelOrder.text = "Скасувати"
                }
            }
            override fun onFailure(call: Call<TaxiOrderDto>, t: Throwable) {
                showToast("Помилка мережі")
                btnCancelOrder.isEnabled = true
                btnCancelOrder.text = "Скасувати"
            }
        })
    }

    private fun showActiveOrderPanel(order: TaxiOrderDto) {
        // 1. Видимость
        findViewById<View>(R.id.active_order_card).visibility = View.VISIBLE
        findViewById<View>(R.id.tariffs_panel).visibility = View.GONE
        findViewById<View>(R.id.btn_menu).visibility = View.GONE

        setLocationButtonAnchor(R.id.active_order_card)
        updateMapPadding(activeOrderCard, 0f, 20f)

        try { btnOpenPromo.visibility = View.GONE } catch (e: Exception) {}

        // 2. Цена
        tvActiveOrderPrice.text = String.format("%.0f ₴", order.price)

        // 3. Оплата
        if (order.paymentMethod == "CARD") {
            ivActiveOrderPayment.setImageResource(R.drawable.ic_card)
        } else {
            ivActiveOrderPayment.setImageResource(R.drawable.ic_cash)
        }

        // 4. Тариф (Название + Иконка)
        tvOrderTariffName.text = order.tariffName

        val ivOrderTariffIcon = findViewById<ImageView>(R.id.iv_order_tariff_icon)

        // Ищем тариф в списке availableTariffs по названию, чтобы взять URL картинки
        val matchingTariff = availableTariffs.find { it.name == order.tariffName }
        
        val iconUrl = matchingTariff?.iconUrl 

        if (iconUrl != null && iconUrl.isNotEmpty()) {
            ivOrderTariffIcon.imageTintList = null // Убираем серый цвет, чтобы иконка была цветной
            
            var finalUrl = iconUrl
            if (finalUrl.contains("localhost")) finalUrl = finalUrl.replace("localhost", "10.0.2.2")

            Glide.with(this)
                .load(finalUrl)
                .placeholder(R.drawable.ic_taxi_model_standard)
                .error(R.drawable.ic_taxi_model_standard)
                .into(ivOrderTariffIcon)
        } else {
            // Если иконки нет - ставим заглушку и красим в серый
            ivOrderTariffIcon.setImageResource(R.drawable.ic_taxi_model_standard)
            ivOrderTariffIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
        }

        // 5. Послуги (ОНОВЛЕНО: Відображаємо назви)
        if (order.services.isNotEmpty()) {
            tvOrderServices.visibility = View.VISIBLE
            // Беремо назви з об'єктів і склеюємо через кому
            val servicesText = order.services.joinToString(separator = ", ") { it.name }
            tvOrderServices.text = "+ $servicesText"
        } else if (!order.serviceIds.isNullOrEmpty()) {
            // Фолбек для старої версії (якщо прийшли тільки ID)
            tvOrderServices.visibility = View.VISIBLE
            tvOrderServices.text = "+ Додаткові послуги"
        } else {
            tvOrderServices.visibility = View.GONE
        }

        // 6. Коментар
        if (order.comment != null && order.comment.isNotEmpty()) {
            tvOrderComment.visibility = View.VISIBLE
            tvOrderComment.text = "Коментар: ${order.comment}"
        } else {
            tvOrderComment.visibility = View.GONE
        }

        updateStatusUI(order)
    }
    
    private fun startStatusPolling() { statusHandler.post(statusRunnable) }
    
    private fun checkOrderStatus() {
        val id = activeOrderId ?: return
        val token = sessionManager.fetchAuthToken() ?: return
        ApiClient.instance.getOrder("Bearer $token", id).enqueue(object : Callback<TaxiOrderDto> {
            override fun onResponse(call: Call<TaxiOrderDto>, response: Response<TaxiOrderDto>) {
                if (response.isSuccessful && response.body() != null) {
                    updateStatusUI(response.body()!!)
                }
            }
            override fun onFailure(call: Call<TaxiOrderDto>, t: Throwable) {}
        })
    }
    
    private fun updateStatusUI(order: TaxiOrderDto) {
        // Скидаємо колір тексту на стандартний
        orderStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        
        // Скидаємо видимість кнопки (за замовчуванням показуємо для REQUESTED/ACCEPTED)
        btnCancelOrder.visibility = View.VISIBLE
        btnCancelOrder.isEnabled = true
        btnCancelOrder.text = "Скасувати"

        layoutActiveOrderPrice.visibility = View.VISIBLE 

        when(order.status) {
            "REQUESTED" -> {
                orderStatusText.text = "Пошук водія..."
                startStatusBlinking()

                layoutSearchDetails.visibility = View.VISIBLE
                layoutDriverDetails.visibility = View.GONE
            }
            
            "ACCEPTED" -> {
                stopStatusBlinking()
                orderStatusText.text = "Водій їде до вас"

                layoutSearchDetails.visibility = View.GONE
                layoutDriverDetails.visibility = View.VISIBLE
                
                updateDriverInfo(order)
            }

            // --- ЗМІНИ ТУТ ---
            "DRIVER_ARRIVED" -> {
                stopStatusBlinking()
                orderStatusText.text = "Водій на місці" 

                layoutSearchDetails.visibility = View.GONE
                layoutDriverDetails.visibility = View.VISIBLE
                
                // Ховаємо кнопку (клієнт вже не може просто так скасувати)
                btnCancelOrder.visibility = View.GONE 

                updateDriverInfo(order)
            }

            "IN_PROGRESS" -> {
                stopStatusBlinking()
                orderStatusText.text = "В дорозі"

                layoutActiveOrderPrice.visibility = View.GONE 

                layoutSearchDetails.visibility = View.GONE
                layoutDriverDetails.visibility = View.VISIBLE
                
                // Ховаємо кнопку
                btnCancelOrder.visibility = View.GONE 

                updateDriverInfo(order)
            }
            
            "COMPLETED" -> {
                stopStatusBlinking()
                orderStatusText.text = "Поїздку завершено"
                
                layoutActiveOrderPrice.visibility = View.GONE
                layoutSearchDetails.visibility = View.GONE
                layoutDriverDetails.visibility = View.GONE
                
                // Ховаємо кнопку (поїздка вже завершена)
                btnCancelOrder.visibility = View.GONE
                
                sessionManager.clearActiveOrderId()
                statusHandler.removeCallbacks(statusRunnable)
                Handler(Looper.getMainLooper()).postDelayed({ showAddressPanel() }, 4000)
            }
            
            "CANCELLED" -> {
                stopStatusBlinking()
                orderStatusText.text = "Скасовано"
                orderStatusText.setTextColor(Color.RED)
                
                layoutActiveOrderPrice.visibility = View.GONE
                layoutSearchDetails.visibility = View.GONE
                layoutDriverDetails.visibility = View.GONE
                
                // Тут кнопку теж можна сховати або залишити (щоб візуально було видно кінець)
                // Залишимо, бо після скасування ми перекидаємо на головну через таймер
                btnCancelOrder.visibility = View.GONE
                
                sessionManager.clearActiveOrderId()
                statusHandler.removeCallbacks(statusRunnable)
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

            if (!drv.photoUrl.isNullOrEmpty()) {
                var finalUrl = drv.photoUrl!!
                if (finalUrl.contains("localhost")) finalUrl = finalUrl.replace("localhost", "10.0.2.2")

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
                // 1. Вимірюємо реальну висоту панелі
                val panelHeight = bottomPanel.height
                if (panelHeight == 0) return@post

                // 2. Рахуємо фізичний відступ (щоб підняти логотип Google)
                val extraBuffer = convertDpToPixel(extraBottomDp).toInt()
                val totalBottomPadding = panelHeight + extraBuffer
                val topPadding = convertDpToPixel(topPaddingDp).toInt()

                // 3. Встановлюємо Padding карті
                // Логотип Google буде закріплений НАД панеллю (як ви і хотіли)
                mMap?.setPadding(0, topPadding, 0, totalBottomPadding)

                // 4. Логіка відображення маршруту
                if (currentRoutePolyline != null) {
                    try {
                        val boundsBuilder = LatLngBounds.Builder()
                        if (originPlace != null && destinationPlace != null) {
                            boundsBuilder.include(originPlace!!.latLng!!)
                            boundsBuilder.include(destinationPlace!!.latLng!!)
                            
                            // Додаємо всі точки
                            currentWaypoints.forEach { boundsBuilder.include(it.first) }
                            decodedRoutePoints?.forEach { boundsBuilder.include(it) }

                            // !!! ГОЛОВНА ЗМІНА ТУТ !!!
                            // Було 30f -> Ставимо 80f (або 90f).
                            // Це "безпечний відступ" всередині видимої зони.
                            // Тепер точки не будуть прилипати до панелі тарифів, 
                            // і місця вистачить для відображення Smart Labels.
                            val labelSafePadding = convertDpToPixel(80f).toInt()

                            // Анімуємо камеру (без всяких scrollBy/zoomBy, просто коректні межі)
                            mMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), labelSafePadding))
                            
                            btnRecenterRoute.visibility = View.GONE
                        }
                    } catch (e: Exception) {}
                }
            }
        }
    }

    private fun showAddressPanel() {
        creationPanelCard.visibility = View.VISIBLE
        activeOrderCard.visibility = View.GONE
        addressPanel.visibility = View.VISIBLE
        tariffsPanel.visibility = View.GONE
        btnRecenter.visibility = View.VISIBLE
        
        setLocationButtonAnchor(R.id.bottom_sheet_card)
        btnMenu.visibility = View.VISIBLE
        
        mMap?.setPadding(0, 0, 0, 0)
        
        try { btnOpenPromo.visibility = View.VISIBLE } catch (e: Exception) {}

        ivMenuIcon.setImageResource(R.drawable.ic_menu_hamburger)
        
        clearMapForRoute() 
        
        sessionManager.clearActiveOrderId()
        tariffAdapter.submitList(emptyList(), 0)

        // --- ДОДАНО: СКИДАННЯ ВСІХ ДАНИХ ЗАМОВЛЕННЯ ---
        // 1. Очищаємо надбавки
        tariffCustomPrices.clear()
        tariffAdapter.clearCustomPrices()
        
        // 2. Очищаємо послуги
        selectedServiceIds.clear()
        servicesExtraCost = 0.0
        tariffAdapter.updateExtraCost(0.0)

        // 3. Очищаємо коментар
        orderComment = ""
        updateCommentIconState()
        // ----------------------------------------------

        tvOrigin.text = "Звідки?"
        tvDestination.text = "Куди?"
        originPlace = null
        destinationPlace = null

        centerPin.visibility = View.VISIBLE
        centerPin.translationY = convertDpToPixel(-48f)
        try { 
            pinShadow.visibility = View.VISIBLE 
            pinShadow.alpha = 0.3f
            pinShadow.scaleX = 0.6f
            pinShadow.scaleY = 0.6f
        } catch(e: Exception){}

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
    
    private fun resetUI() {
        // ... твой код сброса карты ...
    
        // Сброс цен
        tariffCustomPrices.clear()
        tariffAdapter.clearCustomPrices()
    }
    private fun showCitySelectorDialog() { val intent = Intent(this, CityPickerActivity::class.java); cityPickerLauncher.launch(intent) }
    override fun onCreateOptionsMenu(menu: Menu?): Boolean { return true }
    override fun onOptionsItemSelected(item: MenuItem): Boolean { return true }
}