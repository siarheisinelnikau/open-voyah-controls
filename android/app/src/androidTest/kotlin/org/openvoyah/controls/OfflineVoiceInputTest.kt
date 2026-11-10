package org.openvoyah.controls

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.test.AndroidTestCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.domain.voice.SpeechEvent
import org.openvoyah.controls.platform.voice.OfflineVoiceInput
import org.openvoyah.controls.platform.voice.decodeEndpoint

/** Requires microphone permission on the isolated emulator. */
class OfflineVoiceInputTest : AndroidTestCase() {
    fun testRussianModelStarts() = startModel("ru")
    fun testEnglishModelStarts() = startModel("en")

    private fun startModel(language: String) = runBlocking {
        val settings = VoiceSettings(context)
        val previous = settings.exportJson()
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        try {
            settings.language(language)
            val input = OfflineVoiceInput(context, false)
            val event = withTimeout(30_000) {
                input.listen(language, settings.withContacts().grammar()).first {
                    it is SpeechEvent.Failed || it is SpeechEvent.Listening
                }
            }
            assertTrue(event.toString(), event is SpeechEvent.Listening)
        } finally {
            settings.importJson(previous)
        }
    }

    fun testSilentMicrophoneStopsWithinTheCaptureBudget() = runBlocking {
        val settings = VoiceSettings(context)
        var recordingStarted = 0L
        val end = withTimeout(30_000) {
            OfflineVoiceInput(context, false).listen(settings.language(), settings.withContacts().grammar())
                .onEach { if (it is SpeechEvent.Listening) recordingStarted = SystemClock.elapsedRealtime() }
                .first { it !is SpeechEvent.Listening }
        }
        assertTrue("Microphone did not start", recordingStarted > 0)
        assertTrue("No-speech cutoff exceeded", SystemClock.elapsedRealtime() - recordingStarted <= 5_000)
        assertTrue(end.toString(), end is SpeechEvent.Failed)
    }

    fun testOnlyConfidentFinalWordsCanDispatch() {
        fun endpoint(text: String, score: String) =
            decodeEndpoint("""{"text":"$text","result":[{"word":"massage","conf":$score}]}""")
        assertEquals(SpeechEvent.Recognized("massage"), endpoint("massage", "0.93"))
        assertTrue(endpoint("massage", "0.41") is SpeechEvent.Failed)
        assertTrue(endpoint("[unk] massage", "0.99") is SpeechEvent.Failed)
        assertTrue(decodeEndpoint("""{"text":"massage"}""") is SpeechEvent.Failed)
    }
}
