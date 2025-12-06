package com.taxiapp.client

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.taxiapp.client.utils.SessionManager
import com.taxiapp.client.utils.ViewUtils

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_splash)

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
                startActivity(Intent(this, MainActivity::class.java))
            }
            finish()
        } catch (e: Exception) {
            e.printStackTrace()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}