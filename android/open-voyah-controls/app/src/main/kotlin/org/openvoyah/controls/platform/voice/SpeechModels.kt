package org.openvoyah.controls.platform.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.data.voice.BundledVoiceModel
import org.openvoyah.controls.data.voice.SpeechModel
import org.openvoyah.controls.data.voice.SpeechModelCatalog
import org.openvoyah.controls.data.voice.SpeechModelStore
import org.openvoyah.controls.domain.voice.SpeechEngine
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.diagnostics.ControlsLog
import org.vosk.Model
import org.vosk.Recognizer

/**
 * The speech model of this process and a recognizer for the last grammar, kept ready between voice requests. At most
 * one model and one grammar recognizer exist at a time. Every call holds [lock], the same lock as a microphone capture:
 * a request that arrives while a model is loading waits for that load and then uses it instead of starting another.
 * Preparing never opens the microphone.
 */
internal class PreparedSpeech<M : Any, R : AutoCloseable>(
    private val loadModel: (key: String, current: () -> Boolean) -> M,
    private val releaseModel: (M) -> Unit,
    private val openRecognizer: (model: M, grammar: String) -> R,
    private val reset: (R) -> Unit,
    private val clock: () -> Long,
    private val report: (Preparation) -> Unit,
) {
    val lock = Mutex()
    private var key: String? = null
    private var model: M? = null
    private var grammar: String? = null
    private var recognizer: R? = null

    /**
     * [key] names the engine and language (`vosk:ru`). [outcome] is `ready` when something was loaded, `reused` when
     * everything was already prepared, or `failed`.
     */
    data class Preparation(val trigger: String, val outcome: String, val key: String, val elapsedMs: Long,
        val failure: Throwable? = null)

    class Ready<M, R>(val model: M, val recognizer: R?)

    /** Takes the lock and prepares the model [key] and, when known, the recognizer for [grammar], on an I/O thread. */
    suspend fun warm(key: String, grammar: String?, trigger: String): Ready<M, R> = lock.withLock {
        runInterruptible(Dispatchers.IO) { prepare(key, grammar, trigger) { !Thread.currentThread().isInterrupted } }
    }

    /**
     * The caller holds [lock]. Returns the model [key] and a recognizer for [grammar] in its initial state, loading only
     * what is not prepared yet. Another key first closes the recognizer and releases the old model.
     */
    fun prepare(key: String, grammar: String?, trigger: String, current: () -> Boolean): Ready<M, R> {
        check(lock.isLocked) { "Speech preparation needs the speech lock" }
        val started = clock()
        try {
            var loaded = false
            var model = this.model
            if (model == null || this.key != key) {
                discard()
                model = loadModel(key, current)
                this.model = model
                this.key = key
                loaded = true
            }
            var recognizer: R? = null
            if (grammar != null) {
                val kept = this.recognizer?.takeIf { this.grammar == grammar }
                if (kept != null) {
                    reset(kept)
                    recognizer = kept
                } else {
                    closeRecognizer()
                    recognizer = openRecognizer(model, grammar)
                    this.recognizer = recognizer
                    this.grammar = grammar
                    loaded = true
                }
            }
            report(Preparation(trigger, if (loaded) "ready" else "reused", key, clock() - started))
            return Ready(model, recognizer)
        } catch (failure: Throwable) {
            report(Preparation(trigger, "failed", key, clock() - started, failure))
            throw failure
        }
    }

    /** The caller holds [lock]. Drops everything prepared before [files] changes the model on disk. */
    fun invalidate(files: () -> Unit) {
        check(lock.isLocked) { "Speech preparation needs the speech lock" }
        discard()
        files()
    }

    private fun discard() {
        closeRecognizer()
        val loaded = model ?: return
        model = null
        key = null
        releaseModel(loaded)
    }

    private fun closeRecognizer() {
        val open = recognizer ?: return
        recognizer = null
        grammar = null
        open.close()
    }
}

/**
 * The [PreparedSpeech] of this process for every engine, keyed `engine:language`, and its warm-up after start and
 * settings changes. Zipformer ships in the APK; Vosk uses local models obtained separately.
 */
internal object SpeechModels {
    const val SAMPLE_RATE = 16_000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bundled = BundledVoiceModel()
    @Volatile private var instance: PreparedSpeech<LoadedSpeechModel, SpeechDecoder>? = null
    @Volatile private var modelStore: SpeechModelStore? = null
    private val failures = MutableStateFlow<Map<SpeechEngine, String>>(emptyMap())

    /**
     * Engines that could not start in this process, with the name of the error. Voice uses Vosk for them until the
     * engine is chosen again in Recognition or the bundled model is repaired.
     */
    val engineFailures: StateFlow<Map<SpeechEngine, String>> = failures.asStateFlow()

    fun recordFailure(engine: SpeechEngine, failure: Throwable) {
        failures.update { it + (engine to rootCauseName(failure)) }
        ControlsLog.warn("voice_engine_fallback", failure, "engine", engine.key, "fallback", SpeechEngine.VOSK.key)
    }

    fun clearFailure(engine: SpeechEngine) = failures.update { it - engine }

    fun of(context: Context): PreparedSpeech<LoadedSpeechModel, SpeechDecoder> = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    fun store(context: Context): SpeechModelStore = modelStore ?: synchronized(this) {
        val files = context.applicationContext.filesDir
        modelStore ?: SpeechModelStore(File(files, "speech-models"), legacyRoot = files).also { modelStore = it }
    }

    fun key(engine: SpeechEngine, language: String) = "${engine.key}:$language"

    fun zipformerSupported(): Boolean = Process.is64Bit() && Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a"

    fun engineFor(settings: VoiceSettings, language: String): SpeechEngine =
        SpeechEngine.forLanguage(SpeechEngine.fromKey(settings.engine()), language,
            zipformerSupported() && SpeechEngine.ZIPFORMER !in failures.value)

    class ModelNotDownloaded : IOException(UiText.get(R.string.voice_model_not_downloaded))

    /**
     * Loads the selected engine's model and command recognizer in the background, so the first voice key press
     * starts listening at once. Skipped until the microphone is allowed, because voice is not in use before that.
     */
    @JvmStatic fun warmUp(context: Context, trigger: String) {
        val app = context.applicationContext
        scope.launch {
            if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ControlsLog.info("voice_model_prepare", "trigger", trigger, "outcome", "skipped", "reason", "microphone")
                return@launch
            }
            try {
                val settings = VoiceSettings(app)
                val language = settings.language()
                val engine = engineFor(settings, language)
                val model = SpeechModelCatalog.forEngine(engine, language)
                if (model != null && !store(app).installed(model)) {
                    ControlsLog.info("voice_model_prepare", "trigger", trigger, "outcome", "skipped",
                        "reason", "not_downloaded", "model", key(engine, language))
                    return@launch
                }
                val grammar = speechGrammar(settings.withContacts().grammar())
                withVoskFallback(engine, { candidate -> of(app).warm(key(candidate, language), grammar, trigger) }, ::recordFailure)
            } catch (_: Exception) {
                // Reported by the preparation; the next request tries again.
            } catch (_: LinkageError) {
            }
        }
    }

    /** First-run check: the same model as voice requests, so setup never holds a second copy in memory. */
    suspend fun prepareForSetup(context: Context, language: String) {
        val app = context.applicationContext
        withVoskFallback(engineFor(VoiceSettings(app), language),
            { candidate -> of(app).warm(key(candidate, language), null, "setup") }, ::recordFailure)
    }

    /** Releases the recognizer and resets a bundled model so the next preparation copies it from the APK. */
    suspend fun repair(context: Context, language: String) {
        val app = context.applicationContext
        val speech = of(app)
        speech.lock.withLock {
            runInterruptible(Dispatchers.IO) {
                speech.invalidate {
                    if (zipformerSupported() && SpeechEngine.ZIPFORMER.supports(language)) {
                        bundled.invalidate(app)
                        clearFailure(SpeechEngine.ZIPFORMER)
                    }
                }
            }
        }
    }

    /** Releases a downloaded model before its files are deleted. */
    suspend fun forget(context: Context, model: SpeechModel) {
        val app = context.applicationContext
        val speech = of(app)
        speech.lock.withLock { runInterruptible(Dispatchers.IO) { speech.invalidate { store(app).delete(model) } } }
    }

    private fun create(app: Context) = PreparedSpeech<LoadedSpeechModel, SpeechDecoder>(
        loadModel = { key, current -> load(app, key, current) },
        releaseModel = { model ->
            when (model) {
                is VoskSpeechModel -> model.model.close()
                is ZipformerSpeechModel -> model.close()
            }
        },
        openRecognizer = { model, grammar ->
            when (model) {
                is VoskSpeechModel -> VoskDecoder(model.model, Recognizer(model.model, SAMPLE_RATE.toFloat(), grammar))
                is ZipformerSpeechModel -> ZipformerDecoder(model, grammar)
            }
        },
        reset = SpeechDecoder::reset,
        clock = SystemClock::elapsedRealtime,
        report = { step ->
            val fields = arrayOf<Any?>("trigger", step.trigger, "outcome", step.outcome, "model", step.key,
                "elapsed_ms", step.elapsedMs)
            if (step.failure == null) ControlsLog.info("voice_model_prepare", *fields)
            else ControlsLog.warn("voice_model_prepare", step.failure, *fields)
        },
    )

    private fun load(app: Context, key: String, current: () -> Boolean): LoadedSpeechModel {
        val engine = SpeechEngine.fromKey(key.substringBefore(':')) ?: throw IOException("Unknown speech engine")
        val language = key.substringAfter(':')
        if (!current()) throw CancellationException("Speech model preparation cancelled")
        return when (engine) {
            SpeechEngine.VOSK -> {
                val model = SpeechModelCatalog.forEngine(engine, language)
                    ?: throw IOException("No Vosk speech model for $language")
                val directory = store(app).installedDirectory(model) ?: throw ModelNotDownloaded()
                VoskSpeechModel(Model(directory.absolutePath))
            }
            SpeechEngine.ZIPFORMER -> ZipformerSpeechModel.open(bundled.prepare(app, current))
        }
    }
}

/**
 * Prepares [engine]. If Zipformer cannot start, reports it to [failed] and tries Vosk for the same language.
 * The second value is true after a successful fallback.
 */
internal inline fun <T> withVoskFallback(
    engine: SpeechEngine,
    prepare: (SpeechEngine) -> T,
    failed: (SpeechEngine, Throwable) -> Unit,
): Pair<T, Boolean> {
    try {
        return prepare(engine) to false
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (engine == SpeechEngine.VOSK) throw failure
        failed(engine, failure)
    } catch (failure: LinkageError) {
        // A native library or class that cannot load on this head unit.
        if (engine == SpeechEngine.VOSK) throw failure
        failed(engine, failure)
    }
    return prepare(SpeechEngine.VOSK) to true
}

/** The class of the innermost cause: what the user and the journal can name, without a message that may hold data. */
internal fun rootCauseName(failure: Throwable): String {
    var cause = failure
    repeat(8) { cause = cause.cause?.takeIf { it !== cause } ?: return cause.javaClass.simpleName }
    return cause.javaClass.simpleName
}

/** The Vosk grammar for the catalog phrases: digits are transcript aliases only, and `[unk]` absorbs other speech. */
internal fun speechGrammar(phrases: Set<String>): String {
    val grammar = phrases.filterTo(sortedSetOf()) { phrase -> phrase.codePoints().noneMatch(Character::isDigit) }
    grammar += "[unk]"
    return JSONArray(grammar).toString()
}
