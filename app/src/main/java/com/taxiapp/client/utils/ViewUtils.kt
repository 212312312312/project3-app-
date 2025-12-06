package com.taxiapp.client.utils

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object ViewUtils {

    fun makeImmersive(activity: Activity) {
        // 1. Получаем контроллер окна безопасным способом (через AndroidX)
        val window = activity.window
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)

        // 2. Настраиваем поведение: панели появляются при свайпе и исчезают обратно
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // 3. Скрываем системные панели (Status Bar и Navigation Bar)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }
}