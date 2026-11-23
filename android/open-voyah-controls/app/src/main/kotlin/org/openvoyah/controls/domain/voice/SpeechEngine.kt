package org.openvoyah.controls.domain.voice

/**
 * A speech recognizer the app can run, with the recognition languages it has a model for. [key] is the stored value.
 * Vosk keeps a bundled model for Russian and English; Zipformer always uses a downloaded model.
 */
enum class SpeechEngine(val key: String, val languages: Set<String>) {
    VOSK("vosk", setOf("ru", "en", "kk")),
    ZIPFORMER("zipformer", setOf("ru"));

    fun supports(language: String): Boolean = language in languages

    companion object {
        /** Recognition languages in the order the settings list them. */
        val LANGUAGES = listOf("ru", "en", "kk")

        fun fromKey(key: String?): SpeechEngine? = entries.firstOrNull { it.key == key }

        /** The stored engine when it supports [language], otherwise Vosk. */
        fun forLanguage(stored: SpeechEngine?, language: String): SpeechEngine =
            stored?.takeIf { it.supports(language) } ?: VOSK
    }
}
