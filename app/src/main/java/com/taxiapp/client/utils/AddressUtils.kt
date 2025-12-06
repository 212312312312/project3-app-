package com.taxiapp.client.utils

object AddressUtils {
    fun formatAddress(rawAddress: String?): String {
        if (rawAddress.isNullOrEmpty()) return ""

        var text = rawAddress!!

        // 1. ВИДАЛЯЄМО "PLUS CODES" (Дивні символи типу 7F7P+F9P)
        // Регулярка шукає: Букви/Цифри + Плюс + Букви/Цифри
        text = text.replace(Regex("^[A-Z0-9]+\\+[A-Z0-9]+\\s*,?"), "")
            .replace(Regex("\\s*[A-Z0-9]+\\+[A-Z0-9]+"), "") // Якщо код всередині

        // 2. АГРЕСИВНЕ ОЧИЩЕННЯ (Індекси, Країна)
        text = text
            .replace(Regex("\\b\\d{5}\\b"), "") // Індекси (02000)
            .replace(", Україна", "", ignoreCase = true)
            .replace("Україна", "", ignoreCase = true)
            .replace(", Ukraine", "", ignoreCase = true)
            .replace("Ukraine", "", ignoreCase = true)
            .replace(", Украина", "", ignoreCase = true)
            .replace("Украина", "", ignoreCase = true)
            .replace("Unnamed Road", "Точка на карті", ignoreCase = true) // Якщо немає назви вулиці
            .replace(", ,", ",")
            .trim()
            .removeSuffix(",")
            .removePrefix(",")
            .trim()

        // 3. РОЗБИВАЄМО НА ЧАСТИНИ
        val parts = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

        // 4. ПОШУК МІСТА
        var city = ""
        if (parts.size > 1) {
            val lastPart = parts.last()
            // Якщо остання частина не містить цифр (не номер будинку) - це місто
            val isNumber = lastPart.any { it.isDigit() }
            if (!isNumber) {
                city = lastPart
                parts.removeAt(parts.lastIndex)
            }
        }

        // 5. СКОРОЧЕННЯ
        val replacements = mapOf(
            "вулиця" to "вул.",
            "улица" to "вул.",
            "проспект" to "пр-т",
            "провулок" to "пер.",
            "переулок" to "пер.",
            "набережна" to "наб.",
            "набережная" to "наб.",
            "бульвар" to "б-р",
            "шосе" to "ш.",
            "шоссе" to "ш.",
            "площа" to "пл.",
            "площадь" to "пл.",
            "майдан" to "м-н",
            "район" to "р-он",
            "область" to "обл."
        )

        val formattedParts = parts.map { part ->
            var p = part
            replacements.forEach { (full, short) ->
                p = p.replace(full, short, ignoreCase = true)
            }
            p
        }

        // 6. ЗБІРКА
        val mainAddress = formattedParts.joinToString(", ")

        // Якщо після очищення нічого не залишилось (наприклад, був тільки Plus Code і країна)
        if (mainAddress.isEmpty() && city.isNotEmpty()) {
            return city // Повертаємо хоча б місто/село
        }

        return if (city.isNotEmpty()) {
            val formattedCity = city.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            "$mainAddress ($formattedCity)"
        } else {
            mainAddress
        }
    }
}