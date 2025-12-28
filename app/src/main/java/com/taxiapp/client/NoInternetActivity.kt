package com.taxiapp.client

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.taxiapp.client.HomeActivity

class NoInternetActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_no_internet)

        // --- 1. НАСТРОЙКА СИСТЕМНЫХ БАРОВ (Цвет + Иконки) ---
        setupSystemBars()

        // --- 2. ЛОГИКА КНОПКИ ---
        val btnRetry = findViewById<Button>(R.id.btn_retry_connection)

        btnRetry.setOnClickListener {
            if (NetworkUtils.isInternetAvailable(this)) {
                // Если интернет появился - перезапускаем HomeActivity
                val intent = Intent(this, HomeActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                finish()
            } else {
                Toast.makeText(this, "З'єднання відсутнє", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupSystemBars() {
        val window = window
        val controller = WindowCompat.getInsetsController(window, window.decorView)

        // 1. Получаем цвет фона (наш unified_block_bg)
        val backgroundColor = ContextCompat.getColor(this, R.color.unified_block_bg)

        // 2. Красим бары в цвет фона
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        // 3. Определяем тему (День или Ночь)
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isNightMode = currentNightMode == Configuration.UI_MODE_NIGHT_YES

        // 4. Настраиваем цвет иконок (Часы, Батарея, Кнопки навигации)
        // Если тема НЕ ночная (день) -> включаем "LightStatusBars" (это значит ТЕМНЫЕ иконки на светлом фоне)
        controller.isAppearanceLightStatusBars = !isNightMode
        controller.isAppearanceLightNavigationBars = !isNightMode
    }

    override fun onBackPressed() {
        // Блокируем кнопку назад
    }
}