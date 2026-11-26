package org.openvoyah.controls.domain.routines

import java.text.Normalizer
import org.openvoyah.controls.core.VoiceCatalog

private val words = Regex("[\\p{L}\\p{M}]+(?: [\\p{L}\\p{M}]+){0,7}")

internal fun normalizeRoutinePhrase(value: String): String? {
    if (value.length > 120 || value.any(Char::isISOControl)) return null
    val phrase = VoiceCatalog.normalize(Normalizer.normalize(value, Normalizer.Form.NFC))
    return phrase.takeIf { it.length <= 80 && words.matches(it) && it.any(Char::isLetter) }
}
