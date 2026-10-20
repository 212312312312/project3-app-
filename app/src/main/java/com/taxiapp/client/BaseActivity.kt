package com.taxiapp.client

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.PowerManager
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.taxiapp.client.analytics.AnalyticsManager
import com.taxiapp.client.network.ServerStatusBus
import com.taxiapp.client.utils.LocaleHelper
import com.taxiapp.client.utils.SessionManager

open class BaseActivity : AppCompatActivity() {

    private var maintenanceDialog: Dialog? = null
    private var lastScreenOnTime: Long = 0
    private var screenStartTime: Long = 0

    override fun attachBaseContext(newBase: Context) {
        val sessionManager = SessionManager(newBase)
        val language = sessionManager.getLanguage()

        val currentLocales = AppCompatDelegate.getApplicationLocales()
        if (currentLocales.isEmpty && language.isNotEmpty()) {
            LocaleHelper.applyLanguage(language)
        }

        super.attachBaseContext(LocaleHelper.setLocale(newBase, language))
    }

    override fun onResume() {
        super.onResume()
        screenStartTime = System.currentTimeMillis()
    }

    override fun onPause() {
        super.onPause()
        if (screenStartTime > 0) {
            val durationMs = System.currentTimeMillis() - screenStartTime
            val durationSec = durationMs / 1000
            val screenName = this::class.java.simpleName

            // Буферизуємо подію без миттєвого мережевого запиту при кожному переході між екранами
            AnalyticsManager.trackScreenDuration(screenName, durationSec)
        }
    }

    override fun onStop() {
        super.onStop()
        // Відправляємо накопичений батч аналітики лише тоді, коли весь додаток згортається у фон
        if (!TaxiApplication.isAppInForeground) {
            AnalyticsManager.flushEvents()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lastScreenOnTime = System.currentTimeMillis()

        ServerStatusBus.sessionExpired.observe(this) { isExpired ->
            if (isExpired) {
                ServerStatusBus.resetSessionExpired()
                if (this !is MainActivity && this !is SplashActivity) {
                    handleSessionExpired()
                }
            }
        }

        ServerStatusBus.serverError.observe(this) { hasError ->
            if (hasError) {
                if (shouldIgnoreServerError()) {
                    ServerStatusBus.resetServerError()
                } else {
                    showMaintenanceDialog()
                }
            } else {
                hideMaintenanceDialog()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lastScreenOnTime = System.currentTimeMillis()
    }

    override fun onDestroy() {
        hideMaintenanceDialog()
        super.onDestroy()
    }

    private fun shouldIgnoreServerError(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager != null && !powerManager.isInteractive) {
            return true
        }

        val timeSinceScreenOn = System.currentTimeMillis() - lastScreenOnTime
        if (timeSinceScreenOn < 3000) {
            return true
        }

        return false
    }

    private fun handleSessionExpired() {
        val sessionManager = SessionManager(this)
        sessionManager.clearSession()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun showMaintenanceDialog() {
        if (maintenanceDialog?.isShowing == true) return
        if (isFinishing || isDestroyed) return

        maintenanceDialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(true)
            setContentView(R.layout.dialog_maintenance)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            val btnClose = findViewById<Button>(R.id.btn_close_app)
            btnClose.setOnClickListener {
                ServerStatusBus.resetServerError()
                dismiss()
            }
            setOnCancelListener {
                ServerStatusBus.resetServerError()
            }
        }
        maintenanceDialog?.show()
    }

    private fun hideMaintenanceDialog() {
        if (maintenanceDialog?.isShowing == true) {
            try {
                maintenanceDialog?.dismiss()
            } catch (_: Exception) {}
            maintenanceDialog = null
        }
    }
}