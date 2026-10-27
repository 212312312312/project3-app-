package com.taxiapp.client

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
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
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.ui.PlaceSuggestion
import com.taxiapp.client.ui.PlacesAdapter
import com.taxiapp.client.utils.AddressUtils
import com.taxiapp.client.utils.LocationValidator
import com.taxiapp.client.utils.ReverseGeocoderHelper
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class AddressPickerActivity : BaseActivity() {

    companion object {
        const val RESULT_TARGET_TYPE = "result_target_type"
        const val RESULT_WAYPOINT_INDEX = "result_waypoint_index"

        const val TARGET_ORIGIN = "target_origin"
        const val TARGET_DESTINATION = "target_destination"
        const val TARGET_WAYPOINT = "target_waypoint"
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

        private const val MIN_SUGGESTION_DISTANCE_METERS = 2000
    }

    private var isOrigin: Boolean = false

    private var cityLat: Double = 50.4501
    private var cityLng: Double = 30.5234

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
    private var latestSearchQuery: String = ""

    // Флаг защиты от ложного срабатывания TextWatcher при программной установке текста (etOrigin.setText)
    private var isProgrammaticTextChange: Boolean = false

    // Кэш 5–6 популярных адресов и главных улиц (от 2 км) для пустого состояния поля ввода
    private var cachedNearbySuggestions: List<PlaceSuggestion> = emptyList()
    private var nearbySuggestionsJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ViewUtils.makeImmersive(this) } catch (e: Exception) {}
        setContentView(R.layout.activity_address_picker)

        val myApiKey = BuildConfig.GOOGLE_PLACES_API_KEY

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

        val initialAnchor = userLatLng ?: LatLng(cityLat, cityLng)
        // Мгновенно (за 0 мс, еще до отрисовки первого кадра) берем предзагруженные на главном экране улицы
        cachedNearbySuggestions = ReverseGeocoderHelper.getInstantSuggestions(initialAnchor)

        initUI(isOrigin, shouldHideLocation, currentAddressA, currentLatA, currentLngA)

        if (cachedNearbySuggestions.isNotEmpty() && latestSearchQuery.length < 2) {
            adapter.submitList(cachedNearbySuggestions)
        }

        // При необходимости догружаем/обновляем в фоне
        loadNearbySuggestions(initialAnchor)
        refreshUserLocationSilentlyIfNeeded()

        // Обработка Insets для плавного прижатия кнопки к клавиатуре
        val keyboardAccessory = findViewById<View>(R.id.layout_keyboard_accessory)
        ViewCompat.setOnApplyWindowInsetsListener(keyboardAccessory) { view, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            val bottomPadding = if (imeInsets.bottom > 0) {
                imeInsets.bottom
            } else {
                systemBarsInsets.bottom
            }

            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                bottomPadding
            )
            insets
        }
    }

    override fun onDestroy() {
        searchHandler.removeCallbacksAndMessages(null)
        nearbySuggestionsJob?.cancel()
        super.onDestroy()
    }

    private fun setFieldTextSafely(editText: EditText, text: String) {
        isProgrammaticTextChange = true
        try {
            editText.setText(text)
        } finally {
            isProgrammaticTextChange = false
        }
    }

    private fun initUI(isOriginMode: Boolean, hideMyLocation: Boolean, currentAddressA: String?, currentLatA: Double, currentLngA: Double) {
        rvSuggestions = findViewById(R.id.places_recycler_view)
        rvSuggestions.layoutManager = LinearLayoutManager(this)
        adapter = PlacesAdapter { suggestion ->
            onSuggestionClicked(suggestion)
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

        // Сначала предзаполняем поля в configureStandardMode, и только ПОТОМ вешаем слушатели фокуса и текста,
        // чтобы программный etOrigin.setText(currentAddressA) НЕ запускал performSearch() по текущему адресу под ногами!
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

        setupFocusListener(etOrigin)
        setupFocusListener(etDestination)

        // Гарантированно отображаем начальные предложения после настройки полей и фокуса
        showNearbySuggestionsForEmptyField()

        btnMyLocation.setOnClickListener { detectMyLocation() }

        btnPickOnMap.setOnClickListener {
            val intent = Intent()
            intent.putExtra(RESULT_ACTION, "map_click")

            val originLatLng = fieldCoordinates[etOrigin]
            if (originLatLng != null) {
                intent.putExtra(RESULT_ORIGIN_NAME, etOrigin.text.toString())
                intent.putExtra(RESULT_ORIGIN_LAT, originLatLng.latitude)
                intent.putExtra(RESULT_ORIGIN_LNG, originLatLng.longitude)
            }

            val destLatLng = fieldCoordinates[etDestination]
            if (destLatLng != null) {
                intent.putExtra(RESULT_NAME, etDestination.text.toString())
                intent.putExtra(RESULT_LAT, destLatLng.latitude)
                intent.putExtra(RESULT_LNG, destLatLng.longitude)
            }

            if (waypointViews.isNotEmpty()) {
                val wLats = DoubleArray(waypointViews.size)
                val wLngs = DoubleArray(waypointViews.size)
                val wNames = ArrayList<String>()

                for (i in waypointViews.indices) {
                    val view = waypointViews[i]
                    val etWaypoint = view.findViewById<EditText>(R.id.et_waypoint)
                    val latLng = fieldCoordinates[etWaypoint]

                    if (latLng != null) {
                        wLats[i] = latLng.latitude
                        wLngs[i] = latLng.longitude
                    } else {
                        wLats[i] = 0.0
                        wLngs[i] = 0.0
                    }
                    wNames.add(etWaypoint.text.toString())
                }

                intent.putExtra(RESULT_WAYPOINTS_LATS, wLats)
                intent.putExtra(RESULT_WAYPOINTS_LNGS, wLngs)
                intent.putStringArrayListExtra(RESULT_WAYPOINTS_NAMES, wNames)
            }

            val targetType = when (activeEditText?.id) {
                R.id.et_origin -> TARGET_ORIGIN
                R.id.et_destination -> TARGET_DESTINATION
                R.id.et_waypoint -> {
                    val wpIndex = waypointViews.indexOfFirst { it.findViewById<EditText>(R.id.et_waypoint) == activeEditText }
                    intent.putExtra(RESULT_WAYPOINT_INDEX, wpIndex)
                    TARGET_WAYPOINT
                }
                else -> if (isOrigin) TARGET_ORIGIN else TARGET_DESTINATION
            }

            intent.putExtra(RESULT_TARGET_TYPE, targetType)
            setResult(Activity.RESULT_OK, intent)
            finish()
        }

        btnAddWaypoint.setOnClickListener {
            if (waypointViews.size < 3) addWaypointInput()
            else Toast.makeText(this, "Максимум 3 зупинки", Toast.LENGTH_SHORT).show()
        }

        updateButtonsVisibility(hideMyLocation)
    }

    /**
     * Загружает с нашего бэкенда 5–6 популярных адресов и главных улиц (от 2.0 км),
     * если они еще не были предзагружены на главном экране или точка сдвинулась > 350 м.
     */
    private fun loadNearbySuggestions(anchor: LatLng) {
        if (anchor.latitude == 0.0 && anchor.longitude == 0.0) return

        val lang = SessionManager(this).getLanguage().let { if (it == "ua") "uk" else it }
        if (ReverseGeocoderHelper.isCacheFreshFor(anchor, lang) && cachedNearbySuggestions.size >= 4) {
            cachedNearbySuggestions = ReverseGeocoderHelper.getInstantSuggestions(anchor)
            if (latestSearchQuery.length < 2 && ::adapter.isInitialized) {
                adapter.submitList(cachedNearbySuggestions)
            }
            return
        }

        nearbySuggestionsJob?.cancel()
        nearbySuggestionsJob = lifecycleScope.launch {
            try {
                val finalSuggestions = ReverseGeocoderHelper.fetchOrGenerateSuggestions(
                    context = applicationContext,
                    anchor = anchor,
                    lang = lang,
                    limit = 6
                )
                if (!isFinishing && !isDestroyed && finalSuggestions.isNotEmpty()) {
                    cachedNearbySuggestions = finalSuggestions
                    if (latestSearchQuery.length < 2 && ::adapter.isInitialized) {
                        adapter.submitList(cachedNearbySuggestions)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    private fun refreshUserLocationSilentlyIfNeeded() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            LocationServices.getFusedLocationProviderClient(this).lastLocation.addOnSuccessListener { loc ->
                if (!isFinishing && !isDestroyed && loc != null && LocationValidator.isValidLocation(loc.latitude, loc.longitude)) {
                    val freshLatLng = LatLng(loc.latitude, loc.longitude)
                    userLatLng = freshLatLng
                    loadNearbySuggestions(freshLatLng)
                }
            }
        } catch (_: Exception) {}
    }

    private fun showNearbySuggestionsForEmptyField() {
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        latestSearchQuery = ""
        val anchor = userLatLng ?: fieldCoordinates[etOrigin] ?: LatLng(cityLat, cityLng)
        val instant = ReverseGeocoderHelper.getInstantSuggestions(anchor)
        if (instant.isNotEmpty()) {
            cachedNearbySuggestions = instant
        }
        if (::adapter.isInitialized) {
            adapter.submitList(cachedNearbySuggestions)
        }
        if (cachedNearbySuggestions.size < 4) {
            loadNearbySuggestions(anchor)
        }
    }

    private fun onSuggestionClicked(suggestion: PlaceSuggestion) {
        val sLat = suggestion.lat
        val sLng = suggestion.lng
        // Если у подсказки уже есть точные координаты с нашего сервера — применяем мгновенно без платного запроса к Google Places
        if (sLat != null && sLng != null && (sLat != 0.0 || sLng != 0.0)) {
            val finalName = AddressUtils.formatAddress(suggestion.title)
            if (activeEditText != null) {
                saveCoordinateForField(activeEditText!!, finalName, LatLng(sLat, sLng))
                if (isFinishConditionMet()) returnResultData()
            }
            return
        }
        fetchPlaceDetails(suggestion.placeId, suggestion.title)
    }

    private fun configureSingleFieldMode(targetEt: EditText, iconRes: Int, hint: String) {
        val originContainer = findViewById<View>(R.id.container_origin_layout) ?: etOrigin.parent as View
        originContainer.visibility = View.GONE
        lineOriginDown.visibility = View.GONE
        lineDestUp.visibility = View.GONE
        containerWaypoints.visibility = View.GONE

        rowDestination.visibility = View.VISIBLE
        activeEditText = targetEt
        targetEt.hint = hint

        targetEt.requestFocus()
        if (targetEt.text.isNotEmpty()) {
            targetEt.setSelection(targetEt.text.length)
        }

        val iconView = findViewById<ImageView>(R.id.iv_dest_icon)
        try {
            iconView?.setImageResource(iconRes)
            iconView?.setColorFilter(ContextCompat.getColor(this, R.color.text_primary))
        } catch (e: Exception) {}
    }

    private fun configureStandardMode(isOriginMode: Boolean, currentAddressA: String?, latA: Double, lngA: Double) {
        if (isOriginMode) {
            activeEditText = etOrigin

            val addressText = if (!currentAddressA.isNullOrEmpty()) {
                AddressUtils.formatAddress(currentAddressA)
            } else {
                ""
            }
            setFieldTextSafely(etOrigin, addressText)

            if (latA != 0.0 && lngA != 0.0) {
                fieldCoordinates[etOrigin] = LatLng(latA, lngA)
            } else if (addressText.isNotEmpty()) {
                fieldCoordinates[etOrigin] = LatLng(cityLat, cityLng)
            }

            etOrigin.requestFocus()
            if (etOrigin.text.isNotEmpty()) {
                etOrigin.setSelection(etOrigin.text.length)
            }

            lineOriginDown.visibility = View.GONE
            lineDestUp.visibility = View.GONE
            rowDestination.visibility = View.GONE
            containerWaypoints.visibility = View.GONE
        } else {
            activeEditText = etDestination

            val originText = AddressUtils.formatAddress(currentAddressA ?: "Поточне місце")
            setFieldTextSafely(etOrigin, originText)
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

            val prefillDestName = intent.getStringExtra("prefill_dest_name")
            if (!prefillDestName.isNullOrEmpty()) {
                val lat = intent.getDoubleExtra("prefill_dest_lat", 0.0)
                val lng = intent.getDoubleExtra("prefill_dest_lng", 0.0)
                setFieldTextSafely(etDestination, prefillDestName)
                if (lat != 0.0 && lng != 0.0) {
                    fieldCoordinates[etDestination] = LatLng(lat, lng)
                }
            }

            val wLats = intent.getDoubleArrayExtra("prefill_waypoints_lats")
            val wLngs = intent.getDoubleArrayExtra("prefill_waypoints_lngs")
            val wNames = intent.getStringArrayListExtra("prefill_waypoints_names")

            if (wLats != null && wLngs != null && wNames != null) {
                containerWaypoints.removeAllViews()
                waypointViews.clear()

                for (i in wLats.indices) {
                    addWaypointInput()
                    val view = waypointViews.last()
                    val etWaypoint = view.findViewById<EditText>(R.id.et_waypoint)

                    if (i < wNames.size && wNames[i].isNotEmpty()) {
                        setFieldTextSafely(etWaypoint, wNames[i])
                        if (wLats[i] != 0.0) {
                            fieldCoordinates[etWaypoint] = LatLng(wLats[i], wLngs[i])
                            if (!isOriginMode) {
                                containerWaypoints.post {
                                    if (fieldCoordinates.containsKey(etDestination) && isFinishConditionMet()) {
                                        returnResultData()
                                    }
                                }
                            }
                        }
                    }
                }
            }

            etDestination.requestFocus()
            if (etDestination.text.isNotEmpty()) {
                etDestination.setSelection(etDestination.text.length)
            }
        }
    }

    private fun performSearch(query: String) {
        if (isFinishing || isDestroyed) return
        latestSearchQuery = query

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
                if (isFinishing || isDestroyed || query != latestSearchQuery) return@addOnSuccessListener
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
        editText.setOnTouchListener { v, event ->
            val et = v as EditText
            if (!et.hasFocus()) {
                if (event.action == MotionEvent.ACTION_UP) {
                    et.requestFocus()
                    et.setSelection(et.text.length)

                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
                }
                return@setOnTouchListener true
            }
            false
        }

        editText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                activeEditText = editText
                searchRunnable?.let { searchHandler.removeCallbacks(it) }

                if (editText.text.isNotEmpty()) {
                    editText.setSelection(editText.text.length)
                }

                updateButtonsVisibility(intent.getBooleanExtra(EXTRA_HIDE_MY_LOCATION, false))
                if (editText.text.isEmpty() || fieldCoordinates.containsKey(editText)) {
                    showNearbySuggestionsForEmptyField()
                } else {
                    layoutQuickActions.visibility = View.GONE
                    performSearch(editText.text.toString())
                }
            }
        }

        editText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isProgrammaticTextChange) return
                if (editText.hasFocus() && editText == activeEditText) {
                    val query = s.toString().trim()
                    fieldCoordinates.remove(editText)

                    updateButtonsVisibility(intent.getBooleanExtra(EXTRA_HIDE_MY_LOCATION, false))
                    searchRunnable?.let { searchHandler.removeCallbacks(it) }

                    if (query.length >= 2) {
                        searchRunnable = Runnable { performSearch(query) }
                        searchHandler.postDelayed(searchRunnable!!, 300)
                    } else {
                        showNearbySuggestionsForEmptyField()
                    }
                }
            }
        })
    }

    private fun updateButtonsVisibility(hideMyLocation: Boolean) {
        val isEmpty = activeEditText?.text.isNullOrEmpty()

        if (isEmpty) {
            layoutQuickActions.visibility = View.VISIBLE
            if (isOrigin && activeEditText == etOrigin && !hideMyLocation) {
                btnMyLocation.visibility = View.VISIBLE
            } else {
                btnMyLocation.visibility = View.GONE
            }
        } else {
            layoutQuickActions.visibility = View.GONE
        }

        btnPickOnMap.visibility = View.VISIBLE
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

            if (!isOrigin && isFinishConditionMet()) {
                returnResultData()
            }
        }

        containerWaypoints.addView(view)
        waypointViews.add(view)
        activeEditText = etWaypoint
        etWaypoint.requestFocus()
        showNearbySuggestionsForEmptyField()
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
            if (isFinishing || isDestroyed) return@addOnSuccessListener
            val place = response.place
            val latLng = place.latLng
            var fullText = ""
            val name = place.name
            val address = place.address

            if (!address.isNullOrEmpty()) {
                if (!name.isNullOrEmpty() && !address.lowercase().contains(name.lowercase())) {
                    fullText = "$name, $address"
                } else {
                    fullText = address
                }
            } else if (!name.isNullOrEmpty()) {
                fullText = name
            }

            val finalName = AddressUtils.formatAddress(fullText)

            if (latLng != null && activeEditText != null) {
                saveCoordinateForField(activeEditText!!, finalName, latLng)
                sessionToken = AutocompleteSessionToken.newInstance()
                if (isFinishConditionMet()) returnResultData()
            }
        }.addOnFailureListener {
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(this, "Помилка деталей місця", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveCoordinateForField(editText: EditText, name: String, latLng: LatLng) {
        setFieldTextSafely(editText, name)
        editText.clearFocus()
        fieldCoordinates[editText] = latLng
    }

    private fun isFinishConditionMet(): Boolean {
        if (isOrigin) {
            return fieldCoordinates.containsKey(etOrigin)
        } else {
            if (!fieldCoordinates.containsKey(etDestination)) {
                return false
            }

            for (view in waypointViews) {
                val etWaypoint = view.findViewById<EditText>(R.id.et_waypoint)
                if (!fieldCoordinates.containsKey(etWaypoint)) {
                    return false
                }
            }
            return true
        }
    }

    private fun detectMyLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
            return
        }
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (isFinishing || isDestroyed) return@addOnSuccessListener
            if (location != null && LocationValidator.isValidLocation(location.latitude, location.longitude)) {
                val latLng = LatLng(location.latitude, location.longitude)
                userLatLng = latLng

                // Отказоустойчивый геокодинг (системный Geocoder + серверный фоллбэк Nominatim/Redis)
                lifecycleScope.launch(Dispatchers.IO) {
                    val resolvedAddress = com.taxiapp.client.utils.ReverseGeocoderHelper.resolveAddress(
                        context = this@AddressPickerActivity,
                        latLng = latLng
                    ) ?: "Моє місцезнаходження"

                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed && activeEditText != null) {
                            saveCoordinateForField(activeEditText!!, resolvedAddress, latLng)
                            if (isFinishConditionMet()) returnResultData()
                        }
                    }
                }
            } else {
                Toast.makeText(this, "Неточний сигнал GPS (можливий РЕБ). Оберіть адресу зі списку", Toast.LENGTH_SHORT).show()
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
            val originLatLng = fieldCoordinates[etOrigin]

            if (originLatLng != null) {
                intent.putExtra(RESULT_ACTION, "place")
                intent.putExtra(RESULT_NAME, etOrigin.text.toString())
                intent.putExtra(RESULT_LAT, originLatLng.latitude)
                intent.putExtra(RESULT_LNG, originLatLng.longitude)
                setResult(Activity.RESULT_OK, intent)
                finish()
            } else {
                Toast.makeText(this, "Оберіть адресу зі списку", Toast.LENGTH_SHORT).show()
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