package org.openvoyah.controls.presentation.keyboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.domain.keyboard.KeyboardAvailability
import org.openvoyah.controls.domain.keyboard.KeyboardOutcome
import org.openvoyah.controls.domain.keyboard.KeyboardPort
import org.openvoyah.controls.domain.keyboard.KeyboardResult
import org.openvoyah.controls.domain.keyboard.KeyboardStatus
import org.openvoyah.controls.platform.keyboard.InstalledKeyboard

/** The result line under the choices; [id] names the keyboard it is about. */
internal data class KeyboardNotice(val kind: Kind, val id: String? = null) {
    enum class Kind { APPLIED, NOT_CONFIRMED, NOT_ENABLED, FACTORY, FAILED, READ_FAILED }
}

/** The keyboards Android lists and their labels; read off the main thread. */
internal interface KeyboardCatalog {
    fun keyboards(): List<InstalledKeyboard>
    fun labels(): Map<String, String>
}

internal class KeyboardViewModel(
    private val port: KeyboardPort,
    private val catalog: KeyboardCatalog,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    tasks: CoroutineScope? = null,
) : ViewModel() {
    private val scope = tasks ?: viewModelScope

    data class State(
        val loading: Boolean = true,
        val working: Boolean = false,
        val availability: KeyboardAvailability? = null,
        val keyboards: List<InstalledKeyboard> = emptyList(),
        val labels: Map<String, String> = emptyMap(),
        val status: KeyboardStatus? = null,
        val notice: KeyboardNotice? = null,
    ) {
        /** A choice needs the policy agent: without it the firmware would undo the choice at once. */
        val canChoose: Boolean get() = !loading && !working && availability == KeyboardAvailability.READY && status != null
        fun label(id: String?): String? = id?.let { labels[it] ?: keyboards.firstOrNull { k -> k.option.id == it }?.option?.label ?: it }
    }

    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (mutable.value.working) return
        scope.launch {
            val loaded = try {
                withContext(io) {
                    val availability = port.availability()
                    // Without a root channel nothing can be read; the firmware keyboard is then all there is.
                    val readable = availability == KeyboardAvailability.READY || availability == KeyboardAvailability.WINDOW_SERVICE_OFF
                    val result = if (readable) runCatching { port.read() }.getOrNull() else null
                    Loaded(availability, catalog.keyboards(), catalog.labels(), result, readable && result == null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Loaded(null, emptyList(), emptyMap(), null, true)
            }
            mutable.update {
                it.copy(loading = false, availability = loaded.availability, keyboards = loaded.keyboards, labels = loaded.labels,
                    status = loaded.result?.status ?: it.status,
                    notice = if (loaded.readFailed) KeyboardNotice(KeyboardNotice.Kind.READ_FAILED) else it.notice)
            }
        }
    }

    fun select(id: String) {
        val current = mutable.value
        if (!current.canChoose || current.status?.choice == id) return
        run(id) { port.select(id) }
    }

    fun restoreFactory() {
        val current = mutable.value
        // Returning to the factory keyboard needs only the root channel, so it is offered without the agent too.
        if (current.loading || current.working || current.status == null) return
        if (current.status.choice == null && current.status.fallback == null) return
        run(null) { port.restoreFactory() }
    }

    private fun run(id: String?, operation: () -> KeyboardResult) {
        mutable.update { it.copy(working = true, notice = null) }
        scope.launch {
            val result = try {
                withContext(io) { operation() }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(working = false) }
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val notice = when (result?.outcome) {
                null -> KeyboardNotice(KeyboardNotice.Kind.FAILED, id)
                KeyboardOutcome.APPLIED -> KeyboardNotice(KeyboardNotice.Kind.APPLIED, id)
                KeyboardOutcome.NOT_CONFIRMED -> KeyboardNotice(KeyboardNotice.Kind.NOT_CONFIRMED, id)
                KeyboardOutcome.NOT_ENABLED -> KeyboardNotice(KeyboardNotice.Kind.NOT_ENABLED, id)
                KeyboardOutcome.FACTORY, KeyboardOutcome.READ -> KeyboardNotice(KeyboardNotice.Kind.FACTORY)
            }
            mutable.update { it.copy(working = false, status = result?.status ?: it.status, notice = notice) }
        }
    }

    private data class Loaded(
        val availability: KeyboardAvailability?,
        val keyboards: List<InstalledKeyboard>,
        val labels: Map<String, String>,
        val result: KeyboardResult?,
        val readFailed: Boolean,
    )
}
