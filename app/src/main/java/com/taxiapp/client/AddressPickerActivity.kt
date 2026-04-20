package com.taxiapp.client

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.RectangularBounds
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.taxiapp.client.ui.PlaceSuggestion
import com.taxiapp.client.ui.PlacesAdapter
import com.taxiapp.client.utils.AddressUtils
import android.view.MotionEvent
import android.content.Context
import android.view.inputmethod.InputMethodManager
import com.taxiapp.client.utils.ViewUtils
import java.util.Locale

class AddressPickerActivity : BaseActivity() {

    companion object {
        const val MODE_STANDARD = 0
        const val MODE_SAVE_HOME = 1
        const val MODE_SAVE_WORK = 2
        const val EXTRA_PICKER_MODE = "picker_mode"
        const val EXTRA_IS_ORIGIN = "is_origin"
        const val EXTRA_HIDE_MY_LOCATION = "hide_my_location"
        const val EXTRA_CURRENT_ADDRESS = "current_address"

        const val EXTRA_CURRENT_LAT = "current_lat"
        const val EXTRA_CURRENT_LNG = "current_lng"

        const val RESULT_NAME = "result_name"
        const val RESULT_LAT = "result_lat"
        const val RESULT_LNG = "result_lng"
        const val RESULT_ACTION = "result_action"

        const val RESULT_WAYPOINTS_LATS = "waypoints_lats"
        const val RESULT_WAYPOINTS_LNGS = "waypoints_lngs"
        const val RESULT_ORIGIN_NAME = "updated_origin_name"
        const val RESULT_ORIGIN_LAT = "updated_origin_lat"
        const val RESULT_ORIGIN_LNG = "updated_origin_lng"

        const val RESULT_WAYPOINTS_NAMES = "waypoints_names"
    }

    private var isOrigin: Boolean = false

    // cityLat/Lng - центр области поиска
    private var cityLat: Double = 50.4501
    private var cityLng: Double = 30.5234

    // userLatLng - РЕАЛЬНОЕ положение пользователя (для сортировки по расстоянию)
    private var userLatLng: LatLng? = null

    private lateinit var placesClient: PlacesClient
    private var sessionToken: AutocompleteSessionToken? = null

    private lateinit var adapter: PlacesAdapter
    private lateinit var rvSuggestions: RecyclerView

    private lateinit var etOrigin: EditText
    private lateinit var etDestination: EditText

    private lateinit var containerWaypoints: LinearLayout
    private lateinit var rowDestination: View

    private lateinit var btnAddWaypoint: ImageView
    private lateinit var lineOriginDown: View
    private lateinit var lineDestUp: View

    private lateinit var layoutQuickActions: LinearLayout
    private lateinit var btnMyLocation: View
    private lateinit var btnPickOnMap: View

    private val fieldCoordinates = mutableMapOf<EditText, LatLng>()
    private val waypointViews = mutableListOf<View>()
    private var activeEditText: EditText? = null

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    private val mapPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data!!
            val lat = data.getDoubleExtra("picked_lat", 0.0)
            val lng = data.getDoubleExtra("picked_lng", 0.0)
            val name = data.getStringExtra("picked_name") ?: "Точка на карті"
            val formattedName = AddressUtils.formatAddress(name)

            if (activeEditText != null) {
                saveCoordinateForField(activeEditText!!, formattedName, LatLng(lat, lng))
                if (isFinishConditionMet()) returnResultData()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ViewUtils.makeImmersive(this) } catch (e: Exception) {}
        setContentView(R.layout.activity_address_picker)

        val myApiKey = "AIzaSyDp1blRHORukZ08uYYpvh52fN0mGe7Rnu4"

        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, myApiKey, Locale("uk", "UA"))
        }
        placesClient = Places.createClient(this)
        sessionToken = AutocompleteSessionToken.newInstance()

        isOrigin = intent.getBooleanExtra(EXTRA_IS_ORIGIN, false)
        val shouldHideLocation = intent.getBooleanExtra(EXTRA_HIDE_MY_LOCATION, false)

        val currentAddressA = intent.getStringExtra(EXTRA_CURRENT_ADDRESS)

        val currentLatA = intent.getDoubleExtra(EXTRA_CURRENT_LAT, 0.0)
        val currentLngA = intent.getDoubleExtra(EXTRA_CURRENT_LNG, 0.0)

        if (currentLatA != 0.0 && currentLngA != 0.0) {
            userLatLng = LatLng(currentLatA, currentLngA)
        }

        val latParam = intent.getDoubleExtra("city_lat", 0.0)
        val lngParam = intent.getDoubleExtra("city_lng", 0.0)
        if (latParam != 0.0 && lngParam != 0.0) {
            cityLat = latParam
            cityLng = lngParam
        }

        initUI(isOrigin, shouldHideLocation, currentAddressA, currentLatA, currentLngA)
    }

    private fun initUI(isOriginMode: Boolean, hideMyLocation: Boolean, currentAddressA: String?, currentLatA: Double, currentLngA: Double) {
        rvSuggestions = findViewById(R.id.places_recycler_view)
        rvSuggestions.layoutManager = LinearLayoutManager(this)
        adapter = PlacesAdapter { suggestion ->
            fetchPlaceDetails(suggestion.placeId, suggestion.title)
        }
        rvSuggestions.adapter = adapter

        etOrigin = findViewById(R.id.et_origin)
        etDestination = findViewById(R.id.et_destination)

        containerWaypoints = findViewById(R.id.container_waypoints)
        rowDestination = findViewById(R.id.row_destination)

        btnAddWaypoint = findViewById(R.id.btn_add_waypoint)
        lineOriginDown = findViewById(R.id.line_origin_down)
        lineDestUp = findViewById(R.id.line_dest_up)

        layoutQuickActions = findViewById(R.id.layout_quick_actions)
        btnMyLocation = findViewById(R.id.btn_my_location)
        btnPickOnMap = findViewById(R.id.btn_pick_on_map)

        val title = findViewById<TextView>(R.id.picker_title)
        val btnBack = findViewById<View>(R.id.btn_back)
        btnBack.setOnClickListener { finish() }

        setupFocusListener(etOrigin)
        setupFocusListener(etDestination)

        val mode = intent.getIntExtra(EXTRA_PICKER_MODE, MODE_STANDARD)

        when (mode) {
            MODE_SAVE_HOME -> {
                title.text = getString(R.string.title_add_home)
                configureSingleFieldMode(etDestination, R.drawable.ic_home_custom, getString(R.string.hint_add_home))
            }
            MODE_SAVE_WORK -> {
                title.text = getString(R.string.title_add_work)
                configureSingleFieldMode(etDestination, R.drawable.ic_work_custom, getString(R.string.hint_add_work))
            }
            else -> {
                title.text = if (isOriginMode) getString(R.string.title_where_from) else getString(R.string.picker_title_where_to)
                configureStandardMode(isOriginMode, currentAddressA, currentLatA, currentLngA)
            }
        }

        btnMyLocation.setOnClickListener { detectMyLocation() }
        btnPickOnMap.setOnClickListener {
            val intent = Intent(this, MapPickerActivity::class.java)
            intent.putExtra("start_lat", cityLat)
            intent.putExtra("start_lng", cityLng)
            mapPickerLauncher.launch(intent)
        }

        btnAddWaypoint.setOnClickListener {
            if (waypointViews.size < 3) addWaypointInput()
            else Toast.makeText(this, "Максимум 3 зупинки", Toast.LENGTH_SHORT).show()
        }

        updateButtonsVisibility(hideMyLocation)
    }

    private fun configureSingleFieldMode(targetEt: EditText, iconRes: Int, hint: String) {
        val originContainer = findViewById<View>(R.id.container_origin_layout) ?: etOrigin.parent as View
        originContainer.visibility = View.GONE
        lineOriginDown.visibility = View.GONE
        lineDestUp.visibility = View.GONE
        containerWaypoints.visibility = View.GONE

        rowDestination.visibility = View.VISIBLE
        activeEditText = targetEt
        targetEt.requestFocus()
        targetEt.hint = hint

        val iconView = findViewById<ImageView>(R.id.iv_dest_icon)
        try {
            iconView?.setImageResource(iconRes)
            iconView?.setColorFilter(androidx.core.content.ContextCompat.getColor(this, R.color.text_primary))
        } catch (e: Exception) {}
    }

    private fun configureStandardMode(isOriginMode: Boolean, currentAddressA: String?, latA: Double, lngA: Double) {
        if (isOriginMode) {
            // --- РЕЖИМ ВИБОРУ ТОЧКИ А ---
            activeEditText = etOrigin
            etOrigin.requestFocus()

            // ВАЖЛИВО: Очищаємо текст і координати, щоб не залишилось старого значення
            etOrigin.setText("")
            fieldCoordinates.remove(etOrigin)

            lineOriginDown.visibility = View.GONE
            lineDestUp.visibility = View.GONE
            rowDestination.visibility = View.GONE
            containerWaypoints.visibility = View.GONE
        } else {
            // --- РЕЖИМ ВИБОРУ ТОЧКИ Б ---
            activeEditText = etDestination
            etDestination.requestFocus()

            val addressText = AddressUtils.formatAddress(currentAddressA ?: "Поточне місце")
            etOrigin.setText(addressText)

            // Тут ми навмисно зберігаємо поточну точку А, якщо вона передана
            if (latA != 0.0 && lngA != 0.0) {
                fieldCoordinates[etOrigin] = LatLng(latA, lngA)
            }

            etOrigin.isEnabled = true
            etOrigin.isFocusable = true
            etOrigin.isFocusableInTouchMode = true

            lineOriginDown.visibility = View.VISIBLE
            lineDestUp.visibility = View.VISIBLE
            rowDestination.visibility = View.VISIBLE
            containerWaypoints.visibility = View.VISIBLE
        }
    }

    private fun performSearch(query: String) {
        val searchBiasCenter = LatLng(cityLat, cityLng)
        val radiusKm = 50.0
        val latRadian = Math.toRadians(searchBiasCenter.latitude)
        val degLat = radiusKm / 111.0
        val degLng = radiusKm / (111.0 * Math.cos(latRadian))
        val bounds = RectangularBounds.newInstance(
            LatLng(searchBiasCenter.latitude - degLat, searchBiasCenter.longitude - degLng),
            LatLng(searchBiasCenter.latitude + degLat, searchBiasCenter.longitude + degLng)
        )

        val distanceOrigin = userLatLng ?: searchBiasCenter

        val request = FindAutocompletePredictionsRequest.builder()
            .setSessionToken(sessionToken)
            .setQuery(query)
            .setCountries("UA")
            .setLocationRestriction(bounds)
            .setOrigin(distanceOrigin)
            .build()

        placesClient.findAutocompletePredictions(request)
            .addOnSuccessListener { response ->
                val suggestions = mutableListOf<PlaceSuggestion>()
                for (prediction in response.autocompletePredictions) {
                    val rawAddress = prediction.getSecondaryText(null).toString()
                    val types = prediction.placeTypes
                    val isGeneral = types.any { it == Place.Type.LOCALITY || it == Place.Type.COUNTRY }
                    if (isGeneral) continue

                    suggestions.add(
                        PlaceSuggestion(
                            placeId = prediction.placeId,
                            title = prediction.getPrimaryText(null).toString(),
                            subtitle = AddressUtils.formatAddress(rawAddress),
                            distanceMeters = prediction.distanceMeters
                        )
                    )
                }
                if (::adapter.isInitialized) {
                    adapter.submitList(suggestions.sortedBy { it.distanceMeters ?: Int.MAX_VALUE })
                }
            }
            .addOnFailureListener { }
    }

    private fun setupFocusListener(editText: EditText) {

        // 1. ПЕРЕХВАТЫВАЕМ КАСАНИЕ
        editText.setOnTouchListener { v, event ->
            val et = v as EditText
            // Если поле еще не активно, мы берем обработку клика на себя
            if (!et.hasFocus()) {
                if (event.action == MotionEvent.ACTION_UP) {
                    et.requestFocus()
                    et.setSelection(et.text.length) // Ставим курсор в конец мгновенно

                    // Так как мы перехватили клик, нужно вручную поднять клавиатуру
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
                }
                // Возвращаем true — мы "съели" касание, система не будет ставить курсор по координатам пальца
                return@setOnTouchListener true
            }
            // Если поле УЖЕ в фокусе, возвращаем false.
            // Это позволит тебе нормально кликать в середину текста, чтобы что-то исправить.
            false
        }

        // 2. СЛУШАТЕЛЬ ФОКУСА (оставляем для программной смены фокуса)
        editText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                activeEditText = editText

                // Перенос через post больше не нужен, мы делаем это мгновенно в onTouch
                if (editText.text.isNotEmpty()) {
                    editText.setSelection(editText.text.length)
                }

                updateButtonsVisibility(intent.getBooleanExtra(EXTRA_HIDE_MY_LOCATION, false))
                if (editText.text.isEmpty()) adapter.submitList(emptyList())
                else { layoutQuickActions.visibility = View.GONE; performSearch(editText.text.toString()) }
            }
        }

        // 3. СЛУШАТЕЛЬ ТЕКСТА
        editText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (editText.hasFocus()) {
                    val query = s.toString()

                    // При зміні тексту видаляємо старі координати, щоб не збереглось "старе" місце
                    fieldCoordinates.remove(editText)

                    if (query.isEmpty()) {
                        updateButtonsVisibility(intent.getBooleanExtra(EXTRA_HIDE_MY_LOCATION, false))
                        adapter.submitList(emptyList())
                    } else {
                        layoutQuickActions.visibility = View.GONE
                        if (query.length >= 2) {
                            searchRunnable?.let { searchHandler.removeCallbacks(it) }
                            searchRunnable = Runnable { performSearch(query) }
                            searchHandler.postDelayed(searchRunnable!!, 300)
                        }
                    }
                }
            }
        })
    }

    private fun updateButtonsVisibility(hideMyLocation: Boolean) {
        if (activeEditText?.text.isNullOrEmpty()) {
            layoutQuickActions.visibility = View.VISIBLE
            if (isOrigin && activeEditText == etOrigin && !hideMyLocation) {
                btnMyLocation.visibility = View.VISIBLE
            } else {
                btnMyLocation.visibility = View.GONE
            }
            btnPickOnMap.visibility = View.VISIBLE
        } else {
            layoutQuickActions.visibility = View.GONE
        }
    }

    private fun addWaypointInput() {
        val view = layoutInflater.inflate(R.layout.item_waypoint_input, containerWaypoints, false)
        val etWaypoint = view.findViewById<EditText>(R.id.et_waypoint)
        val btnRemove = view.findViewById<ImageView>(R.id.btn_remove_waypoint)
        setupFocusListener(etWaypoint)
        btnRemove.setOnClickListener {
            fieldCoordinates.remove(etWaypoint)
            containerWaypoints.removeView(view)
            waypointViews.remove(view)
            checkAddButtonState()
        }
        containerWaypoints.addView(view)
        waypointViews.add(view)
        activeEditText = etWaypoint
        etWaypoint.requestFocus()
        checkAddButtonState()
    }

    private fun checkAddButtonState() {
        val isMax = waypointViews.size >= 3
        btnAddWaypoint.alpha = if (isMax) 0.3f else 1.0f
        btnAddWaypoint.isEnabled = !isMax
    }

    private fun fetchPlaceDetails(placeId: String, placeName: String) {
        val placeFields = listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS)
        val request = FetchPlaceRequest.builder(placeId, placeFields).setSessionToken(sessionToken).build()
        placesClient.fetchPlace(request).addOnSuccessListener { response ->
            val place = response.place
            val latLng = place.latLng
            var fullText = ""
            val name = place.name
            val address = place.address
            if (!name.isNullOrEmpty()) fullText += name
            if (!address.isNullOrEmpty()) {
                if (name != null && !address.startsWith(name)) {
                    if (fullText.isNotEmpty()) fullText += ", "
                    fullText += address
                } else if (name == null) {
                    fullText = address
                }
            }
            val finalName = AddressUtils.formatAddress(fullText)

            // Зберігаємо координати ТІЛЬКИ якщо вони прийшли і є активне поле
            if (latLng != null && activeEditText != null) {
                saveCoordinateForField(activeEditText!!, finalName, latLng)
                sessionToken = AutocompleteSessionToken.newInstance()
                if (isFinishConditionMet()) returnResultData()
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Помилка деталей місця", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveCoordinateForField(editText: EditText, name: String, latLng: LatLng) {
        editText.setText(name)
        editText.clearFocus()
        // Головне місце збереження координат
        fieldCoordinates[editText] = latLng
    }

    private fun isFinishConditionMet(): Boolean {
        if (isOrigin && activeEditText == etOrigin) return true
        if (!isOrigin && activeEditText == etDestination) return true
        return false
    }

    private fun detectMyLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
            return
        }
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                userLatLng = LatLng(location.latitude, location.longitude)

                try {
                    val geocoder = Geocoder(this, Locale("uk", "UA"))
                    val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    val addressName = if (!addresses.isNullOrEmpty()) AddressUtils.formatAddress(addresses[0].getAddressLine(0)) else "Моє місцезнаходження"

                    if (activeEditText != null) {
                        saveCoordinateForField(activeEditText!!, addressName, LatLng(location.latitude, location.longitude))
                        if (isFinishConditionMet()) returnResultData()
                    }
                } catch (e: Exception) {
                    if (activeEditText != null) {
                        saveCoordinateForField(activeEditText!!, "Моє місцезнаходження", LatLng(location.latitude, location.longitude))
                        if (isFinishConditionMet()) returnResultData()
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) detectMyLocation()
    }

    private fun returnResultData() {
        val intent = Intent()
        if (isOrigin) {
            // --- ЛОГІКА ДЛЯ РЕЖИМУ "ЗВІДКИ" ---
            // Беремо координати ТІЛЬКИ з мапи fieldCoordinates, куди вони потрапили після вибору (зі списку, карти або GPS)
            val originLatLng = fieldCoordinates[etOrigin]

            if (originLatLng != null) {
                intent.putExtra(RESULT_ACTION, "place")
                intent.putExtra(RESULT_NAME, etOrigin.text.toString())
                intent.putExtra(RESULT_LAT, originLatLng.latitude)
                intent.putExtra(RESULT_LNG, originLatLng.longitude)
                setResult(Activity.RESULT_OK, intent)
                finish()
            } else {
                // Якщо чомусь координати пусті (хоча текст є), не закриваємо, а просимо вибрати знову
                Toast.makeText(this, "Оберіть адресу зі списку", Toast.LENGTH_SHORT).show()
            }
        } else {
            // --- ЛОГІКА ДЛЯ РЕЖИМУ "КУДИ" ---
            val destLatLng = fieldCoordinates[etDestination]
            if (destLatLng != null) {
                intent.putExtra(RESULT_ACTION, "place")
                intent.putExtra(RESULT_NAME, etDestination.text.toString())
                intent.putExtra(RESULT_LAT, destLatLng.latitude)
                intent.putExtra(RESULT_LNG, destLatLng.longitude)

                val originLatLng = fieldCoordinates[etOrigin]
                if (originLatLng != null) {
                    intent.putExtra(RESULT_ORIGIN_NAME, etOrigin.text.toString())
                    intent.putExtra(RESULT_ORIGIN_LAT, originLatLng.latitude)
                    intent.putExtra(RESULT_ORIGIN_LNG, originLatLng.longitude)
                }

                val wLats = DoubleArray(waypointViews.size)
                val wLngs = DoubleArray(waypointViews.size)
                val wNames = ArrayList<String>()
                var hasWaypoints = false

                for (i in waypointViews.indices) {
                    val view = waypointViews[i]
                    val etWaypoint = view.findViewById<EditText>(R.id.et_waypoint)
                    val latLng = fieldCoordinates[etWaypoint]
                    if (latLng != null) {
                        wLats[i] = latLng.latitude
                        wLngs[i] = latLng.longitude
                        wNames.add(etWaypoint.text.toString())
                        hasWaypoints = true
                    } else {
                        wNames.add("")
                    }
                }

                if (hasWaypoints) {
                    intent.putExtra(RESULT_WAYPOINTS_LATS, wLats)
                    intent.putExtra(RESULT_WAYPOINTS_LNGS, wLngs)
                    intent.putStringArrayListExtra(RESULT_WAYPOINTS_NAMES, wNames)
                }

                setResult(Activity.RESULT_OK, intent)
                finish()
            } else {
                Toast.makeText(this, "Введіть кінцеву адресу", Toast.LENGTH_SHORT).show()
            }
        }
    }
}