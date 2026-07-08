package com.taxiapp.client.utils

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object ViewUtils {

    fun setupEdgeToEdge(activity: Activity) {
        val window = activity.window

        // Разрешаем контенту затекать под вырезы камеры
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // Включаем edge-to-edge: бары остаются видимыми, но становятся прозрачными
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Делаем системные бары прозрачными (опционально, если стили их красят)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
    }

    fun makeImmersive(activity: Activity) {
        val window = activity.window

        // 1. Разрешаем рисовать под вырезом (камерой) - ЭТО ГЛАВНОЕ ИСПРАВЛЕНИЕ
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // 2. Говорим системе не добавлять отступы под бары автоматически
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 3. Скрываем системные панели
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)

        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }
}