package com.taxiapp.client.controllers

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

/**
 * Контроллер управления полилиниями маршрута, маркерами точек А/Б/остановок,
 * маршрутом подачи автомобиля, статичными плашками SmartLabels (как в Uklon),
 * прогрессивной анимацией заполнения маршрута ("гонка" от А до Б)
 * и единым умным наведением камеры на маршрут (оптимизировано под стабильные 60-120 FPS).
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

    // =========================================================================
    // СОСТОЯНИЕ СТАТИЧНЫХ SMART LABELS И ЕДИНЫХ ГРАНИЦ МАРШРУТА (UKLON-STYLE)
    // =========================================================================
    private var isSmartLabelsLocked: Boolean = false
    private var lockedForPathHash: Int = 0
    private var lockedOriginOffsetX: Float = 0f
    private var lockedOriginOffsetY: Float = 0f
    private var lockedDestOffsetX: Float = 0f
    private var lockedDestOffsetY: Float = 0f

    private var cachedOrigWidthPx: Float = 0f
    private var cachedOrigHeightPx: Float = 0f
    private var cachedDestWidthPx: Float = 0f
    private var cachedDestHeightPx: Float = 0f
    private var cachedMinCardGapPx: Float = 0f

    // Единые рассчитанные границы маршрута (включающие вылет статичных плашек) для 1-го показа и кнопки btnRecenterRoute
    private var cachedIdealRouteBounds: LatLngBounds? = null
    private var cachedIdealRoutePaddingPx: Int = 0
    private var isInitialRouteRecenterRunning: Boolean = false

    // =========================================================================
    // БЫСТРАЯ МАТЕМАТИЧЕСКАЯ ПРОЕКЦИЯ MERCATOR (0 BINDER IPC ВЫЗОВОВ ПРИ ДВИЖЕНИИ КАМЕРЫ)
    // И РАЗВЕДЕНИЕ ПО КАДРАМ VSYNC С НЕПРЕРЫВНОЙ АНИМАЦИЕЙ МАРШРУТА (60-120 FPS)
    // =========================================================================
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isProjectionCalibrated: Boolean = false
    private var isIdleCalibrationScheduled: Boolean = false
    private var lastCameraMoveUptimeMs: Long = 0L

    private var calibFocalScreenX: Float = 0f
    private var calibFocalScreenY: Float = 0f
    private var calibPadLeft: Int = -1
    private var calibPadTop: Int = -1
    private var calibPadRight: Int = -1
    private var calibPadBottom: Int = -1

    private var cachedOriginLat: Double = Double.NaN
    private var cachedOriginLng: Double = Double.NaN
    private var cachedOrigMercX: Double = 0.0
    private var cachedOrigMercY: Double = 0.0

    private var cachedDestLat: Double = Double.NaN
    private var cachedDestLng: Double = Double.NaN
    private var cachedDestMercX: Double = 0.0
    private var cachedDestMercY: Double = 0.0

    private var lastCamLat: Double = Double.NaN
    private var lastCamLng: Double = Double.NaN
    private var lastCamZoom: Float = Float.NaN

    private var lastIdleSyncMap: GoogleMap? = null
    private var lastIdleSyncStatus: String? = null
    private var lastIdleSyncOriginPlace: Place? = null
    private var lastIdleSyncDestPlace: Place? = null
    private var lastIdleSyncRoutePoints: List<LatLng>? = null
    private var lastIdleSyncOverlayOrigin: View? = null
    private var lastIdleSyncOverlayDest: View? = null
    private var lastBtnRecenterRouteRef: View? = null

    private val cameraIdleCalibrationRunnable = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.uptimeMillis() - lastCameraMoveUptimeMs
            if (elapsed < 150L) {
                mainHandler.postDelayed(this, 160L - elapsed)
                return
            }
            isIdleCalibrationScheduled = false
            try {
                val map = lastIdleSyncMap
                val ovOrig = lastIdleSyncOverlayOrigin
                val ovDest = lastIdleSyncOverlayDest
                if (map != null && ovOrig != null && ovDest != null) {
                    isProjectionCalibrated = false
                    updateSmartLabelsInternal(
                        map = map,
                        currentStatus = lastIdleSyncStatus,
                        originPlace = lastIdleSyncOriginPlace,
                        destinationPlace = lastIdleSyncDestPlace,
                        decodedRoutePoints = lastIdleSyncRoutePoints,
                        overlayOrigin = ovOrig,
                        overlayDest = ovDest,
                        forceExactIpcProjection = true
                    )
                }
            } catch (_: Exception) {}
        }
    }

    private data class LabelCandidate(
        val slotId: Int,
        val rect: RectF,
        val offsetX: Float,
        val offsetY: Float,
        val basePreferenceCost: Float
    )

    fun convertDpToPixel(dp: Float): Float {
        val metrics = activity.resources.displayMetrics
        return dp * (metrics.densityDpi / 160f)
    }

    fun getBitmapDescriptor(resId: Int): BitmapDescriptor? {
        return BitmapHelper.vectorToBitmapDescriptor(activity, resId)
    }

    /**
     * O(1) быстрый отпечаток маршрута (вместо тяжелого O(N) List.hashCode() на 1000 точек в каждом кадре).
     */
    private fun fastPathFingerprint(points: List<LatLng>?): Int {
        if (points.isNullOrEmpty()) return 0
        val first = points.first()
        val last = points.last()
        val mid = points[points.size ushr 1]
        var h = points.size
        h = 31 * h + first.latitude.hashCode()
        h = 31 * h + first.longitude.hashCode()
        h = 31 * h + mid.latitude.hashCode()
        h = 31 * h + last.latitude.hashCode()
        h = 31 * h + last.longitude.hashCode()
        return h
    }

    /**
     * Мгновенно сбрасывает 3D-наклон (tilt) и поворот (bearing) камеры в 0f.
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

    private fun applyPaddingIfChanged(map: GoogleMap, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        if (left != lastPadLeft || top != lastPadTop || right != lastPadRight || abs(bottom - lastPadBottom) > 2) {
            lastPadLeft = left
            lastPadTop = top
            lastPadRight = right
            lastPadBottom = bottom
            isProjectionCalibrated = false
            map.setPadding(left, top, right, bottom)
            return true
        }
        return false
    }

    fun computePickupEtaSeconds(): Int {
        return if (pickupRoutePoints.isNotEmpty()) {
            val meters = SphericalUtil.computeLength(pickupRoutePoints)
            (meters / 9.7).toInt().coerceAtLeast(180)
        } else {
            300
        }
    }

    /**
     * Локальный гео-расчет оставшегося времени поездки (в секундах) по реальному положению
     * водителя относительно полилинии маршрута и точки назначения (0 сетевых запросов к серверу).
     */
    fun computeRemainingTripDurationSeconds(
        driverPos: LatLng?,
        destPos: LatLng?,
        routePoints: List<LatLng>?,
        totalDistanceMeters: Int,
        totalDurationSeconds: Int
    ): Int? {
        if (driverPos == null || (driverPos.latitude == 0.0 && driverPos.longitude == 0.0)) {
            return null
        }

        if (!routePoints.isNullOrEmpty() && routePoints.size >= 2) {
            var closestSegmentIndex = 0
            var closestPointOnSegment = routePoints[0]
            var minDistanceToRoute = Double.MAX_VALUE

            for (i in 0 until routePoints.size - 1) {
                val p = findNearestPointOnSegment(driverPos, routePoints[i], routePoints[i + 1])
                val d = SphericalUtil.computeDistanceBetween(driverPos, p)
                if (d < minDistanceToRoute) {
                    minDistanceToRoute = d
                    closestSegmentIndex = i
                    closestPointOnSegment = p
                }
            }

            if (minDistanceToRoute <= 200.0) {
                var remainingMeters = SphericalUtil.computeDistanceBetween(
                    closestPointOnSegment,
                    routePoints[closestSegmentIndex + 1]
                )
                for (i in (closestSegmentIndex + 1) until (routePoints.size - 1)) {
                    remainingMeters += SphericalUtil.computeDistanceBetween(routePoints[i], routePoints[i + 1])
                }

                val fullRouteMeters = if (totalDistanceMeters > 0) {
                    totalDistanceMeters.toDouble()
                } else {
                    SphericalUtil.computeLength(routePoints)
                }

                val avgSpeedMps = if (fullRouteMeters > 50.0 && totalDurationSeconds > 0) {
                    (fullRouteMeters / totalDurationSeconds.toDouble()).coerceIn(5.0, 22.0)
                } else {
                    9.7
                }

                return (remainingMeters / avgSpeedMps).toInt().coerceAtLeast(60)
            }
        }

        val targetDest = destPos ?: routePoints?.lastOrNull() ?: return null
        if (targetDest.latitude == 0.0 && targetDest.longitude == 0.0) return null

        val straightMeters = SphericalUtil.computeDistanceBetween(driverPos, targetDest)
        val estimatedRoadMeters = straightMeters * 1.3
        return (estimatedRoadMeters / 9.7).toInt().coerceAtLeast(60)
    }

    fun clearMainRoutePolylines() {
        mainHandler.removeCallbacks(cameraIdleCalibrationRunnable)
        isIdleCalibrationScheduled = false
        routeAnimator?.cancel()
        routeAnimator = null
        revealAnimator?.cancel()
        revealAnimator = null
        markerRevealAnimator?.cancel()
        markerRevealAnimator = null
        isInitialRouteRecenterRunning = false

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
        isSmartLabelsLocked = false
        isProjectionCalibrated = false
        cachedIdealRouteBounds = null
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
        isSmartLabelsLocked = false
        isProjectionCalibrated = false
        lockedForPathHash = 0
        cachedIdealRouteBounds = null
        lastBtnRecenterRouteRef?.visibility = View.GONE
    }

    /**
     * Единый метод идеального наведения камеры на маршрут (используется и при 1-м построении маршрута,
     * и при обновлении паддингов панели тарифов, и при нажатии на кнопку btnRecenterRoute).
     */
    fun recenterOnRoute(
        map: GoogleMap?,
        originPlace: Place?,
        destinationPlace: Place?,
        currentWaypoints: List<Pair<LatLng, String>>,
        decodedRoutePoints: List<LatLng>?,
        overlayOrigin: View? = null,
        overlayDest: View? = null,
        durationMs: Int = 450,
        onFinishCallback: (() -> Unit)? = null
    ) {
        if (map == null) {
            onFinishCallback?.invoke()
            return
        }

        try {
            resetCameraTilt(map, forceStopAnimation = true)

            val boundsToUse = cachedIdealRouteBounds ?: buildAndLockRouteBoundsAndLabels(
                originPlace = originPlace,
                destinationPlace = destinationPlace,
                currentWaypoints = currentWaypoints,
                decodedRoutePoints = decodedRoutePoints,
                overlayOrigin = overlayOrigin,
                overlayDest = overlayDest
            )

            if (boundsToUse == null) {
                onFinishCallback?.invoke()
                return
            }

            val paddingPx = if (cachedIdealRoutePaddingPx > 0) {
                cachedIdealRoutePaddingPx
            } else {
                convertDpToPixel(42f).toInt()
            }

            val cameraUpdate = CameraUpdateFactory.newLatLngBounds(boundsToUse, paddingPx)
            if (durationMs > 0) {
                map.animateCamera(cameraUpdate, durationMs, object : GoogleMap.CancelableCallback {
                    override fun onFinish() {
                        isProjectionCalibrated = false
                        onFinishCallback?.invoke()
                    }

                    override fun onCancel() {
                        isProjectionCalibrated = false
                        onFinishCallback?.invoke()
                    }
                })
            } else {
                map.moveCamera(cameraUpdate)
                isProjectionCalibrated = false
                onFinishCallback?.invoke()
            }
        } catch (_: Exception) {
            onFinishCallback?.invoke()
        }
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

        lastBtnRecenterRouteRef = btnRecenterRoute

        val pathHash = fastPathFingerprint(path)
        if (pathHash == lastDrawnPathHash && polylineMain != null) {
            return
        }
        lastDrawnPathHash = pathHash
        isSmartLabelsLocked = false
        isProjectionCalibrated = false
        lockedForPathHash = 0
        cachedIdealRouteBounds = null

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

        // Упрощаем избыточные коллинеарные точки OSRM (с допуском 1.8 метра — меньше 0.4 пикселя на экране),
        // чтобы GL-поток карты рендерил основную полилинию без лишней вершинной нагрузки при зуме/свайпе.
        val renderMainPath = if (path.size > 110) {
            try {
                PolyUtil.simplify(path, 1.8)
            } catch (_: Exception) {
                path
            }
        } else {
            path
        }

        val borderOpts = PolylineOptions()
            .addAll(renderMainPath)
            .width(20f)
            .color(transparentBorder)
            .startCap(RoundCap())
            .endCap(RoundCap())
            .zIndex(1f)
        polylineBorder = map.addPolyline(borderOpts)

        val mainOpts = PolylineOptions()
            .addAll(renderMainPath)
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
                val (ow, oh) = prepareOverlayHardwareAndMeasure(overlayOrigin)
                cachedOrigWidthPx = ow
                cachedOrigHeightPx = oh
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
            val (dw, dh) = prepareOverlayHardwareAndMeasure(overlayDest)
            cachedDestWidthPx = dw
            cachedDestHeightPx = dh
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

        // Привязываем кнопку "Показать маршрут" (btnRecenterRoute) к единой новой логике наведения камеры!
        // Используем View.INVISIBLE вместо View.GONE, чтобы при старте жеста карты (переход в View.VISIBLE)
        // корневой RelativeLayout НЕ вызывал полный requestLayout() / onMeasure() и не ронял первый кадр свайпа!
        btnRecenterRoute.setOnClickListener {
            val currentPanel = if (activeOrderCard.visibility == View.VISIBLE) activeOrderCard else tariffsPanel
            applyRouteMapPaddingForPanel(map, currentPanel)
            recenterOnRoute(
                map = map,
                originPlace = originPlace,
                destinationPlace = destinationPlace,
                currentWaypoints = currentWaypoints,
                decodedRoutePoints = path,
                overlayOrigin = overlayOrigin,
                overlayDest = overlayDest,
                durationMs = 450
            )
            btnRecenterRoute.visibility = View.INVISIBLE
        }

        val triggerReveal = {
            activity.runOnUiThread {
                isInitialRouteRecenterRunning = false
                updateSmartLabelsInternal(
                    map = map,
                    currentStatus = activeOrderProvider()?.status,
                    originPlace = originPlace,
                    destinationPlace = destinationPlace,
                    decodedRoutePoints = path,
                    overlayOrigin = overlayOrigin,
                    overlayDest = overlayDest,
                    forceExactIpcProjection = true
                )
                startRouteRevealAnimation(
                    map = map,
                    colorMain = colorMain,
                    colorBorder = colorBorder,
                    path = renderMainPath,
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
            val visibleBottomPanel = if (activeOrderCard.visibility == View.VISIBLE) activeOrderCard else tariffsPanel

            visibleBottomPanel.post {
                try {
                    if (orderStatus != "CANCELLED") {
                        applyRouteMapPaddingForPanel(map, visibleBottomPanel)
                    }

                    // Сбрасываем возможный ранний лок до применения актуальных паддингов нижней панели
                    isSmartLabelsLocked = false
                    isProjectionCalibrated = false
                    cachedIdealRouteBounds = null

                    // 1 раз вычисляем статичные слоты SmartLabels и расширяем границы камеры с учетом габаритов плашек
                    buildAndLockRouteBoundsAndLabels(
                        originPlace = originPlace,
                        destinationPlace = destinationPlace,
                        currentWaypoints = currentWaypoints,
                        decodedRoutePoints = path,
                        overlayOrigin = overlayOrigin,
                        overlayDest = overlayDest
                    )
                    updateSmartLabelsInternal(
                        map = map,
                        currentStatus = activeOrderProvider()?.status,
                        originPlace = originPlace,
                        destinationPlace = destinationPlace,
                        decodedRoutePoints = path,
                        overlayOrigin = overlayOrigin,
                        overlayDest = overlayDest,
                        forceExactIpcProjection = true
                    )

                    if (orderStatus == "ACCEPTED" || orderStatus == "DRIVER_ARRIVED" || orderStatus == "IN_PROGRESS") {
                        resetCameraTilt(map)
                        triggerReveal()
                    } else {
                        resetCameraTilt(map, forceStopAnimation = true)
                        isInitialRouteRecenterRunning = true

                        visibleBottomPanel.postDelayed({
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                recenterOnRoute(
                                    map = map,
                                    originPlace = originPlace,
                                    destinationPlace = destinationPlace,
                                    currentWaypoints = currentWaypoints,
                                    decodedRoutePoints = path,
                                    overlayOrigin = overlayOrigin,
                                    overlayDest = overlayDest,
                                    durationMs = 500,
                                    onFinishCallback = triggerReveal
                                )
                            } else {
                                isInitialRouteRecenterRunning = false
                            }
                        }, 150)
                    }
                } catch (_: Exception) {
                    isInitialRouteRecenterRunning = false
                    triggerReveal()
                }
            }
            btnRecenterRoute.visibility = View.INVISIBLE
        }
    }

    /**
     * Вычисляет и синхронно применяет единый паддинг карты под нижнюю панель (tariffsPanel / activeOrderCard)
     * и верхнюю шапку, чтобы 1-й показ маршрута и кнопка btnRecenterRoute использовали идентичный viewport.
     */
    private fun applyRouteMapPaddingForPanel(map: GoogleMap, bottomPanel: View): Boolean {
        val lp = bottomPanel.layoutParams as? ViewGroup.MarginLayoutParams
        val horizontalMargins = (lp?.leftMargin ?: 0) + (lp?.rightMargin ?: 0)
        val targetWidth = if (bottomPanel.width > 0) {
            bottomPanel.width
        } else {
            activity.resources.displayMetrics.widthPixels - horizontalMargins
        }

        var panelHeight = bottomPanel.height
        if (panelHeight == 0) {
            val widthSpec = View.MeasureSpec.makeMeasureSpec(targetWidth, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            bottomPanel.measure(widthSpec, heightSpec)
            panelHeight = bottomPanel.measuredHeight
        }

        if (bottomPanel.id == R.id.active_order_card) {
            val detailsView = bottomPanel.findViewById<View>(R.id.layout_expandable_details)
            if (detailsView != null && detailsView.visibility == View.VISIBLE) {
                var detailsHeight = detailsView.height
                if (detailsHeight == 0) {
                    val widthSpec = View.MeasureSpec.makeMeasureSpec(targetWidth, View.MeasureSpec.EXACTLY)
                    val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    detailsView.measure(widthSpec, heightSpec)
                    detailsHeight = detailsView.measuredHeight
                }
                panelHeight -= detailsHeight
            }
        }

        val marginBottom = lp?.bottomMargin ?: 0
        val sideMargin = lp?.leftMargin ?: 0
        val paddingBottom = panelHeight + marginBottom
        val paddingTop = convertDpToPixel(14f).toInt()

        return applyPaddingIfChanged(map, sideMargin, paddingTop, sideMargin, paddingBottom)
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

        var lastRevealStepTime = 0L
        revealAnimator?.cancel()
        revealAnimator = ValueAnimator.ofInt(0, 255).apply {
            duration = 900
            addUpdateListener { animator ->
                val alpha = animator.animatedValue as Int
                val now = SystemClock.uptimeMillis()
                if (alpha < 255 && now - lastRevealStepTime < 28L) return@addUpdateListener
                lastRevealStepTime = now
                try {
                    val newMainColor = Color.argb(alpha, Color.red(colorMain), Color.green(colorMain), Color.blue(colorMain))
                    val newBorderColor = Color.argb(alpha, Color.red(colorBorder), Color.green(colorBorder), Color.blue(colorBorder))
                    polylineMain?.color = newMainColor
                    polylineBorder?.color = newBorderColor
                } catch (_: Exception) {}
            }
        }

        var lastMarkerStepTime = 0L
        markerRevealAnimator?.cancel()
        markerRevealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            addUpdateListener { animator ->
                val alpha = animator.animatedValue as Float
                val now = SystemClock.uptimeMillis()
                if (alpha < 1f && now - lastMarkerStepTime < 28L) return@addUpdateListener
                lastMarkerStepTime = now
                try {
                    originMarker?.alpha = alpha
                    destinationMarker?.alpha = alpha
                    waypointMarkers.forEach { it.alpha = alpha }
                } catch (_: Exception) {}
            }
        }

        overlayOrigin.animate().alpha(1f).setDuration(850).start()
        overlayDest.animate().alpha(1f).setDuration(850).start()

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

    /**
     * Анимация линии маршрута в стиле "гонки" (плавное прогрессивное заполнение от точки А до точки Б)
     * с сохранением всех оригинальных параметров (ширина 8f, zIndex 2.5f, RoundCap, цикл 5500ms, те же цвета).
     *
     * КАК ДОСТИГАЕТСЯ 60+ FPS ПРИ НЕПРЕРЫВНОЙ РАБОТЕ АНИМАЦИИ ВО ВРЕМЯ ДВИЖЕНИЯ КАРТЫ (КАК В UKLON):
     * 1) Упрощение геометрии Дугласа-Пекера (PolyUtil.simplify) для внутренней линии свечения (width 8f внутри 14f):
     *    вместо передачи 500-1500 точек OSRM через Binder IPC в каждом кадре, маршрут сжимается до ~35-55 ключевых
     *    поворотных вершин (прямые проспекты заменяются одним отрезком, по которому плавно скользит кончик линии).
     *    Размер Parcel и число вершин для OpenGL уменьшаются в 15-25 раз!
     * 2) Использование стандартного типа сопряжения вершин (как у основной линии polylineMain и polylineBorder)
     *    вместо тяжелого JointType.ROUND, который заставлял GL-поток карты строить веер из 12 треугольников на каждую вершину.
     * 3) Полный предрасчет всех кадров прогрессивного заполнения (prebuiltFrames) и цветов затухания (prebuiltFadeColors)
     *    1 раз при старте — внутри ValueAnimator 0 аллокаций памяти, 0 циклов копирования и 0 бинарных поисков.
     * 4) Разведение по фазе VSYNC с жестом карты: анимация никогда не останавливается при движении карты,
     *    но если в текущую миллисекунду уже выполнился onCameraMove, отправка кадра полилинии сдвигается на следующий тик VSYNC.
     */
    fun animateRouteDrawing(
        map: GoogleMap?,
        path: List<LatLng>,
        currentRoutePolylineProvider: () -> String?
    ) {
        if (path.size < 2 || map == null || currentRoutePolylineProvider() == null) return

        routeAnimator?.cancel()
        routeAnimator = null
        polylineAnim?.remove()
        polylineAnim = null

        val isDark = sessionManager.isDarkMode()
        val colorBase = ContextCompat.getColor(activity, R.color.route_main)
        val colorGlow = if (isDark) Color.rgb(240, 240, 240) else Color.rgb(112, 112, 112)

        // Сжимаем избыточные точки на прямых участках для 8px-линии внутри 14px-маршрута до ~45 ключевых вершин
        var animPath: List<LatLng> = try {
            if (path.size > 48) PolyUtil.simplify(path, 4.5) else path
        } catch (_: Exception) {
            path
        }
        if (animPath.size > 56) {
            animPath = try {
                PolyUtil.simplify(animPath, 8.5)
            } catch (_: Exception) {
                animPath
            }
        }
        if (animPath.size < 2) animPath = path

        val n = animPath.size
        val cumulativeDist = DoubleArray(n)
        var totalLength = 0.0
        for (i in 1 until n) {
            val dLat = animPath[i].latitude - animPath[i - 1].latitude
            val dLng = (animPath[i].longitude - animPath[i - 1].longitude) * 0.64
            val segLen = hypot(dLat, dLng)
            totalLength += segLen
            cumulativeDist[i] = totalLength
        }

        if (totalLength <= 1e-9) return

        // Предвычисляем 84 дискретных кадра заполнения маршрута (0 аллокаций и 0 циклов во время работы ValueAnimator!)
        val totalFillFrames = 84
        val prebuiltFrames = Array<List<LatLng>>(totalFillFrames) { frameIdx ->
            val rawFill = frameIdx.toFloat() / (totalFillFrames - 1).toFloat()
            val fillFraction = rawFill * (2f - rawFill) * 0.35f + rawFill * 0.65f
            val targetDist = fillFraction * totalLength

            val framePoints = ArrayList<LatLng>(n + 1)
            framePoints.add(animPath[0])

            var low = 0
            var high = n - 1
            while (low < high) {
                val mid = (low + high + 1) ushr 1
                if (cumulativeDist[mid] <= targetDist) {
                    low = mid
                } else {
                    high = mid - 1
                }
            }

            for (idx in 1..low) {
                framePoints.add(animPath[idx])
            }

            if (low < n - 1) {
                val segStartDist = cumulativeDist[low]
                val segEndDist = cumulativeDist[low + 1]
                val segSpan = segEndDist - segStartDist
                if (segSpan > 1e-9) {
                    val segT = ((targetDist - segStartDist) / segSpan).coerceIn(0.0, 1.0)
                    val pStart = animPath[low]
                    val pEnd = animPath[low + 1]
                    val interpLat = pStart.latitude + (pEnd.latitude - pStart.latitude) * segT
                    val interpLng = pStart.longitude + (pEnd.longitude - pStart.longitude) * segT
                    framePoints.add(LatLng(interpLat, interpLng))
                }
            }
            framePoints
        }

        val singleStartFrame = listOf(animPath[0])

        val argbEvaluator = ArgbEvaluator()
        val totalFadeSteps = 30
        val prebuiltFadeColors = IntArray(totalFadeSteps) { step ->
            val fadeFraction = 1f - (step.toFloat() / (totalFadeSteps - 1).toFloat()).coerceIn(0f, 1f)
            argbEvaluator.evaluate(fadeFraction, colorBase, colorGlow) as Int
        }

        val animOpts = PolylineOptions()
            .add(animPath[0])
            .width(8f)
            .color(colorGlow)
            .zIndex(2.5f)
            .startCap(RoundCap())
            .endCap(RoundCap())

        polylineAnim = map.addPolyline(animOpts)

        val totalDurationMs = 5500f
        val raceFillDurationMs = 3000f
        val fadeOutEndMs = 4500f

        var lastColor = colorGlow
        var lastFrameIdx = -1
        var wasCleared = false
        var lastIpcUpdateMs = 0L

        routeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = totalDurationMs.toLong()
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART

            addUpdateListener { animator ->
                try {
                    val currentPoly = polylineAnim
                    if (currentPoly == null) {
                        animator.cancel()
                        return@addUpdateListener
                    }

                    val nowMs = SystemClock.uptimeMillis()
                    val sinceCameraMoveMs = nowMs - lastCameraMoveUptimeMs
                    // Если в эту же миллисекунду (этот же кадр VSYNC) обрабатывается движение камеры —
                    // пропускаем ровно 1 тик (8-16 мс), чтобы не складывать два IPC-вызова в один кадр.
                    if (sinceCameraMoveMs in 0L..5L) {
                        return@addUpdateListener
                    }

                    val minIntervalMs = if (sinceCameraMoveMs < 90L) 36L else 28L
                    val progress = animator.animatedValue as Float
                    val timeMs = progress * totalDurationMs

                    when {
                        // Фаза 1: Гонка — плавное заполнение маршрута от точки А (0%) до точки Б (100%)
                        timeMs <= raceFillDurationMs -> {
                            wasCleared = false
                            val rawFill = (timeMs / raceFillDurationMs).coerceIn(0f, 1f)
                            val frameIdx = (rawFill * (totalFillFrames - 1)).toInt().coerceIn(0, totalFillFrames - 1)

                            if (frameIdx == lastFrameIdx) return@addUpdateListener
                            if (nowMs - lastIpcUpdateMs < minIntervalMs && frameIdx < totalFillFrames - 1) {
                                return@addUpdateListener
                            }

                            lastFrameIdx = frameIdx
                            lastIpcUpdateMs = nowMs

                            if (lastColor != colorGlow) {
                                lastColor = colorGlow
                                currentPoly.color = colorGlow
                            }
                            currentPoly.points = prebuiltFrames[frameIdx]
                        }

                        // Фаза 2: Плавное затухание заполненной линии после достижения финиша Б
                        timeMs <= fadeOutEndMs -> {
                            if (lastFrameIdx != totalFillFrames - 1) {
                                lastFrameIdx = totalFillFrames - 1
                                currentPoly.points = prebuiltFrames[totalFillFrames - 1]
                            }
                            val fadeProgress = ((timeMs - raceFillDurationMs) / (fadeOutEndMs - raceFillDurationMs)).coerceIn(0f, 1f)
                            val fadeStep = (fadeProgress * (totalFadeSteps - 1)).toInt().coerceIn(0, totalFadeSteps - 1)
                            val currentColor = prebuiltFadeColors[fadeStep]
                            if (currentColor != lastColor) {
                                lastColor = currentColor
                                lastIpcUpdateMs = nowMs
                                currentPoly.color = currentColor
                            }
                        }

                        // Фаза 3: Короткая пауза перед новым стартом от точки А
                        else -> {
                            if (!wasCleared) {
                                wasCleared = true
                                lastFrameIdx = -1
                                lastIpcUpdateMs = nowMs
                                currentPoly.points = singleStartFrame
                                if (lastColor != colorBase) {
                                    lastColor = colorBase
                                    currentPoly.color = colorBase
                                }
                            }
                        }
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
                    val target = driverPos ?: originPos ?: bounds.center
                    animateCameraFlatZoom(map, target, 16.0f, 800)
                } else {
                    val paddingSide = convertDpToPixel(64f).toInt()
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

    // =========================================================================
    // СТАТИЧНЫЕ SMART LABELS КАК В UKLON (0 BINDER IPC ВЫЗОВОВ + 60-120 FPS)
    // =========================================================================

    private fun prepareOverlayHardwareAndMeasure(view: View): Pair<Float, Float> {
        if (view.layerType != View.LAYER_TYPE_HARDWARE) {
            view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        if (view is ViewGroup && view.childCount >= 2) {
            val secondChild = view.getChildAt(1)
            if (secondChild !is CardView && secondChild !is TextView && secondChild.visibility != View.GONE) {
                secondChild.visibility = View.GONE
            }
        }
        val maxW = activity.resources.displayMetrics.widthPixels
        val maxH = activity.resources.displayMetrics.heightPixels
        view.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)
        )
        val mw = if (view.measuredWidth > 0) view.measuredWidth else view.width
        val mh = if (view.measuredHeight > 0) view.measuredHeight else view.height
        return Pair(mw.toFloat(), mh.toFloat())
    }

    private fun ensureMercatorCache(originLoc: LatLng?, destLoc: LatLng?) {
        if (originLoc != null) {
            if (originLoc.latitude != cachedOriginLat || originLoc.longitude != cachedOriginLng) {
                cachedOriginLat = originLoc.latitude
                cachedOriginLng = originLoc.longitude
                cachedOrigMercX = lngToMercatorX(originLoc.longitude)
                cachedOrigMercY = latToMercatorY(originLoc.latitude)
                isProjectionCalibrated = false
            }
        }
        if (destLoc != null) {
            if (destLoc.latitude != cachedDestLat || destLoc.longitude != cachedDestLng) {
                cachedDestLat = destLoc.latitude
                cachedDestLng = destLoc.longitude
                cachedDestMercX = lngToMercatorX(destLoc.longitude)
                cachedDestMercY = latToMercatorY(destLoc.latitude)
                isProjectionCalibrated = false
            }
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
        updateSmartLabelsInternal(
            map = map,
            currentStatus = currentStatus,
            originPlace = originPlace,
            destinationPlace = destinationPlace,
            decodedRoutePoints = decodedRoutePoints,
            overlayOrigin = overlayOrigin,
            overlayDest = overlayDest,
            forceExactIpcProjection = false
        )
    }

    private fun updateSmartLabelsInternal(
        map: GoogleMap?,
        currentStatus: String?,
        originPlace: Place?,
        destinationPlace: Place?,
        decodedRoutePoints: List<LatLng>?,
        overlayOrigin: View,
        overlayDest: View,
        forceExactIpcProjection: Boolean
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
        val originVisible = originLoc != null && overlayOrigin.visibility == View.VISIBLE
        val destVisible = destLoc != null && overlayDest.visibility == View.VISIBLE
        if (!originVisible && !destVisible) return

        val routeHash = fastPathFingerprint(decodedRoutePoints)
        if (!isSmartLabelsLocked || (routeHash != 0 && routeHash != lockedForPathHash)) {
            buildAndLockRouteBoundsAndLabels(
                originPlace = originPlace,
                destinationPlace = destinationPlace,
                currentWaypoints = emptyList(),
                decodedRoutePoints = decodedRoutePoints,
                overlayOrigin = overlayOrigin,
                overlayDest = overlayDest
            )
        }

        ensureMercatorCache(originLoc, destLoc)

        val camPos = map.cameraPosition
        val cameraMoved = camPos.target.latitude != lastCamLat ||
                camPos.target.longitude != lastCamLng ||
                camPos.zoom != lastCamZoom

        if (cameraMoved && !forceExactIpcProjection) {
            lastCamLat = camPos.target.latitude
            lastCamLng = camPos.target.longitude
            lastCamZoom = camPos.zoom
            lastCameraMoveUptimeMs = SystemClock.uptimeMillis()

            lastIdleSyncMap = map
            lastIdleSyncStatus = currentStatus
            lastIdleSyncOriginPlace = originPlace
            lastIdleSyncDestPlace = destinationPlace
            lastIdleSyncRoutePoints = decodedRoutePoints
            lastIdleSyncOverlayOrigin = overlayOrigin
            lastIdleSyncOverlayDest = overlayDest

            if (!isIdleCalibrationScheduled) {
                isIdleCalibrationScheduled = true
                mainHandler.postDelayed(cameraIdleCalibrationRunnable, 160L)
            }
        }

        val padChanged = calibPadLeft != lastPadLeft ||
                calibPadTop != lastPadTop ||
                calibPadRight != lastPadRight ||
                calibPadBottom != lastPadBottom

        val canUseFastMercatorMath = !forceExactIpcProjection &&
                isProjectionCalibrated &&
                !padChanged &&
                abs(camPos.tilt) < 0.1f &&
                abs(camPos.bearing) < 0.1f

        val density = activity.resources.displayMetrics.density.toDouble()
        val worldSizePx = 256.0 * density * 2.0.pow(camPos.zoom.toDouble())
        val camMercX = lngToMercatorX(camPos.target.longitude)
        val camMercY = latToMercatorY(camPos.target.latitude)

        var origPtX = 0f
        var origPtY = 0f
        var destPtX = 0f
        var destPtY = 0f

        if (canUseFastMercatorMath) {
            // БЫСТРЫЙ ПУТЬ ВО ВРЕМЯ ДВИЖЕНИЯ КАРТЫ: 0 вызовов map.projection.toScreenLocation (0 Binder IPC)
            if (originVisible) {
                origPtX = (calibFocalScreenX + (cachedOrigMercX - camMercX) * worldSizePx).toFloat()
                origPtY = (calibFocalScreenY + (cachedOrigMercY - camMercY) * worldSizePx).toFloat()
            }
            if (destVisible) {
                destPtX = (calibFocalScreenX + (cachedDestMercX - camMercX) * worldSizePx).toFloat()
                destPtY = (calibFocalScreenY + (cachedDestMercY - camMercY) * worldSizePx).toFloat()
            }
        } else {
            // ТОЧНАЯ КАЛИБРОВКА ПО ПРОЕКЦИИ КАРТЫ (1 раз при старте и после полной остановки камеры)
            val projection = map.projection
            if (originVisible) {
                val origPt = projection.toScreenLocation(originLoc!!)
                origPtX = origPt.x.toFloat()
                origPtY = origPt.y.toFloat()
                if (abs(camPos.tilt) < 0.1f && abs(camPos.bearing) < 0.1f) {
                    calibFocalScreenX = (origPtX - (cachedOrigMercX - camMercX) * worldSizePx).toFloat()
                    calibFocalScreenY = (origPtY - (cachedOrigMercY - camMercY) * worldSizePx).toFloat()
                    calibPadLeft = lastPadLeft
                    calibPadTop = lastPadTop
                    calibPadRight = lastPadRight
                    calibPadBottom = lastPadBottom
                    isProjectionCalibrated = true
                }
            }
            if (destVisible) {
                val destPt = projection.toScreenLocation(destLoc!!)
                destPtX = destPt.x.toFloat()
                destPtY = destPt.y.toFloat()
                if (!originVisible && abs(camPos.tilt) < 0.1f && abs(camPos.bearing) < 0.1f) {
                    calibFocalScreenX = (destPtX - (cachedDestMercX - camMercX) * worldSizePx).toFloat()
                    calibFocalScreenY = (destPtY - (cachedDestMercY - camMercY) * worldSizePx).toFloat()
                    calibPadLeft = lastPadLeft
                    calibPadTop = lastPadTop
                    calibPadRight = lastPadRight
                    calibPadBottom = lastPadBottom
                    isProjectionCalibrated = true
                }
            }
        }

        if (originVisible && destVisible) {
            var origX = origPtX + lockedOriginOffsetX
            var origY = origPtY + lockedOriginOffsetY
            var destX = destPtX + lockedDestOffsetX
            var destY = destPtY + lockedDestOffsetY

            val origW = if ( overlayOrigin.width > 0) overlayOrigin.width.toFloat() else cachedOrigWidthPx
            val origH = if ( overlayOrigin.height > 0) overlayOrigin.height.toFloat() else cachedOrigHeightPx
            val destW = if ( overlayDest.width > 0) overlayDest.width.toFloat() else cachedDestWidthPx
            val destH = if ( overlayDest.height > 0) overlayDest.height.toFloat() else cachedDestHeightPx

            if (origW > 0f && origH > 0f && destW > 0f && destH > 0f) {
                val minGap = if (cachedMinCardGapPx > 0f) cachedMinCardGapPx else convertDpToPixel(10f).also { cachedMinCardGapPx = it }
                val overlapX = min(origX + origW, destX + destW) - max(origX, destX) + minGap
                val overlapY = min(origY + origH, destY + destH) - max(origY, destY) + minGap

                if (overlapX > 0f && overlapY > 0f) {
                    val origCenterX = origX + origW * 0.5f
                    val destCenterX = destX + destW * 0.5f
                    val origCenterY = origY + origH * 0.5f
                    val destCenterY = destY + destH * 0.5f

                    if (overlapY <= overlapX * 1.15f) {
                        val origIsAbove = if (abs(origCenterY - destCenterY) > 2f) {
                            origCenterY <= destCenterY
                        } else {
                            origPtY <= destPtY
                        }
                        val shiftY = overlapY * 0.5f + 1f
                        if (origIsAbove) {
                            origY -= shiftY
                            destY += shiftY
                        } else {
                            origY += shiftY
                            destY -= shiftY
                        }
                    } else {
                        val origIsLeft = if (abs(origCenterX - destCenterX) > 2f) {
                            origCenterX <= destCenterX
                        } else {
                            origPtX <= destPtX
                        }
                        val shiftX = overlapX * 0.5f + 1f
                        if (origIsLeft) {
                            origX -= shiftX
                            destX += shiftX
                        } else {
                            origX += shiftX
                            destX -= shiftX
                        }
                    }
                }
            }

            if (abs(overlayOrigin.x - origX) >= 0.1f) overlayOrigin.x = origX
            if (abs(overlayOrigin.y - origY) >= 0.1f) overlayOrigin.y = origY
            if (abs(overlayDest.x - destX) >= 0.1f) overlayDest.x = destX
            if (abs(overlayDest.y - destY) >= 0.1f) overlayDest.y = destY
        } else if (originVisible) {
            val origX = origPtX + lockedOriginOffsetX
            val origY = origPtY + lockedOriginOffsetY
            if (abs(overlayOrigin.x - origX) >= 0.1f) overlayOrigin.x = origX
            if (abs(overlayOrigin.y - origY) >= 0.1f) overlayOrigin.y = origY
        } else if (destVisible) {
            val destX = destPtX + lockedDestOffsetX
            val destY = destPtY + lockedDestOffsetY
            if (abs(overlayDest.x - destX) >= 0.1f) overlayDest.x = destX
            if (abs(overlayDest.y - destY) >= 0.1f) overlayDest.y = destY
        }
    }

    private fun buildAndLockRouteBoundsAndLabels(
        originPlace: Place?,
        destinationPlace: Place?,
        currentWaypoints: List<Pair<LatLng, String>>,
        decodedRoutePoints: List<LatLng>?,
        overlayOrigin: View?,
        overlayDest: View?
    ): LatLngBounds? {
        val rawBoundsBuilder = LatLngBounds.Builder()
        var hasPoints = false

        val originLoc = originPlace?.latLng
        val destLoc = destinationPlace?.latLng
        ensureMercatorCache(originLoc, destLoc)

        if (originLoc != null) {
            rawBoundsBuilder.include(originLoc)
            hasPoints = true
        }
        if (destLoc != null) {
            rawBoundsBuilder.include(destLoc)
            hasPoints = true
        }
        currentWaypoints.forEach {
            if (it.first.latitude != 0.0 || it.first.longitude != 0.0) {
                rawBoundsBuilder.include(it.first)
                hasPoints = true
            }
        }
        decodedRoutePoints?.forEach {
            rawBoundsBuilder.include(it)
            hasPoints = true
        }

        if (!hasPoints) return null
        val rawBounds = rawBoundsBuilder.build()

        val metrics = activity.resources.displayMetrics
        val screenW = metrics.widthPixels.toFloat()
        val screenH = metrics.heightPixels.toFloat()

        val padLeft = if (lastPadLeft >= 0) lastPadLeft.toFloat() else 0f
        val padRight = if (lastPadRight >= 0) lastPadRight.toFloat() else 0f
        val padBottom = if (lastPadBottom > 0) lastPadBottom.toFloat() else screenH * 0.45f

        val baseSidePaddingPx = convertDpToPixel(42f)
        cachedIdealRoutePaddingPx = baseSidePaddingPx.toInt()
        cachedMinCardGapPx = convertDpToPixel(10f)

        val topSafeY = convertDpToPixel(56f)
        val bottomSafeY = (screenH - padBottom - convertDpToPixel(10f)).coerceAtLeast(topSafeY + convertDpToPixel(140f))
        val sideSafeMargin = convertDpToPixel(12f)
        val safeRect = RectF(sideSafeMargin, topSafeY, screenW - sideSafeMargin, bottomSafeY)

        val availW = max(convertDpToPixel(120f), screenW - padLeft - padRight - baseSidePaddingPx * 2f)
        val availH = max(convertDpToPixel(120f), (bottomSafeY - topSafeY) - baseSidePaddingPx * 1.6f)
        val viewportCenterX = padLeft + (screenW - padLeft - padRight) * 0.5f
        val viewportCenterY = (topSafeY + bottomSafeY) * 0.5f

        val swMercX = lngToMercatorX(rawBounds.southwest.longitude)
        val neMercX = lngToMercatorX(rawBounds.northeast.longitude)
        val neMercY = latToMercatorY(rawBounds.northeast.latitude)
        val swMercY = latToMercatorY(rawBounds.southwest.latitude)

        val spanMercX = max(1e-7, neMercX - swMercX)
        val spanMercY = max(1e-7, swMercY - neMercY)

        val maxScale = 256.0 * metrics.density * (1 shl 18)
        val scale = min(maxScale, min(availW.toDouble() / spanMercX, availH.toDouble() / spanMercY))

        val centerMercX = (swMercX + neMercX) * 0.5
        val centerMercY = (neMercY + swMercY) * 0.5

        fun toVirtualScreen(latLng: LatLng): PointF {
            val mx = lngToMercatorX(latLng.longitude)
            val my = latToMercatorY(latLng.latitude)
            val sx = (viewportCenterX + (mx - centerMercX) * scale).toFloat()
            val sy = (viewportCenterY + (my - centerMercY) * scale).toFloat()
            return PointF(sx, sy)
        }

        fun fromVirtualScreen(sx: Float, sy: Float): LatLng {
            val mx = centerMercX + (sx - viewportCenterX).toDouble() / scale
            val my = centerMercY + (sy - viewportCenterY).toDouble() / scale
            return LatLng(
                mercatorYToLat(my).coerceIn(-85.0, 85.0),
                mercatorXToLng(mx).coerceIn(-180.0, 180.0)
            )
        }

        val origPin = originLoc?.let { toVirtualScreen(it) }
        val dstPin = destLoc?.let { toVirtualScreen(it) }

        var routeMinX = origPin?.x ?: dstPin?.x ?: viewportCenterX
        var routeMaxX = routeMinX
        var routeMinY = origPin?.y ?: dstPin?.y ?: viewportCenterY
        var routeMaxY = routeMinY

        fun expandRouteBox(pt: PointF?) {
            if (pt == null) return
            if (pt.x < routeMinX) routeMinX = pt.x
            if (pt.x > routeMaxX) routeMaxX = pt.x
            if (pt.y < routeMinY) routeMinY = pt.y
            if (pt.y > routeMaxY) routeMaxY = pt.y
        }

        expandRouteBox(origPin)
        expandRouteBox(dstPin)

        val currentHash = fastPathFingerprint(decodedRoutePoints).let { if (it != 0) it else rawBounds.hashCode() }
        if (!isSmartLabelsLocked || lockedForPathHash != currentHash) {
            val routeScreenPts = ArrayList<PointF>(128)
            if (!decodedRoutePoints.isNullOrEmpty()) {
                val totalPts = decodedRoutePoints.size
                if (totalPts <= 120) {
                    for (idx in 0 until totalPts) {
                        val pt = toVirtualScreen(decodedRoutePoints[idx])
                        routeScreenPts.add(pt)
                        expandRouteBox(pt)
                    }
                } else {
                    val edgeKeep = 20
                    for (idx in 0 until edgeKeep) {
                        val pt = toVirtualScreen(decodedRoutePoints[idx])
                        routeScreenPts.add(pt)
                        expandRouteBox(pt)
                    }
                    val midStep = max(1, (totalPts - 2 * edgeKeep) / 80)
                    var idx = edgeKeep
                    while (idx < totalPts - edgeKeep) {
                        val pt = toVirtualScreen(decodedRoutePoints[idx])
                        routeScreenPts.add(pt)
                        expandRouteBox(pt)
                        idx += midStep
                    }
                    for (tailIdx in (totalPts - edgeKeep) until totalPts) {
                        val pt = toVirtualScreen(decodedRoutePoints[tailIdx])
                        routeScreenPts.add(pt)
                        expandRouteBox(pt)
                    }
                }
            }

            val waypointScreenPts = currentWaypoints.mapNotNull {
                if (it.first.latitude != 0.0 || it.first.longitude != 0.0) {
                    val pt = toVirtualScreen(it.first)
                    expandRouteBox(pt)
                    pt
                } else null
            }

            val uiObstacles = listOf(
                RectF(0f, 0f, convertDpToPixel(72f), convertDpToPixel(104f)),
                RectF(screenW - convertDpToPixel(72f), 0f, screenW, convertDpToPixel(104f)),
                RectF(0f, bottomSafeY - convertDpToPixel(28f), convertDpToPixel(82f), bottomSafeY + convertDpToPixel(10f))
            )

            val maxFreePanX = max(0f, (availW - (routeMaxX - routeMinX)) * 0.5f)
            val maxFreePanY = max(0f, (availH - (routeMaxY - routeMinY)) * 0.5f)
            val dp1 = convertDpToPixel(1f).coerceAtLeast(0.5f)

            val (origW, origH) = if (overlayOrigin != null) {
                prepareOverlayHardwareAndMeasure(overlayOrigin).also {
                    cachedOrigWidthPx = it.first
                    cachedOrigHeightPx = it.second
                }
            } else {
                Pair(convertDpToPixel(156f), convertDpToPixel(54f))
            }

            val (destW, destH) = if (overlayDest != null) {
                prepareOverlayHardwareAndMeasure(overlayDest).also {
                    cachedDestWidthPx = it.first
                    cachedDestHeightPx = it.second
                }
            } else {
                Pair(convertDpToPixel(156f), convertDpToPixel(54f))
            }

            if (origPin != null && dstPin != null) {
                val origCandidates = generateStaticCandidates(origPin, origW, origH)
                val destCandidates = generateStaticCandidates(dstPin, destW, destH)

                val origRouteDir = computeRouteDepartureVector(origPin, routeScreenPts, isStart = true)
                val destRouteDir = computeRouteDepartureVector(dstPin, routeScreenPts, isStart = false)
                val origLocalRouteDir = computeLocalRouteDensityVector(origPin, routeScreenPts, origW, origH)
                val destLocalRouteDir = computeLocalRouteDensityVector(dstPin, routeScreenPts, destW, destH)

                val origBaseCosts = FloatArray(origCandidates.size) { i ->
                    evaluateStaticCandidateRouteCost(
                        cand = origCandidates[i],
                        ownPin = origPin,
                        otherPin = dstPin,
                        w = origW,
                        h = origH,
                        routePts = routeScreenPts,
                        routeDepartureDir = origRouteDir,
                        localRouteDir = origLocalRouteDir,
                        waypointPts = waypointScreenPts
                    )
                }

                val destBaseCosts = FloatArray(destCandidates.size) { j ->
                    evaluateStaticCandidateRouteCost(
                        cand = destCandidates[j],
                        ownPin = dstPin,
                        otherPin = origPin,
                        w = destW,
                        h = destH,
                        routePts = routeScreenPts,
                        routeDepartureDir = destRouteDir,
                        localRouteDir = destLocalRouteDir,
                        waypointPts = waypointScreenPts
                    )
                }

                val minCardGap = cachedMinCardGapPx
                val repulsionRadius = convertDpToPixel(28f)
                val maxResolvableOverlapPx = convertDpToPixel(34f)
                val pinsDist = hypot(origPin.x - dstPin.x, origPin.y - dstPin.y)

                var bestOrigIdx = 0
                var bestDestIdx = 0
                var bestTotalCost = Float.MAX_VALUE
                var bestPostOrigPin: PointF = origPin
                var bestPostDstPin: PointF = dstPin

                for (i in origCandidates.indices) {
                    val candO = origCandidates[i]
                    val costO = origBaseCosts[i]
                    if (costO >= bestTotalCost) continue

                    for (j in destCandidates.indices) {
                        val candD = destCandidates[j]
                        var pairCost = costO + destBaseCosts[j]
                        if (pairCost >= bestTotalCost) continue

                        val combLeft = min(routeMinX, min(candO.rect.left, candD.rect.left))
                        val combRight = max(routeMaxX, max(candO.rect.right, candD.rect.right))
                        val combTop = min(routeMinY, min(candO.rect.top, candD.rect.top))
                        val combBottom = max(routeMaxY, max(candO.rect.bottom, candD.rect.bottom))

                        val rawPanX = viewportCenterX - (combLeft + combRight) * 0.5f
                        val rawPanY = viewportCenterY - (combTop + combBottom) * 0.5f
                        val sharedPanX = rawPanX.coerceIn(-maxFreePanX, maxFreePanX)
                        val sharedPanY = rawPanY.coerceIn(-maxFreePanY, maxFreePanY)

                        pairCost += evaluateShiftedScreenBoundsAndObstaclesCost(
                            rect = candO.rect,
                            panX = sharedPanX,
                            panY = sharedPanY,
                            safeRect = safeRect,
                            uiObstacles = uiObstacles
                        )
                        pairCost += evaluateShiftedScreenBoundsAndObstaclesCost(
                            rect = candD.rect,
                            panX = sharedPanX,
                            panY = sharedPanY,
                            safeRect = safeRect,
                            uiObstacles = uiObstacles
                        )

                        val combSpanX = max(1f, combRight - combLeft)
                        val combSpanY = max(1f, combBottom - combTop)
                        val overSpanX = max(0f, combSpanX - availW)
                        val overSpanY = max(0f, combSpanY - availH)
                        if (overSpanX > 0f) pairCost += (overSpanX / dp1) * 18f
                        if (overSpanY > 0f) pairCost += (overSpanY / dp1) * 14f

                        if (pairCost >= bestTotalCost) continue

                        val postZoomFactor = (min(1f, min(availW / combSpanX, availH / combSpanY)) * 0.86f).coerceIn(0.45f, 1f)
                        val expCenterX = (combLeft + combRight) * 0.5f
                        val expCenterY = (combTop + combBottom) * 0.5f

                        val postOrigPinX = viewportCenterX + (origPin.x - expCenterX) * postZoomFactor
                        val postOrigPinY = viewportCenterY + (origPin.y - expCenterY) * postZoomFactor
                        val postDstPinX = viewportCenterX + (dstPin.x - expCenterX) * postZoomFactor
                        val postDstPinY = viewportCenterY + (dstPin.y - expCenterY) * postZoomFactor

                        val postOrigLeft = postOrigPinX + candO.offsetX
                        val postOrigTop = postOrigPinY + candO.offsetY
                        val postOrigRight = postOrigLeft + origW
                        val postOrigBottom = postOrigTop + origH

                        val postDestLeft = postDstPinX + candD.offsetX
                        val postDestTop = postDstPinY + candD.offsetY
                        val postDestRight = postDestLeft + destW
                        val postDestBottom = postDestTop + destH

                        val overlapX = min(postOrigRight, postDestRight) - max(postOrigLeft, postDestLeft) + minCardGap
                        val overlapY = min(postOrigBottom, postDestBottom) - max(postOrigTop, postDestTop) + minCardGap

                        if (overlapX > 0f && overlapY > 0f) {
                            val smallerOverlap = min(overlapX, overlapY)
                            var canResolveCleanly = false
                            if (smallerOverlap <= maxResolvableOverlapPx) {
                                val shift = smallerOverlap * 0.5f + 1f
                                if (overlapX <= overlapY * 1.25f) {
                                    val oIsLeft = (postOrigLeft + postOrigRight) <= (postDestLeft + postDestRight)
                                    val newMinLeft = if (oIsLeft) postOrigLeft - shift else postDestLeft - shift
                                    val newMaxRight = if (oIsLeft) postDestRight + shift else postOrigRight + shift
                                    if (newMinLeft >= safeRect.left && newMaxRight <= safeRect.right) {
                                        canResolveCleanly = true
                                    }
                                } else {
                                    val oIsAbove = (postOrigTop + postOrigBottom) <= (postDestTop + postDestBottom)
                                    val newMinTop = if (oIsAbove) postOrigTop - shift else postDestTop - shift
                                    val newMaxBottom = if (oIsAbove) postDestBottom + shift else postOrigBottom + shift
                                    if (newMinTop >= safeRect.top && newMaxBottom <= safeRect.bottom) {
                                        canResolveCleanly = true
                                    }
                                }
                            }

                            if (canResolveCleanly) {
                                pairCost += 320f + (smallerOverlap / dp1) * 14f
                            } else {
                                pairCost += 28000f + (overlapX * overlapY) * 2.0f
                            }
                        } else {
                            val dx = max(0f, max(postOrigLeft - postDestRight, postDestLeft - postOrigRight))
                            val dy = max(0f, max(postOrigTop - postDestBottom, postDestTop - postOrigBottom))
                            val gapDist = hypot(dx, dy)
                            if (gapDist < repulsionRadius) {
                                val t = (repulsionRadius - gapDist) / repulsionRadius
                                pairCost += t * t * 220f
                            }
                        }

                        if (pinsDist < convertDpToPixel(200f)) {
                            val vecOx = candO.rect.centerX() - origPin.x
                            val vecOy = candO.rect.centerY() - origPin.y
                            val vecDx = candD.rect.centerX() - dstPin.x
                            val vecDy = candD.rect.centerY() - dstPin.y

                            val lenO = max(1f, hypot(vecOx, vecOy))
                            val lenD = max(1f, hypot(vecDx, vecDy))
                            val cosAngle = (vecOx * vecDx + vecOy * vecDy) / (lenO * lenD)
                            pairCost += cosAngle * 95f
                        }

                        if (pairCost < bestTotalCost) {
                            bestTotalCost = pairCost
                            bestOrigIdx = i
                            bestDestIdx = j
                            bestPostOrigPin = PointF(postOrigPinX, postOrigPinY)
                            bestPostDstPin = PointF(postDstPinX, postDstPinY)
                        }
                    }
                }

                val chosenOrig = origCandidates[bestOrigIdx]
                val chosenDest = destCandidates[bestDestIdx]

                val postOrigRect = RectF(
                    bestPostOrigPin.x + chosenOrig.offsetX,
                    bestPostOrigPin.y + chosenOrig.offsetY,
                    bestPostOrigPin.x + chosenOrig.offsetX + origW,
                    bestPostOrigPin.y + chosenOrig.offsetY + origH
                )
                val postDestRect = RectF(
                    bestPostDstPin.x + chosenDest.offsetX,
                    bestPostDstPin.y + chosenDest.offsetY,
                    bestPostDstPin.x + chosenDest.offsetX + destW,
                    bestPostDstPin.y + chosenDest.offsetY + destH
                )

                resolveResidualOverlapStatic(postOrigRect, bestPostOrigPin, postDestRect, bestPostDstPin, minCardGap)

                lockedOriginOffsetX = postOrigRect.left - bestPostOrigPin.x
                lockedOriginOffsetY = postOrigRect.top - bestPostOrigPin.y
                lockedDestOffsetX = postDestRect.left - bestPostDstPin.x
                lockedDestOffsetY = postDestRect.top - bestPostDstPin.y
            } else if (origPin != null) {
                val origCandidates = generateStaticCandidates(origPin, origW, origH)
                val origRouteDir = computeRouteDepartureVector(origPin, routeScreenPts, isStart = true)
                val origLocalRouteDir = computeLocalRouteDensityVector(origPin, routeScreenPts, origW, origH)
                var bestIdx = 0
                var bestCost = Float.MAX_VALUE
                for (i in origCandidates.indices) {
                    val cand = origCandidates[i]
                    val combLeft = min(routeMinX, cand.rect.left)
                    val combRight = max(routeMaxX, cand.rect.right)
                    val combTop = min(routeMinY, cand.rect.top)
                    val combBottom = max(routeMaxY, cand.rect.bottom)
                    val panX = (viewportCenterX - (combLeft + combRight) * 0.5f).coerceIn(-maxFreePanX, maxFreePanX)
                    val panY = (viewportCenterY - (combTop + combBottom) * 0.5f).coerceIn(-maxFreePanY, maxFreePanY)

                    val c = evaluateStaticCandidateRouteCost(
                        cand = cand,
                        ownPin = origPin,
                        otherPin = null,
                        w = origW,
                        h = origH,
                        routePts = routeScreenPts,
                        routeDepartureDir = origRouteDir,
                        localRouteDir = origLocalRouteDir,
                        waypointPts = waypointScreenPts
                    ) + evaluateShiftedScreenBoundsAndObstaclesCost(cand.rect, panX, panY, safeRect, uiObstacles)

                    if (c < bestCost) {
                        bestCost = c
                        bestIdx = i
                    }
                }
                lockedOriginOffsetX = origCandidates[bestIdx].offsetX
                lockedOriginOffsetY = origCandidates[bestIdx].offsetY
            } else if (dstPin != null) {
                val destCandidates = generateStaticCandidates(dstPin, destW, destH)
                val destRouteDir = computeRouteDepartureVector(dstPin, routeScreenPts, isStart = false)
                val destLocalRouteDir = computeLocalRouteDensityVector(dstPin, routeScreenPts, destW, destH)
                var bestIdx = 0
                var bestCost = Float.MAX_VALUE
                for (j in destCandidates.indices) {
                    val cand = destCandidates[j]
                    val combLeft = min(routeMinX, cand.rect.left)
                    val combRight = max(routeMaxX, cand.rect.right)
                    val combTop = min(routeMinY, cand.rect.top)
                    val combBottom = max(routeMaxY, cand.rect.bottom)
                    val panX = (viewportCenterX - (combLeft + combRight) * 0.5f).coerceIn(-maxFreePanX, maxFreePanX)
                    val panY = (viewportCenterY - (combTop + combBottom) * 0.5f).coerceIn(-maxFreePanY, maxFreePanY)

                    val c = evaluateStaticCandidateRouteCost(
                        cand = cand,
                        ownPin = dstPin,
                        otherPin = null,
                        w = destW,
                        h = destH,
                        routePts = routeScreenPts,
                        routeDepartureDir = destRouteDir,
                        localRouteDir = destLocalRouteDir,
                        waypointPts = waypointScreenPts
                    ) + evaluateShiftedScreenBoundsAndObstaclesCost(cand.rect, panX, panY, safeRect, uiObstacles)

                    if (c < bestCost) {
                        bestCost = c
                        bestIdx = j
                    }
                }
                lockedDestOffsetX = destCandidates[bestIdx].offsetX
                lockedDestOffsetY = destCandidates[bestIdx].offsetY
            }

            isSmartLabelsLocked = true
            lockedForPathHash = currentHash
        }

        val expandedBoundsBuilder = LatLngBounds.Builder()
        expandedBoundsBuilder.include(rawBounds.southwest)
        expandedBoundsBuilder.include(rawBounds.northeast)

        if (originLoc != null && (overlayOrigin == null || overlayOrigin.visibility == View.VISIBLE)) {
            val origW = if (cachedOrigWidthPx > 0f) cachedOrigWidthPx else convertDpToPixel(156f)
            val origH = if (cachedOrigHeightPx > 0f) cachedOrigHeightPx else convertDpToPixel(54f)
            val origPinPt = origPin ?: toVirtualScreen(originLoc)
            val l = origPinPt.x + lockedOriginOffsetX
            val t = origPinPt.y + lockedOriginOffsetY
            val r = l + origW
            val b = t + origH
            expandedBoundsBuilder.include(fromVirtualScreen(l, t))
            expandedBoundsBuilder.include(fromVirtualScreen(r, b))
        }

        if (destLoc != null && (overlayDest == null || overlayDest.visibility == View.VISIBLE)) {
            val destW = if (cachedDestWidthPx > 0f) cachedDestWidthPx else convertDpToPixel(156f)
            val destH = if (cachedDestHeightPx > 0f) cachedDestHeightPx else convertDpToPixel(54f)
            val dstPinPt = dstPin ?: toVirtualScreen(destLoc)
            val l = dstPinPt.x + lockedDestOffsetX
            val t = dstPinPt.y + lockedDestOffsetY
            val r = l + destW
            val b = t + destH
            expandedBoundsBuilder.include(fromVirtualScreen(l, t))
            expandedBoundsBuilder.include(fromVirtualScreen(r, b))
        }

        cachedIdealRouteBounds = expandedBoundsBuilder.build()
        return cachedIdealRouteBounds
    }

    private fun generateStaticCandidates(
        pin: PointF,
        w: Float,
        h: Float
    ): List<LabelCandidate> {
        val pinRadius = convertDpToPixel(9f)
        val cornerShift = convertDpToPixel(22f)
        val outerGap = convertDpToPixel(4f)

        val tiers = floatArrayOf(
            pinRadius + convertDpToPixel(5f),
            pinRadius + convertDpToPixel(18f)
        )

        val list = ArrayList<LabelCandidate>(32)

        for (tIndex in tiers.indices) {
            val offset = tiers[tIndex]
            val tierPenalty = tIndex * 45f
            val baseId = tIndex * 100

            addStaticCandidate(list, baseId + 1, pin, -cornerShift, offset, w, h, tierPenalty + 0f)
            addStaticCandidate(list, baseId + 2, pin, -w + cornerShift, offset, w, h, tierPenalty + 0f)
            addStaticCandidate(list, baseId + 3, pin, -w + cornerShift, -offset - h, w, h, tierPenalty + 2f)
            addStaticCandidate(list, baseId + 4, pin, -cornerShift, -offset - h, w, h, tierPenalty + 2f)
            addStaticCandidate(list, baseId + 5, pin, -w * 0.5f, offset, w, h, tierPenalty + 5f)
            addStaticCandidate(list, baseId + 6, pin, -w * 0.5f, -offset - h, w, h, tierPenalty + 5f)
            addStaticCandidate(list, baseId + 7, pin, -w - outerGap, -offset * 0.85f - h, w, h, tierPenalty + 8f)
            addStaticCandidate(list, baseId + 8, pin, outerGap, -offset * 0.85f - h, w, h, tierPenalty + 8f)
            addStaticCandidate(list, baseId + 9, pin, -w - outerGap, offset * 0.85f, w, h, tierPenalty + 8f)
            addStaticCandidate(list, baseId + 10, pin, outerGap, offset * 0.85f, w, h, tierPenalty + 8f)
            addStaticCandidate(list, baseId + 11, pin, -offset - w, -h * 0.5f, w, h, tierPenalty + 14f)
            addStaticCandidate(list, baseId + 12, pin, offset, -h * 0.5f, w, h, tierPenalty + 14f)
            // Боковые смещенные слоты (верхне-боковые и нижне-боковые): идеально ложатся внутрь экрана
            // на диагональных маршрутах, обходя петли у пинов и не вылетая за верхнюю/нижнюю границы
            addStaticCandidate(list, baseId + 13, pin, -offset - w, -h * 0.82f, w, h, tierPenalty + 12f)
            addStaticCandidate(list, baseId + 14, pin, offset, -h * 0.82f, w, h, tierPenalty + 12f)
            addStaticCandidate(list, baseId + 15, pin, -offset - w, -h * 0.16f, w, h, tierPenalty + 12f)
            addStaticCandidate(list, baseId + 16, pin, offset, -h * 0.16f, w, h, tierPenalty + 12f)
        }

        return list
    }

    private fun addStaticCandidate(
        out: MutableList<LabelCandidate>,
        slotId: Int,
        pin: PointF,
        offsetX: Float,
        offsetY: Float,
        w: Float,
        h: Float,
        baseCost: Float
    ) {
        val left = pin.x + offsetX
        val top = pin.y + offsetY
        out.add(
            LabelCandidate(
                slotId = slotId,
                rect = RectF(left, top, left + w, top + h),
                offsetX = offsetX,
                offsetY = offsetY,
                basePreferenceCost = baseCost
            )
        )
    }

    private fun computeRouteDepartureVector(
        pin: PointF,
        routePts: List<PointF>,
        isStart: Boolean
    ): PointF? {
        if (routePts.size < 2) return null
        val minRadius = convertDpToPixel(22f)
        val maxRadius = convertDpToPixel(130f)

        var sumX = 0f
        var sumY = 0f
        var count = 0

        val indices = if (isStart) routePts.indices else routePts.indices.reversed()
        for (idx in indices) {
            val pt = routePts[idx]
            val dx = pt.x - pin.x
            val dy = pt.y - pin.y
            val dist = hypot(dx, dy)
            if (dist >= minRadius) {
                sumX += dx / dist
                sumY += dy / dist
                count++
                if (dist >= maxRadius || count >= 4) break
            }
        }

        if (count == 0) {
            val fallbackPt = if (isStart) routePts.last() else routePts.first()
            val dx = fallbackPt.x - pin.x
            val dy = fallbackPt.y - pin.y
            val dist = hypot(dx, dy)
            if (dist > 1f) return PointF(dx / dist, dy / dist)
            return null
        }

        val norm = hypot(sumX, sumY)
        return if (norm > 0.01f) PointF(sumX / norm, sumY / norm) else null
    }

    /**
     * Вычисляет интегральный вектор локальной массы маршрута в окрестности пина (в радиусе ~1.45 габарита плашки).
     */
    private fun computeLocalRouteDensityVector(
        pin: PointF,
        routePts: List<PointF>,
        w: Float,
        h: Float
    ): PointF? {
        if (routePts.size < 2) return null
        val maxRadius = max(w, h) * 1.45f
        val minRadius = convertDpToPixel(6f)
        val sampleStepPx = convertDpToPixel(8f).coerceAtLeast(4f)
        val minWeightDist = convertDpToPixel(18f).coerceAtLeast(1f)

        var sumX = 0f
        var sumY = 0f

        for (k in 0 until routePts.size - 1) {
            val a = routePts[k]
            val b = routePts[k + 1]
            val segLen = hypot(b.x - a.x, b.y - a.y)
            val steps = max(1, (segLen / sampleStepPx).toInt())
            for (s in 0..steps) {
                val t = s.toFloat() / steps.toFloat()
                val px = a.x + (b.x - a.x) * t
                val py = a.y + (b.y - a.y) * t
                val dx = px - pin.x
                val dy = py - pin.y
                val dist = hypot(dx, dy)
                if (dist in minRadius..maxRadius) {
                    val radialDecay = 1f - (dist / maxRadius) * 0.75f
                    val weight = radialDecay / max(dist, minWeightDist)
                    sumX += (dx / dist) * weight
                    sumY += (dy / dist) * weight
                }
            }
        }

        val norm = hypot(sumX, sumY)
        return if (norm > 0.001f) PointF(sumX / norm, sumY / norm) else null
    }

    private fun evaluateStaticCandidateRouteCost(
        cand: LabelCandidate,
        ownPin: PointF,
        otherPin: PointF?,
        w: Float,
        h: Float,
        routePts: List<PointF>,
        routeDepartureDir: PointF?,
        localRouteDir: PointF?,
        waypointPts: List<PointF>
    ): Float {
        var cost = cand.basePreferenceCost
        val rect = cand.rect
        val dp1 = convertDpToPixel(1f).coerceAtLeast(0.5f)
        val pinClearanceRadius = convertDpToPixel(11f)
        val otherPinClearanceRadius = convertDpToPixel(15f)

        if (circleIntersectsRect(ownPin.x, ownPin.y, pinClearanceRadius, rect)) {
            cost += 22000f
        }

        if (otherPin != null && circleIntersectsRect(otherPin.x, otherPin.y, otherPinClearanceRadius, rect)) {
            cost += 26000f
        }

        val wpRadius = convertDpToPixel(10f)
        for (wp in waypointPts) {
            if (circleIntersectsRect(wp.x, wp.y, wpRadius, rect)) {
                cost += 6000f
            }
        }

        val candVecX = rect.centerX() - ownPin.x
        val candVecY = rect.centerY() - ownPin.y
        val candLen = max(1f, hypot(candVecX, candVecY))
        val normCandX = candVecX / candLen
        val normCandY = candVecY / candLen

        val effectiveRouteDir = localRouteDir ?: routeDepartureDir
        if (effectiveRouteDir != null) {
            val dotRoute = normCandX * effectiveRouteDir.x + normCandY * effectiveRouteDir.y
            cost += dotRoute * 85f

            if (effectiveRouteDir.x < -0.25f && cand.offsetX < 0f) {
                val overhangDp = min(abs(cand.offsetX) / dp1, 35f)
                cost += overhangDp * 1.5f * abs(effectiveRouteDir.x)
            } else if (effectiveRouteDir.x > 0.25f && (cand.offsetX + w) > 0f) {
                val overhangDp = min((cand.offsetX + w) / dp1, 35f)
                cost += overhangDp * 1.5f * abs(effectiveRouteDir.x)
            }
        }

        if (otherPin != null) {
            val awayX = ownPin.x - otherPin.x
            val awayY = ownPin.y - otherPin.y
            val pinsDist = hypot(awayX, awayY)
            val maxRepelDist = convertDpToPixel(145f)
            if (pinsDist > 1f && pinsDist < maxRepelDist) {
                val normAwayX = awayX / pinsDist
                val normAwayY = awayY / pinsDist
                val dotAway = normCandX * normAwayX + normCandY * normAwayY
                val proximityWeight = ((maxRepelDist - pinsDist) / maxRepelDist).coerceIn(0f, 1f)
                cost -= dotAway * (35f + 65f * proximityWeight)
            }
        }

        if (routePts.size >= 2) {
            val routePad = convertDpToPixel(4.5f)
            val paddedRect = RectF(
                rect.left - routePad,
                rect.top - routePad,
                rect.right + routePad,
                rect.bottom + routePad
            )
            var overlappingSegments = 0
            var clippedRouteLengthPx = 0f
            var minEdgeClearancePx = Float.MAX_VALUE
            val pinExclusionRadius = convertDpToPixel(18f)
            val courtyardRadius = convertDpToPixel(24f)

            for (k in 0 until routePts.size - 1) {
                val a = routePts[k]
                val b = routePts[k + 1]
                val distA = hypot(a.x - ownPin.x, a.y - ownPin.y)
                val distB = hypot(b.x - ownPin.x, b.y - ownPin.y)

                val clippedLen = clippedSegmentLengthInsideRect(a.x, a.y, b.x, b.y, paddedRect)
                if (clippedLen > 0f) {
                    if (distA < courtyardRadius && distB < courtyardRadius) {
                        clippedRouteLengthPx += clippedLen * 0.35f
                    } else {
                        overlappingSegments++
                        clippedRouteLengthPx += clippedLen
                    }
                }

                if (distA >= pinExclusionRadius || distB >= pinExclusionRadius) {
                    val edgeDist = segmentToRectDistance(a.x, a.y, b.x, b.y, rect)
                    if (edgeDist < minEdgeClearancePx) {
                        minEdgeClearancePx = edgeDist
                    }
                }
            }

            if (overlappingSegments > 0 || clippedRouteLengthPx > dp1 * 2f) {
                cost += 1200f + overlappingSegments * 420f + (clippedRouteLengthPx / dp1) * 26f
            } else {
                val haloPx = convertDpToPixel(24f)
                if (minEdgeClearancePx < haloPx) {
                    val q = ((haloPx - minEdgeClearancePx) / haloPx).coerceIn(0f, 1f)
                    cost += q * q * 140f
                }
            }
        }

        return cost
    }

    /**
     * Жесткий приоритет экранных границ (safeRect.left / safeRect.right) и кнопок интерфейса,
     * чтобы плашки гарантированно помещались внутри экрана и никогда не обрезались по краям.
     */
    private fun evaluateShiftedScreenBoundsAndObstaclesCost(
        rect: RectF,
        panX: Float,
        panY: Float,
        safeRect: RectF,
        uiObstacles: List<RectF>
    ): Float {
        var cost = 0f
        val dp1 = convertDpToPixel(1f).coerceAtLeast(0.5f)
        val sl = rect.left + panX
        val st = rect.top + panY
        val sr = rect.right + panX
        val sb = rect.bottom + panY

        if (sl < safeRect.left) {
            cost += 15000f + (safeRect.left - sl) * 65f
        }
        if (sr > safeRect.right) {
            cost += 15000f + (sr - safeRect.right) * 65f
        }

        val softVertAllowancePx = convertDpToPixel(24f)
        if (st < safeRect.top) {
            val overT = safeRect.top - st
            cost += if (overT <= softVertAllowancePx) {
                380f + (overT / dp1) * 22f
            } else {
                5500f + overT * 45f
            }
        }
        if (sb > safeRect.bottom) {
            val overB = sb - safeRect.bottom
            cost += if (overB <= softVertAllowancePx) {
                480f + (overB / dp1) * 26f
            } else {
                6500f + overB * 50f
            }
        }

        for (obs in uiObstacles) {
            val ox = min(sr, obs.right) - max(sl, obs.left)
            val oy = min(sb, obs.bottom) - max(st, obs.top)
            if (ox > 0f && oy > 0f) {
                cost += 12000f + (ox * oy) * 1.5f
            }
        }

        return cost
    }

    /**
     * Алгоритм Лианга-Барски: возвращает точную длину части отрезка (x1, y1)-(x2, y2),
     * проходящей внутри прямоугольника rect (0f, если отрезок не пересекает rect).
     */
    private fun clippedSegmentLengthInsideRect(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        rect: RectF
    ): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        var u1 = 0f
        var u2 = 1f

        val p0 = -dx
        val q0 = x1 - rect.left
        val p1 = dx
        val q1 = rect.right - x1
        val p2 = -dy
        val q2 = y1 - rect.top
        val p3 = dy
        val q3 = rect.bottom - y1

        val pArr = floatArrayOf(p0, p1, p2, p3)
        val qArr = floatArrayOf(q0, q1, q2, q3)

        for (i in 0 until 4) {
            val p = pArr[i]
            val q = qArr[i]
            if (abs(p) < 1e-5f) {
                if (q < 0f) return 0f
            } else {
                val u = q / p
                if (p < 0f) {
                    if (u > u2) return 0f
                    if (u > u1) u1 = u
                } else {
                    if (u < u1) return 0f
                    if (u < u2) u2 = u
                }
            }
        }

        return if (u1 <= u2) hypot(dx, dy) * (u2 - u1) else 0f
    }

    /**
     * Точное минимальное расстояние от отрезка (x1, y1)-(x2, y2) до краев прямоугольника rect.
     */
    private fun segmentToRectDistance(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        rect: RectF
    ): Float {
        if (segmentIntersectsRect(x1, y1, x2, y2, rect)) return 0f

        var minDist = distancePointToSegment(rect.left, rect.top, x1, y1, x2, y2)
        minDist = min(minDist, distancePointToSegment(rect.right, rect.top, x1, y1, x2, y2))
        minDist = min(minDist, distancePointToSegment(rect.right, rect.bottom, x1, y1, x2, y2))
        minDist = min(minDist, distancePointToSegment(rect.left, rect.bottom, x1, y1, x2, y2))

        minDist = min(minDist, distancePointToSegment(x1, y1, rect.left, rect.top, rect.right, rect.top))
        minDist = min(minDist, distancePointToSegment(x1, y1, rect.right, rect.top, rect.right, rect.bottom))
        minDist = min(minDist, distancePointToSegment(x1, y1, rect.right, rect.bottom, rect.left, rect.bottom))
        minDist = min(minDist, distancePointToSegment(x1, y1, rect.left, rect.bottom, rect.left, rect.top))

        minDist = min(minDist, distancePointToSegment(x2, y2, rect.left, rect.top, rect.right, rect.top))
        minDist = min(minDist, distancePointToSegment(x2, y2, rect.right, rect.top, rect.right, rect.bottom))
        minDist = min(minDist, distancePointToSegment(x2, y2, rect.right, rect.bottom, rect.left, rect.bottom))
        minDist = min(minDist, distancePointToSegment(x2, y2, rect.left, rect.bottom, rect.left, rect.top))

        return minDist
    }

    private fun resolveResidualOverlapStatic(
        rectA: RectF,
        pinA: PointF,
        rectB: RectF,
        pinB: PointF,
        minGap: Float
    ) {
        val overlapX = min(rectA.right, rectB.right) - max(rectA.left, rectB.left) + minGap
        val overlapY = min(rectA.bottom, rectB.bottom) - max(rectA.top, rectB.top) + minGap
        if (overlapX <= 0f || overlapY <= 0f) return

        if (overlapY <= overlapX * 1.15f) {
            val aIsAbove = if (abs(rectA.centerY() - rectB.centerY()) > 2f) {
                rectA.centerY() <= rectB.centerY()
            } else {
                pinA.y <= pinB.y
            }
            val shift = overlapY * 0.5f + 1f
            if (aIsAbove) {
                rectA.offset(0f, -shift)
                rectB.offset(0f, shift)
            } else {
                rectA.offset(0f, shift)
                rectB.offset(0f, -shift)
            }
        } else {
            val aIsLeft = if (abs(rectA.centerX() - rectB.centerX()) > 2f) {
                rectA.centerX() <= rectB.centerX()
            } else {
                pinA.x <= pinB.x
            }
            val shift = overlapX * 0.5f + 1f
            if (aIsLeft) {
                rectA.offset(-shift, 0f)
                rectB.offset(shift, 0f)
            } else {
                rectA.offset(shift, 0f)
                rectB.offset(-shift, 0f)
            }
        }
    }

    private fun lngToMercatorX(lng: Double): Double = (lng + 180.0) / 360.0

    private fun latToMercatorY(lat: Double): Double {
        val clamped = lat.coerceIn(-85.0, 85.0)
        val sinLat = sin(Math.toRadians(clamped))
        return 0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * PI)
    }

    private fun mercatorXToLng(mx: Double): Double = mx * 360.0 - 180.0

    private fun mercatorYToLat(my: Double): Double {
        val n = PI - 2.0 * PI * my
        return Math.toDegrees(atan(sinh(n)))
    }

    private fun rectDistance(a: RectF, b: RectF): Float {
        val dx = max(0f, max(a.left - b.right, b.left - a.right))
        val dy = max(0f, max(a.top - b.bottom, b.top - a.bottom))
        return hypot(dx, dy)
    }

    private fun circleIntersectsRect(cx: Float, cy: Float, radius: Float, rect: RectF): Boolean {
        val closestX = cx.coerceIn(rect.left, rect.right)
        val closestY = cy.coerceIn(rect.top, rect.bottom)
        val dx = cx - closestX
        val dy = cy - closestY
        return (dx * dx + dy * dy) < (radius * radius)
    }

    private fun segmentIntersectsRect(x1: Float, y1: Float, x2: Float, y2: Float, rect: RectF): Boolean {
        if (max(x1, x2) < rect.left || min(x1, x2) > rect.right ||
            max(y1, y2) < rect.top || min(y1, y2) > rect.bottom
        ) {
            return false
        }
        if (rect.contains(x1, y1) || rect.contains(x2, y2)) return true

        return segmentsIntersect(x1, y1, x2, y2, rect.left, rect.top, rect.right, rect.top) ||
                segmentsIntersect(x1, y1, x2, y2, rect.right, rect.top, rect.right, rect.bottom) ||
                segmentsIntersect(x1, y1, x2, y2, rect.right, rect.bottom, rect.left, rect.bottom) ||
                segmentsIntersect(x1, y1, x2, y2, rect.left, rect.bottom, rect.left, rect.top)
    }

    private fun segmentsIntersect(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float
    ): Boolean {
        val d1 = crossProduct(cx, cy, dx, dy, ax, ay)
        val d2 = crossProduct(cx, cy, dx, dy, bx, by)
        val d3 = crossProduct(ax, ay, bx, by, cx, cy)
        val d4 = crossProduct(ax, ay, bx, by, dx, dy)
        return ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) &&
                ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
    }

    private fun crossProduct(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
    }

    private fun distancePointToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val abx = bx - ax
        val aby = by - ay
        val lenSq = abx * abx + aby * aby
        if (lenSq <= 0.0001f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * abx + (py - ay) * aby) / lenSq).coerceIn(0f, 1f)
        val projX = ax + t * abx
        val projY = ay + t * aby
        return hypot(px - projX, py - projY)
    }

    fun updateInProgressDestinationBadge(
        map: GoogleMap?,
        order: TaxiOrderDto,
        syncedMinutesLeft: Int? = null
    ) {
        if (map == null) return

        val minutesLeft = syncedMinutesLeft ?: run {
            val secondsLeft = order.durationSeconds ?: 0
            if (secondsLeft > 0) ceil(secondsLeft.toDouble() / 60.0).toInt() else 1
        }
        val timeText = if (minutesLeft > 0) "$minutesLeft хв" else "1 хв"

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
        // Не вызываем лишний requestLayout(), если панель уже измерена (кроме active_order_card с раскрывающимися деталями)
        if (bottomPanel.width == 0 || bottomPanel.height == 0 || bottomPanel.id == R.id.active_order_card) {
            bottomPanel.requestLayout()
        }

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

                // Для панели тарифов при построенном маршруте используем единую функцию паддингов (top = 14dp, extraBottom = 0dp),
                // чтобы исключить двойной вызов map.setPadding() и обрыв стартовой анимации камеры из displayTariffs()
                val paddingChanged: Boolean
                val totalBottomPadding: Int
                val topPadding: Int

                if (bottomPanel.id == R.id.tariffs_panel && polylineMain != null) {
                    paddingChanged = applyRouteMapPaddingForPanel(map, bottomPanel)
                    totalBottomPadding = lastPadBottom
                    topPadding = lastPadTop
                } else {
                    val lp = bottomPanel.layoutParams as? ViewGroup.MarginLayoutParams
                    val horizontalMargins = (lp?.leftMargin ?: 0) + (lp?.rightMargin ?: 0)
                    val targetWidth = if (bottomPanel.width > 0) {
                        bottomPanel.width
                    } else {
                        activity.resources.displayMetrics.widthPixels - horizontalMargins
                    }

                    var panelHeight = bottomPanel.height
                    if (panelHeight == 0 || bottomPanel.id == R.id.active_order_card) {
                        val widthSpec = View.MeasureSpec.makeMeasureSpec(targetWidth, View.MeasureSpec.EXACTLY)
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
                                val widthSpec = View.MeasureSpec.makeMeasureSpec(targetWidth, View.MeasureSpec.EXACTLY)
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
                    totalBottomPadding = panelHeight + marginBottom + extraBuffer
                    topPadding = convertDpToPixel(topPaddingDp).toInt()

                    paddingChanged = applyPaddingIfChanged(map, sideMargin, topPadding, sideMargin, totalBottomPadding)
                }

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

                // Если drawStylishRoute уже ведет камеру к маршруту, а паддинг панели тарифов не изменился —
                // не обрываем текущую анимацию камеры вызовом map.stopAnimation()!
                if (recenterMap && !(bottomPanel.id == R.id.tariffs_panel && isInitialRouteRecenterRunning && !paddingChanged)) {
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
                                if (originPlace?.latLng != null && destinationPlace?.latLng != null) {
                                    if (paddingChanged) {
                                        isSmartLabelsLocked = false
                                        cachedIdealRouteBounds = null
                                    }
                                    recenterOnRoute(
                                        map = map,
                                        originPlace = originPlace,
                                        destinationPlace = destinationPlace,
                                        currentWaypoints = currentWaypoints,
                                        decodedRoutePoints = decodedRoutePoints,
                                        durationMs = 450
                                    )
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

        var targetHeight = if (toHeight > 0) {
            toHeight
        } else {
            var h = activeOrderCard.height
            val detailsView = activeOrderCard.findViewById<View>(R.id.layout_expandable_details)
            if (detailsView != null && detailsView.visibility == View.VISIBLE && detailsView.height > 0) {
                h -= detailsView.height
            }
            h
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
                        if (originPlace?.latLng != null && destinationPlace?.latLng != null) {
                            recenterOnRoute(
                                map = map,
                                originPlace = originPlace,
                                destinationPlace = destinationPlace,
                                currentWaypoints = currentWaypoints,
                                decodedRoutePoints = decodedRoutePoints,
                                durationMs = 450
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}