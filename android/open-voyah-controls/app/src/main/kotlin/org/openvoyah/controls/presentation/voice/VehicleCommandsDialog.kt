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
import org.openvoyah.controls.data.settings.ActionPermissions
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.permissions.ActionCatalog
import org.openvoyah.controls.domain.permissions.ActionItem
import org.openvoyah.controls.domain.permissions.ActionPermission
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitModal
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.theme.CockpitTextAction

private data class VehicleCommandList(
    val store: ActionPermissions,
    val commands: List<VoiceCatalog.Command>,
    val states: Map<String, ActionPermission>,
)

/**
 * A shortcut into Settings → Allowed actions for the vehicle voice commands: a switch per command, on by default.
 * Off is Forbidden; switching on again keeps an Unverified mark the owner set there.
 */
@Composable
internal fun VehicleCommandsDialog(catalog: VoiceCatalog, close: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val loaded by produceState<VehicleCommandList?>(null, catalog) {
        value = withContext(Dispatchers.IO) {
            val store = ActionPermissions(context)
            val supported = ReviewedVoiceCommands(context).hasApprovedCommands()
            val commands = if (supported) catalog.all().filter { ActionCatalog.isVehicleCommand(it.id()) } else emptyList()
            VehicleCommandList(store, commands, commands.associate { it.id() to store.voice(it.id()) })
        }
    }
    val list = loaded
    var states by remember(list) { mutableStateOf(list?.states.orEmpty()) }
    CockpitModal(UiText.get(R.string.voice_vehicle_commands), footer = {
        CockpitTextAction(UiText.get(R.string.ui_89), close)
        if (list != null && list.commands.isNotEmpty()) CockpitAction(UiText.get(R.string.ui_104), {
            try {
                // Operations, agents and commands not listed here keep their choices.
                val others = list.store.changes().mapNotNull { (key, state) ->
                    ActionCatalog.parseKey(key)?.takeUnless { it.isVoice && it.id in states }?.let { it to state }
                }.toMap()
                list.store.replace(others + states.mapKeys { ActionCatalog.voice(it.key) })
                ControlsLog.info("vehicle_command_switches", "forbidden", states.count { it.value == ActionPermission.FORBIDDEN })
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
            CockpitTextAction(UiText.get(R.string.voice_vehicle_commands_all_on)) {
                states = states.mapValues { (_, state) -> if (state == ActionPermission.FORBIDDEN) ActionPermission.ALLOWED else state }
            }
            CockpitTextAction(UiText.get(R.string.voice_vehicle_commands_all_off)) {
                states = states.mapValues { ActionPermission.FORBIDDEN }
            }
        }
        val guides = remember(list) { list.commands.map { describeVoiceCommand(it, assigned = false) } }
        for ((category, commands) in filterVoiceGuides(guides, null, "")) {
            VoiceCategoryHeading(category)
            for (guide in commands) {
                val id = guide.command.id()
                val state = states[id] ?: ActionPermission.ALLOWED
                CockpitSwitch(guide.command.title(), state.runs, guideId = R.string.voice_vehicle_commands,
                    guideEffect = guide.effect) { on ->
                    states = states + (id to when {
                        !on -> ActionPermission.FORBIDDEN
                        list.states[id] == ActionPermission.UNVERIFIED -> ActionPermission.UNVERIFIED
                        else -> ActionPermission.ALLOWED
                    })
                }
            }
        }
    }
}

private val ActionItem.isVoice get() = kind == org.openvoyah.controls.domain.permissions.ActionKind.VOICE
