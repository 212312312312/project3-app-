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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
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
import kotlin.math.sin
import kotlin.math.sinh

/**
 * Контроллер управления полилиниями маршрута, маркерами точек А/Б/остановок,
 * маршрутом подачи автомобиля, статичными плашками SmartLabels (как в Uklon),
 * прогрессивной анимацией заполнения маршрута ("гонка" от А до Б)
 * и единым умным наведением камеры на маршрут.
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

    // Единые рассчитанные границы маршрута (включающие вылет статичных плашек) для 1-го показа и кнопки btnRecenterRoute
    private var cachedIdealRouteBounds: LatLngBounds? = null
    private var cachedIdealRoutePaddingPx: Int = 0

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
        isSmartLabelsLocked = false
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
        lockedForPathHash = 0
        cachedIdealRouteBounds = null
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
                convertDpToPixel(44f).toInt()
            }

            val cameraUpdate = CameraUpdateFactory.newLatLngBounds(boundsToUse, paddingPx)
            if (durationMs > 0) {
                map.animateCamera(cameraUpdate, durationMs, object : GoogleMap.CancelableCallback {
                    override fun onFinish() {
                        onFinishCallback?.invoke()
                    }

                    override fun onCancel() {
                        onFinishCallback?.invoke()
                    }
                })
            } else {
                map.moveCamera(cameraUpdate)
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

        val pathHash = path.hashCode()
        if (pathHash == lastDrawnPathHash && polylineMain != null) {
            return
        }
        lastDrawnPathHash = pathHash
        isSmartLabelsLocked = false
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
                prepareOverlayHardwareAndMeasure(overlayOrigin)
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
            prepareOverlayHardwareAndMeasure(overlayDest)
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
            btnRecenterRoute.visibility = View.GONE
        }

        val triggerReveal = {
            activity.runOnUiThread {
                updateSmartLabels(
                    map = map,
                    currentStatus = activeOrderProvider()?.status,
                    originPlace = originPlace,
                    destinationPlace = destinationPlace,
                    decodedRoutePoints = path,
                    overlayOrigin = overlayOrigin,
                    overlayDest = overlayDest
                )
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
            val visibleBottomPanel = if (activeOrderCard.visibility == View.VISIBLE) activeOrderCard else tariffsPanel

            visibleBottomPanel.post {
                try {
                    if (orderStatus != "CANCELLED") {
                        applyRouteMapPaddingForPanel(map, visibleBottomPanel)
                    }

                    // 1 раз вычисляем статичные слоты SmartLabels и расширяем границы камеры с учетом габаритов плашек
                    buildAndLockRouteBoundsAndLabels(
                        originPlace = originPlace,
                        destinationPlace = destinationPlace,
                        currentWaypoints = currentWaypoints,
                        decodedRoutePoints = path,
                        overlayOrigin = overlayOrigin,
                        overlayDest = overlayDest
                    )
                    updateSmartLabels(
                        map = map,
                        currentStatus = activeOrderProvider()?.status,
                        originPlace = originPlace,
                        destinationPlace = destinationPlace,
                        decodedRoutePoints = path,
                        overlayOrigin = overlayOrigin,
                        overlayDest = overlayDest
                    )

                    if (orderStatus == "ACCEPTED" || orderStatus == "DRIVER_ARRIVED" || orderStatus == "IN_PROGRESS") {
                        resetCameraTilt(map)
                        triggerReveal()
                    } else {
                        resetCameraTilt(map, forceStopAnimation = true)

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
                            }
                        }, 150)
                    }
                } catch (_: Exception) {
                    triggerReveal()
                }
            }
            btnRecenterRoute.visibility = View.GONE
        }
    }

    /**
     * Вычисляет и синхронно применяет единый паддинг карты под нижнюю панель (tariffsPanel / activeOrderCard)
     * и верхнюю шапку, чтобы 1-й показ маршрута и кнопка btnRecenterRoute использовали идентичный viewport.
     */
    private fun applyRouteMapPaddingForPanel(map: GoogleMap, bottomPanel: View) {
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

        applyPaddingIfChanged(map, sideMargin, paddingTop, sideMargin, paddingBottom)
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

    /**
     * Анимация линии маршрута в стиле "гонки" (плавное прогрессивное заполнение от точки А до точки Б)
     * с сохранением всех оригинальных параметров (ширина 8f, zIndex 2.5f, RoundCap, цикл 5500ms, те же цвета):
     * - 0..3000 мс: Линия свечения (colorGlow) плавно и равномерно бежит/заполняет маршрут от старта А до финиша Б.
     * - 3000..4500 мс: Заполненная линия плавно растворяется в базовый цвет маршрута (colorBase).
     * - 4500..5500 мс: Короткая пауза перед следующим стартом от точки А.
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

        // Предвычисляем накопленную длину вдоль сегментов маршрута для идеально равномерной скорости гонки
        val n = path.size
        val cumulativeDist = DoubleArray(n)
        var totalLength = 0.0
        for (i in 1 until n) {
            val dLat = path[i].latitude - path[i - 1].latitude
            val dLng = (path[i].longitude - path[i - 1].longitude) * 0.64 // косинус широты для точной пропорции
            val segLen = hypot(dLat, dLng)
            totalLength += segLen
            cumulativeDist[i] = totalLength
        }

        if (totalLength <= 1e-9) return

        val animOpts = PolylineOptions()
            .add(path[0])
            .width(8f)
            .color(colorGlow)
            .zIndex(2.5f)
            .startCap(RoundCap())
            .endCap(RoundCap())
            .jointType(JointType.ROUND)

        polylineAnim = map.addPolyline(animOpts)

        val totalDurationMs = 5500f
        val raceFillDurationMs = 3000f
        val fadeOutEndMs = 4500f
        val argbEvaluator = ArgbEvaluator()
        val reusableSubPath = ArrayList<LatLng>(n + 1)

        var lastColor = colorGlow
        var wasCleared = false

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

                    val progress = animator.animatedValue as Float
                    val timeMs = progress * totalDurationMs

                    when {
                        // Фаза 1: Гонка — плавное заполнение маршрута от точки А (0%) до точки Б (100%)
                        timeMs <= raceFillDurationMs -> {
                            wasCleared = false
                            val rawFill = (timeMs / raceFillDurationMs).coerceIn(0f, 1f)
                            // Легкое сглаживание разгона/финиша для ощущения динамичной гонки
                            val fillFraction = rawFill * (2f - rawFill) * 0.35f + rawFill * 0.65f
                            val targetDist = fillFraction * totalLength

                            reusableSubPath.clear()
                            reusableSubPath.add(path[0])

                            // Бинарный поиск текущего сегмента по накопленной дистанции
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
                                reusableSubPath.add(path[idx])
                            }

                            if (low < n - 1) {
                                val segStartDist = cumulativeDist[low]
                                val segEndDist = cumulativeDist[low + 1]
                                val segSpan = segEndDist - segStartDist
                                if (segSpan > 1e-9) {
                                    val segT = ((targetDist - segStartDist) / segSpan).coerceIn(0.0, 1.0)
                                    val pStart = path[low]
                                    val pEnd = path[low + 1]
                                    val interpLat = pStart.latitude + (pEnd.latitude - pStart.latitude) * segT
                                    val interpLng = pStart.longitude + (pEnd.longitude - pStart.longitude) * segT
                                    reusableSubPath.add(LatLng(interpLat, interpLng))
                                }
                            }

                            if (lastColor != colorGlow) {
                                lastColor = colorGlow
                                currentPoly.color = colorGlow
                            }
                            currentPoly.points = reusableSubPath
                        }

                        // Фаза 2: Плавное затухание заполненной линии после достижения финиша Б
                        timeMs <= fadeOutEndMs -> {
                            val fadeFraction = 1f - ((timeMs - raceFillDurationMs) / (fadeOutEndMs - raceFillDurationMs)).coerceIn(0f, 1f)
                            val currentColor = argbEvaluator.evaluate(fadeFraction, colorBase, colorGlow) as Int
                            if (currentColor != lastColor) {
                                lastColor = currentColor
                                currentPoly.color = currentColor
                            }
                        }

                        // Фаза 3: Короткая пауза перед новым стартом от точки А
                        else -> {
                            if (!wasCleared) {
                                wasCleared = true
                                reusableSubPath.clear()
                                reusableSubPath.add(path[0])
                                currentPoly.points = reusableSubPath
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
    // СТАТИЧНЫЕ SMART LABELS КАК В UKLON (0 ПРОСАДОК FPS + ЕДИНЫЙ РАСЧЕТ ГРАНИЦ)
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
        if (view.width == 0 || view.height == 0) {
            val maxW = activity.resources.displayMetrics.widthPixels
            val maxH = activity.resources.displayMetrics.heightPixels
            view.measure(
                View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)
            )
            return Pair(view.measuredWidth.toFloat(), view.measuredHeight.toFloat())
        }
        return Pair(view.width.toFloat(), view.height.toFloat())
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
        val originVisible = originLoc != null && overlayOrigin.visibility == View.VISIBLE
        val destVisible = destLoc != null && overlayDest.visibility == View.VISIBLE
        if (!originVisible && !destVisible) return

        val routeHash = decodedRoutePoints?.hashCode() ?: 0
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

        val projection = map.projection

        // БЫСТРЫЙ СТАТИЧНЫЙ ПУТЬ (0 пересчетов слотов, 0 clamp к краям экрана, 120 FPS)
        if (originVisible) {
            val screenPt = projection.toScreenLocation(originLoc!!)
            overlayOrigin.x = screenPt.x + lockedOriginOffsetX
            overlayOrigin.y = screenPt.y + lockedOriginOffsetY
        }

        if (destVisible) {
            val screenPt = projection.toScreenLocation(destLoc!!)
            overlayDest.x = screenPt.x + lockedDestOffsetX
            overlayDest.y = screenPt.y + lockedDestOffsetY
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

        val currentHash = decodedRoutePoints?.hashCode() ?: rawBounds.hashCode()
        if (!isSmartLabelsLocked || lockedForPathHash != currentHash) {
            val routeScreenPts = ArrayList<PointF>(34)
            if (!decodedRoutePoints.isNullOrEmpty()) {
                val totalPts = decodedRoutePoints.size
                val step = max(1, (totalPts - 1) / 30)
                var idx = 0
                while (idx < totalPts) {
                    routeScreenPts.add(toVirtualScreen(decodedRoutePoints[idx]))
                    idx += step
                }
                if ((totalPts - 1) % step != 0) {
                    routeScreenPts.add(toVirtualScreen(decodedRoutePoints.last()))
                }
            }

            val waypointScreenPts = currentWaypoints.mapNotNull {
                if (it.first.latitude != 0.0 || it.first.longitude != 0.0) toVirtualScreen(it.first) else null
            }

            val uiObstacles = listOf(
                RectF(0f, 0f, convertDpToPixel(72f), convertDpToPixel(104f)),
                RectF(screenW - convertDpToPixel(72f), 0f, screenW, convertDpToPixel(104f)),
                RectF(0f, bottomSafeY - convertDpToPixel(28f), convertDpToPixel(82f), bottomSafeY + convertDpToPixel(10f))
            )

            val origPin = originLoc?.let { toVirtualScreen(it) }
            val dstPin = destLoc?.let { toVirtualScreen(it) }

            val (origW, origH) = if (overlayOrigin != null) {
                prepareOverlayHardwareAndMeasure(overlayOrigin)
            } else {
                Pair(convertDpToPixel(156f), convertDpToPixel(54f))
            }

            val (destW, destH) = if (overlayDest != null) {
                prepareOverlayHardwareAndMeasure(overlayDest)
            } else {
                Pair(convertDpToPixel(156f), convertDpToPixel(54f))
            }

            if (origPin != null && dstPin != null) {
                val origCandidates = generateStaticCandidates(origPin, origW, origH)
                val destCandidates = generateStaticCandidates(dstPin, destW, destH)

                val origRouteDir = computeRouteDepartureVector(origPin, routeScreenPts, isStart = true)
                val destRouteDir = computeRouteDepartureVector(dstPin, routeScreenPts, isStart = false)

                val origCosts = FloatArray(origCandidates.size) { i ->
                    evaluateStaticCandidateCost(
                        cand = origCandidates[i],
                        ownPin = origPin,
                        otherPin = dstPin,
                        w = origW,
                        h = origH,
                        routePts = routeScreenPts,
                        routeDepartureDir = origRouteDir,
                        waypointPts = waypointScreenPts,
                        safeRect = safeRect,
                        uiObstacles = uiObstacles
                    )
                }

                val destCosts = FloatArray(destCandidates.size) { j ->
                    evaluateStaticCandidateCost(
                        cand = destCandidates[j],
                        ownPin = dstPin,
                        otherPin = origPin,
                        w = destW,
                        h = destH,
                        routePts = routeScreenPts,
                        routeDepartureDir = destRouteDir,
                        waypointPts = waypointScreenPts,
                        safeRect = safeRect,
                        uiObstacles = uiObstacles
                    )
                }

                val minCardGap = convertDpToPixel(10f)
                val repulsionRadius = convertDpToPixel(28f)
                val pinsDist = hypot(origPin.x - dstPin.x, origPin.y - dstPin.y)

                var bestOrigIdx = 0
                var bestDestIdx = 0
                var bestTotalCost = Float.MAX_VALUE

                for (i in origCandidates.indices) {
                    val candO = origCandidates[i]
                    val costO = origCosts[i]
                    if (costO >= bestTotalCost) continue

                    for (j in destCandidates.indices) {
                        val candD = destCandidates[j]
                        var pairCost = costO + destCosts[j]
                        if (pairCost >= bestTotalCost) continue

                        val overlapX = min(candO.rect.right + minCardGap * 0.5f, candD.rect.right + minCardGap * 0.5f) -
                                max(candO.rect.left - minCardGap * 0.5f, candD.rect.left - minCardGap * 0.5f)
                        val overlapY = min(candO.rect.bottom + minCardGap * 0.5f, candD.rect.bottom + minCardGap * 0.5f) -
                                max(candO.rect.top - minCardGap * 0.5f, candD.rect.top - minCardGap * 0.5f)

                        if (overlapX > 0f && overlapY > 0f) {
                            pairCost += 25000f + (overlapX * overlapY) * 1.5f
                        } else {
                            val gapDist = rectDistance(candO.rect, candD.rect)
                            if (gapDist < repulsionRadius) {
                                val t = (repulsionRadius - gapDist) / repulsionRadius
                                pairCost += t * t * 180f
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
                        }
                    }
                }

                val finalOrigRect = RectF(origCandidates[bestOrigIdx].rect)
                val finalDestRect = RectF(destCandidates[bestDestIdx].rect)

                resolveResidualOverlapStatic(
                    rectA = finalOrigRect,
                    pinA = origPin,
                    rectB = finalDestRect,
                    pinB = dstPin,
                    minGap = minCardGap
                )

                lockedOriginOffsetX = finalOrigRect.left - origPin.x
                lockedOriginOffsetY = finalOrigRect.top - origPin.y
                lockedDestOffsetX = finalDestRect.left - dstPin.x
                lockedDestOffsetY = finalDestRect.top - dstPin.y
                isSmartLabelsLocked = true
                lockedForPathHash = currentHash
            } else if (origPin != null) {
                val origCandidates = generateStaticCandidates(origPin, origW, origH)
                val origRouteDir = computeRouteDepartureVector(origPin, routeScreenPts, isStart = true)
                val best = origCandidates.minByOrNull { cand ->
                    evaluateStaticCandidateCost(
                        cand = cand,
                        ownPin = origPin,
                        otherPin = null,
                        w = origW,
                        h = origH,
                        routePts = routeScreenPts,
                        routeDepartureDir = origRouteDir,
                        waypointPts = waypointScreenPts,
                        safeRect = safeRect,
                        uiObstacles = uiObstacles
                    )
                } ?: origCandidates.first()
                lockedOriginOffsetX = best.offsetX
                lockedOriginOffsetY = best.offsetY
                isSmartLabelsLocked = true
                lockedForPathHash = currentHash
            } else if (dstPin != null) {
                val destCandidates = generateStaticCandidates(dstPin, destW, destH)
                val destRouteDir = computeRouteDepartureVector(dstPin, routeScreenPts, isStart = false)
                val best = destCandidates.minByOrNull { cand ->
                    evaluateStaticCandidateCost(
                        cand = cand,
                        ownPin = dstPin,
                        otherPin = null,
                        w = destW,
                        h = destH,
                        routePts = routeScreenPts,
                        routeDepartureDir = destRouteDir,
                        waypointPts = waypointScreenPts,
                        safeRect = safeRect,
                        uiObstacles = uiObstacles
                    )
                } ?: destCandidates.first()
                lockedDestOffsetX = best.offsetX
                lockedDestOffsetY = best.offsetY
                isSmartLabelsLocked = true
                lockedForPathHash = currentHash
            }
        }

        val expandedBoundsBuilder = LatLngBounds.Builder()
        expandedBoundsBuilder.include(rawBounds.southwest)
        expandedBoundsBuilder.include(rawBounds.northeast)

        if (originLoc != null && (overlayOrigin == null || overlayOrigin.visibility == View.VISIBLE)) {
            val (origW, origH) = if (overlayOrigin != null) {
                prepareOverlayHardwareAndMeasure(overlayOrigin)
            } else {
                Pair(convertDpToPixel(156f), convertDpToPixel(54f))
            }
            val origPin = toVirtualScreen(originLoc)
            val l = origPin.x + lockedOriginOffsetX
            val t = origPin.y + lockedOriginOffsetY
            val r = l + origW
            val b = t + origH
            expandedBoundsBuilder.include(fromVirtualScreen(l, t))
            expandedBoundsBuilder.include(fromVirtualScreen(r, b))
        }

        if (destLoc != null && (overlayDest == null || overlayDest.visibility == View.VISIBLE)) {
            val (destW, destH) = if (overlayDest != null) {
                prepareOverlayHardwareAndMeasure(overlayDest)
            } else {
                Pair(convertDpToPixel(156f), convertDpToPixel(54f))
            }
            val dstPin = toVirtualScreen(destLoc)
            val l = dstPin.x + lockedDestOffsetX
            val t = dstPin.y + lockedDestOffsetY
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

        val list = ArrayList<LabelCandidate>(24)

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

    private fun evaluateStaticCandidateCost(
        cand: LabelCandidate,
        ownPin: PointF,
        otherPin: PointF?,
        w: Float,
        h: Float,
        routePts: List<PointF>,
        routeDepartureDir: PointF?,
        waypointPts: List<PointF>,
        safeRect: RectF,
        uiObstacles: List<RectF>
    ): Float {
        var cost = cand.basePreferenceCost
        val rect = cand.rect
        val pinClearanceRadius = convertDpToPixel(11f)
        val otherPinClearanceRadius = convertDpToPixel(15f)

        if (circleIntersectsRect(ownPin.x, ownPin.y, pinClearanceRadius, rect)) {
            cost += 12000f
        }

        if (otherPin != null && circleIntersectsRect(otherPin.x, otherPin.y, otherPinClearanceRadius, rect)) {
            cost += 16000f
        }

        val wpRadius = convertDpToPixel(10f)
        for (wp in waypointPts) {
            if (circleIntersectsRect(wp.x, wp.y, wpRadius, rect)) {
                cost += 4000f
            }
        }

        if (rect.top < safeRect.top) {
            cost += 1800f + (safeRect.top - rect.top) * 18f
        }
        if (rect.bottom > safeRect.bottom) {
            cost += 2200f + (rect.bottom - safeRect.bottom) * 20f
        }
        if (rect.left < safeRect.left) {
            cost += 1500f + (safeRect.left - rect.left) * 16f
        }
        if (rect.right > safeRect.right) {
            cost += 1500f + (rect.right - safeRect.right) * 16f
        }

        for (obs in uiObstacles) {
            val ox = min(rect.right, obs.right) - max(rect.left, obs.left)
            val oy = min(rect.bottom, obs.bottom) - max(rect.top, obs.top)
            if (ox > 0f && oy > 0f) {
                cost += 2000f + (ox * oy) * 0.4f
            }
        }

        val candVecX = rect.centerX() - ownPin.x
        val candVecY = rect.centerY() - ownPin.y
        val candLen = max(1f, hypot(candVecX, candVecY))
        val normCandX = candVecX / candLen
        val normCandY = candVecY / candLen

        if (routeDepartureDir != null) {
            val dotRoute = normCandX * routeDepartureDir.x + normCandY * routeDepartureDir.y
            cost += dotRoute * 95f
        }

        if (otherPin != null) {
            val awayX = ownPin.x - otherPin.x
            val awayY = ownPin.y - otherPin.y
            val pinsDist = hypot(awayX, awayY)
            if (pinsDist > 1f && pinsDist < convertDpToPixel(260f)) {
                val normAwayX = awayX / pinsDist
                val normAwayY = awayY / pinsDist
                val dotAway = normCandX * normAwayX + normCandY * normAwayY
                val proximityWeight = ((convertDpToPixel(260f) - pinsDist) / convertDpToPixel(260f)).coerceIn(0f, 1f)
                cost -= dotAway * (45f + 95f * proximityWeight)
            }
        }

        if (routePts.size >= 2) {
            val routePad = convertDpToPixel(5f)
            val paddedRect = RectF(
                rect.left - routePad,
                rect.top - routePad,
                rect.right + routePad,
                rect.bottom + routePad
            )
            var overlappingSegments = 0
            var minRouteDist = Float.MAX_VALUE

            for (k in 0 until routePts.size - 1) {
                val a = routePts[k]
                val b = routePts[k + 1]

                if (segmentIntersectsRect(a.x, a.y, b.x, b.y, paddedRect)) {
                    overlappingSegments++
                } else {
                    val d = distancePointToSegment(rect.centerX(), rect.centerY(), a.x, a.y, b.x, b.y)
                    if (d < minRouteDist) minRouteDist = d
                }
            }

            if (overlappingSegments > 0) {
                cost += 450f + overlappingSegments * 250f
            } else {
                val clearanceThreshold = max(w, h) * 0.5f + convertDpToPixel(12f)
                if (minRouteDist < clearanceThreshold) {
                    cost += ((clearanceThreshold - minRouteDist) / clearanceThreshold) * 45f
                }
            }
        }

        return cost
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
                val totalBottomPadding = panelHeight + marginBottom + extraBuffer
                val topPadding = convertDpToPixel(topPaddingDp).toInt()

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