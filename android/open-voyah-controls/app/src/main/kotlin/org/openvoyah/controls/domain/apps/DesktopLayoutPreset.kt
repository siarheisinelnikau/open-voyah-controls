package org.openvoyah.controls.domain.apps

import java.text.Normalizer

internal data class DesktopLayoutPreset(val id: String, val name: String) {
    init {
        require(validId(id))
        require(normalizeName(name) == name)
    }

    companion object {
        const val MAX_PRESETS = 4
        const val MAX_NAME_LENGTH = 32
        private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        fun validId(value: String): Boolean = value.length == 36 && idPattern.matches(value)

        fun validInput(value: String): Boolean = validDesktopNameInput(value, MAX_NAME_LENGTH)

        fun normalizeName(value: String): String? {
            if (!validInput(value)) return null
            val name = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
            val visible = name.codePoints().anyMatch { point ->
                when (Character.getType(point)) {
                    Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
                    Character.ENCLOSING_MARK.toInt(), Character.SPACE_SEPARATOR.toInt() -> false
                    else -> true
                }
            }
            return name.takeIf { visible && validInput(it) }
        }
    }
}

internal enum class DesktopLayoutProblem { INVALID_NAME, DUPLICATE_NAME, FULL, SIZE_LIMIT, MISSING }

internal class DesktopLayoutException(val problem: DesktopLayoutProblem) : IllegalArgumentException(problem.name)
