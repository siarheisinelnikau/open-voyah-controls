package org.openvoyah.controls.presentation.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openvoyah.controls.R
import org.openvoyah.controls.core.VoiceCatalog
import org.openvoyah.controls.core.voice.VoiceAudioOptions

internal data class VoiceSettingsConfiguration(
    val catalog: VoiceCatalog,
    val language: String,
    val vehicleCommandsReviewed: Boolean,
    val assigned: Set<String>,
    val levels: Map<String, Int>,
    val doubleSmoke: Boolean,
    val doubleVentilation: Boolean,
    val duckMedia: Boolean,
    val spokenFeedback: Boolean,
    val onlineConfigured: Boolean,
    val audioOptions: VoiceAudioOptions = VoiceAudioOptions.DEFAULTS,
    val offlineVoiceName: String = "",
    val readyPrompt: String = "",
    /** The stored speech engine key; Recognition shows whether it can run for [language]. */
    val engine: String = "vosk",
)

internal sealed interface VoiceSettingsChange {
    data class Language(val language: String) : VoiceSettingsChange
    data class Engine(val engine: String) : VoiceSettingsChange
    data class App(val command: String, val component: String) : VoiceSettingsChange
    data class Level(val command: String, val level: Int) : VoiceSettingsChange
    data class Windows(val smoke: Boolean, val ventilation: Boolean) : VoiceSettingsChange
    data class Audio(
        val duck: Boolean, val feedback: Boolean, val options: VoiceAudioOptions, val language: String, val offlineVoiceName: String,
        val readyPrompt: String,
    ) : VoiceSettingsChange
    data class Contact(val name: String, val phone: String) : VoiceSettingsChange
    data object RestorePrevious : VoiceSettingsChange
}

/** The settings owner uses application adapters, never an Activity or a document picker. */
internal interface VoiceSettingsSource {
    fun load(): VoiceSettingsConfiguration
    fun change(change: VoiceSettingsChange)
    fun saveOnline(enabled: Boolean, key: String)
    suspend fun repairModel()
    suspend fun saveDocument(document: String)
    suspend fun restoreDocument(document: String)
}

/** Retains settings across navigation/recreation; detached screens cannot publish late results. */
internal class VoiceSettingsViewModel(
    private val source: VoiceSettingsSource,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val ownerScope: CoroutineScope? = null,
) : ViewModel() {
    enum class Work { LOAD, SAVE, ONLINE, REPAIR, EXPORT, IMPORT }
    data class Message(val resource: Int? = null, val detail: String? = null)
    data class State(
        val configuration: VoiceSettingsConfiguration? = null,
        val work: Work? = null,
        val startupError: String? = null,
        val message: Message? = null,
        val audioOpen: Boolean = false,
        val onlineOpen: Boolean = false,
        val onlineFailed: Boolean = false,
    ) { val busy: Boolean get() = work != null }

    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val owners = mutableSetOf<Any>()
    private val serial = Mutex()
    private var active = false
    private var generation = 0L
    private var operation: Job? = null
    private val scope: CoroutineScope get() = ownerScope ?: viewModelScope

    fun enter(owner: Any) {
        owners.add(owner)
        active = true
        refresh()
    }

    fun leave(owner: Any, recreating: Boolean = false) {
        owners.remove(owner)
        if (owners.isNotEmpty() || recreating) return
        invalidate()
    }

    fun refresh(): Boolean = start(Work.LOAD) { null }
    fun language(value: String) = change(VoiceSettingsChange.Language(value))
    fun engine(value: String) = change(VoiceSettingsChange.Engine(value))
    fun app(command: String, component: String) = change(VoiceSettingsChange.App(command, component))
    fun level(command: String, value: Int) = change(VoiceSettingsChange.Level(command, value))
    fun windows(smoke: Boolean, ventilation: Boolean) = change(VoiceSettingsChange.Windows(smoke, ventilation))
    fun contact(name: String, phone: String) = change(VoiceSettingsChange.Contact(name, phone))
    fun restorePrevious() = change(VoiceSettingsChange.RestorePrevious)

    fun audio(selection: VoiceSettingsChange.Audio): Boolean {
        if (selection.language != mutable.value.configuration?.language || !VoiceAudioOptions.validReadyPrompt(selection.readyPrompt)) return false
        val started = change(selection)
        if (started) mutable.update { it.copy(audioOpen = false) }
        return started
    }

    fun openAudio() { if (available()) mutable.update { it.copy(audioOpen = true) } }
    fun closeAudio() { mutable.update { it.copy(audioOpen = false) } }
    fun openOnline() { if (available()) mutable.update { it.copy(onlineOpen = true, onlineFailed = false) } }
    fun closeOnline() { if (mutable.value.work != Work.ONLINE) mutable.update { it.copy(onlineOpen = false) } }
    fun closeDialogs() { mutable.update { it.copy(audioOpen = false, onlineOpen = false) } }

    fun message(resource: Int) {
        if (active) mutable.update { it.copy(message = Message(resource)) }
    }

    fun saveOnline(enabled: Boolean, key: String): Boolean = start(Work.ONLINE) {
        // Keep the credential out of screen state, exception messages, archives and saved state.
        withContext(NonCancellable + io) { source.saveOnline(enabled, key) }
        null
    }

    fun repairModel(): Boolean = start(Work.REPAIR) {
        withContext(io) { source.repairModel() }
        Message(R.string.ui_184)
    }

    fun transfer(document: String, restoring: Boolean): Boolean = start(if (restoring) Work.IMPORT else Work.EXPORT) {
        withContext(io) {
            if (restoring) source.restoreDocument(document) else source.saveDocument(document)
        }
        if (restoring) null else Message(R.string.ui_196)
    }

    private fun change(change: VoiceSettingsChange): Boolean = start(Work.SAVE) {
        // Once an atomic preferences transaction starts, finish it before a later screen reads.
        withContext(NonCancellable + io) { source.change(change) }
        null
    }

    private fun available() = active && !mutable.value.busy

    private fun start(work: Work, action: suspend () -> Message?): Boolean {
        if (active && work != Work.LOAD && mutable.value.work == Work.LOAD) {
            // A returning document/permission handoff must not lose its user action to resume's read.
            generation += 1
            operation?.cancel()
            operation = null
            mutable.update { it.copy(work = null) }
        }
        if (!available()) return false
        val request = ++generation
        mutable.update { it.copy(work = work, startupError = null,
            onlineFailed = if (work == Work.ONLINE) false else it.onlineFailed) }
        operation = scope.launch {
            try {
                val result = serial.withLock {
                    val message = action()
                    withContext(io) { source.load() } to message
                }
                if (!active || request != generation) return@launch
                mutable.update { it.copy(configuration = result.first, work = null, startupError = null,
                    message = if (work == Work.LOAD) it.message else result.second,
                    onlineOpen = if (work == Work.ONLINE) false else it.onlineOpen) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!active || request != generation) return@launch
                mutable.update {
                    when {
                        work == Work.ONLINE -> it.copy(work = null, onlineFailed = true)
                        it.configuration == null -> it.copy(work = null, startupError = failure.message.orEmpty())
                        work == Work.IMPORT || work == Work.EXPORT -> it.copy(work = null,
                            message = Message(R.string.ui_197, failure.message.orEmpty()))
                        else -> it.copy(work = null, message = failure.message?.takeIf(String::isNotBlank)
                            ?.let { detail -> Message(detail = detail) } ?: Message(R.string.ui_219))
                    }
                }
            } finally {
                if (request == generation) {
                    operation = null
                    mutable.update { it.copy(work = null) }
                }
            }
        }
        return true
    }

    private fun invalidate() {
        active = false
        generation += 1
        operation?.cancel()
        operation = null
        mutable.update { it.copy(work = null, audioOpen = false, onlineOpen = false, onlineFailed = false) }
    }

    override fun onCleared() {
        owners.clear()
        invalidate()
        super.onCleared()
    }
}
