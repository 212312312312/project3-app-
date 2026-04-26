package com.taxiapp.client

import android.app.Dialog
import android.content.Context
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

    override fun onResume() {
        super.onResume()
        // Подписываем текущий активный экран на события сервера
        ServerStatusBus.setListener {
            showMaintenanceDialog()
        }
    }

    override fun onPause() {
        super.onPause()
        // Отписываемся при сворачивании, чтобы избежать утечек памяти
        ServerStatusBus.setListener(null)
    }

    private fun showMaintenanceDialog() {
        if (maintenanceDialog?.isShowing == true) return
        if (isFinishing || isDestroyed) return

        maintenanceDialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(false)
            setContentView(R.layout.dialog_maintenance)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            // --- НОВОЕ: Заставляем диалог занять всю ширину экрана ---
            window?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // -----------------------------------------------------------

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