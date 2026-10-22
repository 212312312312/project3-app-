package com.taxiapp.client.controllers

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.net.Uri
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.libraries.places.api.model.Place
import com.google.maps.android.PolyUtil
import com.google.maps.android.SphericalUtil
import com.taxiapp.client.HomeViewModel
import com.taxiapp.client.R
import com.taxiapp.client.analytics.AnalyticsManager
import com.taxiapp.client.databinding.ActivityHomeBinding
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.dto.CancellationReasonDto
import com.taxiapp.client.network.dto.CarTariffDto
import com.taxiapp.client.network.dto.TaxiOrderDto
import com.taxiapp.client.network.dto.TrackingLocationDto
import com.taxiapp.client.utils.AddressUtils
import com.taxiapp.client.utils.Car3dModelHelper
import com.taxiapp.client.utils.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ActiveOrderUiController(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val sessionManager: SessionManager,
    private val viewModel: HomeViewModel,
    private val mapRouteController: MapRouteController,
    private val orderTimersController: OrderTimersController,
    private val searchRadarController: SearchRadarController
) {

    interface StatusCallbacks {
        val map: GoogleMap?
        val originPlace: Place?
        val routeDurationSeconds: Int
        val isDarkMode: Boolean
        fun onSetDecodedRoutePoints(points: List<LatLng>?)
        fun onDrawStylishRoute(points: List<LatLng>, status: String)
        fun onDrawPickupRoute(polyline: String?)
        fun onFocusOnPickupRoute(carLatLng: LatLng)
        fun onUpdateDriverMarker(loc: TrackingLocationDto)
        fun onStartDriverTracking(orderId: String)
        fun onStopDriverTracking()
        fun onCheckAndScheduleBoost(order: TaxiOrderDto)
        fun onStopBoostTimer()
        fun onShowDriverHealth(issues: List<String>)
        fun onSetRecenterRouteAnchor(anchorId: Int, bottomMarginDp: Float)
        fun onAnimateMapPadding(fromHeight: Int, toHeight: Int, recenterMap: Boolean)
        fun onCompletedUnderstandClicked(order: TaxiOrderDto)
        fun onOrderCancelledReset()
    }

    var isOrderDetailsExpanded: Boolean = false
    var isCameraFocusedOnSearch: Boolean = false
    var unreadChatMessages: Int = 0
    var lastKnownDriverLatLng: LatLng? = null
    var lastKnownDriverBearing: Float = 0f

    private var maxDetailsHeight: Int = 0
    private var orderDetailsAnimator: ValueAnimator? = null
    private var statusBlinkAnimator: ObjectAnimator? = null

    fun setupExpandableDetailsTouchListener() {
        var startY = 0f
        var startHeight = 0

        binding.handleTouchZone.setOnTouchListener { v, event ->
            val order = viewModel.activeOrder.value
            if (order == null || order.status == "CANCELLED" || order.status == "COMPLETED") {
                return@setOnTouchListener true
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    v.parent.requestDisallowInterceptTouchEvent(true)

                    val cardWidth = if (binding.activeOrderCard.width > 0) {
                        binding.activeOrderCard.width
                    } else {
                        activity.resources.displayMetrics.widthPixels
                    }
                    val widthSpec = View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY)
                    val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    binding.layoutExpandableDetails.measure(widthSpec, heightSpec)
                    maxDetailsHeight = binding.layoutExpandableDetails.measuredHeight

                    startHeight = binding.layoutExpandableDetails.height
                    val params = binding.layoutExpandableDetails.layoutParams
                    params.height = startHeight
                    binding.layoutExpandableDetails.layoutParams = params
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val diffY = startY - event.rawY
                    var newHeight = (startHeight + diffY).toInt()

                    if (newHeight < 0) newHeight = 0
                    if (newHeight > maxDetailsHeight) newHeight = maxDetailsHeight

                    if (newHeight > 0 && binding.layoutExpandableDetails.visibility != View.VISIBLE) {
                        binding.layoutExpandableDetails.visibility = View.VISIBLE
                        binding.mapSolidOverlay.visibility = View.VISIBLE
                    } else if (newHeight == 0 && binding.layoutExpandableDetails.visibility == View.VISIBLE) {
                        binding.layoutExpandableDetails.visibility = View.GONE
                        binding.mapSolidOverlay.visibility = View.GONE
                    }

                    val params = binding.layoutExpandableDetails.layoutParams
                    params.height = newHeight
                    binding.layoutExpandableDetails.layoutParams = params

                    if (maxDetailsHeight > 0) {
                        val progress = newHeight.toFloat() / maxDetailsHeight.toFloat()
                        binding.mapSolidOverlay.alpha = progress
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)

                    val currentHeight = binding.layoutExpandableDetails.height

                    if (Math.abs(startY - event.rawY) < 20) {
                        v.performClick()
                        animateOrderDetailsToState(!isOrderDetailsExpanded)
                        return@setOnTouchListener true
                    }

                    val movedDistance = if (maxDetailsHeight > 0) {
                        currentHeight.toFloat() / maxDetailsHeight.toFloat()
                    } else {
                        0f
                    }

                    if (isOrderDetailsExpanded) {
                        animateOrderDetailsToState(movedDistance > 0.8f)
                    } else {
                        animateOrderDetailsToState(movedDistance > 0.2f)
                    }
                    true
                }
                else -> false
            }
        }

        binding.mapSolidOverlay.setOnClickListener {
            if (isOrderDetailsExpanded) {
                animateOrderDetailsToState(false)
            }
        }
    }

    fun toggleOrderDetails(onUpdateMapPadding: () -> Unit) {
        isOrderDetailsExpanded = !isOrderDetailsExpanded

        val autoTransition = AutoTransition().apply { duration = 250 }
        TransitionManager.beginDelayedTransition(binding.activeOrderCard as ViewGroup, autoTransition)

        binding.layoutExpandableDetails.visibility = if (isOrderDetailsExpanded) View.VISIBLE else View.GONE

        if (!activity.isDestroyed && !activity.isFinishing) {
            onUpdateMapPadding()
        }
    }

    fun animateOrderDetailsToState(expand: Boolean) {
        val overlay = binding.mapSolidOverlay

        val cardWidth = if (binding.activeOrderCard.width > 0) {
            binding.activeOrderCard.width
        } else {
            activity.resources.displayMetrics.widthPixels
        }
        val widthSpec = View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        binding.layoutExpandableDetails.measure(widthSpec, heightSpec)

        maxDetailsHeight = binding.layoutExpandableDetails.measuredHeight
        val startHeight = binding.layoutExpandableDetails.height
        val targetHeight = if (expand) maxDetailsHeight else 0

        isOrderDetailsExpanded = expand

        if (expand) {
            binding.layoutExpandableDetails.visibility = View.VISIBLE
            overlay.visibility = View.VISIBLE
        }

        orderDetailsAnimator?.cancel()

        val animator = ValueAnimator.ofInt(startHeight, targetHeight)
        orderDetailsAnimator = animator

        animator.duration = 250
        animator.addUpdateListener { animation ->
            val h = animation.animatedValue as Int
            val params = binding.layoutExpandableDetails.layoutParams
            params.height = h
            binding.layoutExpandableDetails.layoutParams = params

            if (maxDetailsHeight > 0) {
                overlay.alpha = (h.toFloat() / maxDetailsHeight.toFloat()).coerceIn(0f, 1f)
            }
        }

        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (expand) {
                    val params = binding.layoutExpandableDetails.layoutParams
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    binding.layoutExpandableDetails.layoutParams = params
                } else {
                    binding.layoutExpandableDetails.visibility = View.GONE
                    overlay.visibility = View.GONE
                    val params = binding.layoutExpandableDetails.layoutParams
                    params.height = 0
                    binding.layoutExpandableDetails.layoutParams = params
                }
            }
        })
        animator.start()
    }

    fun resetOrderDetailsState() {
        orderDetailsAnimator?.cancel()
        orderDetailsAnimator = null

        isOrderDetailsExpanded = false

        binding.layoutExpandableDetails.visibility = View.GONE
        val params = binding.layoutExpandableDetails.layoutParams
        params.height = 0
        binding.layoutExpandableDetails.layoutParams = params

        binding.mapSolidOverlay.visibility = View.GONE
        binding.mapSolidOverlay.alpha = 0f
    }

    fun startStatusBlinking() {
        if (statusBlinkAnimator?.isRunning == true) return
        statusBlinkAnimator = ObjectAnimator.ofFloat(binding.orderStatusText, "alpha", 1f, 0.4f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    fun stopStatusBlinking() {
        statusBlinkAnimator?.cancel()
        binding.orderStatusText.alpha = 1f
    }

    fun updateChatBadgeUI() {
        activity.runOnUiThread {
            if (unreadChatMessages > 0) {
                binding.tvChatBadge.visibility = View.VISIBLE
                binding.tvChatBadge.text = unreadChatMessages.toString()
            } else {
                binding.tvChatBadge.visibility = View.GONE
            }
        }
    }

    fun cancelCurrentOrder(onShowCancelDialog: (List<CancellationReasonDto>) -> Unit) {
        sessionManager.fetchAuthToken() ?: return
        activity.lifecycleScope.launch {
            try {
                val response = ApiClient.instance.getCancellationReasons("CLIENT")
                if (activity.isFinishing || activity.isDestroyed) return@launch

                if (response.isSuccessful) {
                    val reasons = response.body()?.filter { it.isActive } ?: emptyList()
                    if (reasons.isNotEmpty()) {
                        onShowCancelDialog(reasons)
                    } else {
                        viewModel.cancelOrder(null)
                    }
                } else {
                    viewModel.cancelOrder(null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    viewModel.cancelOrder(null)
                }
            }
        }
    }

    fun restoreOrderOnMap(
        order: TaxiOrderDto,
        map: GoogleMap?,
        isRouteMode: Boolean,
        activeOrderId: String?,
        currentWaypoints: MutableList<Pair<LatLng, String>>,
        onHidePin: () -> Unit,
        onClearDriverMarker: () -> Unit,
        onSetPlacesAndPoints: (Place, Place, List<LatLng>?) -> Unit,
        callbacks: StatusCallbacks
    ): Boolean {
        if (map == null) return false
        if (isRouteMode && activeOrderId == order.id && mapRouteController.polylineMain != null) return true

        onHidePin()

        val originLat = order.originLat ?: return true
        val originLng = order.originLng ?: return true
        val destLat = order.destLat ?: return true
        val destLng = order.destLng ?: return true

        val originLoc = LatLng(originLat, originLng)
        val destLoc = LatLng(destLat, destLng)

        val newOriginPlace = Place.builder().setName(order.fromAddress).setLatLng(originLoc).build()
        val newDestPlace = Place.builder().setName(order.toAddress).setLatLng(destLoc).build()

        binding.textViewOrigin.text = AddressUtils.formatAddress(order.fromAddress ?: "А")
        binding.textViewDestination.text = AddressUtils.formatAddress(order.toAddress ?: "Б")

        currentWaypoints.clear()
        order.stops?.forEach { stop ->
            if (stop.lat != 0.0 && stop.lng != 0.0) {
                currentWaypoints.add(Pair(LatLng(stop.lat, stop.lng), stop.address))
            }
        }

        val polyline = order.googleRoutePolyline
        if (!polyline.isNullOrEmpty()) {
            viewModel.currentRoutePolyline = polyline
            val points = PolyUtil.decode(polyline)
            onSetPlacesAndPoints(newOriginPlace, newDestPlace, points)

            binding.bottomSheetCard.visibility = View.GONE
            binding.addressPanel.visibility = View.GONE

            val s = order.status
            if (s == "ACCEPTED" || s == "DRIVER_ARRIVED") {
                map.clear()
                onClearDriverMarker()

                newOriginPlace.latLng?.let { oLatLng ->
                    mapRouteController.originMarker = map.addMarker(
                        MarkerOptions()
                            .position(oLatLng)
                            .icon(mapRouteController.getBitmapDescriptor(R.drawable.ic_marker_base_yellow))
                            .anchor(0.5f, 0.5f)
                            .zIndex(1000f)
                    )
                    binding.overlayOrigin.visibility = View.GONE
                    binding.overlayOrigin.alpha = 1f
                }
                binding.overlayDest.visibility = View.GONE

                if (s == "ACCEPTED") {
                    callbacks.onDrawPickupRoute(order.driverToPickupPolyline)
                }
                callbacks.onStartDriverTracking(order.id)
            } else {
                if (s == "IN_PROGRESS") {
                    callbacks.onStartDriverTracking(order.id)
                }
                callbacks.onDrawStylishRoute(points, s)
            }
        } else {
            onSetPlacesAndPoints(newOriginPlace, newDestPlace, null)
            binding.bottomSheetCard.visibility = View.GONE
            binding.addressPanel.visibility = View.GONE
            viewModel.fetchDirections(originLoc, destLoc, currentWaypoints)

            if (order.status == "ACCEPTED" || order.status == "DRIVER_ARRIVED" || order.status == "IN_PROGRESS") {
                callbacks.onStartDriverTracking(order.id)
            }
        }

        order.driver?.let { drv ->
            val lat = drv.latitude
            val lng = drv.longitude
            val bearing = drv.bearing ?: 0f
            if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
                lastKnownDriverLatLng = LatLng(lat, lng)
                lastKnownDriverBearing = bearing
            }
        }

        lastKnownDriverLatLng?.let { finalDriverLatLng ->
            callbacks.onUpdateDriverMarker(
                TrackingLocationDto(
                    lat = finalDriverLatLng.latitude,
                    lng = finalDriverLatLng.longitude,
                    bearing = lastKnownDriverBearing
                )
            )
        }
        return true
    }

    fun showActiveOrderPanel(
        order: TaxiOrderDto,
        availableTariffs: List<CarTariffDto>,
        onStopNearbyDrivers: () -> Unit,
        onUpdateMapPaddingFirstShow: () -> Unit,
        callbacks: StatusCallbacks
    ) {
        onStopNearbyDrivers()

        val isFirstShow = binding.activeOrderCard.visibility != View.VISIBLE || binding.btnMenuIcon.tag != "order_mode"

        binding.tvOrderRouteOrigin.text = AddressUtils.formatAddress(order.fromAddress ?: "А")
        binding.tvOrderRouteDest.text = AddressUtils.formatAddress(order.toAddress ?: "Б")

        val isSearch = order.status == "REQUESTED" || order.status == "OFFERING" || order.status == "SCHEDULED"
        if (isSearch) {
            binding.layoutDriverFoundState.visibility = View.GONE
            binding.layoutDriverAssignedDetails.visibility = View.GONE
            binding.layoutSearchControls.visibility = View.VISIBLE
            binding.layoutSearchDetails.visibility = View.VISIBLE
            binding.tvCarPlateLarge.text = ""
            binding.tvDriverFirstName.text = ""
            binding.tvCarDetailsSubtitle.text = ""
        }

        binding.activeOrderCard.visibility = View.VISIBLE
        binding.tariffsPanel.visibility = View.GONE
        binding.bottomSheetCard.visibility = View.GONE
        binding.addressPanel.visibility = View.GONE
        binding.btnRecenterLocation.visibility = View.GONE

        binding.btnMenuIcon.tag = "order_mode"

        if (isFirstShow && order.status != "REQUESTED" && order.status != "OFFERING") {
            onUpdateMapPaddingFirstShow()
        }

        binding.btnMenu.visibility = View.VISIBLE
        binding.btnMenuIcon.setImageResource(R.drawable.ic_arrow_back_black)
        val adaptiveColor = ContextCompat.getColor(activity, R.color.text_primary)
        binding.btnMenuIcon.setColorFilter(adaptiveColor)

        try { binding.btnOpenPromo.visibility = View.GONE } catch (_: Exception) {}

        binding.tvActiveOrderPrice.text = String.format("%.0f ₴", order.effectiveClientPrice)
        binding.tvSearchOrderPrice.text = String.format("%.0f ₴", order.effectiveClientPrice)
        binding.tvSearchTariffSubtitle.text = order.tariffName ?: "Стандарт"

        when (order.paymentMethod) {
            "CARD" -> {
                binding.ivActiveOrderPayment.setImageResource(R.drawable.ic_card)
                binding.tvActiveOrderPaymentText.text = activity.getString(R.string.payment_method_card)
            }
            "DRIVER_CARD" -> {
                binding.ivActiveOrderPayment.setImageResource(R.drawable.ic_card)
                binding.tvActiveOrderPaymentText.text = activity.getString(R.string.payment_method_driver_card)
            }
            else -> {
                binding.ivActiveOrderPayment.setImageResource(R.drawable.ic_cash)
                binding.tvActiveOrderPaymentText.text = activity.getString(R.string.payment_method_cash)
            }
        }

        binding.tvOrderRouteOrigin.text = AddressUtils.formatAddress(order.fromAddress ?: "А")
        binding.tvOrderRouteDest.text = AddressUtils.formatAddress(order.toAddress ?: "Б")

        binding.llRouteWaypoints.removeAllViews()
        if (!order.stops.isNullOrEmpty()) {
            binding.llRouteWaypoints.visibility = View.VISIBLE
            order.stops.forEach { stop ->
                val stopView = activity.layoutInflater.inflate(
                    R.layout.item_active_order_waypoint,
                    binding.llRouteWaypoints,
                    false
                )
                val tvAddress = stopView.findViewById<TextView>(R.id.tv_waypoint_address)
                tvAddress.text = AddressUtils.formatAddress(stop.address)
                binding.llRouteWaypoints.addView(stopView)
            }
        } else {
            binding.llRouteWaypoints.visibility = View.GONE
        }

        updateActiveOrderTariffIcon(order, availableTariffs)

        if (order.services.isNotEmpty()) {
            binding.tvOrderServices.visibility = View.VISIBLE
            binding.tvOrderServices.text = "+ " + order.services.joinToString(", ") { it.name }
        } else {
            binding.tvOrderServices.visibility = View.GONE
        }

        if (!order.comment.isNullOrBlank()) {
            binding.tvCommentTitle.visibility = View.VISIBLE
            binding.layoutOrderCommentCard.visibility = View.VISIBLE
            binding.tvOrderComment.text = order.comment
        } else {
            binding.tvCommentTitle.visibility = View.GONE
            binding.layoutOrderCommentCard.visibility = View.GONE
        }

        updateStatusUI(order, callbacks)
    }

    fun updateActiveOrderTariffIcon(order: TaxiOrderDto, availableTariffs: List<CarTariffDto>) {
        binding.tvOrderTariffName.text = order.tariffName

        val matchingTariff = availableTariffs.find { it.name.equals(order.tariffName, ignoreCase = true) }
        val rawUrl = matchingTariff?.imageUrl

        binding.ivOrderTariffIcon.imageTintList = null
        binding.ivOrderTariffIcon.clearColorFilter()

        if (!rawUrl.isNullOrEmpty()) {
            val fullUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                rawUrl
            } else {
                val cleanPath = rawUrl.replace("\\", "/").replace(Regex("/{2,}"), "/").trimStart('/')
                val baseUrlRoot = ApiClient.BASE_URL.substringBefore("api/v1/")
                "${baseUrlRoot}${cleanPath}"
            }

            Glide.with(activity)
                .load(fullUrl)
                .placeholder(R.drawable.ic_taxi_model_standard)
                .error(R.drawable.ic_taxi_model_standard)
                .into(binding.ivOrderTariffIcon)
        } else {
            binding.ivOrderTariffIcon.setImageResource(R.drawable.ic_taxi_model_standard)
        }
    }

    fun updateHeaderEta(order: TaxiOrderDto, routeDurationSeconds: Int) {
        val calendar = Calendar.getInstance()
        val durationSec = if (order.status == "IN_PROGRESS") {
            order.durationSeconds ?: routeDurationSeconds
        } else {
            if (mapRouteController.pickupRoutePoints.isNotEmpty()) {
                val meters = SphericalUtil.computeLength(mapRouteController.pickupRoutePoints)
                (meters / 9.7).toInt().coerceAtLeast(180)
            } else {
                300
            }
        }
        calendar.add(Calendar.SECOND, durationSec)
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        binding.tvHeaderEtaTime.text = sdf.format(calendar.time)
    }

    fun updateDriverInfo(order: TaxiOrderDto, onShowDriverHealth: (List<String>) -> Unit) {
        order.driver?.let { drv ->
            binding.tvCarPlateLarge.text = drv.carPlateNumber ?: "---"

            val colorPart = drv.carColor ?: ""
            val rawModel = drv.carModel ?: "Авто"
            val modelPart = rawModel.replace(Regex("\\+?\\d{9,13}"), "").trim().trimEnd(',', '-')
            val tariffPart = order.tariffName ?: "Стандарт"

            val subtitle = buildString {
                if (colorPart.isNotEmpty()) append("$colorPart ")
                append(modelPart)
                append(" • ")
                append(tariffPart)
            }
            binding.tvCarDetailsSubtitle.text = subtitle

            Car3dModelHelper.bindCar3dImage(
                context = activity,
                imageView = binding.ivAssignedCar3d,
                visualTypeRaw = drv.carVisualType,
                colorKeyRaw = drv.carColorKey
            )

            val isPartner = (drv.id == -1L)
            binding.btnChatDriver.alpha = if (isPartner) 0.5f else 1.0f
            if (isPartner) {
                binding.tvChatBadge.visibility = View.GONE
            }

            binding.tvDriverFirstName.text = if (isPartner) {
                "Водій"
            } else {
                drv.fullName.split(" ").firstOrNull() ?: drv.fullName
            }

            binding.tvDriverRatingVal.text = String.format(Locale.US, "%.1f", drv.rating)

            val count = drv.completedRides
            val ridesText = when {
                count % 10 == 1 && count % 100 != 11 -> "$count поїздка"
                count % 10 in 2..4 && count % 100 !in 12..14 -> "$count поїздки"
                else -> "$count поїздок"
            }
            binding.tvDriverRidesCount.text = ridesText

            binding.activeOrderCard.tag = drv.phoneNumber

            val healthIssues = mutableListOf<String>()
            if (drv.hasMovementIssue) healthIssues.add("Порушення опорно-рухового апарату")
            if (drv.hasHearingIssue) healthIssues.add("Порушення слуху")
            if (drv.isDeaf) healthIssues.add("Водій не чує (глухий)")
            if (drv.hasSpeechIssue) healthIssues.add("Порушення мовлення")

            if (healthIssues.isNotEmpty()) {
                binding.btnDriverHealthAlert.visibility = View.VISIBLE
                binding.btnDriverHealthAlert.setOnClickListener {
                    onShowDriverHealth(healthIssues)
                }
            } else {
                binding.btnDriverHealthAlert.visibility = View.GONE
            }

            if (!drv.photoUrl.isNullOrEmpty()) {
                var finalUrl = drv.photoUrl!!
                val baseUrlUri = Uri.parse(ApiClient.BASE_URL)
                val serverRoot = "${baseUrlUri.scheme}://${baseUrlUri.host}${if (baseUrlUri.port != -1) ":${baseUrlUri.port}" else ""}"

                if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
                    val cleanPath = finalUrl.replace("\\", "/").replace(Regex("/{2,}"), "/").trimStart('/')
                    finalUrl = "$serverRoot/$cleanPath"
                }

                Glide.with(activity)
                    .load(finalUrl)
                    .placeholder(R.drawable.ic_avatar_placeholder)
                    .circleCrop()
                    .into(binding.ivDriverPhoto)
            } else {
                binding.ivDriverPhoto.setImageResource(R.drawable.ic_avatar_placeholder)
            }
        }
    }

    fun updateStatusUI(order: TaxiOrderDto, callbacks: StatusCallbacks) {
        val mMap = callbacks.map
        val wasInSearchMode = isCameraFocusedOnSearch && order.status == "CANCELLED"
        val fromHeight = if (wasInSearchMode) {
            val marginBottom = (binding.activeOrderCard.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
            val extraBuffer = mapRouteController.convertDpToPixel(4f).toInt()
            mapRouteController.convertDpToPixel(24f).toInt() - marginBottom - extraBuffer
        } else {
            binding.activeOrderCard.height
        }

        if (binding.activeOrderCard.visibility == View.VISIBLE && binding.activeOrderCard.height > 0) {
            (binding.activeOrderCard as? ViewGroup)?.let { cardGroup ->
                val transition = AutoTransition().apply {
                    duration = 250
                    interpolator = AccelerateDecelerateInterpolator()
                }
                TransitionManager.beginDelayedTransition(cardGroup, transition)
            }
        }

        binding.orderStatusText.setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        binding.btnCancelOrder.isEnabled = true
        binding.btnCancelOrder.text = activity.getString(R.string.btn_cancel_order)

        if (!order.scheduledAt.isNullOrEmpty()) {
            binding.tvScheduledTime.visibility = View.VISIBLE
            val timeStr = try {
                if (order.scheduledAt.contains("T")) {
                    order.scheduledAt.substringAfter("T").take(5)
                } else {
                    order.scheduledAt
                }
            } catch (_: Exception) {
                order.scheduledAt
            }
            binding.tvScheduledTime.text = "Орієнтовний час подачі $timeStr"
        } else {
            binding.tvScheduledTime.visibility = View.GONE
        }

        when (order.status) {
            "SCHEDULED" -> {
                binding.animatedBorderHeader.stopBorderAnimation()
                searchRadarController.stop()
                callbacks.onStopBoostTimer()
                isCameraFocusedOnSearch = false

                mMap?.uiSettings?.isScrollGesturesEnabled = true
                mMap?.uiSettings?.isZoomGesturesEnabled = true

                binding.orderStatusText.text = "Заплановано"
                binding.tvSearchTariffSubtitle.text = order.tariffName ?: "Стандарт"
                binding.tvSearchTariffSubtitle.visibility = View.VISIBLE
                binding.tvSearchOrderPrice.text = String.format("%.0f ₴", order.effectiveClientPrice)
                stopStatusBlinking()

                binding.layoutSearchControls.visibility = View.VISIBLE
                binding.layoutDriverFoundState.visibility = View.GONE
                binding.layoutSearchDetails.visibility = View.VISIBLE
                binding.tvOrderTariffTitle.visibility = View.VISIBLE
                binding.layoutDriverAssignedDetails.visibility = View.GONE
                binding.layoutPaymentCompleted.visibility = View.GONE

                binding.btnCancelOrder.visibility = View.VISIBLE
                binding.btnCancelRideDriver.visibility = View.GONE

                binding.overlayOrigin.visibility = View.GONE
                binding.overlayDest.visibility = View.GONE
                binding.btnRecenterRoute.visibility = View.GONE

                callbacks.onStopDriverTracking()
                orderTimersController.stopWaitingTimer(binding.cardWaitingTimer)
            }

            "REQUESTED", "OFFERING" -> {
                callbacks.onCheckAndScheduleBoost(order)

                binding.layoutHeaderSearchContent.visibility = View.VISIBLE
                binding.layoutHeaderDriverContent.visibility = View.GONE

                binding.orderStatusText.text = "Пошук вільного авто"
                binding.tvSearchTariffSubtitle.text = order.tariffName ?: "Стандарт"
                binding.tvSearchTariffSubtitle.visibility = View.VISIBLE
                binding.tvSearchOrderPrice.text = String.format("%.0f ₴", order.effectiveClientPrice)
                startStatusBlinking()
                binding.animatedBorderHeader.startBorderAnimation()

                binding.layoutSearchControls.visibility = View.VISIBLE
                binding.layoutDriverFoundState.visibility = View.GONE
                binding.layoutSearchDetails.visibility = View.VISIBLE
                binding.tvOrderTariffTitle.visibility = View.VISIBLE
                binding.layoutDriverAssignedDetails.visibility = View.GONE
                binding.layoutPaymentCompleted.visibility = View.GONE

                binding.btnCancelOrder.visibility = View.VISIBLE
                binding.btnCancelRideDriver.visibility = View.GONE
                binding.overlayOrigin.visibility = View.GONE
                binding.overlayDest.visibility = View.GONE
                binding.btnRecenterRoute.visibility = View.GONE
                binding.btnRecenterLocation.visibility = View.GONE

                mMap?.uiSettings?.isScrollGesturesEnabled = false
                mMap?.uiSettings?.isZoomGesturesEnabled = false

                val originLoc = callbacks.originPlace?.latLng ?: LatLng(
                    order.originLat ?: 0.0,
                    order.originLng ?: 0.0
                )

                if (!searchRadarController.isRunning && mMap != null) {
                    searchRadarController.start(mMap, originLoc, callbacks.isDarkMode)
                }

                val destLat = order.destLat
                val destLng = order.destLng
                if (destLat != null && destLng != null && destLat != 0.0 && destLng != 0.0) {
                    val destLatLng = LatLng(destLat, destLng)
                    try {
                        if (mapRouteController.destinationMarker == null) {
                            mapRouteController.destinationMarker = mMap?.addMarker(
                                MarkerOptions()
                                    .position(destLatLng)
                                    .icon(mapRouteController.getBitmapDescriptor(R.drawable.ic_marker_base_white))
                                    .anchor(0.5f, 1.0f)
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                if (mapRouteController.waypointMarkers.isEmpty() && !order.stops.isNullOrEmpty()) {
                    order.stops.forEach { stop ->
                        if (stop.lat != 0.0 && stop.lng != 0.0) {
                            val wpMarker = mMap?.addMarker(
                                MarkerOptions()
                                    .position(LatLng(stop.lat, stop.lng))
                                    .icon(mapRouteController.getBitmapDescriptor(R.drawable.ic_marker_waypoint))
                                    .anchor(0.5f, 0.5f)
                            )
                            if (wpMarker != null) mapRouteController.waypointMarkers.add(wpMarker)
                        }
                    }
                }

                if (!isCameraFocusedOnSearch) {
                    isCameraFocusedOnSearch = true
                    binding.activeOrderCard.postDelayed({
                        if (!activity.isFinishing && !activity.isDestroyed && mMap != null) {
                            val cameraPosition = CameraPosition.Builder()
                                .target(originLoc)
                                .zoom(16.0f)
                                .tilt(45f)
                                .bearing(0f)
                                .build()
                            mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), 1000, null)
                        }
                    }, 350)
                }
            }

            "ACCEPTED" -> {
                binding.animatedBorderHeader.stopBorderAnimation()
                callbacks.onStopBoostTimer()
                isCameraFocusedOnSearch = false

                val isPartner = (order.driver?.id == -1L)
                AnalyticsManager.trackOrderAccepted(
                    orderId = order.id,
                    driverSource = if (isPartner) "evos_exchange" else "own_fleet",
                    searchDurationSec = 45L
                )

                mapRouteController.polylineMain?.remove()
                mapRouteController.polylineMain = null
                mapRouteController.polylineBorder?.remove()
                mapRouteController.polylineBorder = null
                mapRouteController.polylineAnim?.remove()
                mapRouteController.polylineAnim = null

                if (mapRouteController.pickupPolyline == null && !order.driverToPickupPolyline.isNullOrEmpty()) {
                    callbacks.onDrawPickupRoute(order.driverToPickupPolyline)
                }
                mapRouteController.waypointMarkers.forEach { it.remove() }
                mapRouteController.waypointMarkers.clear()

                mapRouteController.destinationMarker?.remove()
                mapRouteController.destinationMarker = null

                binding.overlayOrigin.visibility = View.GONE
                binding.overlayDest.visibility = View.GONE

                searchRadarController.stop()

                mMap?.uiSettings?.isScrollGesturesEnabled = true
                mMap?.uiSettings?.isZoomGesturesEnabled = true

                stopStatusBlinking()

                binding.layoutHeaderSearchContent.visibility = View.GONE
                binding.layoutHeaderDriverContent.visibility = View.VISIBLE
                binding.animatedBorderHeader.stopBorderAnimation()

                binding.orderStatusDriverText.text = "Їде до вас"
                binding.tvDriverStatusSubtext.text = "маршрут може змінюватися залежно від дорожніх умов"
                binding.tvDriverStatusSubtext.visibility = View.VISIBLE
                binding.cardHeaderEtaPill.visibility = View.VISIBLE
                updateHeaderEta(order, callbacks.routeDurationSeconds)

                binding.layoutSearchControls.visibility = View.GONE
                binding.layoutDriverFoundState.visibility = View.VISIBLE

                binding.layoutSearchDetails.visibility = View.GONE
                binding.tvOrderTariffTitle.visibility = View.GONE
                binding.layoutDriverAssignedDetails.visibility = View.VISIBLE
                binding.layoutPaymentCompleted.visibility = View.GONE

                binding.btnCancelOrder.visibility = View.GONE
                binding.btnCancelRideDriver.visibility = View.VISIBLE

                binding.btnRecenterRoute.translationY = 0f
                binding.btnRecenterRoute.visibility = View.GONE

                updateDriverInfo(order, callbacks::onShowDriverHealth)
                orderTimersController.stopWaitingTimer(binding.cardWaitingTimer)

                order.driver?.let { drv ->
                    val lat = drv.latitude
                    val lng = drv.longitude
                    if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
                        val initialLoc = TrackingLocationDto(
                            lat = lat,
                            lng = lng,
                            bearing = drv.bearing ?: 0f
                        )
                        callbacks.onUpdateDriverMarker(initialLoc)
                        callbacks.onFocusOnPickupRoute(LatLng(lat, lng))
                    }
                }

                callbacks.onStartDriverTracking(order.id)
            }

            "DRIVER_ARRIVED" -> {
                binding.animatedBorderHeader.stopBorderAnimation()
                callbacks.onStopBoostTimer()
                isCameraFocusedOnSearch = false

                mapRouteController.pickupPolyline?.remove()
                mapRouteController.pickupPolyline = null
                mapRouteController.pickupRoutePoints.clear()

                mapRouteController.polylineMain?.remove()
                mapRouteController.polylineMain = null
                mapRouteController.polylineBorder?.remove()
                mapRouteController.polylineBorder = null
                mapRouteController.polylineAnim?.remove()
                mapRouteController.polylineAnim = null

                mapRouteController.waypointMarkers.forEach { it.remove() }
                mapRouteController.waypointMarkers.clear()

                mapRouteController.destinationMarker?.remove()
                mapRouteController.destinationMarker = null

                binding.overlayOrigin.visibility = View.GONE
                binding.overlayDest.visibility = View.GONE
                searchRadarController.stop()

                mMap?.uiSettings?.isScrollGesturesEnabled = true
                mMap?.uiSettings?.isZoomGesturesEnabled = true

                stopStatusBlinking()

                binding.layoutHeaderSearchContent.visibility = View.GONE
                binding.layoutHeaderDriverContent.visibility = View.VISIBLE
                binding.animatedBorderHeader.stopBorderAnimation()

                binding.orderStatusDriverText.text = "Водій на місці"
                binding.tvDriverStatusSubtext.text = "водій очікує на вас у точці посадки"
                binding.tvDriverStatusSubtext.visibility = View.VISIBLE
                binding.cardHeaderEtaPill.visibility = View.GONE

                binding.layoutSearchControls.visibility = View.GONE
                binding.layoutDriverFoundState.visibility = View.VISIBLE

                binding.layoutSearchDetails.visibility = View.GONE
                binding.tvOrderTariffTitle.visibility = View.GONE
                binding.layoutDriverAssignedDetails.visibility = View.VISIBLE
                binding.layoutPaymentCompleted.visibility = View.GONE

                binding.btnCancelOrder.visibility = View.GONE
                binding.btnCancelRideDriver.visibility = View.VISIBLE
                binding.btnRecenterRoute.visibility = View.GONE

                updateDriverInfo(order, callbacks::onShowDriverHealth)
                orderTimersController.startWaitingTimer(order, binding.cardWaitingTimer, binding.tvNewWaitingTimer)

                val targetLatLng = order.driver?.let { drv ->
                    if (drv.latitude != null && drv.longitude != null && drv.latitude != 0.0 && drv.longitude != 0.0) {
                        val initialLoc = TrackingLocationDto(
                            lat = drv.latitude,
                            lng = drv.longitude,
                            bearing = drv.bearing ?: 0f
                        )
                        callbacks.onUpdateDriverMarker(initialLoc)
                        LatLng(drv.latitude, drv.longitude)
                    } else null
                } ?: callbacks.originPlace?.latLng ?: LatLng(order.originLat ?: 0.0, order.originLng ?: 0.0)

                if (targetLatLng.latitude != 0.0 && targetLatLng.longitude != 0.0) {
                    mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(targetLatLng, 16.5f), 800, null)
                }

                callbacks.onStartDriverTracking(order.id)
            }

            "IN_PROGRESS" -> {
                binding.animatedBorderHeader.stopBorderAnimation()
                callbacks.onStopBoostTimer()
                mapRouteController.pickupPolyline?.remove()
                mapRouteController.pickupPolyline = null
                mapRouteController.pickupRoutePoints.clear()
                isCameraFocusedOnSearch = false
                searchRadarController.stop()

                mMap?.uiSettings?.isScrollGesturesEnabled = true
                mMap?.uiSettings?.isZoomGesturesEnabled = true

                stopStatusBlinking()

                binding.layoutHeaderSearchContent.visibility = View.GONE
                binding.layoutHeaderDriverContent.visibility = View.VISIBLE
                binding.animatedBorderHeader.stopBorderAnimation()

                binding.orderStatusDriverText.text = "В дорозі"
                binding.tvDriverStatusSubtext.text = "маршрут може змінюватися залежно від дорожніх умов"
                binding.tvDriverStatusSubtext.visibility = View.VISIBLE
                binding.cardHeaderEtaPill.visibility = View.VISIBLE
                updateHeaderEta(order, callbacks.routeDurationSeconds)

                binding.layoutSearchControls.visibility = View.GONE
                binding.layoutDriverFoundState.visibility = View.VISIBLE

                binding.layoutSearchDetails.visibility = View.GONE
                binding.tvOrderTariffTitle.visibility = View.GONE
                binding.layoutDriverAssignedDetails.visibility = View.VISIBLE
                binding.layoutPaymentCompleted.visibility = View.GONE

                binding.btnCancelOrder.visibility = View.GONE
                binding.btnCancelRideDriver.visibility = View.GONE

                callbacks.onSetRecenterRouteAnchor(R.id.active_order_card, 16f)
                binding.btnRecenterRoute.translationY = 0f

                binding.overlayOrigin.visibility = View.GONE
                binding.overlayDest.visibility = View.GONE

                if (mapRouteController.polylineMain == null) {
                    val polylineStr = order.googleRoutePolyline ?: viewModel.currentRoutePolyline
                    if (!polylineStr.isNullOrEmpty()) {
                        try {
                            val mainRoutePoints = PolyUtil.decode(polylineStr)
                            if (!mainRoutePoints.isNullOrEmpty()) {
                                callbacks.onSetDecodedRoutePoints(mainRoutePoints)
                                callbacks.onDrawStylishRoute(mainRoutePoints, order.status)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                updateDriverInfo(order, callbacks::onShowDriverHealth)
                orderTimersController.stopWaitingTimer(binding.cardWaitingTimer)

                order.driver?.let { drv ->
                    val lat = drv.latitude
                    val lng = drv.longitude
                    if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
                        val initialLoc = TrackingLocationDto(lat = lat, lng = lng, bearing = drv.bearing ?: 0f)
                        callbacks.onUpdateDriverMarker(initialLoc)
                    }
                }

                mapRouteController.updateInProgressDestinationBadge(mMap, order)
                callbacks.onStartDriverTracking(order.id)
            }

            "COMPLETED" -> {
                binding.animatedBorderHeader.stopBorderAnimation()
                callbacks.onStopBoostTimer()
                isCameraFocusedOnSearch = false
                mMap?.uiSettings?.isScrollGesturesEnabled = true
                mMap?.uiSettings?.isZoomGesturesEnabled = true

                stopStatusBlinking()
                resetOrderDetailsState()

                binding.layoutHeaderSearchContent.visibility = View.GONE
                binding.layoutHeaderDriverContent.visibility = View.VISIBLE
                binding.animatedBorderHeader.stopBorderAnimation()

                binding.orderStatusDriverText.text = "Завершено"
                binding.tvDriverStatusSubtext.text = "Фініш маршруту. Сподіваємось, вам усе сподобалось!"
                binding.tvDriverStatusSubtext.visibility = View.VISIBLE

                binding.cardHeaderEtaPill.visibility = View.GONE
                binding.cardWaitingTimer.visibility = View.GONE

                binding.layoutSearchControls.visibility = View.GONE
                binding.layoutDriverFoundState.visibility = View.GONE

                binding.layoutSearchDetails.visibility = View.GONE
                binding.tvOrderTariffTitle.visibility = View.GONE
                binding.layoutDriverAssignedDetails.visibility = View.GONE

                binding.btnCancelOrder.visibility = View.GONE
                binding.btnCancelRideDriver.visibility = View.GONE
                binding.btnRecenterRoute.visibility = View.GONE

                binding.overlayOrigin.visibility = View.GONE
                binding.overlayDest.visibility = View.GONE

                callbacks.onStopDriverTracking()
                orderTimersController.stopWaitingTimer(binding.cardWaitingTimer)

                binding.layoutPaymentCompleted.visibility = View.VISIBLE
                binding.tvFinalPaymentPrice.text = String.format("%.0f ₴", order.effectiveClientPrice)

                val targetBottomPaddingPx = mapRouteController.convertDpToPixel(20f).toInt()
                binding.layoutPaymentCompleted.setPadding(
                    binding.layoutPaymentCompleted.paddingLeft,
                    binding.layoutPaymentCompleted.paddingTop,
                    binding.layoutPaymentCompleted.paddingRight,
                    targetBottomPaddingPx
                )

                binding.btnUnderstandPayment.setOnClickListener {
                    binding.layoutPaymentCompleted.visibility = View.GONE
                    callbacks.onCompletedUnderstandClicked(order)
                }
            }

            "CANCELLED" -> {
                binding.animatedBorderHeader.stopBorderAnimation()
                callbacks.onStopBoostTimer()
                mapRouteController.pickupPolyline?.remove()
                mapRouteController.pickupPolyline = null
                mapRouteController.pickupRoutePoints.clear()
                isCameraFocusedOnSearch = false
                stopStatusBlinking()
                resetOrderDetailsState()
                binding.orderStatusText.text = activity.getString(R.string.status_cancelled)
                binding.orderStatusText.setTextColor(ContextCompat.getColor(activity, R.color.taxi_red_cancel))

                AnalyticsManager.trackOrderFailed(
                    orderId = order.id,
                    reason = order.cancellationReason ?: "Скасовано клієнтом",
                    searchDurationSec = 60L
                )

                binding.layoutSearchControls.visibility = View.GONE
                binding.layoutDriverFoundState.visibility = View.GONE
                binding.layoutSearchDetails.visibility = View.GONE
                binding.tvOrderTariffTitle.visibility = View.GONE
                binding.layoutDriverAssignedDetails.visibility = View.GONE
                binding.layoutPaymentCompleted.visibility = View.GONE

                binding.btnCancelOrder.visibility = View.GONE
                binding.btnCancelRideDriver.visibility = View.GONE
                binding.btnRecenterRoute.visibility = View.GONE

                searchRadarController.stop()
                callbacks.onStopDriverTracking()
                orderTimersController.stopWaitingTimer(binding.cardWaitingTimer)

                callbacks.onOrderCancelledReset()
            }
        }

        var cardWidth = binding.activeOrderCard.width
        if (cardWidth == 0) {
            val sideMargin = (binding.activeOrderCard.layoutParams as? ViewGroup.MarginLayoutParams)?.leftMargin ?: 0
            cardWidth = activity.resources.displayMetrics.widthPixels - (sideMargin * 2)
        }

        binding.activeOrderCard.measure(
            View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        var toHeight = binding.activeOrderCard.measuredHeight

        if (binding.layoutExpandableDetails.visibility == View.VISIBLE) {
            toHeight -= binding.layoutExpandableDetails.height
        }

        val status = order.status
        val recenterMap = (status == "SCHEDULED" || status == "COMPLETED" || status == "REQUESTED" || status == "OFFERING")

        callbacks.onAnimateMapPadding(fromHeight, toHeight, recenterMap)
    }
}