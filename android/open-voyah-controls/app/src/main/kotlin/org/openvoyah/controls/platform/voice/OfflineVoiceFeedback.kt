package org.openvoyah.controls.platform.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.openvoyah.controls.R
import org.openvoyah.controls.core.voice.VoiceAudioOptions
import org.openvoyah.controls.core.voice.VoiceAudioOptions.SpokenReplyChannel
import org.openvoyah.controls.diagnostics.ControlsLog

/** Speaks through an installed offline voice and owns the engine for one foreground request. */
internal class OfflineVoiceFeedback(
    context: Context,
    private val language: String,
    createEngine: (Context, TextToSpeech.OnInitListener) -> TextToSpeech = { app, listener -> TextToSpeech(app, listener) },
) : AutoCloseable {
    private data class Utterance(val text: String, val options: VoiceAudioOptions, val voiceName: String)
    private data class ReadySynthesis(val id: String, val done: CompletableDeferred<Boolean>)

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val speaking = MutableStateFlow(false)
    private val availableVoices = MutableStateFlow<List<OfflineVoiceChoice>?>(null)
    val isSpeaking = speaking.asStateFlow()
    val choices = availableVoices.asStateFlow()
    private var initialized = false
    private var closed = false
    private var sequence = 0
    private var pending: Utterance? = null
    private var utterance: String? = null
    private var readySynthesis: ReadySynthesis? = null
    private var voices = emptyList<Voice>()

    private val engine = try {
        createEngine(app) { result -> main.post { ready(result) } }
    } catch (_: RuntimeException) {
        ControlsLog.info("offline_speech", "outcome", "engine_unavailable")
        null
    }

    init { if (engine == null) availableVoices.value = emptyList() }

    private fun ready(result: Int) {
        val tts = engine ?: return
        if (closed) return
        initialized = true
        if (result == TextToSpeech.SUCCESS) {
            try {
                voices = eligibleOfflineVoices(tts.voices.orEmpty(), language)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) = Unit
                    override fun onDone(utteranceId: String) = complete(utteranceId, true)
                    @Deprecated("Legacy callback on an Android speech engine")
                    override fun onError(utteranceId: String) = complete(utteranceId, false)
                    override fun onStop(utteranceId: String, interrupted: Boolean) = complete(utteranceId, false)
                })
            } catch (_: RuntimeException) {
                voices = emptyList()
            }
        }
        availableVoices.value = voices.map { OfflineVoiceChoice(it.name, it.locale) }
        val queued = pending
        pending = null
        if (voices.isEmpty()) {
            speaking.value = false
            ControlsLog.info("offline_speech", "outcome", "voice_unavailable")
        } else if (queued != null) speak(queued.text, queued.options, queued.voiceName)
    }

    fun speak(message: String, options: VoiceAudioOptions, voiceName: String): Boolean {
        val tts = engine ?: return false
        if (closed || options.speechVolumePercent() == 0) return false
        if (!initialized) {
            pending = Utterance(message, options, voiceName)
            speaking.value = true
            return true
        }
        if (!selectVoice(tts, voiceName)) {
            stop()
            return false
        }
        val id = "command-${++sequence}"
        val parameters = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, options.speechGain()) }
        utterance = id
        speaking.value = true
        val accepted = try {
            val usage = when (options.spokenReplyChannel()) {
                SpokenReplyChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
                SpokenReplyChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
            }
            val attributes = AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            if (tts.setSpeechRate(options.speechRate()) != TextToSpeech.SUCCESS) {
                ControlsLog.info("offline_speech", "outcome", "speech_rate_rejected")
            }
            tts.setAudioAttributes(attributes) == TextToSpeech.SUCCESS &&
                tts.speak(message, TextToSpeech.QUEUE_FLUSH, parameters, id) == TextToSpeech.SUCCESS
        } catch (_: RuntimeException) { false }
        if (!accepted) complete(id, false)
        return accepted
    }

    /** The capture owns the temporary ready signal and deletes it before opening the microphone. */
    suspend fun synthesizeReadyPrompt(file: File, voiceName: String, readyPrompt: String): Boolean = withContext(Dispatchers.Main.immediate) {
        val tts = engine ?: return@withContext false
        if (!VoiceAudioOptions.validReadyPrompt(readyPrompt)) return@withContext false
        availableVoices.filterNotNull().first()
        if (closed || !selectVoice(tts, voiceName)) return@withContext false
        val request = ReadySynthesis("ready-${++sequence}", CompletableDeferred())
        readySynthesis = request
        val queued = try {
            val prompt = readyPrompt.ifEmpty { app.forRecognitionLanguage(language).getString(R.string.voice_ready_prompt) }
            tts.setSpeechRate(1f)
            tts.synthesizeToFile(prompt, Bundle(), file, request.id) == TextToSpeech.SUCCESS
        } catch (_: RuntimeException) { false }
        try {
            queued && request.done.await()
        } finally {
            readySynthesis = null
            if (queued && !request.done.isCompleted) stop()
        }
    }

    private fun selectVoice(tts: TextToSpeech, name: String): Boolean = try {
        activateOfflineVoice(voices, name) { tts.setVoice(it) == TextToSpeech.SUCCESS }
    } catch (_: RuntimeException) { false }

    private fun complete(id: String, succeeded: Boolean) {
        main.post {
            readySynthesis?.takeIf { it.id == id }?.done?.complete(succeeded)
            if (utterance == id) {
                utterance = null
                speaking.value = false
            }
        }
    }

    suspend fun awaitIdle() { speaking.first { !it } }

    fun stop() {
        readySynthesis?.done?.complete(false)
        pending = null
        utterance = null
        speaking.value = false
        if (initialized) try { engine?.stop() }
        catch (failure: RuntimeException) { ControlsLog.info("offline_speech", "outcome", "stop_failed", "error", failure) }
    }

    override fun close() {
        if (closed) return
        closed = true
        stop()
        voices = emptyList()
        availableVoices.value = emptyList()
        try { engine?.shutdown() }
        catch (failure: RuntimeException) { ControlsLog.info("offline_speech", "outcome", "release_failed", "error", failure) }
    }
}

internal data class OfflineVoiceChoice(val name: String, val locale: Locale)

internal fun eligibleOfflineVoices(voices: Iterable<Voice>, language: String): List<Voice> = voices.asSequence()
    .filter {
        it.locale.language == language && !it.isNetworkConnectionRequired && it.name.isNotEmpty() &&
            VoiceAudioOptions.validOfflineVoiceName(it.name) &&
            it.features?.let { features -> TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features } == true
    }
    .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.locale.toLanguageTag() }.thenBy { it.name })
    .distinctBy { it.name }
    .toList()

internal fun activateOfflineVoice(voices: List<Voice>, preferred: String, activate: (Voice) -> Boolean): Boolean {
    val chosen = voices.firstOrNull { it.name == preferred }
    if (chosen != null && activate(chosen)) return true
    return voices.any { it != chosen && activate(it) }
}
