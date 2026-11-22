package org.openvoyah.controls.presentation.setup

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openvoyah.controls.data.frida.FirstRunCheck

/**
 * First-run checks belong to the process, not to the page that shows them. The installer waits for the report the
 * check writes at its end, while the page can be covered, recreated or dropped: it is excluded from the recents, and
 * Android removes such a task once it is behind home and another task starts. In boot 207 the startup intro's task
 * did that, the check was cancelled with the page's ViewModel and the installer never got its answer.
 *
 * Main thread only.
 */
internal object SetupRuns {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(SetupViewModel.State())
    val state: StateFlow<SetupViewModel.State> = mutable.asStateFlow()
    private var activeNonce: String? = null
    private var attempt = 0
    private var running: Job? = null
    private val serialization = Mutex()

    /** True while a check runs; the startup intro does not start then. */
    val active: Boolean get() = running?.isActive == true

    /** Starts the check for an installer request; a page opened again for the same request shows the same run. */
    fun start(app: Application, nonce: String) {
        if (activeNonce == nonce) return
        launchCheck(app, nonce)
    }

    /** Runs the whole check again for the same installer request; the new report replaces the previous one. */
    fun retry(app: Application) {
        val nonce = activeNonce ?: return
        if (!mutable.value.finished) return
        launchCheck(app, nonce)
    }

    private fun launchCheck(app: Application, nonce: String) {
        activeNonce = nonce
        val current = ++attempt
        running?.cancel()
        mutable.value = SetupViewModel.State()
        running = scope.launch {
            serialization.withLock {
                val message = FirstRunCheck(app).run(nonce) { progress ->
                    if (attempt == current) mutable.update { it.copy(progress = progress) }
                }
                if (attempt == current) mutable.update { it.copy(message = message, finished = true) }
            }
        }
    }
}
