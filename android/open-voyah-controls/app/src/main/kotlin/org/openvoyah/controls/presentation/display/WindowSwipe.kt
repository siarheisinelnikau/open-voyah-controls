package org.openvoyah.controls.presentation.display

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.display.AppDisplayDefaults
import org.openvoyah.controls.domain.display.AppWindowConfiguration
import org.openvoyah.controls.domain.display.DisplayDefaultsDelivery
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitOptionDialog
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog as AlertDialog

/**
 * The swipe that moves an app's window to the instrument cluster or the passenger display. Stored with the display
 * defaults, so a backup keeps it; the window agent watches touches only while it is on.
 */
internal class WindowSwipeViewModel(private val windows: AppWindowConfiguration) : ViewModel() {
    data class State(
        val visible: Boolean = false,
        /** The stored number of fingers, or null before it was read. */
        val saved: Int? = null,
        val fingers: Int = 0,
        /** After a window left the central display it shows the Controls app grid instead of the factory home. */
        val showsApps: Boolean = false,
        val working: Boolean = false,
        val message: Int? = null,
    )

    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()

    fun open() {
        if (mutable.value.visible) return
        mutable.value = State(visible = true, working = true)
        viewModelScope.launch {
            val stored = attempt { windows.readDisplayDefaults() }
            mutable.value = State(visible = true, saved = stored?.moveFingers, fingers = stored?.moveFingers ?: 0,
                showsApps = stored?.moveShowsApps ?: false,
                message = if (stored == null) R.string.window_swipe_unreadable else null)
        }
    }

    fun close() {
        if (!mutable.value.working) mutable.value = State()
    }

    fun choose(fingers: Int) {
        val state = mutable.value
        if (state.working || state.saved == null || fingers !in AppDisplayDefaults.moveFingerChoices) return
        mutable.value = state.copy(fingers = fingers, message = null)
    }

    fun chooseTarget(showsApps: Boolean) {
        val state = mutable.value
        if (state.working || state.saved == null) return
        mutable.value = state.copy(showsApps = showsApps, message = null)
    }

    fun save() {
        val state = mutable.value
        if (state.working || state.saved == null) return
        mutable.value = state.copy(working = true, message = null)
        viewModelScope.launch {
            // The densities are read again: only the swipe changes here.
            val delivery = attempt {
                windows.changeDisplayDefaults(windows.readDisplayDefaults()
                    .copy(moveFingers = state.fingers, moveShowsApps = state.showsApps))
            }
            val message = when {
                delivery == null -> R.string.window_swipe_failed
                delivery == DisplayDefaultsDelivery.SAVED -> R.string.window_swipe_pending
                delivery == DisplayDefaultsDelivery.MOVE_UNAVAILABLE -> R.string.window_swipe_unavailable
                state.fingers == 0 -> R.string.window_swipe_turned_off
                else -> R.string.window_swipe_ready
            }
            mutable.value = state.copy(working = false, saved = if (delivery == null) state.saved else state.fingers,
                message = message)
        }
    }

    fun recall() {
        val state = mutable.value
        if (state.working) return
        mutable.value = state.copy(working = true, message = null)
        viewModelScope.launch {
            val returned = attempt { windows.recallMovedWindows() } == true
            mutable.value = mutable.value.copy(working = false,
                message = if (returned) R.string.window_swipe_recalled else R.string.window_swipe_recall_failed)
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

@Composable
internal fun WindowSwipeControl(model: WindowSwipeViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    CockpitChoice(R.string.window_swipe_title, onClick = model::open)
    if (!state.visible) return

    AlertDialog(
        onDismissRequest = model::close,
        title = { Text(label(R.string.window_swipe_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(label(R.string.window_swipe_description))
                if (state.saved != null) {
                    FingerPicker(state.fingers, !state.working, model::choose)
                    TargetPicker(state.showsApps, !state.working && state.fingers != 0, model::chooseTarget)
                }
                val message = if (state.working) R.string.ui_80 else state.message
                message?.let { Text(label(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.saved != null && state.saved != 0) {
                    CockpitAction(label(R.string.window_swipe_recall), model::recall, enabled = !state.working)
                }
            }
        },
        confirmButton = {
            CockpitAction(label(R.string.ui_104), model::save, enabled = state.saved != null && !state.working, primary = true)
        },
        dismissButton = { CockpitAction(label(R.string.app_display_defaults_close), model::close, enabled = !state.working) },
    )
}

@Composable
private fun FingerPicker(fingers: Int, enabled: Boolean, change: (Int) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    val title = label(R.string.window_swipe_fingers)
    CockpitChoice(R.string.window_swipe_fingers, fingerText(fingers), enabled) { choosing = true }
    if (choosing) {
        CockpitOptionDialog(title, AppDisplayDefaults.moveFingerChoices, fingers, ::fingerText, change,
            { choosing = false }, enabled = enabled)
    }
}

@Composable
private fun TargetPicker(showsApps: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    val title = label(R.string.window_swipe_after)
    CockpitChoice(R.string.window_swipe_after, targetText(showsApps), enabled) { choosing = true }
    if (choosing) {
        CockpitOptionDialog(title, listOf(false, true), showsApps, ::targetText, change, { choosing = false }, enabled = enabled)
    }
}

internal fun targetText(showsApps: Boolean): String =
    label(if (showsApps) R.string.window_swipe_after_apps else R.string.window_swipe_after_home)

internal fun fingerText(fingers: Int): String = label(when (fingers) {
    2 -> R.string.window_swipe_two
    3 -> R.string.window_swipe_three
    else -> R.string.window_swipe_off
})

private fun label(id: Int): String = UiText.get(id)
