package com.taxiapp.client.utils

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object ViewUtils {

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