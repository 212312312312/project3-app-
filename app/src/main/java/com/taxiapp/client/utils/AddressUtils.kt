package com.taxiapp.client.utils

object AddressUtils {
    fun formatAddress(rawAddress: String?): String {
        if (rawAddress.isNullOrEmpty()) return ""

        var text = rawAddress!!

        // 1. ВИДАЛЯЄМО "PLUS CODES" (Наприклад, 7F7P+F9P)
        text = text.replace(Regex("^[A-Z0-9]+\\+[A-Z0-9]+\\s*,?"), "")
            .replace(Regex("\\s*[A-Z0-9]+\\+[A-Z0-9]+"), "")

        // 2. АГРЕСИВНЕ ОЧИЩЕННЯ (Індекси, Країна)
        text = text
            .replace(Regex("\\b\\d{5}\\b"), "")
            .replace(", Україна", "", ignoreCase = true)
            .replace("Україна", "", ignoreCase = true)
            .replace(", Ukraine", "", ignoreCase = true)
            .replace("Ukraine", "", ignoreCase = true)
            .replace(", Украина", "", ignoreCase = true)
            .replace("Украина", "", ignoreCase = true)
            .replace("Unnamed Road", "Точка на карті", ignoreCase = true)
            .replace(", ,", ",")
            .trim()
            .removeSuffix(",")
            .removePrefix(",")
            .trim()

        // 3. НОРМАЛІЗАЦІЯ АНГЛІЦИЗМІВ У НОМЕРАХ БУДИНКІВ (1D -> 1Д, 14A -> 14А)
        val latinToCyrillic = mapOf(
            'A' to 'А', 'a' to 'а',
            'B' to 'Б', 'b' to 'б',
            'C' to 'В', 'c' to 'в',
            'D' to 'Д', 'd' to 'д',
            'E' to 'Е', 'e' to 'е',
            'H' to 'Н', 'h' to 'н',
            'K' to 'К', 'k' to 'к',
            'M' to 'М', 'm' to 'м',
            'O' to 'О', 'o' to 'о',
            'P' to 'Р', 'p' to 'р',
            'T' to 'Т', 't' to 'т'
        )

        text = text.replace(Regex("\\b(\\d+)\\s*([A-Za-z]+)\\b")) { match ->
            val digits = match.groupValues[1]
            val letters = match.groupValues[2].map { latinToCyrillic[it] ?: it }.joinToString("")
            "$digits$letters"
        }

        // 4. РОЗБИВАЄМО НА ЧАСТИНИ
        var parts = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

        // 5. ПОШУК МІСТА
        var city = ""
        if (parts.size > 1) {
            val lastPart = parts.last()
            val isNumber = lastPart.any { it.isDigit() }
            if (!isNumber) {
                city = lastPart
                parts.removeAt(parts.lastIndex)
            }
        }

        // 6. ДЕДУПЛІКАЦІЯ НОМЕРА БУДИНКУ В НАЧАЛІ
        // Якщо перша частина (наприклад, "14А" або "1Д") зустрічається в наступних частинах адреси — видаляємо її з початку
        if (parts.size > 1) {
            val firstPart = parts.first().lowercase()
            val restContainsFirst = parts.drop(1).any { part ->
                val pLower = part.lowercase()
                pLower == firstPart || pLower.contains(" $firstPart") || pLower.contains("$firstPart ")
            }
            if (restContainsFirst) {
                parts.removeAt(0)
            }
        }

        // Видаляємо дублікати однакових слів/частин адреси
        val uniqueParts = mutableListOf<String>()
        for (p in parts) {
            if (uniqueParts.none { it.equals(p, ignoreCase = true) }) {
                uniqueParts.add(p)
            }
        }
        parts = uniqueParts

        // 7. СКОРОЧЕННЯ
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

        // 8. ЗБІРКА
        val mainAddress = formattedParts.joinToString(", ")

        if (mainAddress.isEmpty() && city.isNotEmpty()) {
            return city
        }

        return if (city.isNotEmpty()) {
            val formattedCity = city.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            "$mainAddress ($formattedCity)"
        } else {
            mainAddress
        }
    }
}