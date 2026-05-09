package com.taxiapp.client

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Window
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.taxiapp.client.network.ServerStatusBus
import com.taxiapp.client.utils.LocaleHelper
import com.taxiapp.client.utils.SessionManager
import kotlin.system.exitProcess

open class BaseActivity : AppCompatActivity() {

    private var maintenanceDialog: Dialog? = null

    override fun attachBaseContext(newBase: Context) {
        val sessionManager = SessionManager(newBase)
        val language = sessionManager.getLanguage()
        super.attachBaseContext(LocaleHelper.setLocale(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Слушаем событие "Сессия истекла" (разлогин)
        ServerStatusBus.sessionExpired.observe(this) { isExpired ->
            if (isExpired) {
                ServerStatusBus.resetSessionExpired() // Сбрасываем триггер!
                handleSessionExpired()
            }
        }

        // 2. Слушаем ошибки сервера (502/503)
        ServerStatusBus.serverError.observe(this) { hasError ->
            if (hasError) {
                ServerStatusBus.resetServerError() // Сбрасываем триггер!
                showMaintenanceDialog()
            }
        }
    }

    private fun handleSessionExpired() {
        // Очищаем локальные данные пользователя
        val sessionManager = SessionManager(this)
        sessionManager.clearSession()

        // Перебрасываем на MainActivity и очищаем весь стек экранов
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
            setCancelable(false)
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
}