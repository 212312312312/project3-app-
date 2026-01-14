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
import com.taxiapp.client.utils.ViewUtils
import java.util.Locale

class AddressPickerActivity : AppCompatActivity() {

    companion object {

        const val MODE_STANDARD = 0
        const val MODE_SAVE_HOME = 1
        const val MODE_SAVE_WORK = 2
        const val EXTRA_PICKER_MODE = "picker_mode"
        const val EXTRA_IS_ORIGIN = "is_origin"
        const val EXTRA_HIDE_MY_LOCATION = "hide_my_location"
        const val EXTRA_CURRENT_ADDRESS = "current_address"

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
    private var cityLat: Double = 50.4501
    private var cityLng: Double = 30.5234

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

        // --- ВСТАВТЕ ВАШ КЛЮЧ ---
        val myApiKey = "AIzaSyDp1blRHORukZ08uYYpvh52fN0mGe7Rnu4"
        // -----------------------

        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, myApiKey, Locale("uk", "UA"))
        }
        placesClient = Places.createClient(this)
        sessionToken = AutocompleteSessionToken.newInstance()

        isOrigin = intent.getBooleanExtra(EXTRA_IS_ORIGIN, false)
        val shouldHideLocation = intent.getBooleanExtra(EXTRA_HIDE_MY_LOCATION, false)
        val currentAddressA = intent.getStringExtra(EXTRA_CURRENT_ADDRESS)

        val latParam = intent.getDoubleExtra("city_lat", 0.0)
        val lngParam = intent.getDoubleExtra("city_lng", 0.0)
        if (latParam != 0.0 && lngParam != 0.0) {
            cityLat = latParam
            cityLng = lngParam
        }

        initUI(isOrigin, shouldHideLocation, currentAddressA)
    }

    private fun initUI(isOriginMode: Boolean, hideMyLocation: Boolean, currentAddressA: String?) {
        rvSuggestions = findViewById(R.id.places_recycler_view)
        rvSuggestions.layoutManager = LinearLayoutManager(this)
        adapter = PlacesAdapter { suggestion ->
            fetchPlaceDetails(suggestion.placeId, suggestion.title)
        }
        rvSuggestions.adapter = adapter

        etOrigin = findViewById(R.id.et_origin)
        etDestination = findViewById(R.id.et_destination)

        containerWaypoints = findViewById(R.id.container_waypoints)
        rowDestination = findViewById(R.id.row_destination) // Это поле "Куда"

        // Находим контейнеры полей для скрытия/показа
        val containerOriginLayout = findViewById<LinearLayout>(R.id.container_origin_layout) // Нужно добавить ID в XML!
        // Но пока используем родителя etOrigin, если он есть, или просто скроем ненужное

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

        // --- ЛОГИКА РЕЖИМОВ ---
        val mode = intent.getIntExtra(EXTRA_PICKER_MODE, MODE_STANDARD)

        when (mode) {
            MODE_SAVE_HOME -> {
                title.text = "Додати дім"
                configureSingleFieldMode(etDestination, R.drawable.ic_home_custom, "Введіть адресу дому")
            }
            MODE_SAVE_WORK -> {
                title.text = "Додати роботу"
                configureSingleFieldMode(etDestination, R.drawable.ic_work_custom, "Введіть адресу роботи")
            }
            else -> {
                // Стандартный режим (Заказ такси)
                title.text = if (isOriginMode) "Звідки їдемо?" else "Куди їдемо?"
                configureStandardMode(isOriginMode, currentAddressA)
            }
        }

        // Общая логика кнопок
        btnMyLocation.setOnClickListener { detectMyLocation() }
        btnPickOnMap.setOnClickListener {
            val intent = Intent(this, MapPickerActivity::class.java)
            intent.putExtra("start_lat", cityLat); intent.putExtra("start_lng", cityLng)
            mapPickerLauncher.launch(intent)
        }

        btnAddWaypoint.setOnClickListener {
            if (waypointViews.size < 3) addWaypointInput()
            else Toast.makeText(this, "Максимум 3 зупинки", Toast.LENGTH_SHORT).show()
        }

        updateButtonsVisibility(hideMyLocation)
    }

    // --- НОВЫЙ ВСПОМОГАТЕЛЬНЫЙ МЕТОД ---
    private fun configureSingleFieldMode(targetEt: EditText, iconRes: Int, hint: String) {
        // 1. Скрываем верхний блок "Откуда"
        // Ищем контейнер по ID, а если не нашли (старый XML) - скрываем родителя поля
        val originContainer = findViewById<View>(R.id.container_origin_layout)
            ?: etOrigin.parent as View

        originContainer.visibility = View.GONE

        // Скрываем линии и лишние элементы
        lineOriginDown.visibility = View.GONE
        lineDestUp.visibility = View.GONE
        containerWaypoints.visibility = View.GONE

        // 2. Настраиваем поле "Куда" (оно будет единственным)
        rowDestination.visibility = View.VISIBLE
        activeEditText = targetEt
        targetEt.requestFocus()
        targetEt.hint = hint

        // 3. Меняем иконку (Лупу/Маркер -> Дом/Работа)
        // Ищем по новому ID iv_dest_icon, который мы добавим в XML
        val iconView = findViewById<ImageView>(R.id.iv_dest_icon)

        if (iconView != null) {
            iconView.setImageResource(iconRes)
            // Красим иконку в основной цвет текста (черный/белый)
            iconView.setColorFilter(androidx.core.content.ContextCompat.getColor(this, R.color.text_primary))
        }
    }

    private fun configureStandardMode(isOriginMode: Boolean, currentAddressA: String?) {
        if (isOriginMode) {
            activeEditText = etOrigin
            etOrigin.requestFocus()
            etOrigin.setText("")

            lineOriginDown.visibility = View.GONE
            lineDestUp.visibility = View.GONE
            rowDestination.visibility = View.GONE
            containerWaypoints.visibility = View.GONE
        } else {
            activeEditText = etDestination
            etDestination.requestFocus()
            etOrigin.setText(AddressUtils.formatAddress(currentAddressA ?: "Поточне місце"))

            etOrigin.isEnabled = true
            etOrigin.isFocusable = true
            etOrigin.isFocusableInTouchMode = true

            lineOriginDown.visibility = View.VISIBLE
            lineDestUp.visibility = View.VISIBLE
            rowDestination.visibility = View.VISIBLE
            containerWaypoints.visibility = View.VISIBLE
        }
    }

    // --- ПОШУК ---
    private fun performSearch(query: String) {
        val center = LatLng(cityLat, cityLng)
        val radiusKm = 50.0

        val latRadian = Math.toRadians(center.latitude)
        val degLat = radiusKm / 111.0
        val degLng = radiusKm / (111.0 * Math.cos(latRadian))
        val bounds = RectangularBounds.newInstance(
            LatLng(center.latitude - degLat, center.longitude - degLng),
            LatLng(center.latitude + degLat, center.longitude + degLng)
        )

        val request = FindAutocompletePredictionsRequest.builder()
            .setSessionToken(sessionToken)
            .setQuery(query)
            .setCountries("UA")
            .setLocationRestriction(bounds)
            .setOrigin(center)
            .build()

        placesClient.findAutocompletePredictions(request)
            .addOnSuccessListener { response ->
                val suggestions = mutableListOf<PlaceSuggestion>()

                for (prediction in response.autocompletePredictions) {
                    val distance = prediction.distanceMeters
                    val rawAddress = prediction.getSecondaryText(null).toString()

                    // Фільтруємо міста і країни
                    val types = prediction.placeTypes
                    val isGeneral = types.any { it == Place.Type.LOCALITY || it == Place.Type.COUNTRY }
                    if (isGeneral) continue

                    suggestions.add(
                        PlaceSuggestion(
                            placeId = prediction.placeId,
                            title = prediction.getPrimaryText(null).toString(),
                            subtitle = AddressUtils.formatAddress(rawAddress),
                            distanceMeters = distance
                        )
                    )
                }

                if (::adapter.isInitialized) {
                    adapter.submitList(suggestions.sortedBy { it.distanceMeters ?: Int.MAX_VALUE })
                }
            }
            .addOnFailureListener { }
    }

    // ... (Решта методів без змін) ...

    private fun setupFocusListener(editText: EditText) {
        editText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                activeEditText = editText
                updateButtonsVisibility(intent.getBooleanExtra(EXTRA_HIDE_MY_LOCATION, false))
                if (editText.text.isEmpty()) adapter.submitList(emptyList())
                else { layoutQuickActions.visibility = View.GONE; performSearch(editText.text.toString()) }
            }
        }
        editText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (editText.hasFocus()) {
                    val query = s.toString()
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

    private fun toggleQuickActions(show: Boolean) { val visibility = if (show) View.VISIBLE else View.GONE; if (isOrigin && activeEditText == etOrigin && !intent.getBooleanExtra(EXTRA_HIDE_MY_LOCATION, false)) { btnMyLocation.visibility = visibility } else { btnMyLocation.visibility = View.GONE }; btnPickOnMap.visibility = visibility }
    private fun updateButtonsVisibility(hideMyLocation: Boolean) { if (activeEditText?.text.isNullOrEmpty()) { layoutQuickActions.visibility = View.VISIBLE; if (activeEditText == etOrigin && !hideMyLocation) btnMyLocation.visibility = View.VISIBLE else btnMyLocation.visibility = View.GONE; btnPickOnMap.visibility = View.VISIBLE } else { layoutQuickActions.visibility = View.GONE } }
    private fun addWaypointInput() { val view = layoutInflater.inflate(R.layout.item_waypoint_input, containerWaypoints, false); val etWaypoint = view.findViewById<EditText>(R.id.et_waypoint); val btnRemove = view.findViewById<ImageView>(R.id.btn_remove_waypoint); setupFocusListener(etWaypoint); btnRemove.setOnClickListener { fieldCoordinates.remove(etWaypoint); containerWaypoints.removeView(view); waypointViews.remove(view); checkAddButtonState() }; containerWaypoints.addView(view); waypointViews.add(view); activeEditText = etWaypoint; etWaypoint.requestFocus(); checkAddButtonState() }
    private fun checkAddButtonState() { val isMax = waypointViews.size >= 3; btnAddWaypoint.alpha = if (isMax) 0.3f else 1.0f; btnAddWaypoint.isEnabled = !isMax }
    private fun fetchPlaceDetails(placeId: String, placeName: String) { val placeFields = listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS); val request = FetchPlaceRequest.builder(placeId, placeFields).setSessionToken(sessionToken).build(); placesClient.fetchPlace(request).addOnSuccessListener { response -> val place = response.place; val latLng = place.latLng; var fullText = ""; val name = place.name; val address = place.address; if (!name.isNullOrEmpty()) fullText += name; if (!address.isNullOrEmpty()) { if (name != null && !address.startsWith(name)) { if (fullText.isNotEmpty()) fullText += ", "; fullText += address } else if (name == null) { fullText = address } }; val finalName = AddressUtils.formatAddress(fullText); if (latLng != null && activeEditText != null) { saveCoordinateForField(activeEditText!!, finalName, latLng); sessionToken = AutocompleteSessionToken.newInstance(); if (isFinishConditionMet()) returnResultData() } }.addOnFailureListener { Toast.makeText(this, "Помилка деталей", Toast.LENGTH_SHORT).show() } }
    private fun saveCoordinateForField(editText: EditText, name: String, latLng: LatLng) { editText.setText(name); editText.clearFocus(); fieldCoordinates[editText] = latLng }
    private fun isFinishConditionMet(): Boolean { if (isOrigin && activeEditText == etOrigin) return true; if (!isOrigin && activeEditText == etDestination) return true; return false }
    private fun detectMyLocation() { if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001); return }; val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this); fusedLocationClient.lastLocation.addOnSuccessListener { location -> if (location != null) { try { val geocoder = Geocoder(this, Locale("uk", "UA")); val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1); val addressName = if (!addresses.isNullOrEmpty()) AddressUtils.formatAddress(addresses[0].getAddressLine(0)) else "Моє місцезнаходження"; if (activeEditText != null) { saveCoordinateForField(activeEditText!!, addressName, LatLng(location.latitude, location.longitude)); if (isFinishConditionMet()) returnResultData() } } catch (e: Exception) { activeEditText?.setText("Моє місцезнаходження") } } } }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) detectMyLocation() }
    private fun returnResultData() {
        val intent = Intent()
        if (isOrigin) {
            val originLatLng = fieldCoordinates[etOrigin]
            if (originLatLng != null) {
                intent.putExtra(RESULT_ACTION, "place")
                intent.putExtra(RESULT_NAME, etOrigin.text.toString())
                intent.putExtra(RESULT_LAT, originLatLng.latitude)
                intent.putExtra(RESULT_LNG, originLatLng.longitude)
                setResult(Activity.RESULT_OK, intent)
                finish()
            }
        } else {
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

                // Збираємо координати ТА НАЗВИ зупинок
                val wLats = DoubleArray(waypointViews.size)
                val wLngs = DoubleArray(waypointViews.size)
                val wNames = ArrayList<String>() // Список назв
                var hasWaypoints = false

                for (i in waypointViews.indices) {
                    val view = waypointViews[i]
                    val etWaypoint = view.findViewById<EditText>(R.id.et_waypoint)
                    val latLng = fieldCoordinates[etWaypoint]
                    if (latLng != null) {
                        wLats[i] = latLng.latitude
                        wLngs[i] = latLng.longitude
                        // Зберігаємо текст з поля (наприклад "Горенка")
                        wNames.add(etWaypoint.text.toString())
                        hasWaypoints = true
                    } else {
                        // Якщо координат немає, додаємо пустий рядок, щоб індекси збігалися
                        wNames.add("")
                    }
                }

                if (hasWaypoints) {
                    intent.putExtra(RESULT_WAYPOINTS_LATS, wLats)
                    intent.putExtra(RESULT_WAYPOINTS_LNGS, wLngs)
                    intent.putStringArrayListExtra(RESULT_WAYPOINTS_NAMES, wNames) // !!! ПЕРЕДАЄМО НАЗВИ
                }

                setResult(Activity.RESULT_OK, intent)
                finish()
            } else {
                Toast.makeText(this, "Введіть кінцеву адресу", Toast.LENGTH_SHORT).show()
            }
        }
    }
}