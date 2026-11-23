package org.openvoyah.controls.platform.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.VoiceCatalog
import org.openvoyah.controls.core.voice.VoiceAudioOptions.ListeningWindow
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.audio.DuckTrigger
import org.openvoyah.controls.platform.audio.MediaDuck
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.domain.voice.SpeechEngine
import org.openvoyah.controls.domain.voice.SpeechEvent
import org.openvoyah.controls.domain.voice.VoiceInput
import org.openvoyah.controls.domain.voice.VoiceOutcome

/** The speech lock of [SpeechModels] protects the microphone and native model across both voice entry points. */
class OfflineVoiceInput(
    context: Context,
    private val duckMedia: Boolean,
    private val naturalPhrases: Boolean = false,
    private val readyCue: (suspend () -> Unit)? = null,
    private val endCue: (suspend () -> Unit)? = null,
    private val listeningWindow: ListeningWindow = ListeningWindow.FOUR_SECONDS,
    /** Null uses the engine chosen in Recognition when its model is on the device, otherwise Vosk. */
    private val engine: SpeechEngine? = null,
) : VoiceInput {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val sampleLimit = listeningWindow.maxSamples(SAMPLE_RATE)

    override fun listen(language: String, grammar: Set<String>): Flow<SpeechEvent> = flow {
        var stage = "microphone_lock"
        try {
            // The model and recognizer stay prepared after the request; see SpeechModels.
            val speech = SpeechModels.of(context)
            val result = speech.lock.withLock {
                capture(speech, language, grammar, { stage = it }) { fallback ->
                    emit(SpeechEvent.Listening(UiText.get(
                        if (fallback) R.string.voice_listening_fallback else R.string.voice_listening_action)))
                }
            }
            emit(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // The popup shows the text; only this entry says which capture step failed.
            ControlsLog.warn("voice_capture", failure, "stage", stage)
            emit(error(failure))
        } catch (failure: LinkageError) {
            ControlsLog.warn("voice_capture", failure, "stage", stage)
            emit(error(failure))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun repair(language: String) = withContext(Dispatchers.IO) {
        SpeechModels.repair(context, language)
    }

    private suspend fun capture(
        speech: PreparedSpeech<LoadedSpeechModel, SpeechDecoder>,
        language: String,
        grammar: Set<String>,
        stage: (String) -> Unit,
        listening: suspend (fallback: Boolean) -> Unit,
    ): SpeechEvent {
        stage("permission")
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IOException(UiText.get(R.string.ui_138))
        }
        val captureContext = currentCoroutineContext()
        stage("model_load")
        // Usually prepared at process start; a warm-up still running is awaited through the lock, never repeated.
        val engine = engine ?: SpeechModels.engineFor(context, VoiceSettings(context), language)
        val recognition = speechGrammar(grammar)
        // An optional engine that cannot start falls back to Vosk for this request and is skipped until chosen again.
        val (prepared, fallback) = withVoskFallback(engine, { candidate ->
            speech.prepare(SpeechModels.key(candidate, language), recognition, "request") { captureContext.isActive }
        }, SpeechModels::recordFailure)
        captureContext.ensureActive()
        // The captured audio stays available after the microphone is released: Vosk replays a phrase that is not a
        // command without the grammar, so the popup can show what was heard, and the Zipformer decodes the phrase.
        val replay = ShortArray(sampleLimit)
        val focusLost = CompletableDeferred<Unit>()
        stage("recognizer")
        val decoder = checkNotNull(prepared.recognizer)
        decoder.reset()
        val captured = run {
            stage("buffer_size")
            val size = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (size <= 0) throw IOException(UiText.get(R.string.ui_140))
            stage("recorder_init")
            val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(size, 6400))
            val manager = context.getSystemService(AudioManager::class.java)
            var focus: AudioFocusRequest? = null
            var ducking = false
            try {
                if (audio.state != AudioRecord.STATE_INITIALIZED) throw IOException(UiText.get(R.string.ui_141))
                captureContext.ensureActive()
                stage("ready_cue")
                if (duckMedia && manager != null) focus = requestFocus(manager, focusLost)
                // The OEM focus engine decides itself whether focus ducks; the media level is lowered directly.
                if (duckMedia) {
                    MediaDuck.begin(context, DuckTrigger.LISTENING)
                    ducking = true
                }
                if (readyCue != null) {
                    if (!focusLost.isCompleted) coroutineScope {
                        val cue = launch { readyCue.invoke() }
                        select<Unit> {
                            focusLost.onAwait { cue.cancel() }
                            cue.onJoin { }
                        }
                    }
                    captureContext.ensureActive()
                }
                if (focusLost.isCompleted) return@run Captured(interrupted())
                stage("start_recording")
                audio.startRecording()
                if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException(UiText.get(R.string.ui_142))
                val deadline = SystemClock.elapsedRealtime() + listeningWindow.millis()
                listening(fallback)
                stage("read")
                read(audio, decoder, focusLost, replay, deadline)
            } finally {
                try {
                    if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop()
                } finally {
                    audio.release()
                    focus?.let { manager?.abandonAudioFocusRequest(it) }
                    // Before the command runs, so a volume command steps from the restored level.
                    if (ducking) MediaDuck.end(context, DuckTrigger.LISTENING)
                }
            }
        }
        // The recorder is released and the capture lock still excludes a second microphone request.
        stage("transcript")
        captureContext.ensureActive()
        val cue = endCue.takeUnless { focusLost.isCompleted }
        return alongsideCue(cue) { decoder.finish(captured, replay, naturalPhrases) }
    }

    private fun requestFocus(manager: AudioManager, focusLost: CompletableDeferred<Unit>): AudioFocusRequest? {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    focusLost.complete(Unit)
                }
            }, main).build()
        return request.takeIf { manager.requestAudioFocus(it) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }
    }

    private suspend fun read(
        audio: AudioRecord,
        decoder: SpeechDecoder,
        focusLost: CompletableDeferred<Unit>,
        replay: ShortArray,
        deadline: Long,
    ): Captured {
        var recorded = 0
        val samples = ShortArray(1600)
        val captureContext = currentCoroutineContext()
        while (!focusLost.isCompleted && recorded < sampleLimit && SystemClock.elapsedRealtime() < deadline) {
            captureContext.ensureActive()
            val count = audio.read(samples, 0, minOf(samples.size, sampleLimit - recorded), AudioRecord.READ_NON_BLOCKING)
            if (count < 0) throw IOException(UiText.get(R.string.ui_144) + count)
            if (count == 0) {
                delay(20)
                continue
            }
            samples.copyInto(replay, recorded, endIndex = count)
            recorded += count
            val ended = decoder.accept(samples, count, recorded)
            if (ended != null) {
                if (focusLost.isCompleted) break
                return ended
            }
        }
        if (focusLost.isCompleted) return Captured(interrupted())
        return decoder.cutOff(recorded)
    }

    private fun error(failure: Throwable) = SpeechEvent.Failed(
        UiText.get(R.string.ui_146) + failure.message.orEmpty() + UiText.get(R.string.ui_147)
    )

    private companion object {
        const val SAMPLE_RATE = SpeechModels.SAMPLE_RATE
    }
}

/**
 * Decodes the phrase while the end sound plays instead of after it. The result is returned once both are done, so
 * spoken feedback still follows the end sound, and the command waits for the longer of the two, not their sum.
 */
internal suspend fun <T> alongsideCue(cue: (suspend () -> Unit)?, work: suspend () -> T): T {
    if (cue == null) return work()
    return coroutineScope {
        val playing = launch { cue() }
        val result = work()
        playing.join()
        currentCoroutineContext().ensureActive()
        result
    }
}

private fun interrupted() = SpeechEvent.Failed(UiText.get(R.string.voice_audio_interrupted), VoiceOutcome.AUDIO_INTERRUPTED)

/** The words of a free pass for display, without unknown-word markers, or null when nothing was heard. */
internal fun heardText(json: String): String? {
    val words = JSONObject(json).optString("text").split(' ').filter { it.isNotBlank() && it != "[unk]" }
    return words.joinToString(" ").take(MAX_HEARD_CHARACTERS).trim().takeUnless { VoiceCatalog.isBlank(it) }
}

internal const val MAX_HEARD_CHARACTERS = 120

internal fun decodeEndpoint(json: String): SpeechEvent {
    val result = JSONObject(json)
    val text = result.optString("text")
    if (VoiceCatalog.isBlank(text)) return noSpeech()
    val words = result.optJSONArray("result")
    val confident = words != null && words.length() > 0 && (0 until words.length()).all { index ->
        val score = words.optJSONObject(index)?.optDouble("conf") ?: Double.NaN
        score.isFinite() && score >= 0.65
    }
    if ("[unk]" in text || !confident) return unrecognized()
    return SpeechEvent.Recognized(text)
}
