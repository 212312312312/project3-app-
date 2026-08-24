package com.taxiapp.client

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AnimationUtils
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils

class SplashActivity : BaseActivity()  {

    // Перемінна для тимчасового збереження маркетингового джерела
    private var acquisitionSource: String? = null
    private val navigationHandler = Handler(Looper.getMainLooper())
    private val navigationRunnable = Runnable { checkSessionAndNavigate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_splash)

        // Перехоплюємо маркетингове джерело з Deep Link (наприклад: taxi://open?source=fb_poznyaki)
        intent?.data?.let { uri ->
            acquisitionSource = uri.getQueryParameter("source")
        }

        try {
            ViewUtils.makeImmersive(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // --- ЗМІНА ТУТ ---
        // Знаходимо внутрішню обгортку, а не весь екран
        val contentWrapper = findViewById<View>(R.id.splash_content_wrapper)

        val slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up)
        // Анімуємо тільки контент
        contentWrapper.startAnimation(slideUp)

        val sessionManager = SessionManager(applicationContext)
        val utmSource = intent?.data?.getQueryParameter("utm_source") ?: intent?.data?.getQueryParameter("source")
        val utmMedium = intent?.data?.getQueryParameter("utm_medium")
        val utmCampaign = intent?.data?.getQueryParameter("utm_campaign")

        if (!utmSource.isNullOrBlank()) {
            sessionManager.saveUtmTags(utmSource, utmMedium, utmCampaign)
            com.taxiapp.client.analytics.AnalyticsManager.utmSource = utmSource
            com.taxiapp.client.analytics.AnalyticsManager.utmMedium = utmMedium
            com.taxiapp.client.analytics.AnalyticsManager.utmCampaign = utmCampaign
        }

        val userId = sessionManager.fetchUserId()
        val deviceId = sessionManager.fetchDeviceId()
        com.taxiapp.client.analytics.AnalyticsManager.trackAppOpen(userId, deviceId)
        // -----------------

        navigationHandler.postDelayed(navigationRunnable, 2000)
    }

    override fun onDestroy() {
        navigationHandler.removeCallbacks(navigationRunnable)
        super.onDestroy()
    }

    private fun checkSessionAndNavigate() {
        try {
            val sessionManager = SessionManager(applicationContext)
            val token = sessionManager.fetchAuthToken()
            val phone = sessionManager.getUserPhone()

            // В HomeActivity пускаем ТОЛЬКО если есть и токен, и непустой номер
            if (!token.isNullOrBlank() && !phone.isNullOrBlank()) {
                startActivity(Intent(this, HomeActivity::class.java))
            } else {
                val intent = Intent(this, MainActivity::class.java).apply {
                    putExtra("EXTRA_ACQUISITION_SOURCE", acquisitionSource)
                }
                startActivity(intent)
            }
            finish()
        } catch (e: Exception) {
            e.printStackTrace()
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("EXTRA_ACQUISITION_SOURCE", acquisitionSource)
            }
            startActivity(intent)
            finish()
        }
    }
}