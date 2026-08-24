package com.taxiapp.client.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.maps.model.LatLng
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.libraries.places.api.model.Place

// Допоміжний клас для передачі даних про знижку
data class SessionPromoData(
    val discountPercent: Int,
    val maxDiscountAmount: Double
)

class SessionManager(private val context: Context) {

    private var prefs: SharedPreferences

    companion object {
        const val PREFS_NAME = "TaxiAppPrefs"

        const val KEY_LANGUAGE = "app_language"

        const val KEY_DEVICE_ID = "app_device_id"
        const val KEY_UTM_SOURCE = "utm_source"
        const val KEY_UTM_MEDIUM = "utm_medium"
        const val KEY_UTM_CAMPAIGN = "utm_campaign"

        const val KEY_USER_ID = "user_id"
        const val USER_TOKEN = "user_token"
        const val REFRESH_TOKEN = "refresh_token" // <-- ДОБАВЛЕН REFRESH TOKEN
        const val USER_FULL_NAME = "user_full_name"
        const val USER_PHONE = "user_phone"
        const val USER_CITY_NAME = "user_city_name"
        const val USER_CITY_LAT = "user_city_lat"
        const val USER_CITY_LNG = "user_city_lng"
        const val USER_CITY_ZOOM = "user_city_zoom"
        const val ACTIVE_ORDER_ID = "active_order_id"
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
        const val KEY_CARD_MASK = "card_mask" // <-- ДОБАВЛЕНО
    }

    init {
        try {
            // Создаем аппаратно защищенный мастер-ключ AES-256
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            // Инициализируем зашифрованное хранилище
            prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // 🔥 КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Меняем имя файла на "${PREFS_NAME}_Plain".
            
            // Если при сбое Keystore открыть исходный зашифрованный файл через обычные Prefs,
            // они попытаются прочитать шифрованный XML как чистый текст, не найдут ключей и вернут null,
            // что приведёт к моментальному разлогину! Новый файл изолирует фолбэк.
            prefs = context.getSharedPreferences("${PREFS_NAME}_Plain", Context.MODE_PRIVATE)
        }
    }

    // --- TOKENS ---
    fun saveAuthToken(token: String) {
        prefs.edit().putString(USER_TOKEN, token).apply()
    }

    fun fetchAuthToken(): String? {
        return prefs.getString(USER_TOKEN, null)
    }

    // <-- НОВЫЕ МЕТОДЫ ДЛЯ REFRESH TOKEN -->
    fun saveRefreshToken(token: String) {
        prefs.edit().putString(REFRESH_TOKEN, token).apply()
    }

    fun fetchRefreshToken(): String? {
        return prefs.getString(REFRESH_TOKEN, null)
    }
    // <------------------------------------>

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
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            deviceId = if (!androidId.isNullOrEmpty() && androidId != "9774d56d682e549c") {
                androidId
            } else {
                java.util.UUID.randomUUID().toString()
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

    // --- ACTIVE ORDER (ИЗМЕНЕНО НА STRING/UUID СОВМЕСТИМОСТЬ) ---
    fun saveActiveOrderId(id: String) { // <-- Принимаем строку UUID
        prefs.edit().putString(ACTIVE_ORDER_ID, id).apply()
    }

    fun fetchActiveOrderId(): String? { // <-- Возвращаем String? вместо Long
        return prefs.getString(ACTIVE_ORDER_ID, null)
    }

    fun clearActiveOrderId() {
        prefs.edit().remove(ACTIVE_ORDER_ID).apply()
    }

    // --- THEME ---
    fun saveThemeMode(isDark: Boolean) {
        prefs.edit().putBoolean(KEY_IS_DARK_MODE, isDark).apply()
    }

    fun isDarkMode(): Boolean {
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

    fun saveLanguage(languageCode: String) {
        prefs.edit().putString(KEY_LANGUAGE, languageCode).apply()
    }

    fun getLanguage(): String {
        return prefs.getString(KEY_LANGUAGE, "ua") ?: "ua"
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
}