package org.openvoyah.controls

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.test.AndroidTestCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.openvoyah.controls.core.voice.VoiceAudioOptions.ListeningWindow
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.domain.voice.SpeechEvent
import org.openvoyah.controls.domain.voice.VoiceOutcome
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

    fun testSelectedListeningLimitsBoundCaptureAndReplay() = runBlocking {
        val settings = VoiceSettings(context)
        val language = settings.language()
        val grammar = settings.withContacts().grammar()
        for ((window, replay) in listOf(ListeningWindow.THREE_SECONDS to false, ListeningWindow.SIX_SECONDS to true)) {
            val input = OfflineVoiceInput(context, duckMedia = false, naturalPhrases = replay, listeningWindow = window)
            var started = 0L
            val result = withTimeout(30_000) {
                input.listen(language, grammar).onEach { if (it is SpeechEvent.Listening) started = SystemClock.elapsedRealtime() }
                    .first { it !is SpeechEvent.Listening }
            }
            assertTrue("Microphone did not start", started > 0L)
            assertTrue("Capture exceeded ${window.seconds()} s", SystemClock.elapsedRealtime() - started <= window.millis() + 1000)
            assertTrue(result.toString(), result is SpeechEvent.Failed)
        }
    }

    fun testPendingReadySoundCanBeCancelledBeforeListening() = runBlocking {
        val settings = VoiceSettings(context)
        val cueStarted = CompletableDeferred<Unit>()
        val input = OfflineVoiceInput(context, duckMedia = false, readyCue = {
            cueStarted.complete(Unit)
            awaitCancellation()
        })
        val job = launch {
            val event = input.listen(settings.language(), settings.withContacts().grammar()).first()
            fail("Capture advanced while the cue was pending: $event")
        }
        try { withTimeout(30_000) { cueStarted.await() } }
        finally { job.cancelAndJoin() }
    }

    fun testAudioFocusLossReleasesPendingReadyCueBeforeRecording() = runBlocking {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val settings = VoiceSettings(context)
        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val input = OfflineVoiceInput(context, duckMedia = true, readyCue = {
            started.complete(Unit)
            try { awaitCancellation() } finally { released.complete(Unit) }
        })
        val event = async { input.listen(settings.language(), settings.withContacts().grammar()).first() }
        val manager = context.getSystemService(AudioManager::class.java)!!
        val interruption = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener { }.build()
        try {
            withTimeout(30_000) { started.await() }
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(interruption))
            val result = withTimeout(4_000) { event.await() } as? SpeechEvent.Failed
            assertEquals(VoiceOutcome.AUDIO_INTERRUPTED, result?.reason)
            assertTrue(released.isCompleted)
        } finally {
            manager.abandonAudioFocusRequest(interruption)
            event.cancelAndJoin()
        }
    }

    fun testEndCueRunsAfterListeningBeforeFinalEvent() = runBlocking {
        val settings = VoiceSettings(context)
        val listening = CompletableDeferred<Unit>()
        val cueStarted = CompletableDeferred<Unit>()
        val releaseCue = CompletableDeferred<Unit>()
        val input = OfflineVoiceInput(context, duckMedia = false, endCue = {
            cueStarted.complete(Unit)
            releaseCue.await()
        })
        val final = async {
            input.listen(settings.language(), settings.withContacts().grammar())
                .onEach { if (it is SpeechEvent.Listening) listening.complete(Unit) }
                .first { it !is SpeechEvent.Listening }
        }
        try {
            withTimeout(30_000) { cueStarted.await() }
            assertTrue(listening.isCompleted)
            assertFalse(final.isCompleted)
            releaseCue.complete(Unit)
            assertTrue(withTimeout(3_000) { final.await() } is SpeechEvent.Failed)
        } finally { final.cancelAndJoin() }
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
