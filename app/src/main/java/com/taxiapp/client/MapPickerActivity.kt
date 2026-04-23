package com.taxiapp.client

import android.app.Activity
import android.content.Intent
import android.location.Geocoder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.BounceInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.OnMapsSdkInitializedCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils
import java.util.Locale

class MapPickerActivity : BaseActivity() , OnMapReadyCallback, OnMapsSdkInitializedCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var tvAddress: TextView
    private lateinit var btnConfirm: Button
    private lateinit var centerPin: ImageView
    private lateinit var pinShadow: ImageView

    private lateinit var sessionManager: SessionManager

    private var selectedLatLng: LatLng? = null
    private var selectedAddressName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Виправляємо чорну карту
        MapsInitializer.initialize(applicationContext, MapsInitializer.Renderer.LEGACY, this)

        try { ViewUtils.makeImmersive(this) } catch (e: Exception) {}
        setContentView(R.layout.activity_map_picker)

        sessionManager = SessionManager(applicationContext)

        tvAddress = findViewById(R.id.tv_address_result)
        btnConfirm = findViewById(R.id.btn_confirm_location)
        centerPin = findViewById(R.id.center_pin)
        pinShadow = findViewById(R.id.pin_shadow)

        // Кнопка назад
        val btnBackCard = findViewById<View>(R.id.btn_back_card)
        if (btnBackCard != null) {
            btnBackCard.setOnClickListener { finish() }
        } else {
            findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        }

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        btnConfirm.setOnClickListener {
            if (selectedLatLng != null) {
                val intent = Intent()
                intent.putExtra("picked_lat", selectedLatLng!!.latitude)
                intent.putExtra("picked_lng", selectedLatLng!!.longitude)
                intent.putExtra("picked_name", selectedAddressName)
                setResult(Activity.RESULT_OK, intent)
                finish()
            }
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        // Стиль
        if (sessionManager.isDarkMode()) {
            try { mMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style_dark)) } catch (e: Exception) {}
        } else {
            try { mMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style_standard)) } catch (e: Exception) { mMap.setMapStyle(null) }
        }

        val lat = intent.getDoubleExtra("start_lat", 50.4501)
        val lng = intent.getDoubleExtra("start_lng", 30.5234)
        val startPos = LatLng(lat, lng)

        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startPos, 16f))
        mMap.uiSettings.isZoomControlsEnabled = false

        // --- ЛОГИКА АНИМАЦИИ (Синхронизировано с HomeActivity) ---

        mMap.setOnCameraMoveStartedListener { reason ->
            // Проверяем, что движение вызвано именно пальцем пользователя
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                tvAddress.text = "Визначення..."
                btnConfirm.isEnabled = false
                selectedLatLng = null

                // Стрибок вгору
                centerPin.animate().cancel() // Отменяем текущие анимации
                centerPin.animate()
                    .translationY(convertDpToPixel(-48f)) // Идеальная высота прыжка в DP
                    .setStartDelay(0) // Сбрасываем задержку (мгновенный старт)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .setDuration(250)
                    .start()

                // Тінь
                try {
                    pinShadow.animate().cancel()
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

        mMap.setOnCameraIdleListener {
            val center = mMap.cameraPosition.target
            selectedLatLng = center
            getAddressFromLocation(center)

            // Падіння вниз
            centerPin.animate().cancel()
            centerPin.animate()
                .translationY(convertDpToPixel(-32f)) // Базовая позиция на земле
                .setStartDelay(0)
                .setInterpolator(BounceInterpolator())
                .setDuration(500)
                .start()

            // Тінь
            try {
                pinShadow.animate().cancel()
                pinShadow.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .alpha(0.5f)
                    .setStartDelay(0)
                    .setDuration(250)
                    .start()
            } catch (e: Exception) {}
        }
    }

    private fun getAddressFromLocation(latLng: LatLng) {
        Thread {
            try {
                val geocoder = Geocoder(this, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)

                val resultText = if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    val street = address.thoroughfare
                    val feature = address.subThoroughfare

                    if (street != null && feature != null) {
                        "$street, $feature"
                    } else if (street != null) {
                        street
                    } else {
                        // Якщо вулиця не знайдена окремо, беремо повний рядок і ЧИСТИМО ЙОГО
                        cleanAddress(address.getAddressLine(0))
                    }
                } else {
                    "Точка на карті"
                }

                Handler(Looper.getMainLooper()).post {
                    tvAddress.text = resultText
                    selectedAddressName = resultText
                    btnConfirm.isEnabled = true
                }

            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    tvAddress.text = "Точка на карті"
                    selectedAddressName = "Точка на карті"
                    btnConfirm.isEnabled = true
                }
            }
        }.start()
    }

    // --- ФУНКЦІЯ ОЧИЩЕННЯ ---
    private fun cleanAddress(fullAddress: String): String {
        var result = fullAddress
        val removeList = listOf(
            ", Україна", "Україна",
            ", Ukraine", "Ukraine",
            ", UA", "UA",
            ", Київ", "Київ",
            ", Львів", "Львів",
            ", Одеса", "Одеса",
            ", Харків", "Харків",
            ", Дніпро", "Дніпро",
            ", 01000", ", 02000" // Індекси
        )
        for (word in removeList) {
            result = result.replace(word, "", ignoreCase = true)
        }
        // Видаляємо будь-які 5-значні індекси
        result = result.replace(Regex(",\\s?\\d{5}"), "")

        return result.trim().removeSuffix(",").trim()
    }

    private fun convertDpToPixel(dp: Float): Float {
        val metrics = resources.displayMetrics
        return dp * (metrics.densityDpi / 160f)
    }

    override fun onMapsSdkInitialized(renderer: MapsInitializer.Renderer) {}
}