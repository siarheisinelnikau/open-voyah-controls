package org.openvoyah.controls.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Documents and transactions have no Activity ownership. Preparation never changes settings. */
internal interface SettingsArchiveDocuments {
    interface Restore {
        val bindingCount: Int
        val complete: Boolean
    }

    fun export(document: String)
    fun prepare(document: String): Restore
    /** Returns whether the interface language changed. The transaction must finish or roll back. */
    fun apply(restore: Restore): Boolean
}

internal interface SettingsArchiveRuntime {
    fun configured(): Boolean
    suspend fun reconnect(): String?
    fun publish(error: (String) -> Unit)
}

internal enum class ArchiveRefresh { NONE, PUBLISH, RECONNECT;
    companion object {
        fun afterRestore(complete: Boolean, configured: Boolean): ArchiveRefresh = when {
            !complete -> PUBLISH
            configured -> RECONNECT
            else -> NONE
        }
    }
}

/** Retains the pending confirmation and result across recreation, without retaining the screen. */
internal class SettingsArchiveViewModel(
    private val documents: SettingsArchiveDocuments,
    private val runtime: SettingsArchiveRuntime,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val ownerScope: CoroutineScope? = null,
) : ViewModel() {
    enum class Work { EXPORT, PREPARE, APPLY }
    sealed interface State {
        data object Idle : State
        data class Working(val work: Work) : State
        data class Confirmation(val restore: SettingsArchiveDocuments.Restore) : State
        data class Completed(
            val exported: Boolean,
            val languageChanged: Boolean = false,
            val refreshPending: Boolean = !exported,
            val runtimeError: String? = null,
        ) : State
        data class Failed(val message: String?) : State
    }

    private val mutable = MutableStateFlow<State>(State.Idle)
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    @Volatile private var generation = 0L
    private val runtimeErrors = ConcurrentHashMap<Long, String>()
    private val scope: CoroutineScope get() = ownerScope ?: viewModelScope

    fun export(document: String) = start(Work.EXPORT) {
        runInterruptible(io) { documents.export(document) }
        State.Completed(exported = true)
    }

    fun prepare(document: String) = start(Work.PREPARE) {
        State.Confirmation(runInterruptible(io) { documents.prepare(document) })
    }

    fun confirm() {
        val confirmation = mutable.value as? State.Confirmation ?: return
        start(Work.APPLY, confirmation) { request ->
            // Closing the screen cannot interrupt a committed multi-store transaction or leave its
            // already configured workers using the previous localization dictionary.
            withContext(NonCancellable + io) {
                val refresh = ArchiveRefresh.afterRestore(confirmation.restore.complete, runtime.configured())
                val languageChanged = documents.apply(confirmation.restore)
                val result = State.Completed(exported = false, languageChanged = languageChanged)
                when (refresh) {
                    ArchiveRefresh.RECONNECT -> result.copy(runtimeError = try {
                        runtime.reconnect()
                    } catch (failure: Exception) {
                        failure.message ?: failure.javaClass.simpleName
                    })
                    ArchiveRefresh.PUBLISH -> {
                        try { runtime.publish { error -> runtimeFailed(request, error) } }
                        catch (failure: Exception) {
                            runtimeFailed(request, failure.message ?: failure.javaClass.simpleName)
                        }
                        result
                    }
                    ArchiveRefresh.NONE -> result
                }
            }
        }
    }

    /** Loading or an unconfirmed import can be cancelled. Applying has already been authorized. */
    fun cancel() {
        val before = mutable.value
        if (before is State.Working && before.work == Work.APPLY) return
        generation += 1
        runtimeErrors.clear()
        operation?.cancel()
        operation = null
        mutable.value = State.Idle
    }

    fun acknowledgeRefresh(completed: State.Completed) {
        mutable.compareAndSet(completed, completed.copy(refreshPending = false, languageChanged = false))
    }

    private fun start(work: Work, expected: State = State.Idle, block: suspend (Long) -> State) {
        val working = State.Working(work)
        if (!mutable.compareAndSet(expected, working)) return
        val request = ++generation
        runtimeErrors.clear()
        operation = scope.launch {
            val result = try {
                block(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                State.Failed(failure.message)
            }
            if (request != generation || mutable.value !== working) return@launch
            mutable.value = if (result is State.Completed)
                result.copy(runtimeError = result.runtimeError ?: runtimeErrors[request]) else result
        }
    }

    /** An asynchronous lightweight publish can fail before or after the completion is rendered. */
    private fun runtimeFailed(request: Long, error: String) {
        if (request != generation) return
        runtimeErrors[request] = error
        scope.launch {
            val completed = mutable.value as? State.Completed
            if (request == generation && completed != null && !completed.exported)
                mutable.compareAndSet(completed, completed.copy(runtimeError = error))
        }
    }

    override fun onCleared() {
        generation += 1
        runtimeErrors.clear()
        operation?.cancel()
        mutable.value = State.Idle
        super.onCleared()
    }
}
