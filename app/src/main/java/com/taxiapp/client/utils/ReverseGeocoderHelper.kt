package com.taxiapp.client.utils

import android.content.Context
import android.location.Geocoder
import com.google.android.gms.maps.model.LatLng
import com.taxiapp.client.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Отказоустойчивый геокодер координат под булавкой карты:
 * 1. Сначала опрашивает системный android.location.Geocoder (быстрый ответ при наличии GMS).
 * 2. Если системный Geocoder недоступен, завис или вернул пустой ответ (прошивки Xiaomi/Huawei без GMS,
 *    слабый канал к серверам Google), прозрачно обращается к нашему серверному фоллбэку
 *    GET /api/v1/public/reverse-geocode (Nominatim + Redis кэш + полигоны секторов).
 */
object ReverseGeocoderHelper {

    suspend fun resolveAddress(
        context: Context,
        latLng: LatLng,
        languageCode: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val lang = (languageCode ?: SessionManager(context).getLanguage()).let {
            if (it == "ua") "uk" else it
        }

        // Шаг 1: Пробуем локальный системный Geocoder с коротким таймаутом 2 секунды
        val localResult = try {
            if (Geocoder.isPresent()) {
                withTimeoutOrNull(2000L) {
                    val locale = if (lang == "uk") Locale("uk", "UA") else Locale(lang)
                    val geocoder = Geocoder(context, locale)
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val rawLine = addresses[0].getAddressLine(0)
                        val formatted = AddressUtils.formatAddress(rawLine)
                        formatted.takeIf {
                            it.isNotBlank() &&
                                    !it.equals("Точка на карті", ignoreCase = true) &&
                                    !it.equals("Unnamed Road", ignoreCase = true)
                        }
                    } else {
                        null
                    }
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }

        if (!localResult.isNullOrBlank()) {
            return@withContext localResult
        }

        // Шаг 2: Фоллбэк на наш сервер (Nominatim + Redis кэш + Сектора)
        return@withContext try {
            withTimeoutOrNull(3500L) {
                val response = ApiClient.instance.reverseGeocode(
                    lat = latLng.latitude,
                    lng = latLng.longitude,
                    lang = lang
                )

                if (response.isSuccessful && response.body() != null) {
                    val rawServerAddr = response.body()!!.address
                    val formatted = AddressUtils.formatAddress(rawServerAddr)
                    formatted.ifBlank { rawServerAddr }.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}