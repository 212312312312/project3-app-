package com.taxiapp.client.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.model.Place

class SessionManager(context: Context) {

    private var prefs: SharedPreferences

    companion object {
        const val PREFS_NAME = "TaxiAppPrefs"

        // Токен
        const val USER_TOKEN = "user_token"

        // Інфо користувача
        const val USER_FULL_NAME = "user_full_name"
        const val USER_PHONE = "user_phone"

        // Місто
        const val USER_CITY_NAME = "user_city_name"
        const val USER_CITY_LAT = "user_city_lat"
        const val USER_CITY_LNG = "user_city_lng"
        const val USER_CITY_ZOOM = "user_city_zoom"

        // Активне замовлення
        const val ACTIVE_ORDER_ID = "active_order_id"

        // Тема
        const val KEY_IS_DARK_MODE = "is_dark_mode"

        // --- АДРЕСИ (Дім / Робота) ---
        const val KEY_HOME_NAME = "home_name"
        const val KEY_HOME_LAT = "home_lat"
        const val KEY_HOME_LNG = "home_lng"

        const val KEY_WORK_NAME = "work_name"
        const val KEY_WORK_LAT = "work_lat"
        const val KEY_WORK_LNG = "work_lng"
    }

    init {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- TOKEN ---
    fun saveAuthToken(token: String) {
        prefs.edit().putString(USER_TOKEN, token).apply()
    }

    fun fetchAuthToken(): String? {
        return prefs.getString(USER_TOKEN, null)
    }

    // --- USER INFO ---
    fun saveUserInfo(name: String, phone: String) {
        prefs.edit().apply {
            putString(USER_FULL_NAME, name)
            putString(USER_PHONE, phone)
            apply()
        }
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
    fun saveActiveOrderId(id: Long) {
        prefs.edit().putLong(ACTIVE_ORDER_ID, id).apply()
    }

    fun fetchActiveOrderId(): Long {
        return prefs.getLong(ACTIVE_ORDER_ID, -1L)
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
            remove(USER_CITY_NAME)
            remove(USER_CITY_LAT)
            remove(USER_CITY_LNG)
            remove(USER_CITY_ZOOM)
            remove(ACTIVE_ORDER_ID)
            remove(USER_FULL_NAME)
            remove(USER_PHONE)
            // Адреси Дім/Робота та Тему можна залишити, або видалити:
            // remove(KEY_HOME_NAME)...
            apply()
        }
    }
}