package com.taxiapp.client

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.location.Geocoder
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.BounceInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatImageView
import androidx.cardview.widget.CardView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.libraries.places.api.model.Place
import com.taxiapp.client.utils.AddressUtils
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class FavoriteAddressesActivity : BaseActivity() {

    private lateinit var sessionManager: SessionManager

    private lateinit var tvHomeTitle: TextView
    private lateinit var tvHomeAddress: TextView
    private lateinit var tvWorkTitle: TextView
    private lateinit var tvWorkAddress: TextView

    private var isEditingHome = true
    private var activeMapDialog: Dialog? = null
    private var activeMapView: MapView? = null
    private var geocodeJob: Job? = null

    private val addressPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data!!
            val action = data.getStringExtra(AddressPickerActivity.RESULT_ACTION)

            if (action == "place") {
                val name = data.getStringExtra(AddressPickerActivity.RESULT_NAME)
                val lat = data.getDoubleExtra(AddressPickerActivity.RESULT_LAT, 0.0)
                val lng = data.getDoubleExtra(AddressPickerActivity.RESULT_LNG, 0.0)

                saveAndUpdateAddress(name, lat, lng)
            } else if (action == "map_click") {
                showMapPickerDialog()
            }
        }
    }

    private fun showMapPickerDialog() {
        cleanupMapDialog()

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        activeMapDialog = dialog

        val density = resources.displayMetrics.density
        val dp = { value: Int -> (value * density).toInt() }

        val fontMontserrat = ResourcesCompat.getFont(this, R.font.montserrat)
        val fontMontserratBold = ResourcesCompat.getFont(this, R.font.montserrat_bold)

        dialog.window?.let { window ->
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            val isDark = sessionManager.isDarkMode()
            val controller = WindowInsetsControllerCompat(window, window.decorView)

            if (isDark) {
                window.navigationBarColor = Color.BLACK
                controller.isAppearanceLightNavigationBars = false
            } else {
                window.navigationBarColor = Color.WHITE
                controller.isAppearanceLightNavigationBars = true
            }
        }

        val rootLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            clipChildren = false
            clipToPadding = false
        }

        val mapView = MapView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        activeMapView = mapView
        rootLayout.addView(mapView)

        val pinContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            clipChildren = false
            clipToPadding = false
        }

        val pinShadow = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(32), dp(16), Gravity.CENTER)
            setImageResource(R.drawable.ic_pin_shadow)
            alpha = 0.5f
        }
        pinContainer.addView(pinShadow)

        val centerPin = AppCompatImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER)
            setImageResource(R.drawable.ic_avatar_custom)
            translationY = -32f * density
            elevation = 4f * density
            setColorFilter(getColor(R.color.taxi_yellow))
        }
        pinContainer.addView(centerPin)
        rootLayout.addView(pinContainer)

        val btnBackCard = CardView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48)).apply {
                setMargins(dp(16), dp(50), 0, 0)
            }
            radius = 12f * density
            cardElevation = 6f * density
            setCardBackgroundColor(getColor(R.color.card_background))
            isClickable = true
            isFocusable = true
        }
        val ivBackIcon = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setImageResource(R.drawable.ic_arrow_back_black)
            setColorFilter(getColor(R.color.text_primary))
        }
        btnBackCard.addView(ivBackIcon)

        btnBackCard.setOnClickListener {
            val intent = Intent(this@FavoriteAddressesActivity, AddressPickerActivity::class.java).apply {
                if (isEditingHome) {
                    putExtra(AddressPickerActivity.EXTRA_PICKER_MODE, AddressPickerActivity.MODE_SAVE_HOME)
                } else {
                    putExtra(AddressPickerActivity.EXTRA_PICKER_MODE, AddressPickerActivity.MODE_SAVE_WORK)
                }
                putExtra(AddressPickerActivity.EXTRA_HIDE_MY_LOCATION, true)

                val currentCity = sessionManager.fetchUserCity()
                if (currentCity != null) {
                    putExtra("city_lat", currentCity.lat)
                    putExtra("city_lng", currentCity.lng)
                }
            }
            addressPickerLauncher.launch(intent)
            dialog.dismiss()
        }
        rootLayout.addView(btnBackCard)

        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180), Gravity.BOTTOM)
            setBackgroundResource(R.drawable.bg_bottom_sheet_top_rounded)
            elevation = 16f * density
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val tvLabel = TextView(this).apply {
            text = getString(R.string.map_selected_point)
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            letterSpacing = 0.1f
            typeface = fontMontserrat
            paint.isFakeBoldText = true
        }
        textContainer.addView(tvLabel)

        val tvAddress = TextView(this).apply {
            text = getString(R.string.map_searching_address)
            textSize = 18f
            setTextColor(getColor(R.color.text_primary))
            typeface = fontMontserratBold
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
        }
        textContainer.addView(tvAddress)
        bottomPanel.addView(textContainer)

        val btnConfirmContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)).apply {
                topMargin = dp(8)
            }
            setBackgroundResource(R.drawable.bg_button_yellow)
        }

        val btnConfirm = Button(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            text = getString(R.string.btn_confirm)
            isAllCaps = false
            textSize = 18f
            letterSpacing = 0.05f
            typeface = fontMontserrat
            setTextColor(Color.parseColor("#454754"))
            setBackgroundColor(Color.TRANSPARENT)
            stateListAnimator = null
            elevation = 0f
        }
        btnConfirmContainer.addView(btnConfirm)
        bottomPanel.addView(btnConfirmContainer)
        rootLayout.addView(bottomPanel)

        dialog.setContentView(rootLayout)

        // Обов'язкове звільнення ресурсів Google MapView при закритті діалогу (фікс витоку пам'яті)
        dialog.setOnDismissListener {
            geocodeJob?.cancel()
            try {
                mapView.onPause()
                mapView.onStop()
                mapView.onDestroy()
            } catch (_: Exception) {}
            if (activeMapView === mapView) activeMapView = null
            if (activeMapDialog === dialog) activeMapDialog = null
        }

        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        mapView.getMapAsync { googleMap ->
            if (sessionManager.isDarkMode()) {
                try { googleMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style_dark)) } catch (_: Exception) {}
            } else {
                try { googleMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style_standard)) } catch (_: Exception) {}
            }

            googleMap.uiSettings.isZoomControlsEnabled = false
            googleMap.uiSettings.isMyLocationButtonEnabled = false
            googleMap.setPadding(0, 0, 0, dp(180))

            val currentCity = sessionManager.fetchUserCity()
            val centerLatLng = currentCity?.let { LatLng(it.lat, it.lng) } ?: LatLng(50.4501, 30.5234)
            val zoom = currentCity?.zoom ?: 12f
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(centerLatLng, zoom))

            var selectedLatLng = centerLatLng

            googleMap.setOnCameraMoveStartedListener { reason ->
                if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                    geocodeJob?.cancel()
                    tvAddress.text = getString(R.string.map_searching_address)

                    centerPin.animate().cancel()
                    centerPin.animate()
                        .translationY(-48f * density)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .setDuration(250)
                        .start()

                    pinShadow.animate().cancel()
                    pinShadow.animate()
                        .scaleX(0.6f)
                        .scaleY(0.6f)
                        .alpha(0.3f)
                        .setDuration(250)
                        .start()
                }
            }

            googleMap.setOnCameraIdleListener {
                val target = googleMap.cameraPosition.target
                selectedLatLng = target
                tvAddress.text = getString(R.string.map_searching_address)

                centerPin.animate().cancel()
                centerPin.animate()
                    .translationY(-32f * density)
                    .setInterpolator(BounceInterpolator())
                    .setDuration(500)
                    .start()

                pinShadow.animate().cancel()
                pinShadow.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .alpha(0.5f)
                    .setDuration(250)
                    .start()

                geocodeJob?.cancel()
                geocodeJob = lifecycleScope.launch(Dispatchers.IO) {
                    val addressResult = com.taxiapp.client.utils.ReverseGeocoderHelper.resolveAddress(
                        context = this@FavoriteAddressesActivity,
                        latLng = target,
                        languageCode = sessionManager.getLanguage()
                    )

                    withContext(Dispatchers.Main) {
                        tvAddress.text = addressResult?.takeIf { it.isNotBlank() } ?: getString(R.string.point_on_map)
                    }
                }
            }

            btnConfirm.setOnClickListener {
                val finalAddress = tvAddress.text.toString()
                if (finalAddress != getString(R.string.map_searching_address) && finalAddress.isNotEmpty()) {
                    saveAndUpdateAddress(finalAddress, selectedLatLng.latitude, selectedLatLng.longitude)
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    private fun cleanupMapDialog() {
        geocodeJob?.cancel()
        activeMapView?.let { mv ->
            try {
                mv.onPause()
                mv.onStop()
                mv.onDestroy()
            } catch (_: Exception) {}
        }
        activeMapView = null
        activeMapDialog?.let { dlg ->
            if (dlg.isShowing) {
                try { dlg.dismiss() } catch (_: Exception) {}
            }
        }
        activeMapDialog = null
    }

    private fun saveAndUpdateAddress(name: String?, lat: Double, lng: Double) {
        val place = Place.builder()
            .setName(name)
            .setLatLng(LatLng(lat, lng))
            .build()

        if (isEditingHome) {
            sessionManager.saveHomeAddress(place)
        } else {
            sessionManager.saveWorkAddress(place)
        }

        updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ViewUtils.makeImmersive(this) } catch (_: Exception) {}
        setContentView(R.layout.activity_favorite_addresses)

        sessionManager = SessionManager(applicationContext)

        tvHomeTitle = findViewById(R.id.tv_home_title)
        tvHomeAddress = findViewById(R.id.tv_home_address)
        tvWorkTitle = findViewById(R.id.tv_work_title)
        tvWorkAddress = findViewById(R.id.tv_work_address)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<View>(R.id.card_home).setOnClickListener {
            isEditingHome = true
            openPicker()
        }

        findViewById<View>(R.id.card_work).setOnClickListener {
            isEditingHome = false
            openPicker()
        }

        updateUI()
    }

    override fun onDestroy() {
        cleanupMapDialog()
        super.onDestroy()
    }

    private fun updateUI() {
        val home = sessionManager.getHomeAddress()
        val work = sessionManager.getWorkAddress()

        if (home != null) {
            tvHomeTitle.text = "Дім"
            tvHomeAddress.text = AddressUtils.formatAddress(home.name ?: "")
            tvHomeAddress.setTextColor(getColor(R.color.text_secondary))
        } else {
            tvHomeTitle.text = getString(R.string.title_add_home)
            tvHomeAddress.text = getString(R.string.hint_provide_address)
            tvHomeAddress.setTextColor(getColor(R.color.text_hint))
        }

        if (work != null) {
            tvWorkTitle.text = "Робота"
            tvWorkAddress.text = AddressUtils.formatAddress(work.name ?: "")
            tvWorkAddress.setTextColor(getColor(R.color.text_secondary))
        } else {
            tvWorkTitle.text = getString(R.string.title_add_work)
            tvWorkAddress.text = getString(R.string.hint_provide_address)
            tvWorkAddress.setTextColor(getColor(R.color.text_hint))
        }
    }

    private fun openPicker() {
        val intent = Intent(this, AddressPickerActivity::class.java)

        if (isEditingHome) {
            intent.putExtra(AddressPickerActivity.EXTRA_PICKER_MODE, AddressPickerActivity.MODE_SAVE_HOME)
        } else {
            intent.putExtra(AddressPickerActivity.EXTRA_PICKER_MODE, AddressPickerActivity.MODE_SAVE_WORK)
        }

        intent.putExtra(AddressPickerActivity.EXTRA_HIDE_MY_LOCATION, true)

        val currentCity = sessionManager.fetchUserCity()
        if (currentCity != null) {
            intent.putExtra("city_lat", currentCity.lat)
            intent.putExtra("city_lng", currentCity.lng)
        }

        addressPickerLauncher.launch(intent)
    }
}