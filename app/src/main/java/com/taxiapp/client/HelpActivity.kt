package com.taxiapp.client

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.taxiapp.client.utils.ViewUtils // <--- ВАЖЛИВИЙ ІМПОРТ

class HelpActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_help)

        // --- ВИПРАВЛЕННЯ ---
        // Використовуємо наш універсальний клас, який вміє малювати під "челкою"
        try { ViewUtils.makeImmersive(this) } catch (e: Exception) { e.printStackTrace() }
        // -------------------

        // 2. Кнопка Назад
        findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            finish()
        }

        // 3. Кнопка "Зв'язатися з нами"
        findViewById<MaterialCardView>(R.id.btn_support_action).setOnClickListener {
            showSupportBottomSheet()
        }

        // 4. Заглушки

        findViewById<LinearLayout>(R.id.btn_payment)?.setOnClickListener {
            val intent = Intent(this, HelpPaymentActivity::class.java)
            startActivity(intent)
        }
        findViewById<LinearLayout>(R.id.btn_waiting)?.setOnClickListener {
            val intent = Intent(this, HelpWaitingActivity::class.java)
            startActivity(intent)
        }
    }

    // Метод hideSystemUI видалено, бо він застарів і замінений на ViewUtils

    private fun showSupportBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_bottom_sheet_support, null)

        // Клики
        view.findViewById<LinearLayout>(R.id.bs_btn_telegram).setOnClickListener {
            dialog.dismiss()
            openTelegram()
        }
        view.findViewById<LinearLayout>(R.id.bs_btn_call).setOnClickListener {
            dialog.dismiss()
            makeCall()
        }

        dialog.setContentView(view)

        // --- 1. УБИРАЕМ ФОН КОНТЕЙНЕРА ---
        dialog.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        // --- 2. ЯДЕРНОЕ РЕШЕНИЕ (Убираем отступ снизу) ---
        dialog.window?.let { window ->
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )

            // Скрываем сами бары (визуально)
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        dialog.show()
    }

    private fun openTelegram() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/taxi_support_bot"))
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun makeCall() {
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:+380999999999"))
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}