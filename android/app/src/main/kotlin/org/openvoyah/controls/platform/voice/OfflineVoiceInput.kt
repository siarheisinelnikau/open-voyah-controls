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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.VoiceCatalog
import org.openvoyah.controls.data.voice.VoskModels
import org.openvoyah.controls.domain.voice.SpeechEvent
import org.openvoyah.controls.domain.voice.VoiceInput
import org.vosk.Model
import org.vosk.Recognizer

/** The shared lock protects the microphone and native model across both voice entry points. */
class OfflineVoiceInput(
    context: Context,
    private val duckMedia: Boolean,
    private val naturalPhrases: Boolean = false,
) : VoiceInput {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override fun listen(language: String, grammar: Set<String>): Flow<SpeechEvent> = flow {
        try {
            val result = audioLock.withLock {
                eviction?.cancel()
                try {
                    capture(language, grammar) { emit(SpeechEvent.Listening(UiText.get(R.string.voice_listening_action))) }
                } finally {
                    eviction = cacheScope.launch {
                        delay(90_000)
                        audioLock.withLock { models.release() }
                    }
                }
            }
            emit(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            emit(error(failure))
        } catch (failure: LinkageError) {
            emit(error(failure))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun repair(language: String) = withContext(Dispatchers.IO) {
        audioLock.withLock { models.invalidate(context, language) }
    }

    private suspend fun capture(language: String, grammar: Set<String>, listening: suspend () -> Unit): SpeechEvent {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IOException(UiText.get(R.string.ui_138))
        }
        val captureContext = currentCoroutineContext()
        val model = models.load(context, language) { captureContext.isActive }
        captureContext.ensureActive()
        val replay = if (naturalPhrases) ShortArray((SAMPLE_RATE * LISTEN_MS / 1000).toInt()) else null
        val captured = Recognizer(model, SAMPLE_RATE.toFloat(), recognitionGrammar(grammar)).use { recognizer ->
            val size = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (size <= 0) throw IOException(UiText.get(R.string.ui_140))
            recognizer.setWords(true)
            recognizer.setEndpointerDelays(2.5f, 0.6f, 20f)
            val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(size, 6400))
            val manager = context.getSystemService(AudioManager::class.java)
            var focus: AudioFocusRequest? = null
            val focusLost = AtomicBoolean()
            try {
                if (audio.state != AudioRecord.STATE_INITIALIZED) throw IOException(UiText.get(R.string.ui_141))
                captureContext.ensureActive()
                if (duckMedia && manager != null) focus = requestFocus(manager, focusLost)
                audio.startRecording()
                if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException(UiText.get(R.string.ui_142))
                listening()
                read(audio, recognizer, focusLost, replay)
            } finally {
                try {
                    if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop()
                } finally {
                    audio.release()
                    focus?.let { manager?.abandonAudioFocusRequest(it) }
                }
            }
        }
        if (replay != null && captured.replaySamples > 0) {
            captureContext.ensureActive()
            recognizeNatural(model, replay, captured.replaySamples)?.let { return it }
        }
        return captured.event
    }

    private fun recognizeNatural(model: Model, samples: ShortArray, count: Int): SpeechEvent.Natural? =
        Recognizer(model, SAMPLE_RATE.toFloat()).use { recognizer ->
            recognizer.setWords(true)
            val result = if (recognizer.acceptWaveForm(samples, count)) recognizer.result else recognizer.finalResult
            val text = (decodeEndpoint(result) as? SpeechEvent.Recognized)?.text ?: return@use null
            if (text.length > 160 || text.count { it == ' ' } > 13) return@use null
            SpeechEvent.Natural(text)
        }

    private fun requestFocus(manager: AudioManager, focusLost: AtomicBoolean): AudioFocusRequest? {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    focusLost.set(true)
                }
            }, main).build()
        return request.takeIf { manager.requestAudioFocus(it) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }
    }

    private data class Captured(val event: SpeechEvent, val replaySamples: Int = 0)

    private suspend fun read(
        audio: AudioRecord,
        recognizer: Recognizer,
        focusLost: AtomicBoolean,
        replay: ShortArray?,
    ): Captured {
        var recorded = 0
        val samples = ShortArray(1600)
        val deadline = SystemClock.elapsedRealtime() + LISTEN_MS
        val captureContext = currentCoroutineContext()
        while (!focusLost.get() && SystemClock.elapsedRealtime() < deadline) {
            captureContext.ensureActive()
            val count = audio.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
            if (count < 0) throw IOException(UiText.get(R.string.ui_144) + count)
            if (count == 0) {
                delay(20)
                continue
            }
            if (replay != null && recorded < replay.size) {
                val copied = minOf(count, replay.size - recorded)
                samples.copyInto(replay, recorded, endIndex = copied)
                recorded += copied
            }
            if (recognizer.acceptWaveForm(samples, count)) {
                if (focusLost.get()) break
                val endpoint = recognizer.result
                val event = decodeEndpoint(endpoint)
                val unclear = event is SpeechEvent.Failed && JSONObject(endpoint).optString("text").isNotBlank()
                return Captured(event, if (unclear) recorded else 0)
            }
        }
        if (focusLost.get()) return Captured(SpeechEvent.Failed(UiText.get(R.string.voice_audio_interrupted)))
        val partial = JSONObject(recognizer.partialResult).optString("partial")
        return Captured(SpeechEvent.Failed(UiText.get(if (partial.isBlank()) R.string.voice_no_speech else R.string.unknown_command)))
    }

    private fun error(failure: Throwable) = SpeechEvent.Failed(
        UiText.get(R.string.ui_146) + failure.message.orEmpty() + UiText.get(R.string.ui_147)
    )

    private fun recognitionGrammar(phrases: Set<String>): String {
        val grammar = phrases.filterTo(sortedSetOf()) { phrase -> phrase.codePoints().noneMatch(Character::isDigit) }
        grammar += "[unk]"
        return JSONArray(grammar).toString()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val LISTEN_MS = 4_000L
        val audioLock = Mutex()
        val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val models = VoskModels()
        var eviction: Job? = null
    }
}

internal fun decodeEndpoint(json: String): SpeechEvent {
    val result = JSONObject(json)
    val text = result.optString("text")
    if (VoiceCatalog.isBlank(text)) return SpeechEvent.Failed(UiText.get(R.string.voice_no_speech))
    val words = result.optJSONArray("result")
    val confident = words != null && words.length() > 0 && (0 until words.length()).all { index ->
        val score = words.optJSONObject(index)?.optDouble("conf") ?: Double.NaN
        score.isFinite() && score >= 0.65
    }
    if ("[unk]" in text || !confident) return SpeechEvent.Failed(UiText.get(R.string.unknown_command))
    return SpeechEvent.Recognized(text)
}
