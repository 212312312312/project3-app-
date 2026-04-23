package com.taxiapp.client

import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.taxiapp.client.utils.ViewUtils

class HelpWaitingActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help_waiting) // Переконайтесь, що назва XML вірна

        // ВИПРАВЛЕННЯ
        try { ViewUtils.makeImmersive(this) } catch (e: Exception) { e.printStackTrace() }

        findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            finish()
        }
    }

    private fun hideSystemUI() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }
}