package com.taxiapp.client.utils

import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.model.RectangularBounds

data class RegionData(
    val center: LatLng,
    val zoom: Float,
    val bounds: RectangularBounds
)

object CityDatabase {

    // Словник синонімів та російських назв для розумного пошуку
    val citySearchAliases: Map<String, List<String>> = mapOf(
        "Київ" to listOf("киев", "kiev", "kyiv"),
        "Львів" to listOf("львов", "lvov", "lviv"),
        "Одеса" to listOf("одесса", "odessa", "odesa"),
        "Дніпро" to listOf("днепр", "днепропетровск", "dnepr", "dnipro"),
        "Харків" to listOf("харьков", "kharkov", "kharkiv"),
        "Житомир" to listOf("житомир", "zhitomir", "zhytomyr"),
        "Запоріжжя" to listOf("запорожье", "zaporozhye", "zaporozhe", "zaporizhzhia"),
        "Івано-Франківськ" to listOf("ивано-франковск", "ивано франковск", "иванофранковск", "ivano-frankivsk"),
        "Кропивницький" to listOf("кропивницкий", "кировоград", "kropyvnytskyi", "kirovograd"),
        "Миколаїв" to listOf("николаев", "nikolaev", "mykolaiv"),
        "Полтава" to listOf("полтава", "poltava"),
        "Рівне" to listOf("ровно", "rovno", "rivne"),
        "Суми" to listOf("сумы", "sumy"),
        "Хмельницький" to listOf("хмельницкий", "khmelnitsky", "khmelnytskyi"),
        "Черкаси" to listOf("черкассы", "cherkassy", "cherkasy"),
        "Чернівці" to listOf("черновцы", "chernovtsy", "chernivtsi"),
        "Чернігів" to listOf("чернигов", "chernigov", "chernihiv")
    )

    // Создаем прямоугольные границы (Box) вокруг центра города
    private fun createBounds(center: LatLng, radiusKm: Double = 25.0): RectangularBounds { // 25 км радиус (50км диаметр)
        val latRadian = Math.toRadians(center.latitude)
        val degLat = radiusKm / 111.0
        val degLng = radiusKm / (111.0 * Math.cos(latRadian))
        
        val southwest = LatLng(center.latitude - degLat, center.longitude - degLng)
        val northeast = LatLng(center.latitude + degLat, center.longitude + degLng)
        
        return RectangularBounds.newInstance(southwest, northeast)
    }

    // Список регионов (Координаты центров областных центров)
    val regions: Map<String, RegionData> = mapOf(
        "Київ" to RegionData(LatLng(50.4501, 30.5234), 11f, createBounds(LatLng(50.4501, 30.5234))),
        "Львів" to RegionData(LatLng(49.8397, 24.0297), 12f, createBounds(LatLng(49.8397, 24.0297))),
        "Одеса" to RegionData(LatLng(46.4825, 30.7233), 12f, createBounds(LatLng(46.4825, 30.7233))),
        "Дніпро" to RegionData(LatLng(48.4647, 35.0462), 11f, createBounds(LatLng(48.4647, 35.0462))),
        "Харків" to RegionData(LatLng(49.9935, 36.2304), 11f, createBounds(LatLng(49.9935, 36.2304))),
        "Житомир" to RegionData(LatLng(50.2547, 28.6587), 12f, createBounds(LatLng(50.2547, 28.6587))),
        "Запоріжжя" to RegionData(LatLng(48.6208, 22.2879), 12f, createBounds(LatLng(48.6208, 22.2879))),
        "Івано-Франківськ" to RegionData(LatLng(48.9226, 24.7111), 12f, createBounds(LatLng(48.9226, 24.7111))),
        "Кропивницький" to RegionData(LatLng(48.5079, 32.2623), 12f, createBounds(LatLng(48.5079, 32.2623))),
        "Миколаїв" to RegionData(LatLng(46.9750, 31.9946), 12f, createBounds(LatLng(46.9750, 31.9946))),
        "Полтава" to RegionData(LatLng(49.5883, 34.5514), 12f, createBounds(LatLng(49.5883, 34.5514))),
        "Рівне" to RegionData(LatLng(50.6199, 26.2516), 12f, createBounds(LatLng(50.6199, 26.2516))),
        "Суми" to RegionData(LatLng(50.9077, 34.7981), 12f, createBounds(LatLng(50.9077, 34.7981))),
        "Черкаси" to RegionData(LatLng(49.5535, 25.5948), 12f, createBounds(LatLng(49.5535, 25.5948))),
        "Хмельницький" to RegionData(LatLng(49.4229, 26.9871), 12f, createBounds(LatLng(49.4229, 26.9871))),
        "Черкаси" to RegionData(LatLng(49.4444, 32.0598), 12f, createBounds(LatLng(49.4444, 32.0598))),
        "Чернівці" to RegionData(LatLng(51.4982, 31.2893), 12f, createBounds(LatLng(51.4982, 31.2893))),
        "Чернігів" to RegionData(LatLng(48.2917, 25.9358), 12f, createBounds(LatLng(48.2917, 25.9358)))
    )
}