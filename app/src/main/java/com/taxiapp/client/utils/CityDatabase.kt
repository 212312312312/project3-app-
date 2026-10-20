package com.taxiapp.client.utils

import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.model.RectangularBounds
import kotlin.math.cos

data class RegionData(
    val center: LatLng,
    val zoom: Float,
    val bounds: RectangularBounds
)

object CityDatabase {

    // Словник синонімів, транслітерації та російських назв для розумного пошуку
    val citySearchAliases: Map<String, List<String>> = mapOf(
        "Вінниця" to listOf("винница", "vinnitsa", "vinnytsia"),
        "Дніпро" to listOf("днепр", "днепропетровск", "dnepr", "dnipro"),
        "Житомир" to listOf("житомир", "zhitomir", "zhytomyr"),
        "Запоріжжя" to listOf("запорожье", "zaporozhye", "zaporozhe", "zaporizhzhia"),
        "Івано-Франківськ" to listOf("ивано-франковск", "ивано франковск", "иванофранковск", "ivano-frankivsk", "франківськ"),
        "Київ" to listOf("киев", "kiev", "kyiv"),
        "Кропивницький" to listOf("кропивницкий", "кировоград", "kropyvnytskyi", "kirovograd"),
        "Луцьк" to listOf("луцк", "lutsk"),
        "Львів" to listOf("львов", "lvov", "lviv"),
        "Миколаїв" to listOf("николаев", "nikolaev", "mykolaiv"),
        "Одеса" to listOf("одесса", "odessa", "odesa"),
        "Полтава" to listOf("полтава", "poltava"),
        "Рівне" to listOf("ровно", "rovno", "rivne"),
        "Суми" to listOf("сумы", "sumy"),
        "Тернопіль" to listOf("тернополь", "ternopil", "ternopol"),
        "Ужгород" to listOf("ужгород", "uzhhorod", "uzhgorod"),
        "Харків" to listOf("харьков", "kharkov", "kharkiv"),
        "Херсон" to listOf("херсон", "kherson"),
        "Хмельницький" to listOf("хмельницкий", "khmelnitsky", "khmelnytskyi"),
        "Черкаси" to listOf("черкассы", "cherkassy", "cherkasy"),
        "Чернівці" to listOf("черновцы", "chernovtsy", "chernivtsi"),
        "Чернігів" to listOf("чернигов", "chernigov", "chernihiv")
    )

    // Прямокутні межі (Box) навколо центру міста (радіус 25 км = 50 км діаметр)
    private fun createBounds(center: LatLng, radiusKm: Double = 25.0): RectangularBounds {
        val latRadian = Math.toRadians(center.latitude)
        val degLat = radiusKm / 111.0
        val degLng = radiusKm / (111.0 * cos(latRadian))

        val southwest = LatLng(center.latitude - degLat, center.longitude - degLng)
        val northeast = LatLng(center.latitude + degLat, center.longitude + degLng)

        return RectangularBounds.newInstance(southwest, northeast)
    }

    // Виправлені координати всіх обласних центрів України
    val regions: Map<String, RegionData> = mapOf(
        "Вінниця" to RegionData(LatLng(49.2331, 28.4682), 12f, createBounds(LatLng(49.2331, 28.4682))),
        "Дніпро" to RegionData(LatLng(48.4647, 35.0462), 11f, createBounds(LatLng(48.4647, 35.0462))),
        "Житомир" to RegionData(LatLng(50.2547, 28.6587), 12f, createBounds(LatLng(50.2547, 28.6587))),
        "Запоріжжя" to RegionData(LatLng(47.8388, 35.1396), 11f, createBounds(LatLng(47.8388, 35.1396))),
        "Івано-Франківськ" to RegionData(LatLng(48.9226, 24.7111), 12f, createBounds(LatLng(48.9226, 24.7111))),
        "Київ" to RegionData(LatLng(50.4501, 30.5234), 11f, createBounds(LatLng(50.4501, 30.5234))),
        "Кропивницький" to RegionData(LatLng(48.5079, 32.2623), 12f, createBounds(LatLng(48.5079, 32.2623))),
        "Луцьк" to RegionData(LatLng(50.7472, 25.3254), 12f, createBounds(LatLng(50.7472, 25.3254))),
        "Львів" to RegionData(LatLng(49.8397, 24.0297), 12f, createBounds(LatLng(49.8397, 24.0297))),
        "Миколаїв" to RegionData(LatLng(46.9750, 31.9946), 12f, createBounds(LatLng(46.9750, 31.9946))),
        "Одеса" to RegionData(LatLng(46.4825, 30.7233), 12f, createBounds(LatLng(46.4825, 30.7233))),
        "Полтава" to RegionData(LatLng(49.5883, 34.5514), 12f, createBounds(LatLng(49.5883, 34.5514))),
        "Рівне" to RegionData(LatLng(50.6199, 26.2516), 12f, createBounds(LatLng(50.6199, 26.2516))),
        "Суми" to RegionData(LatLng(50.9077, 34.7981), 12f, createBounds(LatLng(50.9077, 34.7981))),
        "Тернопіль" to RegionData(LatLng(49.5535, 25.5948), 12f, createBounds(LatLng(49.5535, 25.5948))),
        "Ужгород" to RegionData(LatLng(48.6208, 22.2879), 12f, createBounds(LatLng(48.6208, 22.2879))),
        "Харків" to RegionData(LatLng(49.9935, 36.2304), 11f, createBounds(LatLng(49.9935, 36.2304))),
        "Херсон" to RegionData(LatLng(46.6354, 32.6169), 12f, createBounds(LatLng(46.6354, 32.6169))),
        "Хмельницький" to RegionData(LatLng(49.4229, 26.9871), 12f, createBounds(LatLng(49.4229, 26.9871))),
        "Черкаси" to RegionData(LatLng(49.4444, 32.0598), 12f, createBounds(LatLng(49.4444, 32.0598))),
        "Чернівці" to RegionData(LatLng(48.2917, 25.9358), 12f, createBounds(LatLng(48.2917, 25.9358))),
        "Чернігів" to RegionData(LatLng(51.4982, 31.2893), 12f, createBounds(LatLng(51.4982, 31.2893)))
    )
}