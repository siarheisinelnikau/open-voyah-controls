package org.openvoyah.controls.platform.voice

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.voice.TranscriptMatcher
import org.openvoyah.controls.data.voice.BpeVocab
import org.openvoyah.controls.domain.voice.SpeechEvent
import org.openvoyah.controls.domain.voice.VoiceOutcome
import org.vosk.Model
import org.vosk.Recognizer

/** What the microphone loop found: the event, and the replay samples a later pass may decode. */
internal data class Captured(
    val event: SpeechEvent,
    val replaySamples: Int = 0,
    val completed: Boolean = false,
    val replayStart: Int = 0,
)

/** A loaded native model of one engine and language. */
internal sealed interface LoadedSpeechModel

internal class VoskSpeechModel(val model: Model) : LoadedSpeechModel

/**
 * The engine-specific half of a capture. The microphone loop is shared; a decoder decides when the phrase ended and
 * turns the captured audio into the final event after the microphone is released.
 */
internal interface SpeechDecoder : AutoCloseable {
    /** Clean state for a new capture. */
    fun reset()

    /** Samples [count] were appended to the replay buffer, which now holds [recorded] samples. */
    fun accept(samples: ShortArray, count: Int, recorded: Int): Captured?

    /** The listening window ended before the phrase did. */
    fun cutOff(recorded: Int): Captured

    /** True while a phrase has started and not ended; asked only after the listening window. */
    fun speaking(): Boolean

    /** Runs after the microphone is released. [natural] allows online interpretation of a completed phrase. */
    fun finish(captured: Captured, replay: ShortArray, natural: Boolean): SpeechEvent
}

/** Vosk with the command grammar; a phrase that is not a confident command is replayed once without the grammar. */
internal class VoskDecoder(private val model: Model, private val recognizer: Recognizer) : SpeechDecoder {
    override fun reset() {
        recognizer.reset()
        recognizer.setWords(true)
        recognizer.setEndpointerDelays(2.5f, 0.6f, 20f)
    }

    override fun accept(samples: ShortArray, count: Int, recorded: Int): Captured? {
        if (!recognizer.acceptWaveForm(samples, count)) return null
        val endpoint = recognizer.result
        val event = decodeEndpoint(endpoint)
        val unclear = event is SpeechEvent.Failed && JSONObject(endpoint).optString("text").isNotBlank()
        return Captured(event, if (unclear) recorded else 0, completed = true)
    }

    override fun speaking(): Boolean = JSONObject(recognizer.partialResult).optString("partial").isNotBlank()

    override fun cutOff(recorded: Int): Captured {
        val partial = JSONObject(recognizer.partialResult).optString("partial")
        // An unfinished phrase never reaches an action; its transcript only explains the result.
        return if (partial.isBlank()) Captured(noSpeech()) else Captured(unrecognized(), recorded)
    }

    override fun finish(captured: Captured, replay: ShortArray, natural: Boolean): SpeechEvent {
        if (captured.replaySamples <= 0) return captured.event
        return transcribe(replay, captured.replaySamples, natural && captured.completed) ?: captured.event
    }

    /**
     * One pass without the grammar over the captured audio. Only a completed, confident and short phrase may go on to
     * online interpretation; anything else heard is shown and never acted on.
     */
    private fun transcribe(samples: ShortArray, count: Int, natural: Boolean): SpeechEvent? =
        Recognizer(model, SpeechModels.SAMPLE_RATE.toFloat()).use { free ->
            free.setWords(true)
            val result = if (free.acceptWaveForm(samples, count)) free.result else free.finalResult
            val heard = heardText(result) ?: return@use null
            val text = (decodeEndpoint(result) as? SpeechEvent.Recognized)?.text
            if (natural && text != null && text.length <= 160 && text.count { it == ' ' } <= 13) SpeechEvent.Natural(text)
            else SpeechEvent.Unmatched(heard)
        }

    override fun close() = recognizer.close()
}

/** The offline Russian Zipformer with its Silero voice activity detector. */
internal class ZipformerSpeechModel private constructor(val recognizer: OfflineRecognizer, val vad: Vad) :
    LoadedSpeechModel, AutoCloseable {
    override fun close() {
        recognizer.release()
        vad.release()
    }

    companion object {
        /** Silero windows at 16 kHz. */
        const val WINDOW = 512

        fun open(directory: File): ZipformerSpeechModel {
            fun file(name: String) = File(directory, name).also {
                if (!it.isFile) throw IOException("Bundled speech model file is missing: $name")
            }
            val vocab = File(directory, "bpe.vocab")
            if (!vocab.isFile) {
                // sherpa-onnx splits hotwords with this list; sentencepiece is not available to write it on Android.
                val partial = File(directory, "bpe.vocab.part")
                partial.writeText(BpeVocab.fromModel(file("bpe.model").readBytes()))
                if (!partial.renameTo(vocab)) throw IOException("Cannot write bpe.vocab")
            }
            val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SpeechModels.SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = file("encoder.int8.onnx").path,
                        decoder = file("decoder.onnx").path,
                        joiner = file("joiner.int8.onnx").path,
                    ),
                    tokens = file("tokens.txt").path,
                    numThreads = 4,
                    modelingUnit = "bpe",
                    bpeVocab = vocab.path,
                ),
                // Hotwords need beam search; these values are the ones the engine comparison measured.
                decodingMethod = "modified_beam_search",
                maxActivePaths = 4,
                hotwordsScore = 1.5f,
            ))
            val vad = try {
                Vad(config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = file("silero_vad.onnx").path,
                        threshold = 0.5f,
                        minSilenceDuration = 0.5f,
                        minSpeechDuration = 0.2f,
                        windowSize = WINDOW,
                        maxSpeechDuration = 20f,
                    ),
                    sampleRate = SpeechModels.SAMPLE_RATE,
                    numThreads = 1,
                ))
            } catch (failure: Throwable) {
                recognizer.release()
                throw failure
            }
            return ZipformerSpeechModel(recognizer, vad)
        }
    }
}

/**
 * Silero ends the phrase after half a second of silence; the Zipformer then decodes it with the command phrases as
 * hotwords, and [TranscriptMatcher] maps the text to a catalog phrase. Text that matches no phrase is only shown.
 */
internal class ZipformerDecoder(private val model: ZipformerSpeechModel, grammar: String) : SpeechDecoder {
    private val phrases = JSONArray(grammar).let { array ->
        (0 until array.length()).map(array::getString).filter { it != "[unk]" }
    }
    private val hotwords = phrases.joinToString("\n")
    private val matcher = TranscriptMatcher(phrases)
    private val window = FloatArray(ZipformerSpeechModel.WINDOW)
    private var filled = 0

    override fun reset() {
        model.vad.reset()
        filled = 0
    }

    override fun accept(samples: ShortArray, count: Int, recorded: Int): Captured? {
        for (index in 0 until count) {
            window[filled++] = samples[index] / 32768f
            if (filled == window.size) {
                model.vad.acceptWaveform(window.copyOf())
                filled = 0
                segment(recorded, completed = true)?.let { return it }
            }
        }
        return null
    }

    override fun speaking(): Boolean = model.vad.isSpeechDetected()

    override fun cutOff(recorded: Int): Captured {
        model.vad.flush()
        return segment(recorded, completed = false) ?: Captured(noSpeech())
    }

    private fun segment(recorded: Int, completed: Boolean): Captured? {
        if (model.vad.empty()) return null
        val segment = model.vad.front()
        model.vad.pop()
        val start = segment.start.toInt()
        val end = (start + segment.samples.size + PAD).coerceAtMost(recorded)
        return Captured(unrecognized(), end, completed, (start - PAD).coerceAtLeast(0))
    }

    override fun finish(captured: Captured, replay: ShortArray, natural: Boolean): SpeechEvent {
        if (captured.replaySamples <= captured.replayStart) return captured.event
        val audio = FloatArray(captured.replaySamples - captured.replayStart) { replay[captured.replayStart + it] / 32768f }
        val stream = model.recognizer.createStream(hotwords)
        val text = try {
            stream.acceptWaveform(audio, SpeechModels.SAMPLE_RATE)
            model.recognizer.decode(stream)
            model.recognizer.getResult(stream).text.trim().lowercase()
        } finally {
            stream.release()
        }
        if (text.isBlank()) return noSpeech()
        val phrase = if (captured.completed) matcher.match(text) else null
        return when {
            phrase != null -> SpeechEvent.Recognized(phrase)
            natural && captured.completed && text.length <= 160 && text.count { it == ' ' } <= 13 -> SpeechEvent.Natural(text)
            else -> SpeechEvent.Unmatched(text.take(MAX_HEARD_CHARACTERS).trim())
        }
    }

    override fun close() = Unit

    private companion object {
        /** 0.2 s of audio around the detected speech, as in the engine comparison. */
        const val PAD = SpeechModels.SAMPLE_RATE / 5
    }
}

internal fun noSpeech() = SpeechEvent.Failed(UiText.get(R.string.voice_no_speech), VoiceOutcome.NO_SPEECH)
internal fun unrecognized() = SpeechEvent.Failed(UiText.get(R.string.unknown_command), VoiceOutcome.UNRECOGNIZED)
