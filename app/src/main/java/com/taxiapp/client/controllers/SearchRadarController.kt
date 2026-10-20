package com.taxiapp.client.controllers

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.animation.DecelerateInterpolator
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.GroundOverlay
import com.google.android.gms.maps.model.GroundOverlayOptions
import com.google.android.gms.maps.model.LatLng

/**
 * Модульный контроллер анимации радара поиска автомобиля на Google Карте.
 * Кэширует BitmapDescriptor для светлой и темной темы, избавляя от повторной аллокации
 * Bitmap 256x256 при каждом обновлении статуса заказа.
 */
class SearchRadarController {

    var isRunning: Boolean = false
        private set

    private var radarOverlay: GroundOverlay? = null
    private var radarAnimator: ValueAnimator? = null

    private var cachedDarkDescriptor: BitmapDescriptor? = null
    private var cachedLightDescriptor: BitmapDescriptor? = null

    private fun getOrCreateDescriptor(isDarkTheme: Boolean): BitmapDescriptor {
        val cached = if (isDarkTheme) cachedDarkDescriptor else cachedLightDescriptor
        if (cached != null) return cached

        val sizePx = 256
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val fillColor = if (isDarkTheme) "#15FFFFFF" else "#12000000"
        val strokeColor = if (isDarkTheme) "#FFFFFFFF" else "#FF2C2D2F"

        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor(fillColor)
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.color = Color.parseColor(strokeColor)
        val strokeRadius = (sizePx / 2f) - 2.5f
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, strokeRadius, paint)

        val descriptor = BitmapDescriptorFactory.fromBitmap(bitmap)
        if (isDarkTheme) {
            cachedDarkDescriptor = descriptor
        } else {
            cachedLightDescriptor = descriptor
        }
        return descriptor
    }

    fun start(map: GoogleMap?, centerLatLng: LatLng, isDarkTheme: Boolean) {
        if (map == null) return
        if (isRunning && radarOverlay != null) {
            radarOverlay?.position = centerLatLng
            return
        }
        stop()
        isRunning = true

        val descriptor = getOrCreateDescriptor(isDarkTheme)
        val overlayOptions = GroundOverlayOptions()
            .image(descriptor)
            .position(centerLatLng, 0f)
            .transparency(0f)
            .zIndex(0f)

        radarOverlay = map.addGroundOverlay(overlayOptions)

        radarAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator()

            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                try {
                    val currentSize = fraction * 450f
                    radarOverlay?.setDimensions(currentSize)

                    val currentTransparency = if (fraction > 0.75f) {
                        (fraction - 0.75f) / 0.25f
                    } else {
                        0f
                    }
                    radarOverlay?.transparency = currentTransparency
                } catch (_: Exception) {}
            }
            start()
        }
    }

    fun stop() {
        isRunning = false
        radarAnimator?.cancel()
        radarAnimator = null
        try {
            radarOverlay?.remove()
        } catch (_: Exception) {}
        radarOverlay = null
    }
}