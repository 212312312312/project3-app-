package com.taxiapp.client.utils

import android.content.Context
import android.location.Geocoder
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.SphericalUtil
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.ui.PlaceSuggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Отказоустойчивый геокодер координат под булавкой карты + фоновый предзагрузчик (Prefetch)
 * популярных адресов и главных улиц на дистанции ОТ 2.0 км:
 * 1. Опрашивает системный android.location.Geocoder (быстрый ответ при наличии GMS) с фоллбэком на сервер.
 * 2. Параллельно на главном экране предзагружает и кэширует в памяти 6 популярных улиц/мест (от 2 км),
 *    чтобы при открытии AddressPickerActivity список отображался мгновенно (за 0 мс, без ожидания сети).
 */
object ReverseGeocoderHelper {

    private const val MIN_SUGGESTION_DISTANCE_METERS = 2000

    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cachedAnchorLatLng: LatLng? = null

    @Volatile
    private var cachedLanguage: String? = null

    @Volatile
    private var cachedNearbySuggestions: List<PlaceSuggestion> = emptyList()

    /**
     * Мгновенно возвращает уже предзагруженный список подсказок для AddressPickerActivity (0 мс задержки)
     * с пересчетом точного расстояния до текущих координат.
     * Если есть элементы >= 2000 м — отдает их; если сервер еще не перезапущен с новым кольцом —
     * отдает имеющиеся элементы вместо пустого экрана.
     */
    fun getInstantSuggestions(anchor: LatLng?): List<PlaceSuggestion> {
        val currentList = cachedNearbySuggestions
        if (currentList.isEmpty()) return emptyList()

        val recalculated = if (anchor == null || (anchor.latitude == 0.0 && anchor.longitude == 0.0)) {
            currentList
        } else {
            currentList.map { item ->
                if (item.lat != null && item.lng != null && (item.lat != 0.0 || item.lng != 0.0)) {
                    val dist = SphericalUtil.computeDistanceBetween(anchor, LatLng(item.lat, item.lng)).toInt()
                    item.copy(distanceMeters = dist)
                } else {
                    item
                }
            }
        }

        val over2km = recalculated.filter { (it.distanceMeters ?: MIN_SUGGESTION_DISTANCE_METERS) >= MIN_SUGGESTION_DISTANCE_METERS }
        return if (over2km.isNotEmpty()) over2km else recalculated
    }

    /**
     * Проверяет, актуален ли предзагруженный кэш для данной точки (в радиусе 350 метров)
     * и содержит ли он адреса от 2.0 км.
     */
    fun isCacheFreshFor(anchor: LatLng, lang: String): Boolean {
        val prevAnchor = cachedAnchorLatLng ?: return false
        if (cachedNearbySuggestions.size < 4 || cachedLanguage != lang) return false
        val movedMeters = SphericalUtil.computeDistanceBetween(prevAnchor, anchor)
        val over2kmCount = cachedNearbySuggestions.count { (it.distanceMeters ?: 0) >= MIN_SUGGESTION_DISTANCE_METERS }
        return movedMeters < 350.0 && over2kmCount >= 4
    }

    fun updateMemoryCache(anchor: LatLng, lang: String, list: List<PlaceSuggestion>) {
        if (list.isNotEmpty()) {
            val over2km = list.filter { (it.distanceMeters ?: MIN_SUGGESTION_DISTANCE_METERS) >= MIN_SUGGESTION_DISTANCE_METERS }
            cachedAnchorLatLng = anchor
            cachedLanguage = lang
            cachedNearbySuggestions = if (over2km.isNotEmpty()) over2km else list
        }
    }

    /**
     * Фоновая предзагрузка 6 адресов (от 2 км) еще на главном экране (HomeActivity).
     */
    fun prefetchNearbySuggestionsAsync(
        context: Context,
        latLng: LatLng,
        languageCode: String? = null
    ) {
        if (latLng.latitude == 0.0 && latLng.longitude == 0.0) return
        val lang = (languageCode ?: SessionManager(context).getLanguage()).let {
            if (it == "ua") "uk" else it
        }
        if (isCacheFreshFor(latLng, lang)) return

        prefetchScope.launch {
            try {
                val response = ApiClient.instance.getNearbySuggestions(
                    lat = latLng.latitude,
                    lng = latLng.longitude,
                    lang = lang,
                    limit = 6
                )
                if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                    val mapped = response.body()!!.map { dto ->
                        PlaceSuggestion(
                            placeId = dto.placeId,
                            title = dto.title,
                            subtitle = dto.subtitle,
                            distanceMeters = dto.distanceMeters,
                            lat = dto.lat,
                            lng = dto.lng,
                            category = dto.category
                        )
                    }
                    updateMemoryCache(latLng, lang, mapped)
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun resolveAddress(
        context: Context,
        latLng: LatLng,
        languageCode: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val lang = (languageCode ?: SessionManager(context).getLanguage()).let {
            if (it == "ua") "uk" else it
        }

        // Параллельно запускаем предзагрузку 6 улиц/мест (от 2 км) еще на главном экране,
        // чтобы в AddressPickerActivity они открылись мгновенно без ожидания ответа сервера!
        prefetchNearbySuggestionsAsync(context.applicationContext, latLng, lang)

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