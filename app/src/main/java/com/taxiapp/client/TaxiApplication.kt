package com.taxiapp.client

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.taxiapp.client.utils.SessionManager

class TaxiApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val sessionManager = SessionManager(this)

        // За замовчуванням isDarkMode() повертає false (Світла)
        val isDark = sessionManager.isDarkMode()

        if (isDark) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }
}