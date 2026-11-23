package org.openvoyah.controls.platform.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.speech.tts.TextToSpeech
import android.test.InstrumentationTestCase
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.openvoyah.controls.core.voice.VoiceAudioOptions
import org.openvoyah.controls.core.voice.VoiceAudioOptions.ReadyCueStyle
import org.openvoyah.controls.core.voice.VoiceAudioOptions.SpokenReplyChannel
import org.openvoyah.controls.domain.voice.VoiceFeedback
import org.openvoyah.controls.domain.voice.VoiceOutcome
import org.openvoyah.controls.fixtures.SilentTtsFixture

/** Explicitly binds the test package without changing Android's default engine. */
class OfflineVoiceFeedbackEngineTest : InstrumentationTestCase() {
    fun testSelectedVoiceRateAndGainReachTheSilentEngine() = runBlocking {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val app = instrumentation.targetContext
        val testEngine = instrumentation.context.packageName
        val service = app.packageManager.resolveService(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            .setPackage(testEngine), PackageManager.MATCH_DEFAULT_ONLY)?.serviceInfo
        assertEquals(SilentTtsFixture::class.java.name, service?.name)
        val replies = LinkedBlockingQueue<Intent>()
        val broadcast = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { replies.offer(intent) }
        }
        ContextCompat.registerReceiver(app, broadcast, IntentFilter(SilentTtsFixture.REPLY), ContextCompat.RECEIVER_EXPORTED)
        val session = withContext(Dispatchers.Main) {
            OfflineVoiceFeedback(app, "en") { context, listener -> TextToSpeech(context, listener, testEngine) }
        }
        try {
            val voices = withTimeout(8_000L) { session.choices.filterNotNull().first() }
            assertEquals(listOf(SilentTtsFixture.AUTOMATIC, SilentTtsFixture.PREFERRED), voices.map { it.name })

            suspend fun checkSample(preference: String, expected: String, rate: Int, volume: Int,
                sample: String = SilentTtsFixture.SAMPLE_PREFIX + UUID.randomUUID(), channel: SpokenReplyChannel = SpokenReplyChannel.ASSISTANT) {
                val options = audioOptions(rate, volume, channel)
                assertTrue(withContext(Dispatchers.Main) { session.speak(sample, options, preference) })
                val result = checkNotNull(replies.poll(6, TimeUnit.SECONDS)) { "The fixture did not accept a synthesis request" }
                assertEquals(sample, result.getStringExtra("sample"))
                assertEquals(expected, result.getStringExtra("voice"))
                assertEquals(rate, result.getIntExtra("rate", -1))
                assertEquals(options.speechGain(), result.getFloatExtra("gain", -1f), 0f)
                assertEquals(if (channel == SpokenReplyChannel.MEDIA) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ASSISTANT,
                    result.getIntExtra("usage", -1))
                assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, result.getIntExtra("contentType", -1))
            }

            checkSample(SilentTtsFixture.PREFERRED, SilentTtsFixture.PREFERRED, 75, 40)
            checkSample("removed", SilentTtsFixture.AUTOMATIC, 125, 65, channel = SpokenReplyChannel.MEDIA)
            val outcome = VoiceFeedback.Outcome(VoiceOutcome.VEHICLE_ACCEPTED)
            val english = outcome.speechText(app.forRecognitionLanguage("en"))
            assertEquals(SilentTtsFixture.ACCEPTED_OUTCOME, english)
            assertEquals("Автомобиль принял запрос.",
                outcome.speechText(app.forRecognitionLanguage("ru")))
            checkSample(SilentTtsFixture.PREFERRED, SilentTtsFixture.PREFERRED, 100, 65, english)

            // The ready cue uses its own gain, even when spoken replies are muted.
            val ready = audioOptions(125, 0, SpokenReplyChannel.MEDIA)
            suspend fun checkReady(voice: OfflineVoiceFeedback, preference: String, expected: String, custom: String = "") {
                assertEquals(ReadyCueStyle.SPOKEN, withTimeout(4_000L) {
                    playReadyVoiceCue(app, ready, voice, preference, custom) { true }
                })
                val heard = checkNotNull(replies.poll(3, TimeUnit.SECONDS))
                assertEquals(expected, heard.getStringExtra("sample"))
                assertEquals(preference, heard.getStringExtra("voice"))
                assertEquals(100, heard.getIntExtra("rate", -1))
            }
            checkReady(session, SilentTtsFixture.PREFERRED, SilentTtsFixture.READY_EN)
            checkReady(session, SilentTtsFixture.PREFERRED, SilentTtsFixture.READY_CUSTOM, SilentTtsFixture.READY_CUSTOM)
            val russian = withContext(Dispatchers.Main) {
                OfflineVoiceFeedback(app, "ru") { context, listener -> TextToSpeech(context, listener, testEngine) }
            }
            try {
                assertEquals(listOf(SilentTtsFixture.RUSSIAN), withTimeout(8_000L) { russian.choices.filterNotNull().first() }.map { it.name })
                checkReady(russian, SilentTtsFixture.RUSSIAN, SilentTtsFixture.READY_RU)
            } finally { withContext(NonCancellable + Dispatchers.Main) { russian.close() } }
            val unavailable = withContext(Dispatchers.Main) { OfflineVoiceFeedback(app, "en") { _, _ -> error("No test engine") } }
            try {
                assertEquals(ReadyCueStyle.TONE, playReadyVoiceCue(app, ready, unavailable, "") { true })
                assertNull(playReadyVoiceCue(app, ready, unavailable, "") { false })
                assertNull(playReadyVoiceCue(app, VoiceAudioOptions(100, 100, true, 0), unavailable, "") { true })
                assertNull(playReadyVoiceCue(app, VoiceAudioOptions.DEFAULTS, unavailable, "") { true })
            } finally { withContext(NonCancellable + Dispatchers.Main) { unavailable.close() } }
            val tone = VoiceAudioOptions(100, 100, true, 30)
            assertEquals(ReadyCueStyle.TONE, playReadyVoiceCue(app, tone, session, "", SilentTtsFixture.READY_CUSTOM) { true })
            assertNull(playReadyVoiceCue(app, VoiceAudioOptions.DEFAULTS, session, "", SilentTtsFixture.READY_CUSTOM) { true })
            assertTrue(replies.isEmpty())
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { session.close() }
            app.unregisterReceiver(broadcast)
        }
    }

    private fun audioOptions(rate: Int, volume: Int, channel: SpokenReplyChannel) = VoiceAudioOptions(
        rate, volume, true, 35, VoiceAudioOptions.FeedbackStyle.READBACK, VoiceAudioOptions.EndCue.OFF,
        VoiceAudioOptions.ListeningWindow.FOUR_SECONDS, VoiceAudioOptions.ResultDuration.TWO_SECONDS,
        true, null, ReadyCueStyle.SPOKEN, channel,
    )
}
