package org.openvoyah.controls.platform.voice

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import junit.framework.TestCase
import java.util.Locale

class OfflineVoicePolicyTest : TestCase() {
    fun testOnlyInstalledOfflineVoicesForRecognitionLanguageAreListedInStableOrder() {
        val good = voice("good", "en-GB", Voice.QUALITY_HIGH)
        val best = voice("best", "en-US", Voice.QUALITY_VERY_HIGH)
        val network = voice("network", "en-US", Voice.QUALITY_VERY_HIGH, network = true)
        val notInstalled = voice("missing", "en-US", Voice.QUALITY_VERY_HIGH, installed = false)
        val russian = voice("russian", "ru-RU", Voice.QUALITY_VERY_HIGH)
        assertEquals(listOf(best, good), eligibleOfflineVoices(listOf(good, network, russian, notInstalled, best), "en"))
        assertEquals(listOf(russian), eligibleOfflineVoices(listOf(good, russian), "ru"))
    }

    fun testPreferredVoiceIsTriedFirstAndAutomaticFillsMissingOrRejectedChoices() {
        val preferred = voice("preferred", "en-GB", Voice.QUALITY_NORMAL)
        val automatic = voice("best", "en-US", Voice.QUALITY_VERY_HIGH)
        val voices = eligibleOfflineVoices(listOf(preferred, automatic), "en")
        val attempts = mutableListOf<String>()
        assertTrue(activateOfflineVoice(voices, "preferred") { attempts += it.name; it == automatic })
        assertEquals(listOf("preferred", "best"), attempts)
        attempts.clear()
        assertTrue(activateOfflineVoice(voices, "removed") { attempts += it.name; true })
        assertEquals(listOf("best"), attempts)
        assertFalse(activateOfflineVoice(emptyList(), "preferred") { error("No engine call expected") })
    }

    private fun voice(name: String, language: String, quality: Int, network: Boolean = false, installed: Boolean = true) =
        Voice(name, Locale.forLanguageTag(language), quality, Voice.LATENCY_NORMAL, network,
            if (installed) emptySet() else setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
}
