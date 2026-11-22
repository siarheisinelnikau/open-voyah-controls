package org.openvoyah.controls.presentation.voice

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.VoiceCatalog
import org.openvoyah.controls.data.frida.ReviewedVoiceCommands
import org.openvoyah.controls.data.settings.VehicleCommandSwitches
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitModal
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.theme.CockpitTextAction

private data class VehicleCommandList(val commands: List<VoiceCatalog.Command>, val switchedOff: Set<String>)

/** Every command approved for this head unit with its own switch. Switching off is local and enforced by the app. */
@Composable
internal fun VehicleCommandsDialog(catalog: VoiceCatalog, close: () -> Unit) {
    val context = LocalContext.current
    val switches = remember(context) { VehicleCommandSwitches(context) }
    val loaded by produceState<VehicleCommandList?>(null, catalog) {
        value = withContext(Dispatchers.IO) {
            val approved = ReviewedVoiceCommands(context).approvedCommands()
            VehicleCommandList(approved.mapNotNull(catalog::byId), switches.switchedOff())
        }
    }
    val list = loaded
    var switchedOff by remember(list) { mutableStateOf(list?.switchedOff.orEmpty()) }
    CockpitModal(UiText.get(R.string.voice_vehicle_commands), footer = {
        CockpitTextAction(UiText.get(R.string.ui_89), close)
        if (!list?.commands.isNullOrEmpty()) CockpitAction(UiText.get(R.string.ui_104), {
            try {
                switches.replace(switchedOff)
                ControlsLog.info("vehicle_command_switches", "switched_off", switchedOff.size)
            } catch (failure: IllegalArgumentException) {
                ControlsLog.warn("vehicle_command_switches", failure, "outcome", "rejected")
            }
            close()
        }, primary = true)
    }) {
        if (list == null) return@CockpitModal
        if (list.commands.isEmpty()) {
            Text(UiText.get(R.string.voice_vehicle_commands_none), style = MaterialTheme.typography.bodyLarge)
            return@CockpitModal
        }
        Text(UiText.get(R.string.voice_vehicle_commands_hint), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(Modifier.fillMaxWidth()) {
            CockpitTextAction(UiText.get(R.string.voice_vehicle_commands_all_on)) { switchedOff = emptySet() }
            CockpitTextAction(UiText.get(R.string.voice_vehicle_commands_all_off)) {
                switchedOff = list.commands.mapTo(sortedSetOf()) { it.id() }
            }
        }
        for (command in list.commands) {
            CockpitSwitch(command.title(), command.id() !in switchedOff) { on ->
                switchedOff = if (on) switchedOff - command.id() else switchedOff + command.id()
            }
        }
    }
}
