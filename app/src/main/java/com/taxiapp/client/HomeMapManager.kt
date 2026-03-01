package com.taxiapp.client

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.*
import com.google.maps.android.SphericalUtil
import com.taxiapp.client.network.dto.TrackingLocationDto
import com.taxiapp.client.utils.AddressUtils
import com.taxiapp.client.utils.BitmapHelper
import com.taxiapp.client.utils.CityData
import kotlin.math.abs

class HomeMapManager(private val context: Context) {

    var googleMap: GoogleMap? = null

    // Маркеры
    private var originMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var driverMarker: Marker? = null
    private val waypointMarkers = mutableListOf<Marker>()

    // Линии маршрута
    private var polylineBorder: Polyline? = null
    private var polylineMain: Polyline? = null
    private var polylineAnim: Polyline? = null

    // Анимация
    private var routeAnimator: ValueAnimator? = null
    private val animHandler = Handler(Looper.getMainLooper())

    // Ресурсы
    private var customCarIcon: BitmapDescriptor? = null

    fun initMap(map: GoogleMap, isDarkMode: Boolean) {
        this.googleMap = map
        map.uiSettings.isCompassEnabled = false
        map.uiSettings.isZoomControlsEnabled = false
        applyTheme(isDarkMode)
    }

    fun applyTheme(isDarkMode: Boolean) {
        val styleRes = if (isDarkMode) R.raw.map_style_dark else R.raw.map_style_standard
        try {
            googleMap?.setMapStyle(MapStyleOptions.loadRawResourceStyle(context, styleRes))
        } catch (e: Exception) {
            googleMap?.setMapStyle(null)
        }
    }

    fun moveCameraToCity(city: CityData) {
        val center = LatLng(city.lat, city.lng)
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(center, city.zoom))
    }

    // --- Рисование маршрута ---

    fun drawRoute(
        path: List<LatLng>,
        originLatLng: LatLng,
        destLatLng: LatLng,
        waypoints: List<Pair<LatLng, String>>,
        originName: String,
        destName: String,
        durationSeconds: Int,
        uiCallback: (String, String) -> Unit // Обратный вызов для обновления UI текстов
    ) {
        if (googleMap == null) return
        clearRouteVisuals()

        val colorMain = ContextCompat.getColor(context, R.color.route_main)
        val colorBorder = ContextCompat.getColor(context, R.color.route_border)

        // Прозрачные линии для начала (для анимации)
        val transparentMain = Color.argb(0, Color.red(colorMain), Color.green(colorMain), Color.blue(colorMain))
        val transparentBorder = Color.argb(0, Color.red(colorBorder), Color.green(colorBorder), Color.blue(colorBorder))

        polylineBorder = googleMap?.addPolyline(PolylineOptions().addAll(path).width(20f).color(transparentBorder).startCap(RoundCap()).endCap(RoundCap()).zIndex(1f))
        polylineMain = googleMap?.addPolyline(PolylineOptions().addAll(path).width(14f).color(transparentMain).startCap(RoundCap()).endCap(RoundCap()).zIndex(2f))

        // Маркеры
        originMarker = googleMap?.addMarker(MarkerOptions()
            .position(originLatLng)
            .icon(getBitmapDescriptor(R.drawable.ic_marker_base_yellow))
            .anchor(0.5f, 0.5f)
            .alpha(0f)
            .zIndex(1000f))

        destinationMarker = googleMap?.addMarker(MarkerOptions()
            .position(destLatLng)
            .icon(getBitmapDescriptor(R.drawable.ic_marker_base_white))
            .anchor(0.5f, 0.5f)
            .alpha(0f)
            .zIndex(1000f))

        val wpIcon = BitmapHelper.vectorToBitmap(context, R.drawable.ic_waypoint_dot)
        for (wp in waypoints) {
            val m = googleMap?.addMarker(MarkerOptions().position(wp.first).icon(wpIcon).anchor(0.5f, 0.5f).alpha(0f).zIndex(500f))
            if (m != null) waypointMarkers.add(m)
        }

        // Подготовка данных для UI (время прибытия)
        val calendar = java.util.Calendar.getInstance()
        calendar.add(java.util.Calendar.SECOND, durationSeconds)
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val arrivalTime = sdf.format(calendar.time)

        uiCallback("Приїдемо о $arrivalTime", "") // Передаем данные обратно в Activity

        // Анимация камеры
        val boundsBuilder = LatLngBounds.Builder()
        boundsBuilder.include(originLatLng)
        boundsBuilder.include(destLatLng)
        path.forEach { boundsBuilder.include(it) }

        // НОВЫЙ КОД: Убираем принудительный scrollBy и сброс паддингов.
        // Карта сама подстроится под паддинги, которые задала Activity.
        try {
            val metrics = context.resources.displayMetrics
            val paddingSide = (metrics.densityDpi / 160f * 60f).toInt() // 60dp

            val cameraUpdate = CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), paddingSide)

            googleMap?.animateCamera(cameraUpdate, 800, object : GoogleMap.CancelableCallback {
                override fun onFinish() {
                    startRouteRevealAnimation(colorMain, colorBorder, path)
                }
                override fun onCancel() {
                    startRouteRevealAnimation(colorMain, colorBorder, path)
                }
            })
        } catch (e: Exception) {
            startRouteRevealAnimation(colorMain, colorBorder, path)
        }
    }

    private fun startRouteRevealAnimation(colorMain: Int, colorBorder: Int, path: List<LatLng>) {
        if (polylineMain == null) return

        val polylineAnimator = ValueAnimator.ofInt(0, 255)
        polylineAnimator.duration = 1000
        polylineAnimator.addUpdateListener { animator ->
            val alpha = animator.animatedValue as Int
            try {
                val newMain = Color.argb(alpha, Color.red(colorMain), Color.green(colorMain), Color.blue(colorMain))
                val newBorder = Color.argb(alpha, Color.red(colorBorder), Color.green(colorBorder), Color.blue(colorBorder))
                polylineMain?.color = newMain
                polylineBorder?.color = newBorder
            } catch (_: Exception) {}
        }

        val markerAnimator = ValueAnimator.ofFloat(0f, 1f)
        markerAnimator.duration = 1000
        markerAnimator.addUpdateListener { animator ->
            val alpha = animator.animatedValue as Float
            originMarker?.alpha = alpha
            destinationMarker?.alpha = alpha
            waypointMarkers.forEach { it.alpha = alpha }
        }

        polylineAnimator.start()
        markerAnimator.start()

        polylineAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                animateRoute(path)
            }
        })
    }

    private fun animateRoute(path: List<LatLng>) {
        if (path.isEmpty() || googleMap == null) return
        val animOpts = PolylineOptions().width(14f).color(Color.WHITE).zIndex(3f).startCap(RoundCap()).endCap(RoundCap())
        polylineAnim = googleMap?.addPolyline(animOpts)

        routeAnimator = ValueAnimator.ofInt(0, 200)
        routeAnimator?.duration = 4000
        routeAnimator?.interpolator = LinearInterpolator()
        routeAnimator?.repeatCount = ValueAnimator.INFINITE // Бесконечная анимация пока есть маршрут

        routeAnimator?.addUpdateListener { animator ->
            try {
                val progress = animator.animatedValue as Int
                if (path.isNotEmpty()) {
                    val totalPoints = path.size
                    val endRaw = (totalPoints * progress) / 100
                    val startRaw = (endRaw - (totalPoints / 3)).coerceAtLeast(0)
                    val end = endRaw.coerceAtMost(totalPoints)
                    val start = startRaw.coerceAtMost(end)

                    if (end > start) {
                        val subList = path.subList(start, end)
                        polylineAnim?.points = subList
                        val gradient = StyleSpan(StrokeStyle.gradientBuilder(Color.TRANSPARENT, Color.WHITE).build())
                        polylineAnim?.spans = listOf(gradient)
                    } else {
                        polylineAnim?.points = emptyList()
                    }
                }
            } catch (_: Exception) {}
        }
        routeAnimator?.start()
    }

    fun clearRouteVisuals() {
        polylineMain?.remove()
        polylineBorder?.remove()
        polylineAnim?.remove()
        originMarker?.remove()
        destinationMarker?.remove()
        waypointMarkers.forEach { it.remove() }
        waypointMarkers.clear()

        routeAnimator?.cancel()
        routeAnimator = null
        animHandler.removeCallbacksAndMessages(null)

        driverMarker?.remove()
        driverMarker = null

        googleMap?.setPadding(0, 0, 0, 0)
    }

    // --- Логика водителя ---

    fun setCustomCarIcon(url: String?) {
        if (url.isNullOrEmpty()) return

        var finalUrl = url
        if (finalUrl.contains("localhost")) {
            // Hardcoded fix for emulator/local testing
            finalUrl = finalUrl.replace("localhost", "192.168.0.104")
        }

        Glide.with(context)
            .asBitmap()
            .load(finalUrl)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    val scaled = Bitmap.createScaledBitmap(resource, 130, 130, false)
                    customCarIcon = BitmapDescriptorFactory.fromBitmap(scaled)
                    driverMarker?.setIcon(customCarIcon)
                }
                override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
            })
    }

    fun updateDriverMarker(targetPos: LatLng, bearing: Float, routePoints: List<LatLng>?) {
        if (googleMap == null) return

        var adjustedPos = targetPos

        // Smart Snap (притягивание к маршруту)
        if (!routePoints.isNullOrEmpty()) {
            val (snapped, distance) = getSnapPointAndDistance(targetPos, routePoints)
            if (distance < 50.0) {
                adjustedPos = snapped
            }
        }

        if (driverMarker == null) {
            val icon = customCarIcon ?: BitmapHelper.vectorToBitmapDescriptor(context, R.drawable.ic_sun) // Fallback icon
            driverMarker = googleMap?.addMarker(MarkerOptions()
                .position(adjustedPos)
                .icon(icon)
                .anchor(0.5f, 0.5f)
                .rotation(bearing)
                .flat(true))
        } else {
            val oldPos = driverMarker!!.position
            val distanceMoved = SphericalUtil.computeDistanceBetween(oldPos, adjustedPos)
            var newBearing = driverMarker!!.rotation

            if (distanceMoved > 2.0) {
                newBearing = SphericalUtil.computeHeading(oldPos, adjustedPos).toFloat()
            }
            animateMarker(driverMarker!!, adjustedPos, newBearing)
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

    // --- Утилиты ---

    private fun getBitmapDescriptor(id: Int): BitmapDescriptor? {
        val vectorDrawable = ContextCompat.getDrawable(context, id) ?: return null
        vectorDrawable.setBounds(0, 0, vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight)
        val bitmap = Bitmap.createBitmap(vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        vectorDrawable.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    // Умные метки (Smart Labels)
    fun updateSmartLabelPositions(overlayOrigin: View, overlayDest: View, routePoints: List<LatLng>?) {
        if (googleMap == null) return

        val projection = googleMap!!.projection

        // Origin Label
        originMarker?.position?.let { pos ->
            if (overlayOrigin.visibility == View.VISIBLE) {
                val screenPt = projection.toScreenLocation(pos)
                moveViewIdeally(overlayOrigin, screenPt.x.toFloat(), screenPt.y.toFloat(), true, routePoints)
            }
        }

        // Destination Label
        destinationMarker?.position?.let { pos ->
            if (overlayDest.visibility == View.VISIBLE) {
                val screenPt = projection.toScreenLocation(pos)
                moveViewIdeally(overlayDest, screenPt.x.toFloat(), screenPt.y.toFloat(), false, routePoints)
            }
        }
    }

    private fun moveViewIdeally(view: View, targetX: Float, targetY: Float, isStart: Boolean, routePoints: List<LatLng>?) {
        var isRouteGoingUp = false

        if (!routePoints.isNullOrEmpty()) {
            val projection = googleMap!!.projection
            val compareIndex = if (isStart) 1.coerceAtMost(routePoints.size -1) else (routePoints.size - 2).coerceAtLeast(0)
            val comparePt = projection.toScreenLocation(routePoints[compareIndex])
            if (comparePt.y < targetY) isRouteGoingUp = true
        }

        val metrics = context.resources.displayMetrics
        val verticalPadding = 8f * metrics.density

        var finalY = if (isRouteGoingUp) targetY + verticalPadding else targetY - view.height - verticalPadding
        var finalX = targetX - (view.width / 2)

        // Bound checks
        val margin = 16f * metrics.density
        if (finalX < margin) finalX = margin
        if (finalX + view.width > metrics.widthPixels - margin) finalX = metrics.widthPixels - margin - view.width

        val topSafe = 50f * metrics.density
        val bottomSafe = metrics.heightPixels - (150f * metrics.density)

        if (finalY < topSafe) finalY = targetY + verticalPadding
        else if (finalY + view.height > bottomSafe) finalY = targetY - view.height - verticalPadding

        view.x = finalX
        view.y = finalY
    }

    // Математика привязки к дороге
    private fun getSnapPointAndDistance(raw: LatLng, route: List<LatLng>): Pair<LatLng, Double> {
        if (route.size < 2) return Pair(raw, 0.0)
        var closest = raw
        var minDst = Double.MAX_VALUE

        for (i in 0 until route.size - 1) {
            val p = findNearestPointOnSegment(raw, route[i], route[i+1])
            val d = SphericalUtil.computeDistanceBetween(raw, p)
            if (d < minDst) {
                minDst = d
                closest = p
            }
        }
        return Pair(closest, minDst)
    }

    private fun findNearestPointOnSegment(p: LatLng, start: LatLng, end: LatLng): LatLng {
        if (start == end) return start
        // Упрощенная математика проекции (можно использовать PolyUtil.isLocationOnPath с допуском, но тут точнее)
        val sLat = Math.toRadians(start.latitude); val sLng = Math.toRadians(start.longitude)
        val eLat = Math.toRadians(end.latitude); val eLng = Math.toRadians(end.longitude)
        val pLat = Math.toRadians(p.latitude); val pLng = Math.toRadians(p.longitude)

        val x = (eLat - sLat) * (pLat - sLat) + (eLng - sLng) * (pLng - sLng) * Math.cos(sLat)
        val y = (eLat - sLat) * (eLat - sLat) + (eLng - sLng) * (eLng - sLng) * Math.cos(sLat)
        val u = (x / y).coerceIn(0.0, 1.0)

        val resLat = Math.toDegrees(sLat + u * (eLat - sLat))
        val resLng = Math.toDegrees(sLng + u * (eLng - sLng))
        return LatLng(resLat, resLng)
    }
}