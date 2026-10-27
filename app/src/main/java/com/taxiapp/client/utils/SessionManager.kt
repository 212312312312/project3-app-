package com.taxiapp.client.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.provider.Settings
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.model.Place
import java.util.UUID

// Допоміжний клас для передачі даних про знижку
data class SessionPromoData(
    val discountPercent: Int,
    val maxDiscountAmount: Double
)

class SessionManager(context: Context) {

    private val appContext: Context = context.applicationContext ?: context
    private val prefs: SharedPreferences = getOrCreateSharedPreferences(appContext)

    companion object {
        private const val TAG = "SessionManager"
        const val PREFS_NAME = "TaxiAppPrefs"

        const val KEY_LANGUAGE = "app_language"
        const val KEY_DEVICE_ID = "app_device_id"
        const val KEY_UTM_SOURCE = "utm_source"
        const val KEY_UTM_MEDIUM = "utm_medium"
        const val KEY_UTM_CAMPAIGN = "utm_campaign"

        const val KEY_USER_ID = "user_id"
        const val USER_TOKEN = "user_token"
        const val REFRESH_TOKEN = "refresh_token"
        const val USER_FULL_NAME = "user_full_name"
        const val USER_PHONE = "user_phone"
        const val KEY_USER_RATING = "user_rating"
        const val USER_CITY_NAME = "user_city_name"
        const val USER_CITY_LAT = "user_city_lat"
        const val USER_CITY_LNG = "user_city_lng"
        const val USER_CITY_ZOOM = "user_city_zoom"
        const val ACTIVE_ORDER_ID = "active_order_id"
        const val KEY_IS_MANUAL_THEME = "is_manual_theme"
        const val KEY_IS_DARK_MODE = "is_dark_mode"
        const val KEY_HOME_NAME = "home_name"
        const val KEY_HOME_LAT = "home_lat"
        const val KEY_HOME_LNG = "home_lng"
        const val KEY_WORK_NAME = "work_name"
        const val KEY_WORK_LAT = "work_lat"
        const val KEY_WORK_LNG = "work_lng"
        const val KEY_PAYMENT_METHOD = "payment_method"
        const val KEY_PROMO_DISCOUNT = "promo_discount_percent"
        const val KEY_PROMO_LIMIT = "promo_discount_limit"
        const val KEY_CARD_MASK = "card_mask"

        private val initLock = Any()

        @Volatile
        private var sharedPrefsInstance: SharedPreferences? = null

        // In-memory кеш токенів, щоб AuthInterceptor не розшифровував XML при кожному HTTP-запиті
        @Volatile
        private var cachedAuthToken: String? = null

        @Volatile
        private var cachedRefreshToken: String? = null

        @Volatile
        private var isTokenCacheInitialized = false

        private fun getOrCreateSharedPreferences(context: Context): SharedPreferences {
            sharedPrefsInstance?.let { return it }

            synchronized(initLock) {
                sharedPrefsInstance?.let { return it }

                val created = try {
                    val masterKey = MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()

                    EncryptedSharedPreferences.create(
                        context,
                        PREFS_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "EncryptedSharedPreferences init failed, falling back to isolated prefs", e)
                    context.getSharedPreferences("${PREFS_NAME}_Plain", Context.MODE_PRIVATE)
                }

                cachedAuthToken = created.getString(USER_TOKEN, null)
                cachedRefreshToken = created.getString(REFRESH_TOKEN, null)
                isTokenCacheInitialized = true
                sharedPrefsInstance = created
                return created
            }
        }
    }

    // --- TOKENS ---
    fun saveAuthToken(token: String) {
        cachedAuthToken = token
        isTokenCacheInitialized = true
        prefs.edit().putString(USER_TOKEN, token).apply()
    }

    fun fetchAuthToken(): String? {
        if (isTokenCacheInitialized) {
            return cachedAuthToken
        }
        val token = prefs.getString(USER_TOKEN, null)
        cachedAuthToken = token
        isTokenCacheInitialized = true
        return token
    }

    fun saveRefreshToken(token: String) {
        cachedRefreshToken = token
        isTokenCacheInitialized = true
        prefs.edit().putString(REFRESH_TOKEN, token).apply()
    }

    fun fetchRefreshToken(): String? {
        if (isTokenCacheInitialized && cachedRefreshToken != null) {
            return cachedRefreshToken
        }
        val token = prefs.getString(REFRESH_TOKEN, null)
        cachedRefreshToken = token
        return token
    }

    // --- USER INFO ---
    fun saveUserInfo(name: String, phone: String) {
        prefs.edit().apply {
            putString(USER_FULL_NAME, name)
            putString(USER_PHONE, phone)
            apply()
        }
    }

    fun saveUserId(id: Long) {
        prefs.edit().putLong(KEY_USER_ID, id).apply()
    }

    fun fetchUserId(): Long {
        return prefs.getLong(KEY_USER_ID, -1L)
    }

    fun getUserName(): String = prefs.getString(USER_FULL_NAME, "Райдер") ?: "Райдер"
    fun getUserPhone(): String = prefs.getString(USER_PHONE, "") ?: ""

    fun saveUserRating(rating: Double) {
        val formatted = String.format(java.util.Locale.US, "%.1f", rating)
        prefs.edit().putString(KEY_USER_RATING, formatted).apply()
    }

    fun getUserRating(): String = prefs.getString(KEY_USER_RATING, "5.0") ?: "5.0"

    // --- CITY ---
    fun saveUserCity(city: CityData) {
        prefs.edit().apply {
            putString(USER_CITY_NAME, city.name)
            putFloat(USER_CITY_LAT, city.lat.toFloat())
            putFloat(USER_CITY_LNG, city.lng.toFloat())
            putFloat(USER_CITY_ZOOM, city.zoom)
            apply()
        }
    }

    fun fetchDeviceId(): String {
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId.isNullOrEmpty()) {
            val androidId = try {
                Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            } catch (e: Exception) {
                null
            }
            deviceId = if (!androidId.isNullOrEmpty() && androidId != "9774d56d682e549c") {
                androidId
            } else {
                UUID.randomUUID().toString()
            }
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }

    fun saveUtmTags(source: String?, medium: String?, campaign: String?) {
        prefs.edit().apply {
            putString(KEY_UTM_SOURCE, source)
            putString(KEY_UTM_MEDIUM, medium)
            putString(KEY_UTM_CAMPAIGN, campaign)
            apply()
        }
    }

    fun getUtmSource(): String? = prefs.getString(KEY_UTM_SOURCE, null)

    fun fetchUserCity(): CityData? {
        val name = prefs.getString(USER_CITY_NAME, null) ?: return null
        return CityData(
            name = name,
            lat = prefs.getFloat(USER_CITY_LAT, 0f).toDouble(),
            lng = prefs.getFloat(USER_CITY_LNG, 0f).toDouble(),
            zoom = prefs.getFloat(USER_CITY_ZOOM, 11f)
        )
    }

    // --- ACTIVE ORDER ---
    fun saveActiveOrderId(id: String) {
        prefs.edit().putString(ACTIVE_ORDER_ID, id).apply()
    }

    fun fetchActiveOrderId(): String? {
        return prefs.getString(ACTIVE_ORDER_ID, null)
    }

    fun clearActiveOrderId() {
        prefs.edit().remove(ACTIVE_ORDER_ID).apply()
    }

    // --- THEME ---
    fun isManualThemeSelected(): Boolean {
        return prefs.getBoolean(KEY_IS_MANUAL_THEME, false)
    }

    fun saveThemeMode(isDark: Boolean) {
        prefs.edit()
            .putBoolean(KEY_IS_MANUAL_THEME, true)
            .putBoolean(KEY_IS_DARK_MODE, isDark)
            .apply()
    }

    fun isDarkMode(): Boolean {
        if (!isManualThemeSelected()) {
            val nightModeFlags = appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
        }
        return prefs.getBoolean(KEY_IS_DARK_MODE, false)
    }

    // --- HOME ADDRESS ---
    fun saveHomeAddress(place: Place) {
        prefs.edit().apply {
            putString(KEY_HOME_NAME, place.name)
            if (place.latLng != null) {
                putFloat(KEY_HOME_LAT, place.latLng!!.latitude.toFloat())
                putFloat(KEY_HOME_LNG, place.latLng!!.longitude.toFloat())
            }
            apply()
        }
    }

    // --- LANGUAGE (Нормалізація ISO-коду "uk" замість застарілого "ua") ---
    fun saveLanguage(languageCode: String) {
        val normalized = if (languageCode == "ua") "uk" else languageCode
        prefs.edit().putString(KEY_LANGUAGE, normalized).apply()
    }

    fun getLanguage(): String {
        val raw = prefs.getString(KEY_LANGUAGE, "uk") ?: "uk"
        return if (raw == "ua") "uk" else raw
    }

    fun getHomeAddress(): Place? {
        val name = prefs.getString(KEY_HOME_NAME, null) ?: return null
        val lat = prefs.getFloat(KEY_HOME_LAT, 0f).toDouble()
        val lng = prefs.getFloat(KEY_HOME_LNG, 0f).toDouble()
        return Place.builder().setName(name).setLatLng(LatLng(lat, lng)).build()
    }

    // --- WORK ADDRESS ---
    fun saveWorkAddress(place: Place) {
        prefs.edit().apply {
            putString(KEY_WORK_NAME, place.name)
            if (place.latLng != null) {
                putFloat(KEY_WORK_LAT, place.latLng!!.latitude.toFloat())
                putFloat(KEY_WORK_LNG, place.latLng!!.longitude.toFloat())
            }
            apply()
        }
    }

    fun getWorkAddress(): Place? {
        val name = prefs.getString(KEY_WORK_NAME, null) ?: return null
        val lat = prefs.getFloat(KEY_WORK_LAT, 0f).toDouble()
        val lng = prefs.getFloat(KEY_WORK_LNG, 0f).toDouble()
        return Place.builder().setName(name).setLatLng(LatLng(lat, lng)).build()
    }

    // --- LOGOUT ---
    fun clearSession() {
        cachedAuthToken = null
        cachedRefreshToken = null
        isTokenCacheInitialized = true
        prefs.edit().apply {
            remove(USER_TOKEN)
            remove(REFRESH_TOKEN)
            remove(USER_CITY_NAME)
            remove(USER_CITY_LAT)
            remove(USER_CITY_LNG)
            remove(USER_CITY_ZOOM)
            remove(ACTIVE_ORDER_ID)
            remove(USER_FULL_NAME)
            remove(USER_PHONE)
            remove(KEY_USER_RATING)
            remove(KEY_PROMO_DISCOUNT)
            remove(KEY_PROMO_LIMIT)
            remove(KEY_CARD_MASK)
            apply()
        }
    }

    // --- NOTIFICATIONS ---
    fun setNotificationAsked(asked: Boolean) {
        prefs.edit().putBoolean("notification_asked", asked).apply()
    }

    fun isNotificationAsked(): Boolean {
        return prefs.getBoolean("notification_asked", false)
    }

    // --- PAYMENT METHOD ---
    fun savePaymentMethod(method: String) {
        prefs.edit().putString(KEY_PAYMENT_METHOD, method).apply()
    }

    fun fetchPaymentMethod(): String {
        return prefs.getString(KEY_PAYMENT_METHOD, "CASH") ?: "CASH"
    }

    // --- PROMO / DISCOUNTS ---
    fun savePromoDiscount(percent: Double, limit: Double = 0.0) {
        prefs.edit().apply {
            putString(KEY_PROMO_DISCOUNT, percent.toString())
            putString(KEY_PROMO_LIMIT, limit.toString())
            apply()
        }
    }

    fun fetchPromoDiscount(): Double {
        val str = prefs.getString(KEY_PROMO_DISCOUNT, "0.0")
        return str?.toDoubleOrNull() ?: 0.0
    }

    fun fetchPromoLimit(): Double {
        val str = prefs.getString(KEY_PROMO_LIMIT, "0.0")
        return str?.toDoubleOrNull() ?: 0.0
    }

    fun fetchActivePromo(): SessionPromoData? {
        val percent = fetchPromoDiscount()
        if (percent <= 0.0) return null
        return SessionPromoData(percent.toInt(), fetchPromoLimit())
    }

    // --- BIND CARD ---
    fun saveCardMask(mask: String?) {
        if (mask == null) {
            prefs.edit().remove(KEY_CARD_MASK).apply()
        } else {
            prefs.edit().putString(KEY_CARD_MASK, mask).apply()
        }
    }

    fun getCardMask(): String? {
        return prefs.getString(KEY_CARD_MASK, null)
    }

    fun clearDiscounts() {
        prefs.edit().apply {
            remove(KEY_PROMO_DISCOUNT)
            remove(KEY_PROMO_LIMIT)
            apply()
        }
    }

    fun isWelcomeDiscountShown(userId: Long): Boolean {
        val key = if (userId > 0L) "welcome_discount_shown_$userId" else "welcome_discount_shown_default"
        return prefs.getBoolean(key, false)
    }

    fun setWelcomeDiscountShown(userId: Long, shown: Boolean = true) {
        val key = if (userId > 0L) "welcome_discount_shown_$userId" else "welcome_discount_shown_default"
        prefs.edit().putBoolean(key, shown).apply()
    }
}