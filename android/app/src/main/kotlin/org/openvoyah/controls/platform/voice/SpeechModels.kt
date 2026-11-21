package org.openvoyah.controls.platform.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.data.voice.VoskModels
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
    private val loadModel: (language: String, current: () -> Boolean) -> M,
    private val releaseModel: () -> Unit,
    private val openRecognizer: (model: M, grammar: String) -> R,
    private val reset: (R) -> Unit,
    private val clock: () -> Long,
    private val report: (Preparation) -> Unit,
) {
    val lock = Mutex()
    private var language: String? = null
    private var model: M? = null
    private var grammar: String? = null
    private var recognizer: R? = null

    /** [outcome] is `ready` when something was loaded, `reused` when everything was already prepared, or `failed`. */
    data class Preparation(val trigger: String, val outcome: String, val language: String, val elapsedMs: Long,
        val failure: Throwable? = null)

    class Ready<M, R>(val model: M, val recognizer: R?)

    /** Takes the lock and prepares [language] and, when known, the recognizer for [grammar], on an I/O thread. */
    suspend fun warm(language: String, grammar: String?, trigger: String): Ready<M, R> = lock.withLock {
        runInterruptible(Dispatchers.IO) { prepare(language, grammar, trigger) { !Thread.currentThread().isInterrupted } }
    }

    /**
     * The caller holds [lock]. Returns the model for [language] and a recognizer for [grammar] in its initial state,
     * loading only what is not prepared yet. Another language first closes the recognizer and releases the old model.
     */
    fun prepare(language: String, grammar: String?, trigger: String, current: () -> Boolean): Ready<M, R> {
        check(lock.isLocked) { "Speech preparation needs the speech lock" }
        val started = clock()
        try {
            var loaded = false
            var model = this.model
            if (model == null || this.language != language) {
                discard()
                model = loadModel(language, current)
                this.model = model
                this.language = language
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
            report(Preparation(trigger, if (loaded) "ready" else "reused", language, clock() - started))
            return Ready(model, recognizer)
        } catch (failure: Throwable) {
            report(Preparation(trigger, "failed", language, clock() - started, failure))
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
        if (model != null) {
            model = null
            language = null
            releaseModel()
        }
    }

    private fun closeRecognizer() {
        val open = recognizer ?: return
        recognizer = null
        grammar = null
        open.close()
    }
}

/** The Vosk instance of [PreparedSpeech] for this process, and its warm-up after start and language changes. */
internal object SpeechModels {
    const val SAMPLE_RATE = 16_000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val vosk = VoskModels()
    @Volatile private var instance: PreparedSpeech<Model, Recognizer>? = null

    fun of(context: Context): PreparedSpeech<Model, Recognizer> = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    /**
     * Loads the selected language's model and command recognizer in the background, so the first voice key press
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
                of(app).warm(language, speechGrammar(settings.withContacts().grammar()), trigger)
            } catch (_: Exception) {
                // Reported by the preparation; the next request tries again.
            } catch (_: LinkageError) {
            }
        }
    }

    /** First-run check: the same model as voice requests, so setup never holds a second copy in memory. */
    suspend fun prepareForSetup(context: Context, language: String) {
        of(context).warm(language, null, "setup")
    }

    /** Forgets the unpacked model of [language], so the next preparation copies it again from the APK. */
    suspend fun repair(context: Context, language: String) {
        val app = context.applicationContext
        val speech = of(app)
        speech.lock.withLock { runInterruptible(Dispatchers.IO) { speech.invalidate { vosk.invalidate(app, language) } } }
    }

    private fun create(app: Context) = PreparedSpeech<Model, Recognizer>(
        loadModel = { language, current -> vosk.load(app, language) { current() } },
        releaseModel = vosk::release,
        openRecognizer = { model, grammar -> Recognizer(model, SAMPLE_RATE.toFloat(), grammar) },
        reset = Recognizer::reset,
        clock = SystemClock::elapsedRealtime,
        report = { step ->
            val fields = arrayOf<Any?>("trigger", step.trigger, "outcome", step.outcome, "language", step.language,
                "elapsed_ms", step.elapsedMs)
            if (step.failure == null) ControlsLog.info("voice_model_prepare", *fields)
            else ControlsLog.warn("voice_model_prepare", step.failure, *fields)
        },
    )
}

/** The Vosk grammar for the catalog phrases: digits are transcript aliases only, and `[unk]` absorbs other speech. */
internal fun speechGrammar(phrases: Set<String>): String {
    val grammar = phrases.filterTo(sortedSetOf()) { phrase -> phrase.codePoints().noneMatch(Character::isDigit) }
    grammar += "[unk]"
    return JSONArray(grammar).toString()
}
