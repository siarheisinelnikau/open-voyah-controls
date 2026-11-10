package org.openvoyah.controls.presentation.display

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.R
import org.openvoyah.controls.data.frida.FridaWindowControls
import org.openvoyah.controls.data.frida.FridaWindowControls.DefaultsDelivery
import org.openvoyah.controls.domain.display.AppDisplayDefaults

internal class AppDisplayDefaultsViewModel(private val windows: FridaWindowControls) : ViewModel() {
    data class State(
        val visible: Boolean = false,
        val draft: AppDisplayDefaults? = null,
        val working: Boolean = false,
        val message: Int? = null,
    )

    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()

    fun open() {
        if (mutable.value.visible) return
        mutable.value = State(visible = true, working = true)
        viewModelScope.launch {
            val draft = try {
                withContext(Dispatchers.IO) { windows.loadDisplayDefaults() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                null
            }
            mutable.value = State(visible = true, draft = draft,
                message = if (draft == null) R.string.app_display_defaults_unreadable else null)
        }
    }

    fun close() {
        if (!mutable.value.working) mutable.value = State()
    }

    fun change(central: Boolean, dpi: Int?) {
        mutable.update { state ->
            val draft = state.draft ?: return@update state
            if (state.working) state
            else state.copy(
                draft = if (central) draft.copy(centralDpi = requireNotNull(dpi)) else draft.copy(secondaryDpi = dpi),
                message = null,
            )
        }
    }

    fun save() {
        val state = mutable.value
        val draft = state.draft ?: return
        if (state.working) return
        mutable.value = state.copy(working = true, message = null)
        viewModelScope.launch {
            val message = try {
                val delivered = withContext(Dispatchers.IO) { windows.saveDisplayDefaults(draft) }
                when (delivered) {
                    DefaultsDelivery.SAVED -> R.string.app_display_defaults_pending
                    DefaultsDelivery.CENTRAL_ONLY -> R.string.app_display_defaults_central_only
                    DefaultsDelivery.NEXT_LAUNCH -> R.string.app_display_defaults_synced
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                R.string.app_display_defaults_failed
            }
            mutable.value = state.copy(working = false, message = message)
        }
    }
}
