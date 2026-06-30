package com.taxiapp.client

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Window
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.taxiapp.client.network.ServerStatusBus
import com.taxiapp.client.utils.LocaleHelper
import com.taxiapp.client.utils.SessionManager
import kotlin.system.exitProcess

open class BaseActivity : AppCompatActivity() {

    private var maintenanceDialog: Dialog? = null
    private var lastScreenOnTime: Long = 0

    private var screenStartTime: Long = 0

    override fun attachBaseContext(newBase: Context) {
        val sessionManager = SessionManager(newBase)
        val language = sessionManager.getLanguage()
        super.attachBaseContext(LocaleHelper.setLocale(newBase, language))
    }

    override fun onResume() {
        super.onResume()
        // Фиксируем точное время входа пользователя на экран
        screenStartTime = System.currentTimeMillis()
    }

    override fun onPause() {
        super.onPause()
        if (screenStartTime > 0) {
            val durationMs = System.currentTimeMillis() - screenStartTime
            val durationSec = durationMs / 1000
            val screenName = this::class.java.simpleName

            // Передаем точное имя Activity и время нахождения на ней в менеджер аналитики
            com.taxiapp.client.analytics.AnalyticsManager.trackScreenDuration(screenName, durationSec)

            // ДОБАВЛЯЕМ СТРОКУ НИЖЕ: Принудительно отправляем батч при уходе пользователя с экрана
            com.taxiapp.client.analytics.AnalyticsManager.flushEvents()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Фиксируем время создания/восстановления активности
        lastScreenOnTime = System.currentTimeMillis()

        // 1. Слушаем событие "Сессия истекла"
        ServerStatusBus.sessionExpired.observe(this) { isExpired ->
            if (isExpired) {
                ServerStatusBus.resetSessionExpired()
                handleSessionExpired()
            }
        }

        // 2. Слушаем ошибки сервера (502/503/Timeout)
        ServerStatusBus.serverError.observe(this) { hasError ->
            if (hasError) {
                if (shouldIgnoreServerError()) {
                    // Если экран выключен или только включился — гасим ложный триггер в шине
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
        // Фиксируем время, когда приложение выходит на передний план после включения экрана
        lastScreenOnTime = System.currentTimeMillis()
    }

    /**
     * Проверяет, нужно ли игнорировать сетевую ошибку на основе состояния экрана.
     */
    private fun shouldIgnoreServerError(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager

        // 1. Если экран физически выключен (устройство спит) — игнорируем ошибку сокетов/HTTP в фоне
        if (powerManager != null && !powerManager.isInteractive) {
            println(">>> MAINTENANCE_DEBUG: Screen is OFF. Ignoring server error.")
            return true
        }

        // 2. Буфер вежливости: если экран зажгли менее 3 секунд назад, сеть могла не успеть подняться.
        // Даем WebSocketManager и ApiClient время на авто-переподключение.
        val timeSinceScreenOn = System.currentTimeMillis() - lastScreenOnTime
        if (timeSinceScreenOn < 3000) {
            println(">>> MAINTENANCE_DEBUG: Screen just turned ON ($timeSinceScreenOn ms ago). Postponing error check.")
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
            setCancelable(false) // Диалог нельзя закрыть кликом мимо
            setContentView(R.layout.dialog_maintenance)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            window?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )

            val btnClose = findViewById<Button>(R.id.btn_close_app)
            btnClose.setOnClickListener {
                dismiss()
                finishAffinity()
                exitProcess(0)
            }
        }
        maintenanceDialog?.show()
    }

    private fun hideMaintenanceDialog() {
        if (maintenanceDialog?.isShowing == true) {
            maintenanceDialog?.dismiss()
            maintenanceDialog = null
        }
    }
}