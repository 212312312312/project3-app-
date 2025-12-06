package com.taxiapp.client

import android.Manifest
import android.app.Activity
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.BounceInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
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
import com.taxiapp.client.utils.ViewUtils
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Locale

class HomeActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var sessionManager: SessionManager
    private var mMap: GoogleMap? = null

    private val animHandler = Handler(Looper.getMainLooper())
    private val statusHandler = Handler(Looper.getMainLooper())

    // --- ЗМІННІ ДЛЯ ПОВІДОМЛЕННЯ ---
    private lateinit var customToastContainer: CardView
    private lateinit var tvToastMessage: TextView
    private lateinit var ivToastIcon: ImageView
    private val toastHandler = Handler(Looper.getMainLooper())
    private val hideToastRunnable = Runnable { hideTopMessage() }

    // --- МАРШРУТ ---
    private var polylineBorder: Polyline? = null
    private var polylineMain: Polyline? = null
    private var polylineAnim: Polyline? = null
    private var routeAnimator: ValueAnimator? = null
    
    // ЗУПИНКИ: Pair(Координати, Назва)
    private val currentWaypoints = mutableListOf<Pair<LatLng, String>>()

    // --- UI ---
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var btnMenu: CardView
    private lateinit var ivMenuIcon: ImageView

    private lateinit var profileUserName: TextView
    private lateinit var profileBtnDetails: CardView
    private lateinit var profileCityText: TextView
    private lateinit var profileBtnCity: CardView
    private lateinit var profileThemeSwitch: SwitchCompat
    private lateinit var profileBtnFavorites: CardView

    private lateinit var btnOpenPromo: CardView
    private lateinit var centerPin: ImageView
    private lateinit var pinShadow: ImageView
    private lateinit var btnRecenter: CardView

    private lateinit var containerOrigin: LinearLayout
    private lateinit var tvOrigin: TextView
    private lateinit var containerDestination: LinearLayout
    private lateinit var tvDestination: TextView

    private lateinit var creationPanelCard: View
    private lateinit var addressPanel: LinearLayout
    private lateinit var tariffsPanel: LinearLayout
    private lateinit var tariffsProgressBar: ProgressBar
    private lateinit var btnOrderTaxi: Button
    private lateinit var tariffsRecyclerView: RecyclerView
    private lateinit var tariffAdapter: TariffAdapter

    private lateinit var cvDiscountBanner: CardView
    private lateinit var tvDiscountBannerText: TextView

    private lateinit var btnFavHome: CardView
    private lateinit var indicatorAddHome: ImageView
    private lateinit var btnFavWork: CardView
    private lateinit var indicatorAddWork: ImageView

    private lateinit var activeOrderCard: View
    private lateinit var orderStatusText: TextView
    private lateinit var statusProgressBar: ProgressBar
    private lateinit var driverInfoLayout: LinearLayout
    private lateinit var btnCancelOrder: Button

    private lateinit var tvCarModel: TextView
    private lateinit var tvCarPlate: TextView
    private lateinit var tvDriverName: TextView
    private lateinit var btnCallDriver: android.widget.ImageButton

    private lateinit var tvOrderTariffName: TextView
    private lateinit var tvOrderPrice: TextView
    private lateinit var ivOrderTariffIcon: ImageView

    // Дані
    private var originPlace: Place? = null
    private var destinationPlace: Place? = null
    private var routeDistanceMeters: Int = 0
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

    private val statusRunnable = object : Runnable {
        override fun run() {
            checkOrderStatus()
            statusHandler.postDelayed(this, 3000)
        }
    }

    // --- LAUNCHERS ---
    private val profileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            currentCity = sessionManager.fetchUserCity()
            currentCity?.let {
                val latLng = LatLng(it.lat, it.lng)
                mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, it.zoom))
            }
            resetUI()
        }
    }

    private val userDetailsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            profileUserName.text = sessionManager.getUserName()
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
                
                // 1. Оновлення Точки А
                if (data.hasExtra(AddressPickerActivity.RESULT_ORIGIN_LAT)) {
                    val originName = data.getStringExtra(AddressPickerActivity.RESULT_ORIGIN_NAME)
                    val originLat = data.getDoubleExtra(AddressPickerActivity.RESULT_ORIGIN_LAT, 0.0)
                    val originLng = data.getDoubleExtra(AddressPickerActivity.RESULT_ORIGIN_LNG, 0.0)
                    
                    originPlace = Place.builder().setName(originName).setLatLng(LatLng(originLat, originLng)).build()
                    tvOrigin.text = AddressUtils.formatAddress(originName ?: "")
                }
                
                // 2. Обробка зупинок
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

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                try {
                    mMap?.isMyLocationEnabled = true
                    detectCityAndMove()
                } catch (e: SecurityException) {}
            } else {
                showCitySelectorDialog()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ViewUtils.makeImmersive(this) } catch (e: Exception) { e.printStackTrace() }
        setContentView(R.layout.activity_home)

        sessionManager = SessionManager(applicationContext)

        // --- ВСТАВТЕ ВАШ КЛЮЧ ---
        val myApiKey = "AIzaSyCcKH30fg81bqdUs62QzOBhmpy8hCOHNkI" 
        // -----------------------

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

        val savedOrderId = sessionManager.fetchActiveOrderId()
        if (savedOrderId != -1L) {
            activeOrderId = savedOrderId
            showActiveOrderPanel()
            startStatusPolling()
        } else if (currentCity == null) {
            checkPermissionsAndAutoDetectCity()
        }
    }

    override fun onResume() {
        super.onResume()
        updateFavoriteButtonsUI()
    }

    private fun initUI() {
        drawerLayout = findViewById(R.id.drawer_layout)
        btnMenu = findViewById(R.id.btn_menu)
        ivMenuIcon = btnMenu.getChildAt(0) as ImageView

        centerPin = findViewById(R.id.center_pin)
        val shadowView = findViewById<ImageView>(R.id.pin_shadow)
        pinShadow = shadowView ?: centerPin 
        btnRecenter = findViewById(R.id.btn_recenter_location)

        customToastContainer = findViewById(R.id.custom_toast_container)
        tvToastMessage = findViewById(R.id.tv_toast_message)
        ivToastIcon = findViewById(R.id.iv_toast_icon)

        profileUserName = findViewById(R.id.profile_user_name)
        profileBtnDetails = findViewById(R.id.btn_open_profile_details)
        profileCityText = findViewById(R.id.profile_current_city)
        profileBtnCity = findViewById(R.id.profile_btn_change_city)
        profileThemeSwitch = findViewById(R.id.profile_switch_theme)
        profileBtnFavorites = findViewById(R.id.btn_open_favorites)
        
        try {
            btnOpenPromo = findViewById(R.id.btn_open_promo)
            btnOpenPromo.setOnClickListener { startActivity(Intent(this, PromoActivity::class.java)) }
        } catch (e: Exception) { }

        creationPanelCard = findViewById(R.id.bottom_sheet_card)
        addressPanel = findViewById(R.id.address_panel)
        tariffsPanel = findViewById(R.id.tariffs_panel)
        tariffsProgressBar = findViewById(R.id.tariffs_progress_bar)
        btnOrderTaxi = findViewById(R.id.btn_order_taxi)

        cvDiscountBanner = findViewById(R.id.cv_discount_banner)
        tvDiscountBannerText = findViewById(R.id.tv_discount_banner_text)

        containerOrigin = findViewById(R.id.container_origin)
        tvOrigin = findViewById(R.id.text_view_origin)
        containerDestination = findViewById(R.id.container_destination)
        tvDestination = findViewById(R.id.text_view_destination)

        tariffsRecyclerView = findViewById(R.id.tariffs_recycler_view)

        btnFavHome = findViewById(R.id.btn_fav_home)
        indicatorAddHome = findViewById(R.id.indicator_add_home)
        btnFavWork = findViewById(R.id.btn_fav_work)
        indicatorAddWork = findViewById(R.id.indicator_add_work)

        activeOrderCard = findViewById(R.id.active_order_card)
        orderStatusText = findViewById(R.id.order_status_text)
        statusProgressBar = findViewById(R.id.status_progress_bar)
        driverInfoLayout = findViewById(R.id.driver_info_layout)
        btnCancelOrder = findViewById(R.id.btn_cancel_order)

        tvCarModel = findViewById(R.id.tv_car_model)
        tvCarPlate = findViewById(R.id.tv_car_plate)
        tvDriverName = findViewById(R.id.tv_driver_name)
        btnCallDriver = findViewById(R.id.btn_call_driver)

        tvOrderTariffName = findViewById(R.id.tv_order_tariff_name)
        tvOrderPrice = findViewById(R.id.tv_order_price)
        ivOrderTariffIcon = findViewById(R.id.iv_order_tariff_icon)

        btnMenu.setOnClickListener {
            if (tariffsPanel.visibility == View.VISIBLE) {
                showAddressPanel()
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        btnRecenter.setOnClickListener {
            if (currentCity != null) recenterMapOnUser() else checkPermissionsAndAutoDetectCity()
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
                createOrder(selectedTariffItem!!.tariff, selectedTariffItem!!.priceValue)
            } else {
                showToast("Оберіть тариф")
            }
        }

        btnCancelOrder.setOnClickListener {
            cancelCurrentOrder()
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
        val isError = msg.contains("Помилка", true) || msg.contains("Error", true) || msg.contains("недоступний")
        showTopMessage(msg, isError)
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
        indicatorAddHome.visibility = if (home != null) View.GONE else View.VISIBLE
        indicatorAddWork.visibility = if (work != null) View.GONE else View.VISIBLE
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            detectCityAndMove()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
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

    private fun setupProfileLogic() {
        profileUserName.text = sessionManager.getUserName()
        profileBtnDetails.setOnClickListener { userDetailsLauncher.launch(Intent(this, UserDetailsActivity::class.java)) }
        profileCityText.text = currentCity?.name ?: "Не обрано"
        profileBtnCity.setOnClickListener { drawerLayout.closeDrawer(GravityCompat.START); showCitySelectorDialog() }
        profileBtnFavorites.setOnClickListener { drawerLayout.closeDrawer(GravityCompat.START); startActivity(Intent(this, FavoriteAddressesActivity::class.java)) }

        profileThemeSwitch.isChecked = sessionManager.isDarkMode()
        profileThemeSwitch.setOnCheckedChangeListener { _, isChecked ->
            sessionManager.saveThemeMode(isChecked)
            if (isChecked) AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
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
        tariffsRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        if (sessionManager.isDarkMode()) {
            try { mMap?.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style_dark)) } catch (e: Exception) {}
        } else {
            try { mMap?.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style_standard)) } catch (e: Exception) { mMap?.setMapStyle(null) }
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
        mMap?.setOnCameraMoveStartedListener {
            if (currentRoutePolyline == null) {
                tvOrigin.text = "Визначення..."
                centerPin.animate().translationY(convertDpToPixel(-32f)).setInterpolator(AccelerateDecelerateInterpolator()).setDuration(250).start()
                try { pinShadow.animate().scaleX(0.6f).scaleY(0.6f).alpha(0.3f).setDuration(250).start() } catch (e: Exception) {}
            }
        }
        mMap?.setOnCameraIdleListener {
            if (currentRoutePolyline == null) {
                val center = mMap!!.cameraPosition.target
                getAddressForOrigin(center)
                centerPin.animate().translationY(convertDpToPixel(-32f)).setInterpolator(BounceInterpolator()).setDuration(500).start()
                try { pinShadow.animate().scaleX(1.0f).scaleY(1.0f).alpha(0.5f).setDuration(250).start() } catch (e: Exception) {}
            }
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
                
                Handler(Looper.getMainLooper()).post { tvOrigin.text = addressName; originPlace = Place.builder().setName(addressName).setLatLng(latLng).build() }
            } catch (e: Exception) { Handler(Looper.getMainLooper()).post { tvOrigin.text = "Точка на карті" } }
        }.start()
    }

    // --- ROUTE + ANIMATION ---
    private fun tryDrawRoute() {
        if (originPlace == null || destinationPlace == null) return
        clearMapForRoute()
        
        val originLatLng = originPlace!!.latLng!!
        val destinationLatLng = destinationPlace!!.latLng!!
        
        val iconA = BitmapHelper.createCustomMarkerFromLayout(this, cleanAddress(originPlace!!.name ?: "А"), false)
        mMap?.addMarker(MarkerOptions().position(originLatLng).icon(iconA).anchor(0.5f, 0.85f))
        
        // ЗУПИНКИ
        val waypointIcon = BitmapHelper.vectorToBitmap(this, R.drawable.ic_waypoint_dot)
        for (wpPair in currentWaypoints) {
            mMap?.addMarker(MarkerOptions().position(wpPair.first).icon(waypointIcon).anchor(0.5f, 0.5f).title(wpPair.second))
        }
        
        val originApiString = "${originLatLng.latitude},${originLatLng.longitude}"
        val destApiString = "${destinationLatLng.latitude},${destinationLatLng.longitude}"
        
        // Передаємо зупинки в API
        val waypointsString = if (currentWaypoints.isNotEmpty()) {
            "optimize:false|" + currentWaypoints.joinToString("%7C") { "${it.first.latitude},${it.first.longitude}" }
        } else {
            null
        }
        
        val myApiKey = "AIzaSyCcKH30fg81bqdUs62QzOBhmpy8hCOHNkI" 
        
        DirectionsApiClient.instance.getDirections(originApiString, destApiString, waypointsString, myApiKey).enqueue(object : Callback<DirectionsResponse> {
            override fun onResponse(call: Call<DirectionsResponse>, response: Response<DirectionsResponse>) {
                if (response.isSuccessful && response.body()?.routes?.isNotEmpty() == true) {
                    val route = response.body()!!.routes[0]
                    currentRoutePolyline = route.overviewPolyline.points
                    drawStylishRoute(PolyUtil.decode(currentRoutePolyline))
                    
                    val boundsBuilder = LatLngBounds.Builder()
                    boundsBuilder.include(originLatLng)
                    boundsBuilder.include(destinationLatLng)
                    currentWaypoints.forEach { boundsBuilder.include(it.first) }
                    mMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 300))
                    
                    var totalDistance = 0L
                    for (leg in route.legs) {
                        totalDistance += leg.distance.meters
                    }
                    routeDistanceMeters = totalDistance.toInt()
                    val distanceText = String.format(Locale.US, "%.1f км", routeDistanceMeters / 1000.0)
                    
                    val iconB = BitmapHelper.createCustomMarkerFromLayout(this@HomeActivity, cleanAddress(destinationPlace!!.name ?: "Б"), true, distanceText, true)
                    mMap?.addMarker(MarkerOptions().position(destinationLatLng).icon(iconB).anchor(0.5f, 0.85f))
                    
                    fetchTariffsAndShowPanel()
                } else showToast("Маршрут не знайдено")
            }
            override fun onFailure(call: Call<DirectionsResponse>, t: Throwable) { showToast("Помилка мережі") }
        })
    }
    
    private fun drawStylishRoute(path: List<LatLng>) {
        val borderOpts = PolylineOptions().addAll(path).width(20f).color(ContextCompat.getColor(this, R.color.route_border)).startCap(RoundCap()).endCap(RoundCap()).zIndex(1f)
        polylineBorder = mMap?.addPolyline(borderOpts)
        val mainOpts = PolylineOptions().addAll(path).width(14f).color(ContextCompat.getColor(this, R.color.route_main)).startCap(RoundCap()).endCap(RoundCap()).zIndex(2f)
        polylineMain = mMap?.addPolyline(mainOpts)
        animateRoute(path)
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
        routeAnimator?.removeAllListeners()
        routeAnimator?.cancel()
        routeAnimator = null
        animHandler.removeCallbacksAndMessages(null)

        polylineBorder = null
        polylineMain = null
        polylineAnim = null
        currentRoutePolyline = null
        centerPin.visibility = View.GONE
        try { pinShadow.visibility = View.GONE } catch (e: Exception) {}

        selectedTariffItem = null
        btnOrderTaxi.isEnabled = false
        btnOrderTaxi.text = "Замовити"
    }

    // --- NETWORKING (ОНОВЛЕНИЙ МЕТОД) ---
    private fun fetchTariffsAndShowPanel() {
        addressPanel.visibility = View.GONE
        tariffsPanel.visibility = View.VISIBLE
        tariffsProgressBar.visibility = View.VISIBLE
        tariffAdapter.submitList(emptyList(), 0)
        ivMenuIcon.setImageResource(android.R.drawable.ic_menu_revert)
        
        val token = sessionManager.fetchAuthToken()
        if (token != null) {
             ApiClient.instance.getActiveDiscount("Bearer $token").enqueue(object : Callback<ActiveDiscountDto> {
                 override fun onResponse(call: Call<ActiveDiscountDto>, response: Response<ActiveDiscountDto>) {
                     if (response.isSuccessful && response.body() != null) {
                         val discount = response.body()!!.percent
                         tariffAdapter.setDiscount(discount)
                         if (discount > 0.0) {
                             cvDiscountBanner.visibility = View.VISIBLE
                             tvDiscountBannerText.text = "🎉 Діє ${discount.toInt()}% знижка на поїздку!"
                         } else {
                             cvDiscountBanner.visibility = View.GONE
                         }
                     }
                     loadTariffs()
                 }
                 override fun onFailure(call: Call<ActiveDiscountDto>, t: Throwable) { loadTariffs() }
             })
        } else {
            loadTariffs()
        }
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
    
    private fun displayTariffs() {
        if (availableTariffs.isEmpty()) {
            showToast("Немає тарифів")
            return
        }
        tariffAdapter.submitList(availableTariffs, routeDistanceMeters)
    }
    
    // !!! ОНОВЛЕНИЙ createOrder З ПІДТРИМКОЮ WAYPOINTS !!!
    private fun createOrder(tariff: CarTariffDto, price: Double) { 
        val token = sessionManager.fetchAuthToken() ?: return
        btnOrderTaxi.isEnabled = false
        btnOrderTaxi.text = "Замовлення..."
        
        if (selectedTariffItem != null) {
            tvOrderTariffName.text = selectedTariffItem!!.tariff.name
            val formattedPrice = "${price.toInt()} ₴"
            tvOrderPrice.text = formattedPrice
            var iconUrl = selectedTariffItem!!.tariff.iconUrl
            if (iconUrl != null) {
                 if (iconUrl.contains("localhost")) iconUrl = iconUrl.replace("localhost", "192.168.0.104")
                 if (!iconUrl.startsWith("http")) iconUrl = "http://192.168.0.104:8080$iconUrl"
            }
            if (!iconUrl.isNullOrEmpty()) {
                Glide.with(this).load(iconUrl).placeholder(R.drawable.ic_car_marker_info).into(ivOrderTariffIcon)
            } else {
                ivOrderTariffIcon.setImageResource(R.drawable.ic_car_marker_info)
            }
        }
        
        // Перетворюємо зупинки в DTO
        val waypointsDto = currentWaypoints.map { pair ->
            WaypointDto(
                address = pair.second,
                lat = pair.first.latitude,
                lng = pair.first.longitude
            )
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
            googleRoutePolyline = currentRoutePolyline,
            // Передаємо зупинки на сервер
            waypoints = if (waypointsDto.isNotEmpty()) waypointsDto else null
        )
        
        ApiClient.instance.createOrder("Bearer $token", request).enqueue(object : Callback<TaxiOrderDto> {
             override fun onResponse(call: Call<TaxiOrderDto>, response: Response<TaxiOrderDto>) {
                 if (response.isSuccessful) {
                     val order = response.body()!!
                     activeOrderId = order.id
                     sessionManager.saveActiveOrderId(order.id)
                     showActiveOrderPanel()
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

    private fun showActiveOrderPanel() { 
        creationPanelCard.visibility = View.GONE
        activeOrderCard.visibility = View.VISIBLE
        ivMenuIcon.setImageResource(R.drawable.ic_menu_hamburger)
        btnMenu.visibility = View.GONE 
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
        orderStatusText.setTextColor(Color.BLACK)
        btnCancelOrder.visibility = View.GONE
        val formattedPrice = "${order.price.toInt()} ₴"
        tvOrderPrice.text = formattedPrice
        
        when(order.status) {
            "REQUESTED" -> { 
                orderStatusText.text = "Пошук водія..."
                orderStatusText.setTextColor(Color.BLUE)
                statusProgressBar.visibility = View.VISIBLE
                driverInfoLayout.visibility = View.GONE
                btnCancelOrder.visibility = View.VISIBLE 
            }
            "ACCEPTED" -> { 
                orderStatusText.text = "Водій їде!"
                orderStatusText.setTextColor(Color.parseColor("#FFA500"))
                statusProgressBar.visibility = View.VISIBLE
                driverInfoLayout.visibility = View.VISIBLE
                order.driver?.let { drv -> 
                    tvCarModel.text = drv.carModel ?: "Авто"
                    tvCarPlate.text = drv.carPlateNumber ?: "---"
                    tvDriverName.text = "Водій: ${drv.fullName}"
                    activeOrderCard.tag = drv.phoneNumber 
                } 
            }
            "IN_PROGRESS" -> { 
                orderStatusText.text = "В дорозі"
                orderStatusText.setTextColor(Color.GREEN)
                statusProgressBar.visibility = View.INVISIBLE
                driverInfoLayout.visibility = View.VISIBLE 
            }
            "COMPLETED" -> { 
                orderStatusText.text = "Завершено"
                driverInfoLayout.visibility = View.GONE
                sessionManager.clearActiveOrderId()
                statusHandler.removeCallbacks(statusRunnable)
                Handler(Looper.getMainLooper()).postDelayed({ showAddressPanel() }, 4000) 
            }
            "CANCELLED" -> { 
                orderStatusText.text = "Скасовано"
                orderStatusText.setTextColor(Color.RED)
                sessionManager.clearActiveOrderId()
                statusHandler.removeCallbacks(statusRunnable)
                Handler(Looper.getMainLooper()).postDelayed({ showAddressPanel() }, 3000) 
            }
        }
    }
    
    private fun showAddressPanel() { 
        creationPanelCard.visibility = View.VISIBLE; activeOrderCard.visibility = View.GONE; addressPanel.visibility = View.VISIBLE; tariffsPanel.visibility = View.GONE; 
        btnMenu.visibility = View.VISIBLE; ivMenuIcon.setImageResource(R.drawable.ic_menu_hamburger); clearMapForRoute(); centerPin.visibility = View.VISIBLE; try{pinShadow.visibility=View.VISIBLE}catch(e:Exception){}; tvOrigin.text = "Звідки?"; tvDestination.text = "Куда?"; originPlace = null; destinationPlace = null; sessionManager.clearActiveOrderId(); tariffAdapter.submitList(emptyList(), 0); originPlace?.latLng?.let { mMap?.moveCamera(CameraUpdateFactory.newLatLng(it)) } 
    }
    private fun resetUI() { showAddressPanel(); tvOrigin.text = "Звідки?"; originPlace = null }
    private fun showCitySelectorDialog() { val intent = Intent(this, CityPickerActivity::class.java); cityPickerLauncher.launch(intent) }
    override fun onCreateOptionsMenu(menu: Menu?): Boolean { return true }
    override fun onOptionsItemSelected(item: MenuItem): Boolean { return true }
}