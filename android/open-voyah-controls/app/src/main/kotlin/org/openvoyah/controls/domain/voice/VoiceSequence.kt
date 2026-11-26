package org.openvoyah.controls.domain.voice

import org.openvoyah.controls.core.VoiceCatalog
import org.openvoyah.controls.core.voice.ClimatePhrases
import org.openvoyah.controls.core.voice.VolumePhrases

internal class VoiceSequence(language: String, private val known: (String) -> Boolean) {
    private val hard = when (language) {
        "en" -> listOf("and then", "after that", "then")
        "kk" -> listOf("содан кейін", "одан кейін", "сосын")
        else -> listOf("а затем", "и затем", "и потом", "далее", "затем", "потом")
    }
    private val and = when (language) { "en" -> "and"; "kk" -> "және"; else -> "и" }
    val separators = (hard + and).toSet()
    private val hardPattern = Regex(" (?:${hard.joinToString("|", transform = Regex::escape)}) ")

    fun hasSeparator(text: String): Boolean {
        val phrase = " ${VoiceCatalog.normalize(text)} "
        return separators.any { " $it " in phrase }
    }

    /** Whole commands and custom phrases keep priority. Only exact segments can run; there is no fuzzy repair. */
    fun split(text: String): List<String>? {
        if (text.length > MAX_CHARACTERS) return null
        val phrase = VoiceCatalog.normalize(text)
        if (phrase.isEmpty() || known(phrase)) return null
        val parts = splitHard(phrase)
        return parts.takeIf { it.size > 1 && it.any(known) }
    }

    private fun splitHard(phrase: String): List<String> {
        if (known(phrase)) return listOf(phrase)
        val separators = hardPattern.findAll(phrase).toList()
        val next = separators.lastOrNull { known(phrase.substring(0, it.range.first)) }
            ?: separators.firstOrNull() ?: return splitAnd(phrase)
        return splitAnd(phrase.substring(0, next.range.first)) + splitHard(phrase.substring(next.range.last + 1))
    }

    private fun splitAnd(phrase: String): List<String> {
        if (known(phrase)) return listOf(phrase)
        val marker = " $and "
        val ends = generateSequence(phrase.indexOf(marker)) { i -> phrase.indexOf(marker, i + marker.length) }
            .takeWhile { it > 0 }.toList()
        // Prefer the longest exact command. For example, airflow body and feet is one action.
        val index = ends.lastOrNull { known(phrase.substring(0, it)) }
            ?: ends.firstOrNull { known(phrase.substring(it + marker.length)) }
            ?: return listOf(phrase)
        return splitAnd(phrase.substring(0, index)) + splitAnd(phrase.substring(index + marker.length))
    }

    companion object {
        const val LIMIT = 3
        const val MAX_CHARACTERS = 200
    }
}

internal enum class SequenceCommandPolicy { ANY, LAST_ONLY, SINGLE_ONLY }

/** Only the listed controls can use the existing physical dispatcher during a sequence. */
internal fun sequenceCommandPolicy(id: String): SequenceCommandPolicy = when {
    id in localSteps || id in reviewedComfortSteps || ClimatePhrases.supports(id) || id == VolumePhrases.COMMAND_ID ->
        SequenceCommandPolicy.ANY
    id in finalSteps || id.startsWith("Call_") -> SequenceCommandPolicy.LAST_ONLY
    else -> SequenceCommandPolicy.SINGLE_ONLY
}

private val localSteps = setOf("MediaPlay", "MediaPause", "MediaNext", "MediaPrevious", "VolumeUp", "VolumeDown",
    "VolumeMute", "Like", "RemoveLike", "DisLike", "Weather", "WeatherTomorrow")
private val finalSteps = setOf("Assistant", "Camera", "Music", "Navigator", "Radio", "Split", "ControlsSettings", "SystemSettings")
private val reviewedComfortSteps = setOf(
    "IncreaseTemperature", "DecreaseTemperature", "FastIncreaseTemperature", "FastDecreaseTemperature",
    "FlowBody", "FlowBodyLegs", "FlowLegs", "FlowWindow", "FlowWindowLegs", "EnableWheelHeat", "DisableWheelHeat",
    "DisableDriverMassage", "EnableMassageDriverLevelDefault", "EnableMassageDriverLevelOne",
    "EnableMassageDriverLevelTwo", "EnableMassageDriverLevelTree", "DisablePassengerMassage",
    "EnableMassagePassengerLevelDefault", "EnableMassagePassengerLevelOne", "EnableMassagePassengerLevelTwo",
    "EnableMassagePassengerLevelTree",
)

internal fun VoiceOutcome.sequenceIsUncertain() = this in setOf(VoiceOutcome.VEHICLE_SENT, VoiceOutcome.VEHICLE_PARTIAL,
    VoiceOutcome.VEHICLE_UNCONFIRMED, VoiceOutcome.UNAVAILABLE)
