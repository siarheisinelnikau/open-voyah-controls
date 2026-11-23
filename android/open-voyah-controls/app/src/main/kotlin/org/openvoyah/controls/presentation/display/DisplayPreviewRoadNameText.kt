package org.openvoyah.controls.presentation.display

import org.openvoyah.controls.domain.display.DisplayPreviewRoadNames

internal fun DisplayPreviewRoadNames.formatRoadName(value: String): String {
    if (this == DisplayPreviewRoadNames.ORIGINAL) return value
    val first = value.indexOfFirst { it.lowercaseChar() in latinLetters }
    if (first < 0) return value
    return buildString(value.length) {
        append(value, 0, first)
        for (index in first until value.length) {
            val letter = value[index]
            val latin = latinLetters[letter.lowercaseChar()]
            when {
                latin == null -> append(letter)
                !letter.isUpperCase() -> append(latin)
                value.inUppercaseRun(index) -> append(latin.uppercase())
                else -> append(latin.replaceFirstChar { it.uppercaseChar() })
            }
        }
    }
}

private fun String.inUppercaseRun(index: Int): Boolean {
    val next = getOrNull(index + 1)
    if (next?.isLetter() == true) return next.isUpperCase()
    return getOrNull(index - 1)?.isUpperCase() == true
}

// Readable Latin spellings for Russian, Belarusian and Ukrainian road labels.
private val latinLetters = mapOf(
    'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
    'е' to "e", 'ё' to "yo", 'ж' to "zh", 'з' to "z", 'и' to "i",
    'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
    'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
    'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch",
    'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "",
    'э' to "e", 'ю' to "yu", 'я' to "ya", 'і' to "i", 'ў' to "u",
    'є' to "ye", 'ї' to "yi", 'ґ' to "g",
)
