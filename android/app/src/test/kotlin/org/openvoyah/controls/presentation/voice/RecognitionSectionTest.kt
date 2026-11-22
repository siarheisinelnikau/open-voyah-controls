package org.openvoyah.controls.presentation.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.data.voice.SpeechModelCatalog
import org.openvoyah.controls.domain.voice.SpeechEngine

class RecognitionSectionTest {
    @Test fun recognitionIsOneOptionRightAfterTryingTheMicrophone() {
        assertEquals(listOf(VoiceOption.TRY_VOICE, VoiceOption.RECOGNITION, VoiceOption.LEVELS, VoiceOption.WINDOWS,
            VoiceOption.VEHICLE, VoiceOption.AUDIO, VoiceOption.RECOVERY), VoiceOption.entries)
    }

    @Test fun everySpeechEngineHasARow() {
        assertEquals(SpeechEngine.entries, RecognitionEngine.entries.map { it.engine })
    }

    @Test fun zipformerRunsOnlyForRussianWithItsModelOnTheDevice() {
        val zipformer = SpeechModelCatalog.ZIPFORMER_RU.id
        assertEquals(RecognitionEngine.ZIPFORMER, RecognitionEngine.effective("zipformer", "ru", setOf(zipformer)))
        assertEquals("No model yet", RecognitionEngine.VOSK, RecognitionEngine.effective("zipformer", "ru", emptySet()))
        assertEquals("English has no Zipformer", RecognitionEngine.VOSK, RecognitionEngine.effective("zipformer", "en", setOf(zipformer)))
        assertEquals(RecognitionEngine.VOSK, RecognitionEngine.effective("vosk", "ru", setOf(zipformer)))
        assertEquals("Unknown stored value", RecognitionEngine.VOSK, RecognitionEngine.effective("other", "ru", setOf(zipformer)))
        assertTrue(SpeechEngine.VOSK.supports("kk"))
    }
}
