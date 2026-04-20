package com.taxiapp.client

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.taxiapp.client.utils.LocaleHelper
import com.taxiapp.client.utils.SessionManager

open class BaseActivity : AppCompatActivity() {

    // Цей метод викликається до onCreate і підміняє контекст системи на наш (з потрібною мовою)
    override fun attachBaseContext(newBase: Context) {
        val sessionManager = SessionManager(newBase)
        val language = sessionManager.getLanguage()
        super.attachBaseContext(LocaleHelper.setLocale(newBase, language))
    }
}