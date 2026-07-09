package com.taxiapp.client.utils

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleHelper {

    /**
     * Применяет язык на уровне всей операционной системы для нашего приложения.
     * Именно этот вызов переключает язык карт Google Maps на лету.
     */
    fun applyLanguage(language: String) {
        val localeList = LocaleListCompat.forLanguageTags(language)
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    /**
     * Оставляем для обратной совместимости со старыми компонентами (актуально для Android 12 и ниже)
     */
    fun setLocale(context: Context, language: String): Context {
        val locale = Locale(language)
        Locale.setDefault(locale)

        val configuration = context.resources.configuration
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)

        return context.createConfigurationContext(configuration)
    }
}