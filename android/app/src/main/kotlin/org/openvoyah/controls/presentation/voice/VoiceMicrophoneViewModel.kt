package org.openvoyah.controls.presentation.voice

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Android supplies observations; the screen owns the permission or settings handoff. */
internal class VoiceMicrophoneViewModel : ViewModel() {
    enum class Access { ALLOWED, REQUESTABLE, SETTINGS }
    data class State(val access: Access = Access.REQUESTABLE, val requesting: Boolean = false)

    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()

    fun refresh(granted: Boolean, requested: Boolean, showRationale: Boolean, requestFinished: Boolean = false) {
        val access = when {
            granted -> Access.ALLOWED
            requested && !showRationale -> Access.SETTINGS
            else -> Access.REQUESTABLE
        }
        mutable.update { it.copy(access = access, requesting = it.requesting && !requestFinished && !granted) }
    }

    fun beginRequest(): Boolean {
        val current = mutable.value
        if (current.access != Access.REQUESTABLE || current.requesting) return false
        return mutable.compareAndSet(current, current.copy(requesting = true))
    }
}
