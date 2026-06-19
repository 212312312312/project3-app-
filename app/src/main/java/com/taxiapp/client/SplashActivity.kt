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
        // -----------------

        Handler(Looper.getMainLooper()).postDelayed({
            checkSessionAndNavigate()
        }, 2000)
    }

    private fun checkSessionAndNavigate() {
        try {
            val sessionManager = SessionManager(applicationContext)
            val token = sessionManager.fetchAuthToken()

            if (token != null) {
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