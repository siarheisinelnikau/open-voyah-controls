package org.openvoyah.controls.presentation.voice

import org.openvoyah.controls.R
import org.openvoyah.controls.domain.voice.SpeechEngine

/** How the Recognition section names each [SpeechEngine]. */
internal enum class RecognitionEngine(val engine: SpeechEngine, val label: Int, val detail: Int) {
    VOSK(SpeechEngine.VOSK, R.string.speech_engine_vosk, R.string.speech_engine_vosk_detail),
    ZIPFORMER(SpeechEngine.ZIPFORMER, R.string.speech_engine_zipformer, R.string.speech_engine_zipformer_detail);

    companion object {
        fun of(engine: SpeechEngine) = entries.first { it.engine == engine }

        fun effective(stored: String?, language: String, zipformerSupported: Boolean,
                      failed: Set<SpeechEngine> = emptySet()): RecognitionEngine =
            of(SpeechEngine.forLanguage(SpeechEngine.fromKey(stored), language,
                zipformerSupported && SpeechEngine.ZIPFORMER !in failed))
    }
}

internal fun recognitionLanguageName(language: String): Int = when (language) {
    "en" -> R.string.language_name_english
    "kk" -> R.string.language_name_kazakh
    else -> R.string.language_name_russian
}
