package org.openvoyah.controls.presentation.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.domain.voice.SpeechEngine

class RecognitionSectionTest {
    @Test fun recognitionIsOneOptionRightAfterTryingTheMicrophone() {
        assertEquals(listOf(VoiceOption.TRY_VOICE, VoiceOption.RECOGNITION, VoiceOption.LEVELS, VoiceOption.WINDOWS,
            VoiceOption.VEHICLE, VoiceOption.AUDIO, VoiceOption.RECOVERY), VoiceOption.entries)
    }

    @Test fun everySpeechEngineHasARow() {
        assertEquals(SpeechEngine.entries, RecognitionEngine.entries.map { it.engine })
    }

    @Test fun bundledZipformerIsTheDefaultForRussianOnSupportedDevices() {
        assertEquals(RecognitionEngine.ZIPFORMER, RecognitionEngine.effective(null, "ru", true))
        assertEquals(RecognitionEngine.ZIPFORMER, RecognitionEngine.effective("zipformer", "ru", true))
        assertEquals("Unsupported ABI", RecognitionEngine.VOSK, RecognitionEngine.effective("zipformer", "ru", false))
        assertEquals("English uses Vosk", RecognitionEngine.VOSK, RecognitionEngine.effective("zipformer", "en", true))
        assertEquals("Kazakh uses Vosk", RecognitionEngine.VOSK, RecognitionEngine.effective("zipformer", "kk", true))
        assertEquals("Explicit choice is preserved", RecognitionEngine.VOSK, RecognitionEngine.effective("vosk", "ru", true))
        assertEquals("Unknown stored value", RecognitionEngine.ZIPFORMER, RecognitionEngine.effective("other", "ru", true))
        assertEquals("Failed to start in this process", RecognitionEngine.VOSK,
            RecognitionEngine.effective("zipformer", "ru", true, setOf(SpeechEngine.ZIPFORMER)))
        assertTrue(SpeechEngine.VOSK.supports("kk"))
    }
}
