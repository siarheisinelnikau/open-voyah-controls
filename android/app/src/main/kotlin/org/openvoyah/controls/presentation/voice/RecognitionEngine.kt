package org.openvoyah.controls.presentation.voice

import org.openvoyah.controls.R
import org.openvoyah.controls.data.voice.SpeechModelCatalog
import org.openvoyah.controls.domain.voice.SpeechEngine

/** How the Recognition section names each [SpeechEngine]. */
internal enum class RecognitionEngine(val engine: SpeechEngine, val label: Int, val detail: Int) {
    VOSK(SpeechEngine.VOSK, R.string.speech_engine_vosk, R.string.speech_engine_vosk_detail),
    ZIPFORMER(SpeechEngine.ZIPFORMER, R.string.speech_engine_zipformer, R.string.speech_engine_zipformer_detail);

    companion object {
        fun of(engine: SpeechEngine) = entries.first { it.engine == engine }

        /**
         * The engine that runs for [language]: the stored choice when it supports the language, its model is
         * downloaded ([installed] model ids) and it has not [failed] to start, otherwise Vosk. Mirrors
         * SpeechModels.engineFor without touching storage.
         */
        fun effective(stored: String, language: String, installed: Set<String>,
                      failed: Set<SpeechEngine> = emptySet()): RecognitionEngine {
            val chosen = SpeechEngine.forLanguage(SpeechEngine.fromKey(stored), language)
            if (chosen in failed) return VOSK
            val model = SpeechModelCatalog.forEngine(chosen, language)
            return of(if (chosen != SpeechEngine.VOSK && (model == null || model.id !in installed)) SpeechEngine.VOSK else chosen)
        }
    }
}

internal fun recognitionLanguageName(language: String): Int = when (language) {
    "en" -> R.string.language_name_english
    "kk" -> R.string.language_name_kazakh
    else -> R.string.language_name_russian
}
