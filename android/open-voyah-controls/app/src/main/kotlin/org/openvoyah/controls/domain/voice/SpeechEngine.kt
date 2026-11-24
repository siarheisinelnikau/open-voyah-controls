package org.openvoyah.controls.domain.voice

/**
 * A speech recognizer the app can run, with the recognition languages it has a model for. [key] is the stored value.
 * Zipformer uses the bundled Russian model; Vosk uses separately installed models.
 */
enum class SpeechEngine(val key: String, val languages: Set<String>) {
    VOSK("vosk", setOf("ru", "en", "kk")),
    ZIPFORMER("zipformer", setOf("ru"));

    fun supports(language: String): Boolean = language in languages

    companion object {
        /** Recognition languages in the order the settings list them. */
        val LANGUAGES = listOf("ru", "en", "kk")

        fun fromKey(key: String?): SpeechEngine? = entries.firstOrNull { it.key == key }

        @JvmField val DEFAULT = ZIPFORMER

        /** Resolve the preference against the language and whether the Zipformer runtime is usable. */
        fun forLanguage(stored: SpeechEngine?, language: String, zipformerAvailable: Boolean): SpeechEngine =
            if ((stored ?: DEFAULT) == ZIPFORMER && ZIPFORMER.supports(language) && zipformerAvailable) ZIPFORMER else VOSK
    }
}
