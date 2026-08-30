package com.taxiapp.client.utils

object AddressUtils {
    fun formatAddress(rawAddress: String?): String {
        if (rawAddress.isNullOrBlank()) return ""

        var text = rawAddress.trim()

        // 1. ВИДАЛЯЄМО "PLUS CODES" (Наприклад, 7F7P+F9P)
        text = text.replace(Regex("^[A-Z0-9]+\\+[A-Z0-9]+\\s*,?"), "")
            .replace(Regex("\\s*[A-Z0-9]+\\+[A-Z0-9]+"), "")

        // 2. ОЧИЩЕННЯ ВІД КРАЇНИ ТА ПОШТОВИХ ІНДЕКСІВ
        text = text
            .replace(Regex("\\b\\d{5}\\b"), "")
            .replace(", Україна", "", ignoreCase = true)
            .replace("Україна", "", ignoreCase = true)
            .replace(", Ukraine", "", ignoreCase = true)
            .replace("Ukraine", "", ignoreCase = true)
            .replace(", Украина", "", ignoreCase = true)
            .replace("Украина", "", ignoreCase = true)
            .replace("Unnamed Road", "Точка на карті", ignoreCase = true)

        // 3. ВИДАЛЯЄМО ВСІ ОБЛАСТІ ТА РАЙОНИ (Одеська обл., Київська область, р-н тощо)
        text = text
            .replace(Regex("\\b\\w+(ська|цька|зька|ская|цкая|зская)\\s+(область|обл\\.?|район|р-н\\.?)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b(область|обл\\.?|район|р-н\\.?)\\s+\\w+\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b\\w+\\s+(Oblast|Region|Raion|District)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Автономна Республіка Крим|АР Крим", RegexOption.IGNORE_CASE), "")

        // 4. ОЧИЩЕННЯ ВІД ВЛАДЕНИХ ТА ПОРОЖНІХ ДУЖОК
        text = text
            .replace(Regex("\\(\\s*\\)"), "")
            .replace("((", "(")
            .replace("))", ")")
            .replace(Regex("\\(\\s*,"), "(")
            .replace(Regex(",\\s*\\)"), ")")

        // 5. НОРМАЛІЗАЦІЯ АНГЛІЦИЗМІВ У НОМЕРАХ БУДИНКІВ (1D -> 1Д, 3V -> 3В, 14A -> 14А)
        val latinToCyrillic = mapOf(
            'A' to 'А', 'a' to 'а',
            'B' to 'Б', 'b' to 'б',
            'C' to 'С', 'c' to 'с', // ВИПРАВЛЕНО: було 'В'
            'D' to 'Д', 'd' to 'д',
            'E' to 'Е', 'e' to 'е',
            'H' to 'Н', 'h' to 'н',
            'K' to 'К', 'k' to 'к',
            'M' to 'М', 'm' to 'м',
            'O' to 'О', 'o' to 'о',
            'P' to 'Р', 'p' to 'р',
            'S' to 'С', 's' to 'с',
            'T' to 'Т', 't' to 'т',
            'V' to 'В', 'v' to 'в', // ДОДАНО: англійська V -> кирилична В
            'W' to 'В', 'w' to 'в',
            'X' to 'Х', 'x' to 'х'
        )

        text = text.replace(Regex("\\b(\\d+)\\s*([A-Za-z]+)\\b")) { match ->
            val digits = match.groupValues[1]
            val letters = match.groupValues[2].map { latinToCyrillic[it] ?: it }.joinToString("")
            "$digits$letters"
        }

        // 6. РОЗБИВАЄМО НА ЧАСТИНИ ПО КОМАХ ТА ДУЖКАХ
        var parts = text
            .replace("(", ", ")
            .replace(")", "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()

        // 7. ПОШУК ТА ВИТЯГ МІСТА
        var city = ""
        if (parts.size > 1) {
            val lastPart = parts.last()
            val isHouseNumber = lastPart.any { it.isDigit() }
            val isStreetKeyword = listOf("вул", "пр-т", "пер", "наб", "б-р", "ш.", "пл", "м-н").any {
                lastPart.contains(it, ignoreCase = true)
            }

            if (!isHouseNumber && !isStreetKeyword) {
                city = lastPart
                    .replace(Regex("^(м\\.|м\\s+|місто\\s+|город\\s+|c\\.|с\\.\\s+|смт\\.?\\s*)", RegexOption.IGNORE_CASE), "")
                    .trim()
                parts.removeAt(parts.lastIndex)
            }
        }

        // 8. ДЕДУПЛІКАЦІЯ ТА ВИПРАВЛЕННЯ НОМЕРА БУДИНКУ В ПОЧАТКУ
        if (parts.size > 1) {
            val firstPart = parts.first().trim()
            val firstPartLower = firstPart.lowercase()

            // Перевіряємо, чи перша частина є просто номером будинку (наприклад: "12", "3В", "3-В", "14/2")
            val isFirstOnlyHouseNumber = firstPart.matches(Regex("^[\\d]+[A-Za-zА-Яа-яіІїЇєЄ/'\"\\-\\s]*$"))

            if (isFirstOnlyHouseNumber) {
                val restText = parts.drop(1).joinToString(" ").lowercase()

                // Перевіряємо, чи решта адреси вже містить цей номер або будь-який номер будинку
                val restContainsNumber = restText.contains(firstPartLower) ||
                        parts.drop(1).any { it.any { char -> char.isDigit() } }

                if (restContainsNumber) {
                    // Якщо номер уже є далі по тексту або в іншій секції — просто видаляємо дублікат з початку
                    parts.removeAt(0)
                } else {
                    // Якщо номер був ТІЛЬКИ на початку ("12", "вул. Івана Дзюби"), переносимо його в кінець назви вулиці
                    val streetIndex = parts.indexOfFirst { part ->
                        listOf("вул", "пр-т", "пер", "наб", "б-р", "ш.", "пл", "м-н").any { part.contains(it, ignoreCase = true) }
                    }
                    val targetIndex = if (streetIndex != -1) streetIndex else 1
                    parts[targetIndex] = "${parts[targetIndex]} $firstPart"
                    parts.removeAt(0)
                }
            }
        }

        // 9. ВИДАЛЯЄМО ДУБЛІКАТИ ТА МІСТО З ОСНОВНОЇ ЧАСТИНИ
        val uniqueParts = mutableListOf<String>()
        for (p in parts) {
            if (uniqueParts.none { it.equals(p, ignoreCase = true) } &&
                (city.isEmpty() || !p.equals(city, ignoreCase = true))) {
                uniqueParts.add(p)
            }
        }
        parts = uniqueParts

        // 10. СКОРОЧЕННЯ ТИПІВ ВУЛИЦЬ
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
            "майдан" to "м-н"
        )

        val formattedParts = parts.map { part ->
            var p = part
            replacements.forEach { (full, short) ->
                p = p.replace(full, short, ignoreCase = true)
            }
            p
        }

        // 11. ФІНАЛЬНА ЗБІРКА АДРЕСИ
        val mainAddress = formattedParts.joinToString(", ")

        if (mainAddress.isEmpty() && city.isNotEmpty()) {
            return city.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

        return if (city.isNotEmpty()) {
            val formattedCity = city.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            "$mainAddress ($formattedCity)"
        } else {
            mainAddress
        }
    }
}