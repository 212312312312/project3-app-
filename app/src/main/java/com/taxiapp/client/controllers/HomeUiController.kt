package com.taxiapp.client.controllers

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.RelativeLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.libraries.places.api.model.Place
import com.google.maps.android.SphericalUtil
import com.taxiapp.client.AddressPickerActivity
import com.taxiapp.client.BuildConfig
import com.taxiapp.client.FavoriteAddressesActivity
import com.taxiapp.client.HelpActivity
import com.taxiapp.client.HelpDiscountsActivity
import com.taxiapp.client.HistoryActivity
import com.taxiapp.client.HomeActivity
import com.taxiapp.client.HomeViewModel
import com.taxiapp.client.NewsActivity
import com.taxiapp.client.R
import com.taxiapp.client.StatsActivity
import com.taxiapp.client.UserDetailsActivity
import com.taxiapp.client.analytics.AnalyticsManager
import com.taxiapp.client.databinding.ActivityHomeBinding
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.WebSocketManager
import com.taxiapp.client.network.dto.CarTariffDto
import com.taxiapp.client.network.dto.CreateOrderRequestDto
import com.taxiapp.client.network.dto.DriverLocationDto
import com.taxiapp.client.network.dto.TrackingLocationDto
import com.taxiapp.client.network.dto.WaypointDto
import com.taxiapp.client.ui.TariffAdapter
import com.taxiapp.client.ui.TariffItem
import com.taxiapp.client.ui.dialogs.NotificationPermissionDialog
import com.taxiapp.client.utils.BitmapHelper
import com.taxiapp.client.utils.CityData
import com.taxiapp.client.utils.CityDatabase
import com.taxiapp.client.utils.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

class HomeUiController(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val sessionManager: SessionManager,
    private val markerAnimationController: MarkerAnimationController
) {

    var isButtonsPanelLoading: Boolean = false
    var isInterfaceRevealed: Boolean = false

    val nearbyDriverMarkers = mutableMapOf<Long, Marker>()
    var driverMarker: Marker? = null
    var customCarIcon: BitmapDescriptor? = null

    private val toastHandler = Handler(Looper.getMainLooper())
    private val hideToastRunnable = Runnable { hideTopMessage() }
    private var lastSwitchTime: Long = 0L

    fun setupSystemBars(isDark: Boolean) {
        val window = activity.window
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        if (isDark) {
            window.statusBarColor = Color.BLACK
            window.navigationBarColor = Color.BLACK
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        } else {
            window.statusBarColor = Color.WHITE
            window.navigationBarColor = Color.WHITE
            controller.isAppearanceLightStatusBars = true
            controller.isAppearanceLightNavigationBars = true
        }
    }

    fun applyTheme(isDark: Boolean) {
        try {
            HomeActivity.lastScreenshot?.let {
                if (!it.isRecycled) it.recycle()
            }

            val rootView = activity.window.decorView.rootView
            if (rootView.width > 0 && rootView.height > 0) {
                val bitmap = Bitmap.createBitmap(
                    rootView.width,
                    rootView.height,
                    Bitmap.Config.RGB_565
                )
                val canvas = Canvas(bitmap)
                rootView.draw(canvas)
                HomeActivity.lastScreenshot = bitmap
            } else {
                HomeActivity.lastScreenshot = null
            }
        } catch (_: Exception) {
            HomeActivity.lastScreenshot = null
        }

        val mode = if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    fun renderScreenshotTransitionIfPresent() {
        val screenshot = HomeActivity.lastScreenshot ?: return
        val coverImage = ImageView(activity).apply {
            setImageBitmap(screenshot)
            scaleType = ImageView.ScaleType.FIT_XY
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        (activity.window.decorView as ViewGroup).addView(coverImage)
        coverImage.animate()
            .alpha(0f)
            .setDuration(600)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                (activity.window.decorView as ViewGroup).removeView(coverImage)
                HomeActivity.lastScreenshot?.recycle()
                HomeActivity.lastScreenshot = null
            }
            .start()
    }

    fun setupProfileLogic(currentCityName: String?) {
        val name = sessionManager.getUserName() ?: "User"
        binding.profileUserName.text = name

        val firstLetter = if (name.isNotEmpty()) name.first().toString().uppercase() else "U"
        binding.tvAvatarLetter.text = firstLetter

        binding.profileCurrentCity.text = currentCityName ?: "Не обрано"

        try {
            binding.tvUserRating.text = sessionManager.getUserRating()
        } catch (_: Exception) {}

        val isDark = sessionManager.isDarkMode()
        updateThemeSwitchUI(isDark, animate = false, updateColors = true)

        binding.themeSwitchContainer.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastSwitchTime < 1000) return@setOnClickListener
            lastSwitchTime = currentTime

            val currentMode = sessionManager.isDarkMode()
            val newMode = !currentMode
            sessionManager.saveThemeMode(newMode)

            updateThemeSwitchUI(newMode, animate = true, updateColors = false) {
                applyTheme(newMode)
            }
        }
    }

    fun updateThemeSwitchUI(
        isDarkMode: Boolean,
        animate: Boolean = true,
        updateColors: Boolean = true,
        onAnimationEnd: (() -> Unit)? = null
    ) {
        val duration = if (animate) 200L else 0L

        binding.themeSwitchContainer.post {
            val containerWidth = binding.themeSwitchContainer.width -
                    binding.themeSwitchContainer.paddingStart -
                    binding.themeSwitchContainer.paddingEnd
            val translationX = if (isDarkMode) (containerWidth / 2f) else 0f

            binding.themeSwitchThumb.animate()
                .translationX(translationX)
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    onAnimationEnd?.invoke()
                }
                .start()
        }

        if (updateColors) {
            binding.tvThemeLabel.text = if (isDarkMode) "Темна тема" else "Світла тема"

            val sunColor = ContextCompat.getColor(
                activity,
                if (isDarkMode) R.color.switch_icon_inactive_dark else R.color.switch_icon_active_light
            )
            val moonColor = ContextCompat.getColor(
                activity,
                if (isDarkMode) R.color.switch_icon_active_dark else R.color.switch_icon_inactive_light
            )
            binding.ivThemeSun.setColorFilter(sunColor)
            binding.ivThemeMoon.setColorFilter(moonColor)

            val trackColor = ContextCompat.getColor(
                activity,
                if (isDarkMode) R.color.switch_track_dark else R.color.switch_track_light
            )
            val thumbColor = ContextCompat.getColor(
                activity,
                if (isDarkMode) R.color.switch_thumb_dark else R.color.switch_thumb_light
            )

            binding.themeSwitchContainer.background.setTint(trackColor)
            binding.themeSwitchThumb.background.setTint(thumbColor)
        }
    }

    fun updateThemeLabel() {
        binding.tvThemeLabel.text = if (sessionManager.isDarkMode()) {
            activity.getString(R.string.theme_label_dark)
        } else {
            activity.getString(R.string.theme_label_light)
        }
    }

    fun updateDrawerHeader() {
        val currentName = sessionManager.getUserName()
        if (!currentName.isNullOrEmpty()) {
            binding.profileUserName.text = currentName
            val firstLetter = currentName.trim().firstOrNull()?.toString()?.uppercase() ?: "U"
            binding.tvAvatarLetter.text = firstLetter
        }
        try {
            binding.tvUserRating.text = sessionManager.getUserRating()
        } catch (_: Exception) {}
    }

    fun updateCityUI(name: String) {
        binding.profileCurrentCity.text = name
    }

    fun setupDrawerNavigationListeners(
        onOpenCitySelector: () -> Unit,
        onOpenPaymentScreen: () -> Unit
    ) {
        binding.btnOpenProfileDetails.setOnClickListener {
            activity.startActivity(Intent(activity, UserDetailsActivity::class.java))
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        binding.btnMenuStats.setOnClickListener {
            activity.startActivity(Intent(activity, StatsActivity::class.java))
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        binding.profileBtnChangeCity.setOnClickListener {
            onOpenCitySelector()
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        binding.btnMenuHistory.setOnClickListener {
            activity.startActivity(Intent(activity, HistoryActivity::class.java))
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        binding.btnOpenFavorites.setOnClickListener {
            activity.startActivity(Intent(activity, FavoriteAddressesActivity::class.java))
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        binding.btnMenuPayment.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            onOpenPaymentScreen()
        }
        binding.btnMenuDiscounts.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            activity.startActivity(Intent(activity, HelpDiscountsActivity::class.java))
        }
        binding.btnMenuNews.setOnClickListener {
            activity.startActivity(Intent(activity, NewsActivity::class.java))
        }
        binding.btnMenuHelp.setOnClickListener {
            activity.startActivity(Intent(activity, HelpActivity::class.java))
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
    }

    fun showTopMessage(message: String, isError: Boolean = false) {
        toastHandler.removeCallbacks(hideToastRunnable)
        binding.tvToastMessage.text = message
        if (isError) {
            binding.ivToastIcon.setColorFilter(Color.parseColor("#FF5252"))
        } else {
            binding.ivToastIcon.setColorFilter(Color.parseColor("#FFD600"))
        }

        binding.customToastContainer.visibility = View.VISIBLE
        binding.customToastContainer.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(400)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        toastHandler.postDelayed(hideToastRunnable, 3500)
    }

    fun hideTopMessage() {
        binding.customToastContainer.animate()
            .translationY(-200f)
            .alpha(0f)
            .setDuration(300)
            .withEndAction { binding.customToastContainer.visibility = View.INVISIBLE }
            .start()
    }

    fun showToast(msg: String) {
        val isError = msg.contains("Помилка", true) ||
                msg.contains("Error", true) ||
                msg.contains("недоступний") ||
                msg.contains("геолокацію")
        showTopMessage(msg, isError)
    }

    fun checkAndShowNotificationDialogWithDelay(onRequestPermission: () -> Unit) {
        if (shouldShowNotificationDialog()) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (!activity.isFinishing && !activity.isDestroyed) {
                    showNotificationPermissionDialog(onRequestPermission)
                }
            }, 1000)
        }
    }

    fun shouldShowNotificationDialog(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val isGranted = ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            val alreadyAsked = sessionManager.isNotificationAsked()
            return !isGranted && !alreadyAsked
        }
        return false
    }

    fun showNotificationPermissionDialog(onRequestPermission: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        NotificationPermissionDialog(
            onAllow = {
                sessionManager.setNotificationAsked(true)
                onRequestPermission()
            },
            onDeny = {
                sessionManager.setNotificationAsked(true)
            }
        ).show(activity.supportFragmentManager, "NotificationPermissionDialog")
    }

    fun updateCommentIconState() {
        val nightModeFlags = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDarkMode = nightModeFlags == Configuration.UI_MODE_NIGHT_YES
        val targetColor = if (isDarkMode) Color.WHITE else Color.BLACK
        binding.ivCommentIcon.setColorFilter(targetColor)
        binding.ivServicesIcon.setColorFilter(targetColor)
        binding.ivPriceIcon.setColorFilter(targetColor)
    }

    fun updateFavoriteButtonsUI() {
        val home = sessionManager.getHomeAddress()
        val work = sessionManager.getWorkAddress()

        if (home != null) {
            binding.indicatorAddHome.visibility = View.GONE
            binding.ivIconHome.setColorFilter(ContextCompat.getColor(activity, R.color.text_primary))
        } else {
            binding.indicatorAddHome.visibility = View.VISIBLE
            binding.ivIconHome.setColorFilter(ContextCompat.getColor(activity, R.color.text_secondary))
        }

        if (work != null) {
            binding.indicatorAddWork.visibility = View.GONE
            binding.ivIconWork.setColorFilter(ContextCompat.getColor(activity, R.color.text_primary))
        } else {
            binding.indicatorAddWork.visibility = View.VISIBLE
            binding.ivIconWork.setColorFilter(ContextCompat.getColor(activity, R.color.text_secondary))
        }
    }

    fun updatePaymentIcon(currentPaymentMethod: String) {
        val mask = sessionManager.getCardMask()
        val defaultColor = ContextCompat.getColor(activity, R.color.text_primary)

        binding.tvPaymentMethodText.visibility = if (isButtonsPanelLoading) View.INVISIBLE else View.VISIBLE

        when (currentPaymentMethod) {
            "CARD" -> {
                if (!mask.isNullOrEmpty()) {
                    binding.ivPaymentIcon.setImageResource(R.drawable.ic_card)
                    binding.ivPaymentIcon.setColorFilter(defaultColor)
                    val cleanMask = mask.takeLast(4).replace("*", "0")
                    binding.tvPaymentMethodText.text = "••$cleanMask"
                } else {
                    binding.ivPaymentIcon.setImageResource(R.drawable.ic_cash)
                    binding.ivPaymentIcon.setColorFilter(defaultColor)
                    binding.tvPaymentMethodText.text = activity.getString(R.string.payment_method_cash)
                }
            }
            "DRIVER_CARD" -> {
                binding.ivPaymentIcon.setImageResource(R.drawable.ic_card)
                binding.ivPaymentIcon.setColorFilter(defaultColor)
                binding.tvPaymentMethodText.text = activity.getString(R.string.payment_method_card_short)
            }
            else -> {
                binding.ivPaymentIcon.setImageResource(R.drawable.ic_cash)
                binding.ivPaymentIcon.setColorFilter(defaultColor)
                binding.tvPaymentMethodText.text = activity.getString(R.string.payment_method_cash)
            }
        }
    }

    fun setButtonsLoadingState(isLoading: Boolean) {
        val isOrdering = binding.btnOrderTaxi.text.toString() == "Обробка..."
        isButtonsPanelLoading = isLoading && !isOrdering

        if (isLoading && !isOrdering) {
            binding.buttonsShimmerContainer.showShimmer(true)
            binding.buttonsShimmerContainer.startShimmer()
        } else {
            binding.buttonsShimmerContainer.stopShimmer()
            binding.buttonsShimmerContainer.hideShimmer()
        }

        val componentVisibility = if (isLoading && !isOrdering) View.INVISIBLE else View.VISIBLE

        binding.ivCommentIcon.visibility = componentVisibility
        binding.ivPaymentIcon.visibility = componentVisibility
        binding.ivServicesIcon.visibility = componentVisibility
        binding.ivPriceIcon.visibility = componentVisibility

        binding.tvCommentLabel.visibility = componentVisibility
        binding.tvServicesLabel.visibility = componentVisibility
        binding.tvTipsLabel.visibility = componentVisibility

        if (isLoading && !isOrdering) {
            if (binding.tvPaymentMethodText.visibility == View.VISIBLE) {
                binding.tvPaymentMethodText.tag = "was_visible"
                binding.tvPaymentMethodText.visibility = View.INVISIBLE
            }
        } else {
            binding.tvPaymentMethodText.visibility = View.VISIBLE
            binding.tvPaymentMethodText.tag = null
        }

        binding.btnOpenComment.isEnabled = !isLoading
        binding.btnOpenPayment.isEnabled = !isLoading
        binding.btnOpenServices.isEnabled = !isLoading
        binding.btnChangePrice.isEnabled = !isLoading
    }

    fun updateOrderButtonWithTime(scheduledDate: Calendar?, hasSelectedTariff: Boolean) {
        if (scheduledDate != null) {
            val now = Calendar.getInstance()
            val timeSdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val timeStr = timeSdf.format(scheduledDate.time)

            val dateStr = when {
                isSameDay(scheduledDate, now) -> "Сьогодні, $timeStr"
                isTomorrow(scheduledDate, now) -> "Завтра, $timeStr"
                else -> {
                    val dayMonthSdf = SimpleDateFormat("d MMM", Locale("uk", "UA"))
                    "${dayMonthSdf.format(scheduledDate.time)}, $timeStr"
                }
            }

            val topText = activity.getString(R.string.btn_order)
            val fullText = "$topText\n$dateStr"
            val spannable = SpannableString(fullText)
            spannable.setSpan(
                RelativeSizeSpan(0.75f),
                topText.length + 1,
                fullText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            binding.btnOrderTaxi.text = spannable
        } else {
            if (hasSelectedTariff) {
                binding.btnOrderTaxi.text = activity.getString(R.string.btn_order)
            } else {
                binding.btnOrderTaxi.text = "Оберіть тариф"
            }
        }
        binding.btnSchedule.setColorFilter(Color.parseColor("#454754"))
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    private fun isTomorrow(target: Calendar, now: Calendar): Boolean {
        val tomorrow = Calendar.getInstance()
        tomorrow.timeInMillis = now.timeInMillis
        tomorrow.add(Calendar.DAY_OF_YEAR, 1)
        return isSameDay(target, tomorrow)
    }

    fun revealInterface(isRouteActive: Boolean) {
        if (isInterfaceRevealed) return
        isInterfaceRevealed = true

        binding.mapLoadingCurtain.animate()
            .alpha(0f)
            .setDuration(800)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                binding.mapLoadingCurtain.visibility = View.GONE
            }
            .start()

        val skeletonViews = listOf(
            binding.textViewOrigin, binding.textViewDestination,
            binding.ivMarkerOrigin, binding.ivMarkerDest,
            binding.ivIconHome, binding.ivIconWork,
            binding.indicatorAddHome, binding.indicatorAddWork
        )

        skeletonViews.forEach { view ->
            try {
                view.animate()
                    .alpha(1f)
                    .setDuration(500)
                    .setStartDelay(200)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            } catch (_: Exception) {}
        }

        if (!isRouteActive) {
            binding.centerPin.visibility = View.VISIBLE
            binding.centerPin.animate()
                .alpha(1f)
                .setDuration(500)
                .setStartDelay(200)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { binding.centerPin.alpha = 1f }
                .start()

            try {
                binding.pinShadow.visibility = View.VISIBLE
                binding.pinShadow.animate()
                    .alpha(0.5f)
                    .setDuration(500)
                    .setStartDelay(200)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { binding.pinShadow.alpha = 0.5f }
                    .start()
            } catch (_: Exception) {}
        } else {
            hidePin()
        }
    }

    fun animatePinLift(dpToPx: (Float) -> Float) {
        binding.centerPin.animate().cancel()
        binding.centerPin.alpha = 1f
        binding.centerPin.visibility = View.VISIBLE
        binding.centerPin.animate()
            .translationY(dpToPx(-48f))
            .setStartDelay(0)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .setDuration(250)
            .start()

        try {
            binding.pinShadow.animate().cancel()
            binding.pinShadow.visibility = View.VISIBLE
            binding.pinShadow.animate()
                .scaleX(0.6f)
                .scaleY(0.6f)
                .alpha(0.3f)
                .setStartDelay(0)
                .setDuration(250)
                .start()
        } catch (_: Exception) {}
    }

    fun animatePinDrop(dpToPx: (Float) -> Float) {
        binding.centerPin.animate().cancel()
        binding.centerPin.alpha = 1f
        binding.centerPin.visibility = View.VISIBLE
        binding.centerPin.animate()
            .translationY(dpToPx(-32f))
            .setStartDelay(0)
            .setInterpolator(BounceInterpolator())
            .setDuration(500)
            .start()

        try {
            binding.pinShadow.animate().cancel()
            binding.pinShadow.visibility = View.VISIBLE
            binding.pinShadow.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .alpha(0.5f)
                .setStartDelay(0)
                .setDuration(250)
                .start()
        } catch (_: Exception) {}
    }

    fun hidePin() {
        binding.centerPin.animate().cancel()
        binding.centerPin.visibility = View.GONE
        binding.centerPin.alpha = 0f
        try {
            binding.pinShadow.animate().cancel()
            binding.pinShadow.visibility = View.GONE
            binding.pinShadow.alpha = 0f
        } catch (_: Exception) {}
    }

    fun resetPinForAddressPanel(dpToPx: (Float) -> Float) {
        binding.centerPin.visibility = View.VISIBLE
        binding.centerPin.alpha = 1f
        binding.centerPin.translationY = dpToPx(-48f)
        try {
            binding.pinShadow.visibility = View.VISIBLE
            binding.pinShadow.alpha = 0.3f
            binding.pinShadow.scaleX = 0.6f
            binding.pinShadow.scaleY = 0.6f
        } catch (_: Exception) {}

        binding.centerPin.animate()
            .translationY(dpToPx(-32f))
            .setInterpolator(BounceInterpolator())
            .setDuration(500)
            .start()

        try {
            binding.pinShadow.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .alpha(0.5f)
                .setDuration(250)
                .start()
        } catch (_: Exception) {}
    }

    fun setupTariffsRecyclerView(tariffAdapter: TariffAdapter) {
        binding.tariffsRecyclerView.adapter = tariffAdapter
        binding.tariffsRecyclerView.itemAnimator = null
        binding.tariffsRecyclerView.layoutManager = LinearLayoutManager(activity, LinearLayoutManager.VERTICAL, false)

        while (binding.tariffsRecyclerView.itemDecorationCount > 0) {
            binding.tariffsRecyclerView.removeItemDecorationAt(0)
        }

        binding.tariffsRecyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            val divider: Drawable? = ContextCompat.getDrawable(activity, R.drawable.divider_horizontal)

            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val position = parent.getChildAdapterPosition(view)
                if (position != RecyclerView.NO_POSITION && position < state.itemCount - 1) {
                    outRect.bottom = divider?.intrinsicHeight ?: 0
                } else {
                    outRect.bottom = 0
                }
            }

            override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
                divider?.let {
                    val left = parent.paddingLeft
                    val right = parent.width - parent.paddingRight
                    val childCount = parent.childCount
                    for (i in 0 until childCount) {
                        val child = parent.getChildAt(i)
                        val position = parent.getChildAdapterPosition(child)
                        if (position != RecyclerView.NO_POSITION && position < state.itemCount - 1) {
                            val params = child.layoutParams as RecyclerView.LayoutParams
                            val top = child.bottom + params.bottomMargin
                            val bottom = top + it.intrinsicHeight
                            it.setBounds(left, top, right, bottom)
                            it.draw(c)
                        }
                    }
                }
            }
        })
    }

    fun setLocationButtonAnchor(anchorId: Int) {
        val params = binding.btnRecenterLocation.layoutParams as RelativeLayout.LayoutParams
        params.removeRule(RelativeLayout.ABOVE)
        params.addRule(RelativeLayout.ABOVE, anchorId)
        binding.btnRecenterLocation.layoutParams = params
    }

    fun setRecenterRouteButtonAnchor(anchorId: Int, bottomMarginPx: Int) {
        val params = binding.btnRecenterRoute.layoutParams as RelativeLayout.LayoutParams
        params.removeRule(RelativeLayout.ABOVE)
        params.addRule(RelativeLayout.ABOVE, anchorId)
        params.bottomMargin = bottomMarginPx
        binding.btnRecenterRoute.layoutParams = params
    }

    fun clearNearbyDrivers() {
        nearbyDriverMarkers.values.forEach { it.remove() }
        nearbyDriverMarkers.clear()
    }

    fun updateNearbyDriversOnMap(
        map: GoogleMap?,
        drivers: List<DriverLocationDto>,
        shouldHideAll: Boolean
    ) {
        if (map == null) return

        if (shouldHideAll) {
            clearNearbyDrivers()
            return
        }

        val mapCenter = map.cameraPosition.target
        // Синхронизировано с серверным радиусом (2000 м + буфер 500 м на смещение камеры),
        // чтобы клиент не отсекал машины в 800–2000 м от пина
        val searchRadiusMeters = 2500.0
        val driversInRange = drivers.filter { driver ->
            val driverPos = LatLng(driver.lat, driver.lng)
            val distance = SphericalUtil.computeDistanceBetween(mapCenter, driverPos)
            distance <= searchRadiusMeters
        }

        val newDriverIds = driversInRange.map { it.driverId }

        val iterator = nearbyDriverMarkers.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!newDriverIds.contains(entry.key)) {
                entry.value.remove()
                iterator.remove()
            }
        }

        for (driver in driversInRange) {
            val position = LatLng(driver.lat, driver.lng)
            if (nearbyDriverMarkers.containsKey(driver.driverId)) {
                val marker = nearbyDriverMarkers[driver.driverId]!!
                markerAnimationController.animateMarker(marker, position, driver.bearing)
            } else {
                val fallbackIcon = BitmapHelper.getScaledBitmapDescriptor(
                    activity,
                    R.drawable.ic_car_icon,
                    40,
                    40
                )
                val carIcon = customCarIcon ?: fallbackIcon
                val markerOpts = MarkerOptions()
                    .position(position)
                    .icon(carIcon)
                    .flat(true)
                    .anchor(0.5f, 0.5f)
                    .rotation(driver.bearing)
                    .zIndex(50f)

                val marker = map.addMarker(markerOpts)
                if (marker != null) {
                    nearbyDriverMarkers[driver.driverId] = marker
                }
            }
        }
    }

    fun updateDriverMarker(
        map: GoogleMap?,
        loc: TrackingLocationDto,
        isMapPickingMode: Boolean,
        currentOrderStatus: String?,
        onTrimPickupRoute: (LatLng) -> Unit,
        onFocusOnPickupRoute: (LatLng) -> Unit
    ) {
        if (binding.activeOrderCard.visibility != View.VISIBLE ||
            binding.tariffsPanel.visibility == View.VISIBLE ||
            isMapPickingMode
        ) {
            removeDriverMarker()
            return
        }

        val lat = loc.lat ?: return
        val lng = loc.lng ?: return
        if (lat == 0.0 && lng == 0.0) return

        val latLng = LatLng(lat, lng)
        onTrimPickupRoute(latLng)

        if (driverMarker != null) {
            val oldPos = driverMarker!!.position
            if (abs(oldPos.latitude - latLng.latitude) < 0.00001 &&
                abs(oldPos.longitude - latLng.longitude) < 0.00001
            ) {
                return
            }
            markerAnimationController.animateMarker(driverMarker!!, latLng, loc.bearing ?: 0f)
            return
        }

        val carIcon = BitmapHelper.getScaledBitmapDescriptor(
            activity,
            R.drawable.ic_car_icon,
            40,
            40
        )

        driverMarker = map?.addMarker(
            MarkerOptions()
                .position(latLng)
                .icon(carIcon)
                .flat(true)
                .anchor(0.5f, 0.5f)
                .rotation(loc.bearing ?: 0f)
                .zIndex(1100f)
        )

        if (currentOrderStatus == "ACCEPTED") {
            onFocusOnPickupRoute(latLng)
        }
    }

    fun removeDriverMarker() {
        driverMarker?.remove()
        driverMarker = null
    }

    fun fetchCustomCarIcon() {
        activity.lifecycleScope.launch {
            try {
                val response = ApiClient.instance.getCarIconUrl()
                if (activity.isFinishing || activity.isDestroyed) return@launch

                var url = response.body()?.get("url")
                if (!url.isNullOrEmpty()) {
                    if (BuildConfig.DEBUG && url.contains("localhost")) {
                        url = url.replace("localhost", "192.168.0.104")
                    }

                    Glide.with(activity)
                        .asBitmap()
                        .load(url)
                        .into(object : CustomTarget<Bitmap>() {
                            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                if (activity.isFinishing || activity.isDestroyed) return
                                val scaled = Bitmap.createScaledBitmap(resource, 180, 180, false)
                                customCarIcon = BitmapDescriptorFactory.fromBitmap(scaled)
                                driverMarker?.setIcon(customCarIcon)
                            }
                            override fun onLoadCleared(placeholder: Drawable?) {}
                        })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    fun fetchClientProfile(
        activeOrderId: String?,
        currentPaymentMethod: String,
        viewModel: HomeViewModel,
        webSocketManager: WebSocketManager?
    ) {
        val token = sessionManager.fetchAuthToken()
        if (token.isNullOrEmpty()) return

        activity.lifecycleScope.launch {
            try {
                val response = ApiClient.instance.getClientProfile()
                if (activity.isFinishing || activity.isDestroyed) return@launch

                if (response.isSuccessful && response.body() != null) {
                    val profile = response.body()!!
                    val oldId = sessionManager.fetchUserId()
                    sessionManager.saveUserId(profile.id)

                    if (profile.fullName.isNotBlank()) {
                        val syncedPhone = if (profile.phoneNumber.isNotBlank() && profile.phoneNumber != "Не вказано") {
                            profile.phoneNumber
                        } else {
                            sessionManager.getUserPhone()
                        }
                        sessionManager.saveUserInfo(profile.fullName, syncedPhone)
                    }
                    profile.rating?.let { sessionManager.saveUserRating(it) }

                    sessionManager.saveCardMask(profile.cardMask)
                    if (profile.cardMask.isNullOrEmpty() && sessionManager.fetchPaymentMethod() == "CARD") {
                        sessionManager.savePaymentMethod("CASH")
                        if (activeOrderId != null) {
                            viewModel.updateActiveOrderPaymentMethod("CASH")
                        }
                    }

                    updatePaymentIcon(currentPaymentMethod)

                    if (oldId == -1L && profile.id != -1L) {
                        viewModel.startOrderSocketListening(webSocketManager)
                    }

                    updateDrawerHeader()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    fun findClosestCity(lat: Double, lng: Double): String? {
        var closestCity: String? = null
        var minDistance = Float.MAX_VALUE
        for ((name, data) in CityDatabase.regions) {
            val results = FloatArray(1)
            Location.distanceBetween(lat, lng, data.center.latitude, data.center.longitude, results)
            val distance = results[0]
            if (distance < 100000 && distance < minDistance) {
                minDistance = distance
                closestCity = name
            }
        }
        return closestCity
    }

    fun buildOpenAddressPickerIntent(
        isOrigin: Boolean,
        hideMyLocation: Boolean,
        map: GoogleMap?,
        originPlace: Place?,
        currentCity: CityData?
    ): Intent {
        val intent = Intent(activity, AddressPickerActivity::class.java)
        intent.putExtra(AddressPickerActivity.EXTRA_IS_ORIGIN, isOrigin)
        intent.putExtra(AddressPickerActivity.EXTRA_CURRENT_ADDRESS, binding.textViewOrigin.text.toString())

        var latToSend = 0.0
        var lngToSend = 0.0

        if (isOrigin) {
            try {
                val myLoc = map?.myLocation
                if (myLoc != null) {
                    latToSend = myLoc.latitude
                    lngToSend = myLoc.longitude
                }
            } catch (_: SecurityException) {}

            if (latToSend == 0.0 && originPlace?.latLng != null) {
                latToSend = originPlace.latLng!!.latitude
                lngToSend = originPlace.latLng!!.longitude
            }
        } else {
            if (originPlace?.latLng != null) {
                latToSend = originPlace.latLng!!.latitude
                lngToSend = originPlace.latLng!!.longitude
            } else {
                try {
                    val myLoc = map?.myLocation
                    if (myLoc != null) {
                        latToSend = myLoc.latitude
                        lngToSend = myLoc.longitude
                    }
                } catch (_: SecurityException) {}
            }
        }

        if (latToSend != 0.0 && lngToSend != 0.0) {
            intent.putExtra(AddressPickerActivity.EXTRA_CURRENT_LAT, latToSend)
            intent.putExtra(AddressPickerActivity.EXTRA_CURRENT_LNG, lngToSend)
        }

        if (hideMyLocation) intent.putExtra(AddressPickerActivity.EXTRA_HIDE_MY_LOCATION, true)

        val mapTarget = map?.cameraPosition?.target
        if (mapTarget != null) {
            intent.putExtra("city_lat", mapTarget.latitude)
            intent.putExtra("city_lng", mapTarget.longitude)
        } else if (currentCity != null) {
            intent.putExtra("city_lat", currentCity.lat)
            intent.putExtra("city_lng", currentCity.lng)
        }
        return intent
    }

    fun buildCreateOrderRequest(
        tariff: CarTariffDto,
        price: Double,
        originPlace: Place,
        destinationPlace: Place,
        currentWaypoints: List<Pair<LatLng, String>>,
        selectedTariffItem: TariffItem?,
        scheduledDate: Calendar?,
        currentRoutePolyline: String?,
        routeDistanceMeters: Int,
        routeDurationSeconds: Int,
        orderComment: String,
        currentPaymentMethod: String,
        selectedServiceIds: ArrayList<Long>
    ): CreateOrderRequestDto {
        val waypointsDto = currentWaypoints.map { pair ->
            WaypointDto(address = pair.second, lat = pair.first.latitude, lng = pair.first.longitude)
        }

        val myAddedValue = selectedTariffItem?.addedValue ?: 0.0
        val scheduledAtString = scheduledDate?.let {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:00", Locale.getDefault()).format(it.time)
        }

        val startDistrict = originPlace.name?.split(",")?.getOrNull(1)?.trim() ?: "Центр"
        val endDistrict = destinationPlace.name?.split(",")?.getOrNull(1)?.trim() ?: "Місто"
        val deviceId = sessionManager.fetchDeviceId()

        AnalyticsManager.trackOrderCreated(
            orderId = "temp_${System.currentTimeMillis()}",
            district = startDistrict,
            basePrice = tariff.basePrice
        )

        return CreateOrderRequestDto(
            fromAddress = originPlace.name ?: "А",
            toAddress = destinationPlace.name ?: "Б",
            tariffId = tariff.id,
            price = price,
            originLat = originPlace.latLng?.latitude,
            originLng = originPlace.latLng?.longitude,
            destLat = destinationPlace.latLng?.latitude,
            destLng = destinationPlace.latLng?.longitude,
            googleRoutePolyline = currentRoutePolyline,
            waypoints = if (waypointsDto.isNotEmpty()) waypointsDto else null,
            distanceMeters = routeDistanceMeters,
            durationSeconds = routeDurationSeconds,
            comment = if (orderComment.isBlank()) null else orderComment,
            paymentMethod = currentPaymentMethod,
            serviceIds = selectedServiceIds,
            addedValue = myAddedValue,
            scheduledAt = scheduledAtString,
            districtStart = startDistrict,
            districtEnd = endDistrict,
            deviceId = deviceId,
            boostAmount = myAddedValue
        )
    }

    fun bindTariffsList(
        availableTariffs: List<CarTariffDto>,
        routeDistanceMeters: Int,
        tariffCustomPrices: MutableMap<Long, Double>,
        tariffAdapter: TariffAdapter,
        currentSelectedItem: TariffItem?,
        scheduledDate: Calendar?
    ): TariffItem? {
        val includedKm = 3.0
        val totalKm = routeDistanceMeters / 1000.0
        val billableKm = if (totalKm > includedKm) totalKm - includedKm else 0.0

        availableTariffs.forEach { tariff ->
            if (tariff.calculatedPrice != null && tariff.calculatedPrice!! > 0) {
                tariffCustomPrices[tariff.id] = tariff.calculatedPrice!!
            } else {
                val localPrice = tariff.basePrice + (billableKm * tariff.pricePerKm)
                tariffCustomPrices[tariff.id] = ceil(localPrice)
            }
        }

        tariffAdapter.submitList(availableTariffs, routeDistanceMeters)
        tariffAdapter.updatePrices(tariffCustomPrices)

        var updatedSelection = currentSelectedItem
        if (updatedSelection == null) {
            binding.tariffsRecyclerView.scrollToPosition(0)
            val defaultTariff = availableTariffs.find { it.name.contains("Standard", ignoreCase = true) }
                ?: availableTariffs.firstOrNull()

            if (defaultTariff != null) {
                val finalPrice = tariffCustomPrices[defaultTariff.id] ?: defaultTariff.basePrice
                updatedSelection = TariffItem(
                    tariff = defaultTariff,
                    priceString = String.format("%.0f", finalPrice),
                    priceValue = finalPrice,
                    addedValue = 0.0
                )
                tariffAdapter.setSelectedTariffId(defaultTariff.id)
                binding.btnOrderTaxi.isEnabled = true
                updateOrderButtonWithTime(scheduledDate, true)
            }
        } else {
            val newBasePrice = tariffCustomPrices[updatedSelection.tariff.id]
            if (newBasePrice != null) {
                updatedSelection = updatedSelection.copy(
                    priceValue = newBasePrice,
                    priceString = String.format("%.0f", newBasePrice)
                )
            }
            tariffAdapter.setSelectedTariffId(updatedSelection.tariff.id)
            binding.btnOrderTaxi.isEnabled = true
            updateOrderButtonWithTime(scheduledDate, true)
        }
        return updatedSelection
    }

    fun updateIntentDataForReturn(
        intentData: Intent?,
        mapPickerTarget: String,
        mapPickerWaypointIndex: Int,
        target: LatLng,
        addressName: String
    ) {
        intentData?.apply {
            when (mapPickerTarget) {
                AddressPickerActivity.TARGET_ORIGIN -> {
                    putExtra(AddressPickerActivity.RESULT_ORIGIN_NAME, addressName)
                    putExtra(AddressPickerActivity.RESULT_ORIGIN_LAT, target.latitude)
                    putExtra(AddressPickerActivity.RESULT_ORIGIN_LNG, target.longitude)
                }
                AddressPickerActivity.TARGET_WAYPOINT -> {
                    var wLats = getDoubleArrayExtra(AddressPickerActivity.RESULT_WAYPOINTS_LATS) ?: DoubleArray(0)
                    var wLngs = getDoubleArrayExtra(AddressPickerActivity.RESULT_WAYPOINTS_LNGS) ?: DoubleArray(0)
                    val wNames = getStringArrayListExtra(AddressPickerActivity.RESULT_WAYPOINTS_NAMES) ?: ArrayList()

                    if (mapPickerWaypointIndex != -1) {
                        if (mapPickerWaypointIndex >= wLats.size) {
                            val newSize = mapPickerWaypointIndex + 1
                            wLats = DoubleArray(newSize) { if (it < wLats.size) wLats[it] else 0.0 }
                            wLngs = DoubleArray(newSize) { if (it < wLngs.size) wLngs[it] else 0.0 }
                            while (wNames.size < newSize) wNames.add("")
                        }
                        wLats[mapPickerWaypointIndex] = target.latitude
                        wLngs[mapPickerWaypointIndex] = target.longitude
                        wNames[mapPickerWaypointIndex] = addressName
                    }

                    putExtra(AddressPickerActivity.RESULT_WAYPOINTS_LATS, wLats)
                    putExtra(AddressPickerActivity.RESULT_WAYPOINTS_LNGS, wLngs)
                    putStringArrayListExtra(AddressPickerActivity.RESULT_WAYPOINTS_NAMES, wNames)
                }
            }
        }
    }

    fun buildReturnToAddressPickerIntent(data: Intent, mapPickerTarget: String): Intent {
        return Intent(activity, AddressPickerActivity::class.java).apply {
            putExtra(AddressPickerActivity.EXTRA_IS_ORIGIN, mapPickerTarget == AddressPickerActivity.TARGET_ORIGIN)
            putExtra(AddressPickerActivity.EXTRA_CURRENT_ADDRESS, data.getStringExtra(AddressPickerActivity.RESULT_ORIGIN_NAME))
            putExtra(AddressPickerActivity.EXTRA_CURRENT_LAT, data.getDoubleExtra(AddressPickerActivity.RESULT_ORIGIN_LAT, 0.0))
            putExtra(AddressPickerActivity.EXTRA_CURRENT_LNG, data.getDoubleExtra(AddressPickerActivity.RESULT_ORIGIN_LNG, 0.0))
            putExtra("prefill_dest_name", data.getStringExtra(AddressPickerActivity.RESULT_NAME))
            putExtra("prefill_dest_lat", data.getDoubleExtra(AddressPickerActivity.RESULT_LAT, 0.0))
            putExtra("prefill_dest_lng", data.getDoubleExtra(AddressPickerActivity.RESULT_LNG, 0.0))
            putExtra("prefill_waypoints_lats", data.getDoubleArrayExtra(AddressPickerActivity.RESULT_WAYPOINTS_LATS))
            putExtra("prefill_waypoints_lngs", data.getDoubleArrayExtra(AddressPickerActivity.RESULT_WAYPOINTS_LNGS))
            putStringArrayListExtra("prefill_waypoints_names", data.getStringArrayListExtra(AddressPickerActivity.RESULT_WAYPOINTS_NAMES))
        }
    }
}