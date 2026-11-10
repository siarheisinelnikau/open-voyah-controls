package org.openvoyah.controls.presentation.maintenance

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A confirmed operation can finish when its screen closes. Reopening observes the same result. */
internal class MaintenanceWork(
    initial: String?,
    private val working: () -> String,
    private val failure: (Exception) -> String,
    private val persist: (String) -> Unit = {},
) {
    data class State(val message: String?, val working: Boolean = false, val failed: Boolean = false)

    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val current = MutableStateFlow(State(initial))
    val state = current.asStateFlow()

    fun run(operation: () -> String) {
        val before = current.value
        if (before.working || !current.compareAndSet(before, State(working(), working = true))) return
        worker.launch {
            val result = try {
                persist(requireNotNull(current.value.message))
                State(operation())
            } catch (error: Exception) {
                State(failure(error), failed = true)
            }
            current.value = try {
                result.message?.let(persist)
                result
            } catch (error: Exception) {
                State(failure(error), failed = true)
            }
        }
    }

    fun report(message: String) {
        val before = current.value
        if (!before.working) current.compareAndSet(before, State(message, failed = true))
    }
}

internal class MaintenanceViewModel(private val work: MaintenanceWork) : ViewModel() {
    val state = work.state
    fun run(operation: () -> String) = work.run(operation)
    fun report(message: String) = work.report(message)
}
