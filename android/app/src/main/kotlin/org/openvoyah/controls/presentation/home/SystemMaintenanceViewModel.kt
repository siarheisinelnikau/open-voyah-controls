package org.openvoyah.controls.presentation.home

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
import org.openvoyah.controls.data.frida.ActivationRecovery
import org.openvoyah.controls.data.frida.SystemRestartScripts

/** The root operations behind the restart actions; the Frida bridge implements it, tests use a fake. */
internal interface SystemRestarts {
    fun restartMultimedia(): SystemRestartScripts.MediaRestart
    /** Returns once the reboot was requested; throws when it was refused (for example, not in Park). */
    fun rebootSystem()
    /** Re-arms the car agents without the PC when the approved deployment still verifies. */
    fun restoreActivation(): ActivationRecovery.Result
}

internal enum class SystemOperation { MEDIA, REBOOT, RESTORE }

/** What the page tells the owner after an operation. [message] is the refusal text from the bridge, if any. */
internal sealed interface SystemNotice {
    data class MediaRestarted(val result: SystemRestartScripts.MediaRestart) : SystemNotice
    data object RebootRequested : SystemNotice
    data class Restored(val result: ActivationRecovery.Result) : SystemNotice
    data class Failed(val operation: SystemOperation, val message: String?) : SystemNotice
}

internal class SystemMaintenanceViewModel(
    private val restarts: SystemRestarts,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    tasks: CoroutineScope? = null,
) : ViewModel() {
    private val scope = tasks ?: viewModelScope

    data class State(val working: SystemOperation? = null, val notice: SystemNotice? = null)

    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()

    fun restartMultimedia() = run(SystemOperation.MEDIA) { SystemNotice.MediaRestarted(restarts.restartMultimedia()) }

    fun rebootSystem() = run(SystemOperation.REBOOT) { restarts.rebootSystem(); SystemNotice.RebootRequested }

    fun restoreActivation() = run(SystemOperation.RESTORE) { SystemNotice.Restored(restarts.restoreActivation()) }

    fun acknowledge() = mutable.update { it.copy(notice = null) }

    private fun run(operation: SystemOperation, work: () -> SystemNotice) {
        val previous = mutable.value
        // One root operation at a time: a second tap while the first runs is ignored.
        if (previous.working != null || !mutable.compareAndSet(previous, previous.copy(working = operation, notice = null))) return
        scope.launch {
            val notice = try {
                withContext(io) { work() }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(working = null) }
                throw cancelled
            } catch (failure: Exception) {
                SystemNotice.Failed(operation, failure.message)
            }
            mutable.update { State(working = null, notice = notice) }
        }
    }
}
