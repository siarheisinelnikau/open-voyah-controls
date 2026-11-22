package org.openvoyah.controls.presentation.permissions

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.frida.ReviewedFirmware
import org.openvoyah.controls.data.frida.ReviewedVoiceCommands
import org.openvoyah.controls.data.settings.ActionPermissions
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.permissions.ActionCatalog
import org.openvoyah.controls.domain.permissions.ActionItem
import org.openvoyah.controls.domain.permissions.ActionKind
import org.openvoyah.controls.domain.permissions.ActionPermission
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitSection

/** One row of Settings → Allowed actions. [verified] comes from an optional review row for this firmware. */
internal data class ActionRow(val item: ActionItem, val title: String, val summary: String?, val verified: Boolean)

internal data class ActionPermissionList(val rows: List<ActionRow>, val states: Map<ActionItem, ActionPermission>)

@Composable
internal fun ActionPermissionsDestination(back: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val loaded by produceState<Pair<ActionPermissions, ActionPermissionList>?>(null) {
        value = withContext(Dispatchers.IO) { load(context) }
    }
    var states by remember(loaded) { mutableStateOf(loaded?.second?.states.orEmpty()) }
    val store = loaded?.first
    ActionPermissionsScreen(loaded?.second?.rows, states, back,
        change = { item, state ->
            states = states + (item to state)
            if (store != null) scope.launch(Dispatchers.IO) { store.set(item, state) }
        },
        reset = {
            states = states.mapValues { ActionPermission.ALLOWED }
            if (store != null) scope.launch(Dispatchers.IO) { store.replace(emptyMap()) }
        },
    )
}

/** Stateless body, so Compose tests render it without preferences or a firmware review. */
@Composable
internal fun ActionPermissionsScreen(
    rows: List<ActionRow>?,
    states: Map<ActionItem, ActionPermission>,
    back: () -> Unit,
    change: (ActionItem, ActionPermission) -> Unit,
    reset: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    CockpitDetail(text(R.string.action_permissions_title), text(R.string.action_permissions_summary), back) {
        CockpitSection(text(R.string.action_permissions_title), CockpitIcon.LOCK) {
            Text(text(R.string.action_permissions_legend), style = MaterialTheme.typography.bodyMedium)
            Text(text(R.string.action_permissions_safety), color = muted, style = MaterialTheme.typography.bodyMedium)
            CockpitAction(text(R.string.action_permissions_reset), reset, Modifier.testTag("action_permissions_reset"),
                enabled = states.values.any { it != ActionPermission.ALLOWED })
        }
        if (rows == null) return@CockpitDetail
        for (kind in ActionKind.entries) {
            val group = rows.filter { it.item.kind == kind }
            val (title, summary, icon) = when (kind) {
                ActionKind.OPERATION -> Triple(R.string.action_group_operations, R.string.action_group_operations_summary, CockpitIcon.SYSTEM)
                ActionKind.AGENT -> Triple(R.string.action_group_agents, R.string.action_group_agents_summary, CockpitIcon.FIRMWARE)
                ActionKind.VOICE -> Triple(R.string.action_group_voice, R.string.action_group_voice_summary, CockpitIcon.VOICE)
            }
            CockpitSection(text(title), icon, text(summary)) {
                if (group.isEmpty()) Text(text(R.string.action_group_voice_empty), color = muted)
                group.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider()
                    ActionPermissionRow(row, states[row.item] ?: ActionPermission.ALLOWED) { change(row.item, it) }
                }
            }
        }
    }
}

@Composable
private fun ActionPermissionRow(row: ActionRow, state: ActionPermission, change: (ActionPermission) -> Unit) {
    val palette = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("action_${row.item.key}"),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(row.title, style = MaterialTheme.typography.titleMedium)
        row.summary?.let { Text(it, color = palette.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
        if (row.verified) Text(text(R.string.action_permissions_verified), color = palette.primary,
            style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (choice in ActionPermission.entries) {
                val label = text(stateLabel(choice))
                val colors = if (choice == ActionPermission.FORBIDDEN) FilterChipDefaults.filterChipColors(
                    selectedContainerColor = palette.errorContainer, selectedLabelColor = palette.onErrorContainer)
                else FilterChipDefaults.filterChipColors(
                    selectedContainerColor = palette.primaryContainer, selectedLabelColor = palette.onPrimaryContainer)
                FilterChip(choice == state, { if (choice != state) change(choice) }, label = { Text(label) },
                    modifier = Modifier.testTag("action_${row.item.key}_${choice.name.lowercase()}")
                        .semantics { contentDescription = "${row.title}, $label" },
                    colors = colors)
            }
        }
    }
}

internal fun stateLabel(state: ActionPermission): Int = when (state) {
    ActionPermission.ALLOWED -> R.string.action_state_allowed
    ActionPermission.UNVERIFIED -> R.string.action_state_unverified
    ActionPermission.FORBIDDEN -> R.string.action_state_forbidden
}

/** Titles and descriptions of the fixed operations and agent channels. */
internal fun actionRows(verifiedOperations: Set<String>): List<ActionRow> {
    val operations = ActionCatalog.operations.map { id ->
        val (title, summary) = when (id) {
            ActionCatalog.WINDOW_SERVICE -> R.string.action_window_service to R.string.action_window_service_summary
            ActionCatalog.PASSENGER_WINDOW_DPI -> R.string.action_passenger_window_dpi to R.string.action_passenger_window_dpi_summary
            ActionCatalog.WINDOW_DISPLAY_MOVE -> R.string.action_window_display_move to R.string.action_window_display_move_summary
            ActionCatalog.CAN_STEERING -> R.string.action_can_steering to R.string.action_can_steering_summary
            ActionCatalog.CAN_STEERING_EDGES -> R.string.action_can_steering_edges to R.string.action_can_steering_edges_summary
            ActionCatalog.OEM_PROFILES -> R.string.action_oem_profiles to R.string.action_oem_profiles_summary
            ActionCatalog.CLUSTER_WRITE -> R.string.action_cluster_write to R.string.action_cluster_write_summary
            ActionCatalog.TEXT_OVERLAYS -> R.string.action_text_overlays to R.string.action_text_overlays_summary
            ActionCatalog.TBOX_REBOOT -> R.string.action_tbox_reboot to R.string.action_tbox_reboot_summary
            ActionCatalog.MEDIA_RESTART -> R.string.action_media_restart to R.string.action_media_restart_summary
            else -> R.string.action_system_reboot to R.string.action_system_reboot_summary
        }
        ActionRow(ActionCatalog.operation(id), text(title), text(summary), id in verifiedOperations)
    }
    val agents = ActionCatalog.agents.map { id ->
        val (title, summary) = when (id) {
            ActionCatalog.NAVIGATION -> R.string.action_navigation to R.string.action_navigation_summary
            "locale-setting" -> R.string.action_locale_setting to R.string.action_locale_summary
            "locale-vehicle" -> R.string.action_locale_vehicle to R.string.action_locale_summary
            "locale-bluetooth" -> R.string.action_locale_bluetooth to R.string.action_locale_summary
            else -> R.string.action_locale_hiboard to R.string.action_locale_summary
        }
        ActionRow(ActionCatalog.agent(id), text(title), text(summary), false)
    }
    return operations + agents
}

private fun load(context: Context): Pair<ActionPermissions, ActionPermissionList> {
    val store = ActionPermissions(context)
    // Review rows are optional; an unknown firmware simply shows no "verified" marks.
    val verifiedOperations = runCatching { ReviewedFirmware.load(context).operations }.getOrDefault(emptySet())
    val verifiedCommands = runCatching { ReviewedVoiceCommands(context).verifiedCommands() }.getOrDefault(emptySet())
    val commands = try {
        VoiceSettings(context).withContacts().all().filter { ActionCatalog.isVehicleCommand(it.id()) }
    } catch (failure: Exception) {
        ControlsLog.warn("action_permissions_catalog", failure, "outcome", "unavailable")
        emptyList()
    }
    val rows = actionRows(verifiedOperations) + commands.map {
        ActionRow(ActionCatalog.voice(it.id()), it.title().replaceFirstChar(Char::titlecase), null, it.id() in verifiedCommands)
    }
    return store to ActionPermissionList(rows, rows.associate { it.item to store.state(it.item) })
}

private fun text(id: Int): String = UiText.get(id)
