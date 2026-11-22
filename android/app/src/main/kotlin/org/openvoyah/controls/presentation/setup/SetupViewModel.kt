package org.openvoyah.controls.presentation.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openvoyah.controls.R
import org.openvoyah.controls.data.frida.FirstRunCheck
import org.openvoyah.controls.domain.setup.SetupProgress

class SetupViewModel(app: Application) : AndroidViewModel(app) {
    /** [message] is the summary: the final result once [finished], otherwise unused while the rows run. */
    data class State(
        val progress: SetupProgress = SetupProgress(),
        val message: Int = R.string.setup_check_root,
        val finished: Boolean = false,
    ) {
        /** Read from the result text alone, so the check and its report to the installer stay as they are. */
        val failed: Boolean get() = finished && message in failures
    }
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var activeNonce: String? = null
    private var attempt = 0
    private var running: Job? = null
    private val serialization = Mutex()

    fun start(nonce: String) {
        if (activeNonce == nonce) return
        launchCheck(nonce)
    }

    /** Runs the whole check again for the same installer request; the new report replaces the previous one. */
    fun retry() {
        val nonce = activeNonce ?: return
        if (!mutable.value.finished) return
        launchCheck(nonce)
    }

    private fun launchCheck(nonce: String) {
        activeNonce = nonce
        val current = ++attempt
        running?.cancel()
        mutable.value = State()
        running = viewModelScope.launch {
            serialization.withLock {
                val message = FirstRunCheck(getApplication()).run(nonce) { progress ->
                    if (attempt == current) mutable.update { it.copy(progress = progress) }
                }
                if (attempt == current) mutable.update { it.copy(message = message, finished = true) }
            }
        }
    }
}

private val failures = setOf(R.string.setup_failed, R.string.setup_root_failed, R.string.setup_voice_failed,
    R.string.setup_space_failed, R.string.setup_handlers_failed)
