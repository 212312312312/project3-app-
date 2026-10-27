package com.taxiapp.client.utils

import android.content.Context
import android.location.Geocoder
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.SphericalUtil
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.dto.NearbyPlaceSuggestionDto
import com.taxiapp.client.ui.PlaceSuggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.random.Random

/**
 * Отказоустойчивый геокодер координат под булавкой карты + фоновый предзагрузчик (Prefetch)
 * 6 рандомных/популярных адресов на дистанции от 2.0 км:
 * 1. Опрашивает серверный эндпоинт public/nearby-suggestions (с защитой от null-полей в JSON).
 * 2. Если сервер вернул пустой список или недоступен — автоматически генерирует 6 рандомных реальных
 *    адресов в кольце 2.2–4.8 км вокруг пользователя через параллельный геокодинг.
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
     * Безопасный маппинг серверного DTO в PlaceSuggestion без риска NullPointerException,
     * если сервер не прислал placeId, subtitle или distanceMeters.
     */
    fun mapDtoToSuggestion(dto: NearbyPlaceSuggestionDto, anchor: LatLng): PlaceSuggestion? {
        val rawTitle = dto.title?.trim().orEmpty()
        if (rawTitle.isEmpty()) return null

        val lat = dto.lat ?: 0.0
        val lng = dto.lng ?: 0.0
        val computedDist = if (dto.distanceMeters != null && dto.distanceMeters > 0) {
            dto.distanceMeters
        } else if ((lat != 0.0 || lng != 0.0) && (anchor.latitude != 0.0 || anchor.longitude != 0.0)) {
            SphericalUtil.computeDistanceBetween(anchor, LatLng(lat, lng)).toInt()
        } else {
            null
        }

        val safePlaceId = dto.placeId?.takeIf { it.isNotBlank() }
            ?: "nearby_${lat}_${lng}_${rawTitle.hashCode()}"

        return PlaceSuggestion(
            placeId = safePlaceId,
            title = AddressUtils.formatAddress(rawTitle),
            subtitle = dto.subtitle?.trim()?.let { AddressUtils.formatAddress(it) }.orEmpty(),
            distanceMeters = computedDist,
            lat = if (lat != 0.0 || lng != 0.0) lat else null,
            lng = if (lat != 0.0 || lng != 0.0) lng else null,
            category = dto.category
        )
    }

    /**
     * Мгновенно возвращает уже предзагруженный список подсказок для AddressPickerActivity (0 мс задержки)
     * с пересчетом точного расстояния до текущих координат.
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
     * Проверяет, актуален ли предзагруженный кэш для данной точки (в радиусе 350 метров).
     */
    fun isCacheFreshFor(anchor: LatLng, lang: String): Boolean {
        val prevAnchor = cachedAnchorLatLng ?: return false
        if (cachedNearbySuggestions.size < 4 || cachedLanguage != lang) return false
        val movedMeters = SphericalUtil.computeDistanceBetween(prevAnchor, anchor)
        return movedMeters < 350.0
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
     * Загружает 6 адресов с сервера, а если сервер вернул пустой список или недоступен —
     * генерирует 6 рандомных реальных адресов в кольце 2.2–4.8 км вокруг пользователя.
     */
    suspend fun fetchOrGenerateSuggestions(
        context: Context,
        anchor: LatLng,
        lang: String,
        limit: Int = 6
    ): List<PlaceSuggestion> = withContext(Dispatchers.IO) {
        if (anchor.latitude == 0.0 && anchor.longitude == 0.0) return@withContext emptyList()

        // 1. Пробуем получить список с сервера
        val serverList = try {
            withTimeoutOrNull(3500L) {
                val response = ApiClient.instance.getNearbySuggestions(
                    lat = anchor.latitude,
                    lng = anchor.longitude,
                    lang = lang,
                    limit = limit
                )
                if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                    response.body()!!.mapNotNull { dto -> mapDtoToSuggestion(dto, anchor) }
                } else {
                    emptyList()
                }
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val over2km = serverList.filter { (it.distanceMeters ?: MIN_SUGGESTION_DISTANCE_METERS) >= MIN_SUGGESTION_DISTANCE_METERS }
        val validServerList = if (over2km.isNotEmpty()) over2km else serverList
        if (validServerList.isNotEmpty()) {
            updateMemoryCache(anchor, lang, validServerList)
            return@withContext validServerList
        }

        // 2. Фоллбэк: генерируем 6 рандомных точек в кольце 2.2–4.8 км (по 6 секторам 0°..300°)
        val generated = generateLocalRingSuggestions(context.applicationContext, anchor, lang, limit)
        if (generated.isNotEmpty()) {
            updateMemoryCache(anchor, lang, generated)
        }
        return@withContext generated
    }

    private suspend fun generateLocalRingSuggestions(
        context: Context,
        anchor: LatLng,
        lang: String,
        limit: Int
    ): List<PlaceSuggestion> = coroutineScope {
        val locale = if (lang == "uk") Locale("uk", "UA") else Locale(lang)
        val baseAngles = listOf(15.0, 75.0, 135.0, 195.0, 255.0, 315.0).shuffled()

        val deferredList = baseAngles.take(limit).mapIndexed { index, baseAngle ->
            async(Dispatchers.IO) {
                val heading = (baseAngle + Random.nextDouble(-20.0, 20.0) + 360.0) % 360.0
                val distanceMeters = Random.nextInt(2200, 4800)
                val targetPoint = SphericalUtil.computeOffset(anchor, distanceMeters.toDouble(), heading)

                val resolved = resolvePointForSuggestion(context, targetPoint, locale, lang)
                if (resolved != null) {
                    val (title, subtitle) = resolved
                    val actualDist = SphericalUtil.computeDistanceBetween(anchor, targetPoint).toInt()
                    PlaceSuggestion(
                        placeId = "ring_${index}_${targetPoint.latitude}_${targetPoint.longitude}",
                        title = title,
                        subtitle = subtitle,
                        distanceMeters = actualDist,
                        lat = targetPoint.latitude,
                        lng = targetPoint.longitude,
                        category = "street"
                    )
                } else {
                    null
                }
            }
        }

        deferredList.awaitAll()
            .filterNotNull()
            .distinctBy { it.title.lowercase(locale) }
            .sortedBy { it.distanceMeters ?: Int.MAX_VALUE }
    }

    private suspend fun resolvePointForSuggestion(
        context: Context,
        point: LatLng,
        locale: Locale,
        lang: String
    ): Pair<String, String>? {
        // Сначала пробуем быстрый системный Geocoder
        try {
            if (Geocoder.isPresent()) {
                val localPair = withTimeoutOrNull(1800L) {
                    val geocoder = Geocoder(context, locale)
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(point.latitude, point.longitude, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val addr = addresses[0]
                        val street = addr.thoroughfare?.trim()
                        val house = addr.subThoroughfare?.trim()
                        val city = (addr.locality ?: addr.subAdminArea ?: "").trim()

                        val title = when {
                            !street.isNullOrEmpty() && !house.isNullOrEmpty() -> "$street, $house"
                            !street.isNullOrEmpty() -> street
                            else -> AddressUtils.formatAddress(addr.getAddressLine(0) ?: "")
                        }
                        if (title.isNotBlank() &&
                            !title.equals("Точка на карті", ignoreCase = true) &&
                            !title.equals("Unnamed Road", ignoreCase = true) &&
                            !title.any { it == '+' }
                        ) {
                            Pair(title, city)
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }
                if (localPair != null) return localPair
            }
        } catch (_: Exception) {}

        // Фоллбэк на серверный reverse-geocode
        return try {
            withTimeoutOrNull(2500L) {
                val resp = ApiClient.instance.reverseGeocode(point.latitude, point.longitude, lang)
                if (resp.isSuccessful && resp.body() != null) {
                    val formatted = AddressUtils.formatAddress(resp.body()!!.address)
                    if (formatted.isNotBlank() && !formatted.equals("Точка на карті", ignoreCase = true)) {
                        Pair(formatted, "")
                    } else null
                } else null
            }
        } catch (_: Exception) {
            null
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
            fetchOrGenerateSuggestions(context.applicationContext, latLng, lang, limit = 6)
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

        // Параллельно запускаем предзагрузку 6 улиц/мест (от 2 км) еще на главном экране
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