package org.openvoyah.controls.presentation.vehicle.comfort

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.vehicle.comfort.ComfortCommandAvailability
import org.openvoyah.controls.data.vehicle.comfort.ReviewedComfortCommands
import org.openvoyah.controls.domain.voice.VoiceOutcome
import org.openvoyah.controls.domain.voice.VoiceResult
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitTextAction
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout

internal enum class ComfortCardAction(val commandId: String, val title: Int, val confirmation: Int) {
    POWER_HOLD("PowerHold", R.string.comfort_power_hold, R.string.comfort_power_hold_confirm),
    CAR_WASH("CarWashMode", R.string.comfort_car_wash, R.string.comfort_car_wash_confirm),
}

private data class ComfortActionState(
    val availability: ComfortCommandAvailability = ComfortCommandAvailability.FIRMWARE_UNREVIEWED,
    val busy: Boolean = false,
    val result: VoiceResult? = null,
)

private class ComfortActionViewModel(private val commands: ReviewedComfortCommands, private val action: ComfortCardAction) : ViewModel() {
    private val state = MutableStateFlow(ComfortActionState())
    val current = state.asStateFlow()

    fun refresh() = state.update { it.copy(availability = commands.available(action.commandId)) }

    fun confirm(visible: AtomicBoolean) {
        refresh()
        if (!visible.get() || state.value.busy || state.value.availability != ComfortCommandAvailability.AVAILABLE) return
        state.update { it.copy(busy = true, result = null) }
        viewModelScope.launch {
            try {
                val receipt = commands.dispatch(action.commandId, visible::get)
                state.update { it.copy(result = receipt) }
            } finally {
                state.update { it.copy(busy = false) }
                refresh()
            }
        }
    }
}

@Composable
internal fun ComfortActionCard(action: ComfortCardAction, interactive: Boolean) {
    val app = LocalContext.current.applicationContext
    val activity = LocalActivity.current as ComponentActivity
    val model = remember(app, activity, action) {
        ViewModelProvider(activity, viewModelFactory {
            initializer { ComfortActionViewModel(ReviewedComfortCommands(app), action) }
        })["desktop.command.${action.commandId}", ComfortActionViewModel::class.java]
    }
    val state by model.current.collectAsStateWithLifecycle()
    val visible = remember { AtomicBoolean(false) }
    var confirming by remember { mutableStateOf(false) }
    if (interactive) LifecycleResumeEffect(model) {
        visible.set(true)
        model.refresh()
        onPauseOrDispose { visible.set(false); confirming = false }
    }
    val layout = LocalCockpitLayout.current
    val title = UiText.get(action.title)
    Column(Modifier.padding(horizontal = layout.spacing(13.dp), vertical = layout.spacing(10.dp)),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val reason = when (state.availability) {
            ComfortCommandAvailability.AVAILABLE -> null
            ComfortCommandAvailability.FORBIDDEN -> R.string.comfort_command_forbidden
            ComfortCommandAvailability.FIRMWARE_UNREVIEWED -> R.string.comfort_command_unreviewed
        }
        if (reason != null) Text(UiText.get(reason), style = MaterialTheme.typography.bodyMedium)
        else {
            CockpitAction(UiText.get(if (state.busy) R.string.comfort_command_sending else R.string.comfort_command_open),
                { if (visible.get()) confirming = true }, enabled = interactive && !state.busy)
            state.result?.let { receipt ->
                val message = if (action == ComfortCardAction.POWER_HOLD && receipt.outcome == VoiceOutcome.VEHICLE_ACCEPTED)
                    UiText.get(R.string.comfort_power_hold_requested)
                else receipt.message
                CockpitFeedback(message, error = receipt.failed)
            }
        }
    }
    if (confirming && interactive && state.availability == ComfortCommandAvailability.AVAILABLE) {
        CockpitAlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(title) },
            text = { Text(UiText.get(action.confirmation)) },
            dismissButton = { CockpitTextAction(UiText.get(R.string.vehicle_automation_cancel)) { confirming = false } },
            confirmButton = { CockpitAction(UiText.get(R.string.comfort_command_request), {
                confirming = false
                model.confirm(visible)
            }, primary = true) },
        )
    }
}
