package com.taxiapp.client.controllers

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.RoundCap
import com.google.android.libraries.places.api.model.Place
import com.google.maps.android.PolyUtil
import com.google.maps.android.SphericalUtil
import com.taxiapp.client.R
import com.taxiapp.client.network.dto.TaxiOrderDto
import com.taxiapp.client.utils.BitmapHelper
import com.taxiapp.client.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Контроллер управления полилиниями маршрута, маркерами точек А/Б/остановок,
 * маршрутом подачи автомобиля и умными паддингами Google Карты.
 */
class MapRouteController(
    private val activity: Activity,
    private val sessionManager: SessionManager,
    private val supportFragmentManager: FragmentManager
) {

    var polylineBorder: Polyline? = null
    var polylineMain: Polyline? = null
    var polylineAnim: Polyline? = null
    var pickupPolyline: Polyline? = null
    var pickupRoutePoints: MutableList<LatLng> = mutableListOf()

    var originMarker: Marker? = null
    var destinationMarker: Marker? = null
    val waypointMarkers: MutableList<Marker> = mutableListOf()

    var lastDrawnPathHash: Int = 0
    var lastDrawnRemainingTime: String? = null

    private var routeAnimator: ValueAnimator? = null
    private var revealAnimator: ValueAnimator? = null
    private var markerRevealAnimator: ValueAnimator? = null

    private var lastPadLeft: Int = -1
    private var lastPadTop: Int = -1
    private var lastPadRight: Int = -1
    private var lastPadBottom: Int = -1

    fun convertDpToPixel(dp: Float): Float {
        val metrics = activity.resources.displayMetrics
        return dp * (metrics.densityDpi / 160f)
    }

    fun getBitmapDescriptor(resId: Int): BitmapDescriptor? {
        return BitmapHelper.vectorToBitmapDescriptor(activity, resId)
    }

    /**
     * Мгновенно сбрасывает 3D-наклон (tilt) и поворот (bearing) камеры в 0f.
     * Вызывается при любом выходе из состояния REQUESTED / OFFERING, чтобы
     * последующие анимации (newLatLngBounds / newLatLngZoom) и движение водителя
     * всегда отображались на плоской 2D-карте.
     */
    fun resetCameraTilt(map: GoogleMap?, forceStopAnimation: Boolean = false) {
        if (map == null) return
        try {
            if (forceStopAnimation) {
                map.stopAnimation()
            }
            val currentPos = map.cameraPosition
            if (currentPos.tilt != 0f || currentPos.bearing != 0f) {
                val flatPos = CameraPosition.Builder(currentPos)
                    .tilt(0f)
                    .bearing(0f)
                    .build()
                map.moveCamera(CameraUpdateFactory.newCameraPosition(flatPos))
            }
        } catch (_: Exception) {}
    }

    /**
     * Плавное перемещение камеры к точке с гарантированным сбросом наклона (tilt = 0f).
     * В отличие от CameraUpdateFactory.newLatLngZoom(), который сохраняет предыдущий tilt (45°),
     * этот метод явно задает CameraPosition с tilt(0f) и bearing(0f).
     */
    fun animateCameraFlatZoom(
        map: GoogleMap?,
        target: LatLng,
        zoom: Float,
        durationMs: Int = 600
    ) {
        if (map == null) return
        try {
            val flatPos = CameraPosition.Builder()
                .target(target)
                .zoom(zoom)
                .tilt(0f)
                .bearing(0f)
                .build()
            map.animateCamera(CameraUpdateFactory.newCameraPosition(flatPos), durationMs, null)
        } catch (_: Exception) {}
    }

    private fun applyPaddingIfChanged(map: GoogleMap, left: Int, top: Int, right: Int, bottom: Int) {
        if (left != lastPadLeft || top != lastPadTop || right != lastPadRight || abs(bottom - lastPadBottom) > 2) {
            lastPadLeft = left
            lastPadTop = top
            lastPadRight = right
            lastPadBottom = bottom
            map.setPadding(left, top, right, bottom)
        }
    }

    fun computePickupEtaSeconds(): Int {
        return if (pickupRoutePoints.isNotEmpty()) {
            val meters = SphericalUtil.computeLength(pickupRoutePoints)
            (meters / 9.7).toInt().coerceAtLeast(180)
        } else {
            300
        }
    }

    fun clearMainRoutePolylines() {
        routeAnimator?.cancel()
        routeAnimator = null
        revealAnimator?.cancel()
        revealAnimator = null
        markerRevealAnimator?.cancel()
        markerRevealAnimator = null

        try {
            polylineMain?.remove()
            polylineBorder?.remove()
            polylineAnim?.remove()
        } catch (_: Exception) {}

        polylineMain = null
        polylineBorder = null
        polylineAnim = null
    }

    fun clearPickupRoute() {
        try {
            pickupPolyline?.remove()
        } catch (_: Exception) {}
        pickupPolyline = null
        pickupRoutePoints.clear()
    }

    fun clearDestinationAndWaypoints() {
        try {
            waypointMarkers.forEach { it.remove() }
            waypointMarkers.clear()
            (destinationMarker?.tag as? Marker)?.remove()
            destinationMarker?.remove()
        } catch (_: Exception) {}
        destinationMarker = null
    }

    fun clearAllRouteElements() {
        clearMainRoutePolylines()
        clearPickupRoute()
        clearDestinationAndWaypoints()
        try {
            originMarker?.remove()
        } catch (_: Exception) {}
        originMarker = null
        lastDrawnPathHash = 0
        lastDrawnRemainingTime = null
    }

    fun drawStylishRoute(
        map: GoogleMap?,
        path: List<LatLng>,
        orderStatus: String,
        originPlace: Place?,
        destinationPlace: Place?,
        currentWaypoints: List<Pair<LatLng, String>>,
        routeDurationSeconds: Int,
        tvOriginText: String,
        tvDestinationText: String,
        centerPin: ImageView,
        pinShadow: ImageView,
        overlayOrigin: View,
        tvOverlayOrigin: TextView,
        overlayDest: View,
        tvOverlayDest: TextView,
        tvOverlayDestDetails: TextView,
        btnRecenterRoute: CardView,
        btnRecenter: CardView,
        activeOrderCard: View,
        tariffsPanel: View,
        contentBottomSheet: View,
        currentRoutePolylineProvider: () -> String?,
        activeOrderProvider: () -> TaxiOrderDto?,
        onClearDriverMarker: () -> Unit,
        onRevealInterface: () -> Unit,
        onUpdateStatusUI: (TaxiOrderDto) -> Unit
    ) {
        if (map == null || path.isEmpty()) return

        val pathHash = path.hashCode()
        if (pathHash == lastDrawnPathHash && polylineMain != null) {
            return
        }
        lastDrawnPathHash = pathHash

        val isSearchingState = (orderStatus == "REQUESTED" || orderStatus == "OFFERING")
        if (!isSearchingState) {
            resetCameraTilt(map)
        }

        try {
            polylineMain?.remove()
            polylineMain = null
            polylineBorder?.remove()
            polylineBorder = null
            waypointMarkers.forEach { it.remove() }
            waypointMarkers.clear()

            if (!isSearchingState) {
                originMarker?.remove()
                originMarker = null
                (destinationMarker?.tag as? Marker)?.remove()
                destinationMarker?.remove()
                destinationMarker = null
            }
        } catch (_: Exception) {}

        centerPin.visibility = View.GONE
        try { pinShadow.visibility = View.GONE } catch (_: Exception) {}
        try { map.isMyLocationEnabled = false } catch (_: SecurityException) {}

        if (!isSearchingState) {
            map.clear()
            onClearDriverMarker()
        }

        val colorMain = ContextCompat.getColor(activity, R.color.route_main)
        val colorBorder = ContextCompat.getColor(activity, R.color.route_border)

        val transparentMain = Color.argb(0, Color.red(colorMain), Color.green(colorMain), Color.blue(colorMain))
        val transparentBorder = Color.argb(0, Color.red(colorBorder), Color.green(colorBorder), Color.blue(colorBorder))

        val borderOpts = PolylineOptions()
            .addAll(path)
            .width(20f)
            .color(transparentBorder)
            .startCap(RoundCap())
            .endCap(RoundCap())
            .zIndex(1f)
        polylineBorder = map.addPolyline(borderOpts)

        val mainOpts = PolylineOptions()
            .addAll(path)
            .width(14f)
            .color(transparentMain)
            .startCap(RoundCap())
            .endCap(RoundCap())
            .zIndex(2f)
        polylineMain = map.addPolyline(mainOpts)

        if (originPlace?.latLng != null) {
            if (originMarker == null) {
                originMarker = map.addMarker(
                    MarkerOptions()
                        .position(originPlace.latLng!!)
                        .icon(getBitmapDescriptor(R.drawable.ic_marker_base_yellow))
                        .anchor(0.5f, 0.5f)
                        .alpha(0f)
                        .zIndex(1000f)
                        .flat(false)
                )
            } else {
                originMarker?.isFlat = false
            }

            val finalOriginText = if (tvOriginText.contains("...") || tvOriginText.isBlank()) {
                originPlace.name ?: "А"
            } else {
                tvOriginText
            }
            tvOverlayOrigin.text = finalOriginText

            if (isSearchingState) {
                overlayOrigin.visibility = View.GONE
            } else {
                overlayOrigin.alpha = 0f
                overlayOrigin.visibility = View.VISIBLE
            }
        }

        if (orderStatus != "IN_PROGRESS" && !isSearchingState && destinationPlace?.latLng != null) {
            destinationMarker = map.addMarker(
                MarkerOptions()
                    .position(destinationPlace.latLng!!)
                    .icon(getBitmapDescriptor(R.drawable.ic_marker_base_white))
                    .anchor(0.5f, 0.5f)
                    .alpha(0f)
                    .zIndex(1000f)
            )

            val finalDestText = if (tvDestinationText.contains("...") || tvDestinationText.isBlank()) {
                destinationPlace.name ?: "Б"
            } else {
                tvDestinationText
            }
            tvOverlayDest.text = finalDestText

            val calendar = Calendar.getInstance()
            calendar.add(Calendar.SECOND, routeDurationSeconds)
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val arrivalTime = sdf.format(calendar.time)
            tvOverlayDestDetails.text = "Приїдемо о $arrivalTime"

            overlayDest.alpha = 0f
            overlayDest.visibility = View.VISIBLE
        }

        if (!isSearchingState) {
            val waypointIcon = BitmapHelper.vectorToBitmapDescriptor(activity, R.drawable.ic_waypoint_dot)
            for (wpPair in currentWaypoints) {
                if (wpPair.first.latitude != 0.0 && wpPair.first.longitude != 0.0) {
                    val marker = map.addMarker(
                        MarkerOptions()
                            .position(wpPair.first)
                            .icon(waypointIcon)
                            .anchor(0.5f, 0.5f)
                            .alpha(0f)
                            .zIndex(500f)
                    )
                    if (marker != null) waypointMarkers.add(marker)
                }
            }
        }

        val triggerReveal = {
            activity.runOnUiThread {
                startRouteRevealAnimation(
                    map = map,
                    colorMain = colorMain,
                    colorBorder = colorBorder,
                    path = path,
                    overlayOrigin = overlayOrigin,
                    overlayDest = overlayDest,
                    currentRoutePolylineProvider = currentRoutePolylineProvider,
                    activeOrderProvider = activeOrderProvider,
                    onRevealInterface = onRevealInterface,
                    onUpdateStatusUI = onUpdateStatusUI
                )
            }
        }

        if (isSearchingState) {
            btnRecenterRoute.visibility = View.GONE
            btnRecenter.visibility = View.GONE
            triggerReveal()
        } else {
            val boundsBuilder = LatLngBounds.Builder()
            if (originPlace?.latLng != null) boundsBuilder.include(originPlace.latLng!!)
            if (destinationPlace?.latLng != null) boundsBuilder.include(destinationPlace.latLng!!)
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
                    val paddingTop = convertDpToPixel(10f).toInt()
                    val paddingSide = convertDpToPixel(80f).toInt()

                    if (orderStatus != "CANCELLED") {
                        applyPaddingIfChanged(map, sideMargin, paddingTop, sideMargin, paddingBottom)
                    }

                    if (orderStatus == "ACCEPTED" || orderStatus == "DRIVER_ARRIVED" || orderStatus == "IN_PROGRESS") {
                        resetCameraTilt(map)
                        triggerReveal()
                    } else {
                        resetCameraTilt(map, forceStopAnimation = true)

                        visibleBottomPanel.postDelayed({
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                try {
                                    resetCameraTilt(map)
                                    val cameraUpdate = CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), paddingSide)
                                    map.animateCamera(cameraUpdate, 500, object : GoogleMap.CancelableCallback {
                                        override fun onFinish() = triggerReveal()
                                        override fun onCancel() = triggerReveal()
                                    })
                                } catch (_: Exception) {
                                    triggerReveal()
                                }
                            }
                        }, 200)
                    }
                } catch (_: Exception) {
                    triggerReveal()
                }
            }
            btnRecenterRoute.visibility = View.GONE
        }

        if (!isSearchingState) {
            contentBottomSheet.post {
                updateSmartLabels(
                    map = map,
                    currentStatus = activeOrderProvider()?.status,
                    originPlace = originPlace,
                    destinationPlace = destinationPlace,
                    decodedRoutePoints = path,
                    overlayOrigin = overlayOrigin,
                    overlayDest = overlayDest
                )
            }
        }
    }

    fun startRouteRevealAnimation(
        map: GoogleMap?,
        colorMain: Int,
        colorBorder: Int,
        path: List<LatLng>,
        overlayOrigin: View,
        overlayDest: View,
        currentRoutePolylineProvider: () -> String?,
        activeOrderProvider: () -> TaxiOrderDto?,
        onRevealInterface: () -> Unit,
        onUpdateStatusUI: (TaxiOrderDto) -> Unit
    ) {
        if (polylineMain == null || polylineBorder == null || currentRoutePolylineProvider() == null) return
        onRevealInterface()

        routeAnimator?.cancel()
        routeAnimator = null
        polylineAnim?.remove()
        polylineAnim = null

        revealAnimator?.cancel()
        revealAnimator = ValueAnimator.ofInt(0, 255).apply {
            duration = 1000
            addUpdateListener { animator ->
                val alpha = animator.animatedValue as Int
                try {
                    val newMainColor = Color.argb(alpha, Color.red(colorMain), Color.green(colorMain), Color.blue(colorMain))
                    val newBorderColor = Color.argb(alpha, Color.red(colorBorder), Color.green(colorBorder), Color.blue(colorBorder))
                    polylineMain?.color = newMainColor
                    polylineBorder?.color = newBorderColor
                } catch (_: Exception) {}
            }
        }

        markerRevealAnimator?.cancel()
        markerRevealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            addUpdateListener { animator ->
                val alpha = animator.animatedValue as Float
                try {
                    originMarker?.alpha = alpha
                    destinationMarker?.alpha = alpha
                    waypointMarkers.forEach { it.alpha = alpha }
                } catch (_: Exception) {}
            }
        }

        overlayOrigin.animate().alpha(1f).setDuration(1000).start()
        overlayDest.animate().alpha(1f).setDuration(1000).start()

        revealAnimator?.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (currentRoutePolylineProvider() != null) {
                    animateRouteDrawing(map, path, currentRoutePolylineProvider)
                }

                activeOrderProvider()?.let { currentOrder ->
                    if (currentOrder.status != "CANCELLED" && currentOrder.status != "COMPLETED") {
                        onUpdateStatusUI(currentOrder)
                    }
                }
            }
        })

        revealAnimator?.start()
        markerRevealAnimator?.start()
    }

    fun animateRouteDrawing(
        map: GoogleMap?,
        path: List<LatLng>,
        currentRoutePolylineProvider: () -> String?
    ) {
        if (path.isEmpty() || map == null || currentRoutePolylineProvider() == null) return

        routeAnimator?.cancel()
        routeAnimator = null
        polylineAnim?.remove()
        polylineAnim = null

        val isDark = sessionManager.isDarkMode()
        val colorBase = ContextCompat.getColor(activity, R.color.route_main)
        val colorGlow = if (isDark) Color.rgb(240, 240, 240) else Color.rgb(112, 112, 112)

        val animOpts = PolylineOptions()
            .addAll(path)
            .width(8f)
            .color(colorBase)
            .zIndex(2.5f)
            .startCap(RoundCap())
            .endCap(RoundCap())
            .jointType(JointType.ROUND)

        polylineAnim = map.addPolyline(animOpts)

        val totalDurationMs = 5500f
        val argbEvaluator = ArgbEvaluator()
        var lastColor = 0

        routeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = totalDurationMs.toLong()
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART

            addUpdateListener { animator ->
                try {
                    if (polylineAnim == null) {
                        animator.cancel()
                        return@addUpdateListener
                    }

                    val progress = animator.animatedValue as Float
                    val timeMs = progress * totalDurationMs

                    val fraction = when {
                        timeMs <= 750f -> timeMs / 750f
                        timeMs <= 1250f -> 1f
                        timeMs <= 3750f -> 1f - ((timeMs - 1250f) / 2500f)
                        else -> 0f
                    }

                    val currentColor = argbEvaluator.evaluate(fraction, colorBase, colorGlow) as Int
                    if (currentColor != lastColor) {
                        lastColor = currentColor
                        polylineAnim?.color = currentColor
                    }
                } catch (_: Exception) {}
            }
        }

        routeAnimator?.start()
    }

    fun drawPickupRoute(
        map: GoogleMap?,
        polylineStr: String?,
        originPlace: Place?,
        activeOrder: TaxiOrderDto?,
        driverMarkerPos: LatLng?
    ) {
        if (polylineStr.isNullOrEmpty() || map == null) return
        try {
            pickupPolyline?.remove()
            val points = PolyUtil.decode(polylineStr)
            pickupRoutePoints = points.toMutableList()

            val pickupOpts = PolylineOptions()
                .addAll(pickupRoutePoints)
                .width(14f)
                .color(ContextCompat.getColor(activity, R.color.taxi_yellow))
                .startCap(RoundCap())
                .endCap(RoundCap())
                .jointType(JointType.ROUND)
                .zIndex(2.0f)

            pickupPolyline = map.addPolyline(pickupOpts)

            focusOnPickupRoute(
                map = map,
                driverLatLng = driverMarkerPos,
                originPlace = originPlace,
                activeOrder = activeOrder,
                driverMarkerPos = driverMarkerPos
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun trimPickupRoute(driverPos: LatLng) {
        if (pickupRoutePoints.isEmpty() || pickupPolyline == null) return

        try {
            val snap = getSnapPointAndDistance(driverPos, pickupRoutePoints)

            var closestIndex = 0
            var minDistance = Double.MAX_VALUE
            for (i in pickupRoutePoints.indices) {
                val d = SphericalUtil.computeDistanceBetween(driverPos, pickupRoutePoints[i])
                if (d < minDistance) {
                    minDistance = d
                    closestIndex = i
                }
            }

            if (closestIndex > 0) {
                pickupRoutePoints = pickupRoutePoints.subList(closestIndex, pickupRoutePoints.size).toMutableList()
            }

            if (pickupRoutePoints.isNotEmpty()) {
                pickupRoutePoints[0] = snap.first
            }

            pickupPolyline?.points = pickupRoutePoints
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getSnapPointAndDistance(rawLocation: LatLng, route: List<LatLng>): Pair<LatLng, Double> {
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

    fun findNearestPointOnSegment(p: LatLng, start: LatLng, end: LatLng): LatLng {
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

    fun focusOnPickupRoute(
        map: GoogleMap?,
        driverLatLng: LatLng? = null,
        originPlace: Place?,
        activeOrder: TaxiOrderDto?,
        driverMarkerPos: LatLng?
    ) {
        if (map == null) return

        try {
            // Всегда сбрасываем 3D-наклон перед фокусировкой на подаче автомобиля
            resetCameraTilt(map, forceStopAnimation = true)

            val boundsBuilder = LatLngBounds.Builder()
            var pointsCount = 0

            val originPos = originPlace?.latLng
                ?: activeOrder?.let {
                    if (it.originLat != null && it.originLng != null && it.originLat != 0.0 && it.originLng != 0.0) {
                        LatLng(it.originLat, it.originLng)
                    } else null
                }

            if (originPos != null) {
                boundsBuilder.include(originPos)
                pointsCount++
            }

            val driverPos = driverLatLng
                ?: driverMarkerPos
                ?: activeOrder?.driver?.let {
                    if (it.latitude != null && it.longitude != null && it.latitude != 0.0 && it.longitude != 0.0) {
                        LatLng(it.latitude, it.longitude)
                    } else null
                }

            if (driverPos != null) {
                boundsBuilder.include(driverPos)
                pointsCount++
            }

            if (pickupRoutePoints.isNotEmpty()) {
                pickupRoutePoints.forEach {
                    boundsBuilder.include(it)
                    pointsCount++
                }
            }

            if (pointsCount >= 2) {
                val bounds = boundsBuilder.build()
                val diagonalMeters = SphericalUtil.computeDistanceBetween(bounds.northeast, bounds.southwest)
                if (diagonalMeters < 35.0) {
                    // Если водитель и клиент в одной точке (например, при тесте на одном телефоне) —
                    // не зумим до максимума через LatLngBounds, а ставим плоскую камеру с комфортным зумом
                    val target = driverPos ?: originPos ?: bounds.center
                    animateCameraFlatZoom(map, target, 16.0f, 800)
                } else {
                    val paddingSide = convertDpToPixel(90f).toInt()
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingSide), 800, null)
                }
            } else {
                val target = driverPos ?: originPos
                if (target != null) {
                    animateCameraFlatZoom(map, target, 16.0f, 800)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateSmartLabels(
        map: GoogleMap?,
        currentStatus: String?,
        originPlace: Place?,
        destinationPlace: Place?,
        decodedRoutePoints: List<LatLng>?,
        overlayOrigin: View,
        overlayDest: View
    ) {
        if (map == null) return

        if (currentStatus == "REQUESTED" || currentStatus == "OFFERING" ||
            currentStatus == "ACCEPTED" || currentStatus == "DRIVER_ARRIVED" || currentStatus == "IN_PROGRESS"
        ) {
            overlayOrigin.visibility = View.GONE
            overlayDest.visibility = View.GONE
            return
        }

        val originLoc = originPlace?.latLng
        val destLoc = destinationPlace?.latLng
        val projection = map.projection

        if (originLoc != null && overlayOrigin.visibility == View.VISIBLE) {
            val screenPt = projection.toScreenLocation(originLoc)
            moveViewIdeally(map, overlayOrigin, screenPt.x.toFloat(), screenPt.y.toFloat(), isStartPoint = true, decodedRoutePoints = decodedRoutePoints)
        }

        if (destLoc != null && overlayDest.visibility == View.VISIBLE) {
            val screenPt = projection.toScreenLocation(destLoc)
            moveViewIdeally(map, overlayDest, screenPt.x.toFloat(), screenPt.y.toFloat(), isStartPoint = false, decodedRoutePoints = decodedRoutePoints)
        }
    }

    private fun moveViewIdeally(
        map: GoogleMap,
        view: View,
        targetX: Float,
        targetY: Float,
        isStartPoint: Boolean,
        decodedRoutePoints: List<LatLng>?
    ) {
        val viewWidth = view.width
        val viewHeight = view.height
        val screenWidth = activity.resources.displayMetrics.widthPixels

        var isRouteGoingUp = false

        if (!decodedRoutePoints.isNullOrEmpty()) {
            val projection = map.projection
            val compareLatLng = if (isStartPoint) {
                if (decodedRoutePoints.size > 1) decodedRoutePoints[1] else decodedRoutePoints[0]
            } else {
                if (decodedRoutePoints.size > 1) decodedRoutePoints[decodedRoutePoints.size - 2] else decodedRoutePoints[0]
            }

            val compareScreenPt = projection.toScreenLocation(compareLatLng)
            if (compareScreenPt.y < targetY) {
                isRouteGoingUp = true
            }
        }

        val verticalPadding = convertDpToPixel(8f)
        var finalY = if (isRouteGoingUp) {
            targetY + verticalPadding
        } else {
            targetY - viewHeight - verticalPadding
        }

        var finalX = targetX - (viewWidth / 2)
        val margin = convertDpToPixel(16f)

        if (finalX < margin) finalX = margin
        if (finalX + viewWidth > screenWidth - margin) {
            finalX = screenWidth - margin - viewWidth
        }

        val topSafeArea = convertDpToPixel(50f)
        val bottomSafeArea = activity.resources.displayMetrics.heightPixels - convertDpToPixel(150f)

        if (finalY < topSafeArea) {
            finalY = targetY + verticalPadding
        } else if (finalY + viewHeight > bottomSafeArea) {
            finalY = targetY - viewHeight - verticalPadding
        }

        view.x = finalX
        view.y = finalY
    }

    fun updateInProgressDestinationBadge(
        map: GoogleMap?,
        order: TaxiOrderDto
    ) {
        if (map == null) return

        val secondsLeft = order.durationSeconds ?: 0
        val minutesLeft = if (secondsLeft > 0) ceil(secondsLeft.toDouble() / 60.0).toInt() else 0
        val timeText = if (minutesLeft > 0) "$minutesLeft хв" else "?? хв"

        if (timeText != lastDrawnRemainingTime || destinationMarker == null) {
            lastDrawnRemainingTime = timeText

            val destLat = order.destLat
            val destLng = order.destLng
            if (destLat != null && destLng != null && destLat != 0.0 && destLng != 0.0) {
                val destLatLng = LatLng(destLat, destLng)
                try {
                    (destinationMarker?.tag as? Marker)?.remove()
                    destinationMarker?.remove()

                    destinationMarker = map.addMarker(
                        MarkerOptions()
                            .position(destLatLng)
                            .icon(getBitmapDescriptor(R.drawable.ic_marker_base_white))
                            .anchor(0.5f, 1.0f)
                    )

                    val markerView = LayoutInflater.from(activity).inflate(R.layout.layout_route_time_marker, null)
                    val tvMarkerTime = markerView.findViewById<TextView>(R.id.tv_marker_time)
                    tvMarkerTime.text = timeText

                    markerView.measure(
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    )
                    markerView.layout(0, 0, markerView.measuredWidth, markerView.measuredHeight)

                    val bitmap = Bitmap.createBitmap(markerView.measuredWidth, markerView.measuredHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    markerView.draw(canvas)

                    val timeBadgeMarker = map.addMarker(
                        MarkerOptions()
                            .position(destLatLng)
                            .icon(BitmapDescriptorFactory.fromBitmap(bitmap))
                            .anchor(0.5f, 1.5f)
                    )

                    destinationMarker?.tag = timeBadgeMarker
                    bitmap.recycle()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun updateMapPadding(
        map: GoogleMap?,
        bottomPanel: View,
        extraBottomDp: Float = 20f,
        topPaddingDp: Float = 20f,
        recenterMap: Boolean = true,
        creationPanelCard: View?,
        pinContainer: FrameLayout?,
        activeOrder: TaxiOrderDto?,
        activeOrderId: String?,
        originPlace: Place?,
        destinationPlace: Place?,
        currentWaypoints: List<Pair<LatLng, String>>,
        decodedRoutePoints: List<LatLng>?,
        driverMarkerPos: LatLng?
    ) {
        bottomPanel.requestLayout()

        bottomPanel.post {
            if (map != null) {
                if (bottomPanel.visibility != View.VISIBLE) {
                    if (creationPanelCard != null && creationPanelCard.visibility == View.VISIBLE && bottomPanel.id != creationPanelCard.id) {
                        updateMapPadding(
                            map = map,
                            bottomPanel = creationPanelCard,
                            extraBottomDp = 2f,
                            topPaddingDp = 20f,
                            recenterMap = false,
                            creationPanelCard = creationPanelCard,
                            pinContainer = pinContainer,
                            activeOrder = activeOrder,
                            activeOrderId = activeOrderId,
                            originPlace = originPlace,
                            destinationPlace = destinationPlace,
                            currentWaypoints = currentWaypoints,
                            decodedRoutePoints = decodedRoutePoints,
                            driverMarkerPos = driverMarkerPos
                        )
                    } else {
                        applyPaddingIfChanged(map, 0, 0, 0, 0)
                        pinContainer?.translationY = 0f
                    }
                    return@post
                }

                var panelHeight = bottomPanel.height
                if (panelHeight == 0) {
                    val widthSpec = View.MeasureSpec.makeMeasureSpec(activity.resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY)
                    val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    bottomPanel.measure(widthSpec, heightSpec)
                    panelHeight = bottomPanel.measuredHeight
                }
                if (panelHeight == 0) return@post

                if (bottomPanel.id == R.id.active_order_card) {
                    val detailsView = bottomPanel.findViewById<View>(R.id.layout_expandable_details)
                    if (detailsView != null && detailsView.visibility == View.VISIBLE) {
                        var detailsHeight = detailsView.height
                        if (detailsHeight == 0) {
                            val widthSpec = View.MeasureSpec.makeMeasureSpec(activity.resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY)
                            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                            detailsView.measure(widthSpec, heightSpec)
                            detailsHeight = detailsView.measuredHeight
                        }
                        panelHeight -= detailsHeight
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

                // Применяем паддинг синхронно (без вложенного mapView.post), чтобы не срывать анимацию камеры
                applyPaddingIfChanged(map, sideMargin, topPadding, sideMargin, totalBottomPadding)

                val currentStatus = activeOrder?.status
                if (currentStatus != "REQUESTED" && currentStatus != "OFFERING") {
                    resetCameraTilt(map)
                }

                if (pinContainer != null) {
                    if (bottomPanel.id == R.id.bottom_sheet_card || bottomPanel.id == R.id.layout_map_picker_panel) {
                        val shiftY = (totalBottomPadding - topPadding) / 2f
                        pinContainer.animate().translationY(-shiftY).setDuration(200).start()
                    } else {
                        pinContainer.animate().translationY(0f).setDuration(200).start()
                    }
                }

                if (recenterMap) {
                    try {
                        map.stopAnimation()
                        when {
                            currentStatus == "REQUESTED" || currentStatus == "OFFERING" -> {
                                val targetLoc = originPlace?.latLng
                                    ?: activeOrder?.let { LatLng(it.originLat ?: 0.0, it.originLng ?: 0.0) }
                                if (targetLoc != null) {
                                    val cameraPosition = CameraPosition.Builder()
                                        .target(targetLoc)
                                        .zoom(16.0f)
                                        .tilt(45f)
                                        .bearing(0f)
                                        .build()
                                    map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), 400, null)
                                }
                            }
                            currentStatus == "ACCEPTED" -> {
                                focusOnPickupRoute(
                                    map = map,
                                    originPlace = originPlace,
                                    activeOrder = activeOrder,
                                    driverMarkerPos = driverMarkerPos
                                )
                            }
                            currentStatus == "DRIVER_ARRIVED" -> {
                                val pos = driverMarkerPos ?: originPlace?.latLng
                                ?: activeOrder?.let { LatLng(it.originLat ?: 0.0, it.originLng ?: 0.0) }
                                if (pos != null && (pos.latitude != 0.0 || pos.longitude != 0.0)) {
                                    animateCameraFlatZoom(map, pos, 16.5f, 400)
                                }
                            }
                            currentStatus == "IN_PROGRESS" || currentStatus == "SCHEDULED" || currentStatus == "COMPLETED" || activeOrderId == null -> {
                                resetCameraTilt(map)
                                if (originPlace?.latLng != null && destinationPlace?.latLng != null) {
                                    val boundsBuilder = LatLngBounds.Builder()
                                    boundsBuilder.include(originPlace.latLng!!)
                                    boundsBuilder.include(destinationPlace.latLng!!)
                                    currentWaypoints.forEach { boundsBuilder.include(it.first) }
                                    decodedRoutePoints?.forEach { boundsBuilder.include(it) }

                                    val labelSafePadding = convertDpToPixel(80f).toInt()
                                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), labelSafePadding), 400, null)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun animateMapPadding(
        map: GoogleMap?,
        activeOrderCard: View,
        fromHeight: Int,
        toHeight: Int,
        recenterMap: Boolean,
        currentRoutePolyline: String?,
        activeOrder: TaxiOrderDto?,
        originPlace: Place?,
        destinationPlace: Place?,
        currentWaypoints: List<Pair<LatLng, String>>,
        decodedRoutePoints: List<LatLng>?,
        driverMarkerPos: LatLng?
    ) {
        if (map == null || activeOrderCard.visibility != View.VISIBLE) return

        val params = activeOrderCard.layoutParams as? ViewGroup.MarginLayoutParams
        val marginBottom = params?.bottomMargin ?: 0
        val sideMargin = params?.leftMargin ?: 0
        val extraBuffer = convertDpToPixel(4f).toInt()
        val topPadding = convertDpToPixel(20f).toInt()

        var targetHeight = if (activeOrderCard.height > 0) activeOrderCard.height else toHeight
        val detailsView = activeOrderCard.findViewById<View>(R.id.layout_expandable_details)
        if (detailsView != null && detailsView.visibility == View.VISIBLE && detailsView.height > 0) {
            targetHeight -= detailsView.height
        }

        val totalBottomPadding = targetHeight + marginBottom + extraBuffer
        applyPaddingIfChanged(map, sideMargin, topPadding, sideMargin, totalBottomPadding)

        val currentStatus = activeOrder?.status
        if (currentStatus != "REQUESTED" && currentStatus != "OFFERING") {
            resetCameraTilt(map)
        }

        if (recenterMap && currentRoutePolyline != null) {
            try {
                when (currentStatus) {
                    "REQUESTED", "OFFERING" -> {
                        val originLoc = originPlace?.latLng
                            ?: activeOrder.let { LatLng(it.originLat ?: 0.0, it.originLng ?: 0.0) }
                        val cameraPosition = CameraPosition.Builder()
                            .target(originLoc)
                            .zoom(16.0f)
                            .tilt(45f)
                            .bearing(0f)
                            .build()
                        map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), 400, null)
                    }
                    "ACCEPTED" -> {
                        focusOnPickupRoute(
                            map = map,
                            originPlace = originPlace,
                            activeOrder = activeOrder,
                            driverMarkerPos = driverMarkerPos
                        )
                    }
                    "DRIVER_ARRIVED" -> {
                        val pos = driverMarkerPos ?: originPlace?.latLng
                        ?: activeOrder.let { LatLng(it.originLat ?: 0.0, it.originLng ?: 0.0) }
                        if (pos.latitude != 0.0 || pos.longitude != 0.0) {
                            animateCameraFlatZoom(map, pos, 16.5f, 400)
                        }
                    }
                    "IN_PROGRESS", "SCHEDULED", "COMPLETED" -> {
                        resetCameraTilt(map)
                        if (originPlace?.latLng != null && destinationPlace?.latLng != null) {
                            val boundsBuilder = LatLngBounds.Builder()
                            boundsBuilder.include(originPlace.latLng!!)
                            boundsBuilder.include(destinationPlace.latLng!!)
                            currentWaypoints.forEach { boundsBuilder.include(it.first) }
                            decodedRoutePoints?.forEach { boundsBuilder.include(it) }

                            val labelSafePadding = convertDpToPixel(80f).toInt()
                            map.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), labelSafePadding), 400, null)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}