package com.taxiapp.client

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.taxiapp.client.utils.SessionManager

class TaxiApplication : Application() {

    companion object {
        // Железный флаг: видно ли приложение сейчас на экране пользователю
        var isAppInForeground = false
    }

    // Счетчик запущенных Activity
    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()

        // Встроенный в Android SDK трекер жизненного цикла экранов
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                isAppInForeground = startedActivities > 0
            }

            override fun onActivityResumed(activity: Activity) {}

            override fun onActivityPaused(activity: Activity) {}

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                isAppInForeground = startedActivities > 0
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {}
        })

        val sessionManager = SessionManager(this)
        val isDark = sessionManager.isDarkMode()

        if (isDark) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }
}