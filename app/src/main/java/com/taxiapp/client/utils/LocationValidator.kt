package com.taxiapp.client.utils

import android.location.Location

object LocationValidator {
    // Границы территории Украины (Широта и Долгота)
    private const val MIN_LAT = 44.0
    private const val MAX_LAT = 52.5
    private const val MIN_LNG = 22.0
    private const val MAX_LNG = 40.5

    /**
     * Проверка точки: отсекает Лиму, Перу, океан (0,0) и другие континенты.
     * Не дает ложных срабатываний в любой точке Украины.
     */
    fun isValidLocation(lat: Double, lng: Double): Boolean {
        // 1. Отсекаем нулевые и неинициализированные координаты
        if (lat == 0.0 && lng == 0.0) return false

        // 2. Отсекаем все координаты за пределами Украины (Лима: lat -12.04, lng -77.04)
        if (lat !in MIN_LAT..MAX_LAT || lng !in MIN_LNG..MAX_LNG) {
            return false
        }

        return true
    }

    /**
     * Дополнительная проверка системного объекта Location (на точность)
     */
    fun isValidLocation(location: Location?): Boolean {
        if (location == null) return false

        // Отсекаем полностью сбитый сигнал с погрешностью более 1.5 км
        if (location.hasAccuracy() && location.accuracy > 1500f) {
            return false
        }

        return isValidLocation(location.latitude, location.longitude)
    }
}