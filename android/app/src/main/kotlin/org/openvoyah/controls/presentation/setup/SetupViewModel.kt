package org.openvoyah.controls.presentation.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openvoyah.controls.R
import org.openvoyah.controls.data.frida.FirstRunCheck

class SetupViewModel(app: Application) : AndroidViewModel(app) {
    data class State(val message: Int = R.string.setup_check_root, val finished: Boolean = false)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var activeNonce: String? = null
    private var running: Job? = null
    private val serialization = Mutex()
    fun start(nonce: String) {
        if (activeNonce == nonce) return
        activeNonce = nonce
        running?.cancel()
        mutable.value = State()
        running = viewModelScope.launch {
            serialization.withLock {
                val message = FirstRunCheck(getApplication()).run(nonce) {
                    if (activeNonce == nonce) mutable.value = State(it)
                }
                if (activeNonce == nonce) mutable.value = State(message, true)
            }
        }
    }
}
