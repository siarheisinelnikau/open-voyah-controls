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
import org.openvoyah.controls.domain.voice.SpeechEvent
import org.openvoyah.controls.domain.voice.VoiceInput
import org.openvoyah.controls.domain.voice.VoiceOutcome
import org.vosk.Model
import org.vosk.Recognizer

/** The speech lock of [SpeechModels] protects the microphone and native model across both voice entry points. */
class OfflineVoiceInput(
    context: Context,
    private val duckMedia: Boolean,
    private val naturalPhrases: Boolean = false,
    private val readyCue: (suspend () -> Unit)? = null,
    private val endCue: (suspend () -> Unit)? = null,
    private val listeningWindow: ListeningWindow = ListeningWindow.FOUR_SECONDS,
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
                capture(speech, language, grammar, { stage = it }) {
                    emit(SpeechEvent.Listening(UiText.get(R.string.voice_listening_action)))
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
        speech: PreparedSpeech<Model, Recognizer>,
        language: String,
        grammar: Set<String>,
        stage: (String) -> Unit,
        listening: suspend () -> Unit,
    ): SpeechEvent {
        stage("permission")
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IOException(UiText.get(R.string.ui_138))
        }
        val captureContext = currentCoroutineContext()
        stage("model_load")
        // Usually prepared at process start; a warm-up still running is awaited through the lock, never repeated.
        val prepared = speech.prepare(language, speechGrammar(grammar), "request") { captureContext.isActive }
        val model = prepared.model
        captureContext.ensureActive()
        // Speech that is not a command is replayed once without the grammar, so the popup can show what was heard.
        val replay = ShortArray(sampleLimit)
        val focusLost = CompletableDeferred<Unit>()
        stage("recognizer")
        val captured = checkNotNull(prepared.recognizer).let { recognizer ->
            stage("buffer_size")
            val size = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (size <= 0) throw IOException(UiText.get(R.string.ui_140))
            recognizer.setWords(true)
            recognizer.setEndpointerDelays(2.5f, 0.6f, 20f)
            stage("recorder_init")
            val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(size, 6400))
            val manager = context.getSystemService(AudioManager::class.java)
            var focus: AudioFocusRequest? = null
            try {
                if (audio.state != AudioRecord.STATE_INITIALIZED) throw IOException(UiText.get(R.string.ui_141))
                captureContext.ensureActive()
                stage("ready_cue")
                if (duckMedia && manager != null) focus = requestFocus(manager, focusLost)
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
                if (focusLost.isCompleted) return@let Captured(interrupted())
                stage("start_recording")
                audio.startRecording()
                if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException(UiText.get(R.string.ui_142))
                val deadline = SystemClock.elapsedRealtime() + listeningWindow.millis()
                listening()
                stage("read")
                read(audio, recognizer, focusLost, replay, deadline)
            } finally {
                try {
                    if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop()
                } finally {
                    audio.release()
                    focus?.let { manager?.abandonAudioFocusRequest(it) }
                }
            }
        }
        // The recorder is released and the capture lock still excludes a second microphone request.
        if (endCue != null && !focusLost.isCompleted) {
            stage("end_cue")
            captureContext.ensureActive()
            endCue.invoke()
            captureContext.ensureActive()
        }
        if (captured.replaySamples > 0) {
            stage("transcript")
            captureContext.ensureActive()
            transcribe(model, replay, captured.replaySamples, naturalPhrases && captured.completed)?.let { return it }
        }
        return captured.event
    }

    /**
     * One pass without the grammar over the captured audio. Only a completed, confident and short phrase may go on to
     * online interpretation; anything else heard is shown and never acted on.
     */
    private fun transcribe(model: Model, samples: ShortArray, count: Int, natural: Boolean): SpeechEvent? =
        Recognizer(model, SAMPLE_RATE.toFloat()).use { recognizer ->
            recognizer.setWords(true)
            val result = if (recognizer.acceptWaveForm(samples, count)) recognizer.result else recognizer.finalResult
            val heard = heardText(result) ?: return@use null
            val text = (decodeEndpoint(result) as? SpeechEvent.Recognized)?.text
            if (natural && text != null && text.length <= 160 && text.count { it == ' ' } <= 13) SpeechEvent.Natural(text)
            else SpeechEvent.Unmatched(heard)
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

    /** [completed] is true when the speaker finished the phrase, false when the listening window cut it off. */
    private data class Captured(val event: SpeechEvent, val replaySamples: Int = 0, val completed: Boolean = false)

    private suspend fun read(
        audio: AudioRecord,
        recognizer: Recognizer,
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
            if (recognizer.acceptWaveForm(samples, count)) {
                if (focusLost.isCompleted) break
                val endpoint = recognizer.result
                val event = decodeEndpoint(endpoint)
                val unclear = event is SpeechEvent.Failed && JSONObject(endpoint).optString("text").isNotBlank()
                return Captured(event, if (unclear) recorded else 0, completed = true)
            }
        }
        if (focusLost.isCompleted) return Captured(interrupted())
        val partial = JSONObject(recognizer.partialResult).optString("partial")
        // An unfinished phrase never reaches an action; its transcript only explains the result.
        return if (partial.isBlank()) Captured(noSpeech()) else Captured(unrecognized(), recorded)
    }

    private fun error(failure: Throwable) = SpeechEvent.Failed(
        UiText.get(R.string.ui_146) + failure.message.orEmpty() + UiText.get(R.string.ui_147)
    )

    private companion object {
        const val SAMPLE_RATE = SpeechModels.SAMPLE_RATE
    }
}

private fun interrupted() = SpeechEvent.Failed(UiText.get(R.string.voice_audio_interrupted), VoiceOutcome.AUDIO_INTERRUPTED)
private fun noSpeech() = SpeechEvent.Failed(UiText.get(R.string.voice_no_speech), VoiceOutcome.NO_SPEECH)
private fun unrecognized() = SpeechEvent.Failed(UiText.get(R.string.unknown_command), VoiceOutcome.UNRECOGNIZED)

/** The words of a free pass for display, without unknown-word markers, or null when nothing was heard. */
internal fun heardText(json: String): String? {
    val words = JSONObject(json).optString("text").split(' ').filter { it.isNotBlank() && it != "[unk]" }
    return words.joinToString(" ").take(MAX_HEARD_CHARACTERS).trim().takeUnless { VoiceCatalog.isBlank(it) }
}

private const val MAX_HEARD_CHARACTERS = 120

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
