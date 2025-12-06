package com.taxiapp.client.utils

import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.model.RectangularBounds

data class RegionData(
    val center: LatLng,
    val zoom: Float,
    val bounds: RectangularBounds
)

object CityDatabase {

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
        "Київська" to RegionData(LatLng(50.4501, 30.5234), 11f, createBounds(LatLng(50.4501, 30.5234))),
        "Львівська" to RegionData(LatLng(49.8397, 24.0297), 12f, createBounds(LatLng(49.8397, 24.0297))),
        "Одеська" to RegionData(LatLng(46.4825, 30.7233), 12f, createBounds(LatLng(46.4825, 30.7233))),
        "Дніпропетровська" to RegionData(LatLng(48.4647, 35.0462), 11f, createBounds(LatLng(48.4647, 35.0462))),
        "Харківська" to RegionData(LatLng(49.9935, 36.2304), 11f, createBounds(LatLng(49.9935, 36.2304))),
        "Волинська" to RegionData(LatLng(50.7472, 25.3254), 12f, createBounds(LatLng(50.7472, 25.3254))),
        "Житомирська" to RegionData(LatLng(50.2547, 28.6587), 12f, createBounds(LatLng(50.2547, 28.6587))),
        "Закарпатська" to RegionData(LatLng(48.6208, 22.2879), 12f, createBounds(LatLng(48.6208, 22.2879))),
        "Івано-Франківська" to RegionData(LatLng(48.9226, 24.7111), 12f, createBounds(LatLng(48.9226, 24.7111))),
        "Кіровоградська" to RegionData(LatLng(48.5079, 32.2623), 12f, createBounds(LatLng(48.5079, 32.2623))),
        "Миколаївська" to RegionData(LatLng(46.9750, 31.9946), 12f, createBounds(LatLng(46.9750, 31.9946))),
        "Полтавська" to RegionData(LatLng(49.5883, 34.5514), 12f, createBounds(LatLng(49.5883, 34.5514))),
        "Рівненська" to RegionData(LatLng(50.6199, 26.2516), 12f, createBounds(LatLng(50.6199, 26.2516))),
        "Сумська" to RegionData(LatLng(50.9077, 34.7981), 12f, createBounds(LatLng(50.9077, 34.7981))),
        "Тернопільська" to RegionData(LatLng(49.5535, 25.5948), 12f, createBounds(LatLng(49.5535, 25.5948))),
        "Хмельницька" to RegionData(LatLng(49.4229, 26.9871), 12f, createBounds(LatLng(49.4229, 26.9871))),
        "Черкаська" to RegionData(LatLng(49.4444, 32.0598), 12f, createBounds(LatLng(49.4444, 32.0598))),
        "Чернігівська" to RegionData(LatLng(51.4982, 31.2893), 12f, createBounds(LatLng(51.4982, 31.2893))),
        "Чернівецька" to RegionData(LatLng(48.2917, 25.9358), 12f, createBounds(LatLng(48.2917, 25.9358)))
    )
}