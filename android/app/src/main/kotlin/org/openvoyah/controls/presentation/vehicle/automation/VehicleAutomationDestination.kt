package org.openvoyah.controls.presentation.vehicle.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.vehicle.automation.DrivingMode
import org.openvoyah.controls.domain.vehicle.automation.HeadlightGearAction
import org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationOutcome
import org.openvoyah.controls.presentation.theme.LocalCockpitEmbedded
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitColumns
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.theme.CockpitTextAction
import org.openvoyah.controls.presentation.vehicle.headlightModeLabel

@Composable
internal fun VehicleAutomationDestination(
    model: VehicleAutomationViewModel,
    back: () -> Unit,
    climate: () -> Unit,
    rules: () -> Unit,
    routines: () -> Unit,
    factoryLighting: (() -> Unit)?,
    factoryDriving: (() -> Unit)?,
) {
    val state by model.state.collectAsStateWithLifecycle()
    var choosingDrive by remember { mutableStateOf<Boolean?>(null) }
    VehicleAutomationScreen(state, back, climate, rules, routines, factoryLighting, factoryDriving,
        model::setHeadlightsEnabled, { choosingDrive = true }, { choosingDrive = false }, model::setDrivingMemoryEnabled,
        model::prepareRestore, model::rememberCurrent)
    choosingDrive?.let { drive ->
        CockpitAlertDialog(
            onDismissRequest = { choosingDrive = null },
            title = { Text(text(if (drive) R.string.vehicle_automation_on_drive else R.string.vehicle_automation_on_park)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    HeadlightGearAction.entries.filter { !drive || it.allowedInDrive }.forEach { action ->
                        CockpitChoice(actionLabel(action), onClick = {
                            choosingDrive = null
                            if (drive) model.setDriveAction(action) else model.setParkAction(action)
                        })
                    }
                }
            },
            confirmButton = { CockpitTextAction(text(R.string.vehicle_automation_close)) { choosingDrive = null } },
        )
    }
    state.prompt?.let { prompt ->
        CockpitAlertDialog(
            onDismissRequest = model::dismissRestore,
            title = { Text(text(R.string.vehicle_automation_restore_title)) },
            text = { Text(text(R.string.vehicle_automation_restore_confirm).format(modeLabel(prompt.record.mode))) },
            dismissButton = { CockpitTextAction(text(R.string.vehicle_automation_cancel), model::dismissRestore) },
            confirmButton = { CockpitAction(text(R.string.vehicle_automation_restore), { model.confirmRestore(prompt) }, primary = true) },
        )
    }
}

@Composable
internal fun VehicleAutomationScreen(
    state: VehicleAutomationState,
    back: () -> Unit, climate: () -> Unit, rules: () -> Unit, routines: () -> Unit,
    factoryLighting: (() -> Unit)?, factoryDriving: (() -> Unit)?,
    setHeadlightsEnabled: (Boolean) -> Unit, chooseDrive: () -> Unit, choosePark: () -> Unit,
    setDrivingMemoryEnabled: (Boolean) -> Unit, restore: () -> Unit, rememberCurrent: () -> Unit,
) {
    val enabled = state.settings != null && !state.saving && !state.working
    // Settings → Routines and automation: the routines of Controls and everything the car does on its own.
    // Inside Settings → Routines and automation the chips move the page to the routines, rules and climate below.
    val embedded = LocalCockpitEmbedded.current
    CockpitDetail(text(if (embedded) R.string.settings_part_automation_vehicle else R.string.settings_section_automation),
        text(R.string.vehicle_automation_description), back) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CockpitChoice(text(R.string.settings_my_routines), modifier = Modifier.weight(1f), onClick = routines)
            CockpitChoice(text(R.string.vehicle_automation_rules), modifier = Modifier.weight(1f), onClick = rules)
            CockpitChoice(text(R.string.vehicle_automation_climate), modifier = Modifier.weight(1f), onClick = climate)
        }
        VehicleStatusSection(state.telemetry)
        CockpitColumns(first = {
            CockpitSection(text(R.string.vehicle_automation_lights), CockpitIcon.LIGHT) {
                CockpitSwitch(text(R.string.vehicle_automation_lights_enabled), state.settings?.headlights?.enabled == true,
                    enabled = enabled, change = setHeadlightsEnabled)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CockpitChoice(text(R.string.vehicle_automation_on_drive),
                        actionLabel(state.settings?.headlights?.onDrive ?: HeadlightGearAction.AUTOMATIC),
                        enabled = enabled, modifier = Modifier.weight(1f), onClick = chooseDrive)
                    CockpitChoice(text(R.string.vehicle_automation_on_park),
                        actionLabel(state.settings?.headlights?.onPark ?: HeadlightGearAction.KEEP),
                        enabled = enabled, modifier = Modifier.weight(1f), onClick = choosePark)
                }
                val current = state.lighting.currentObservation
                val confirmed = current ?: state.lighting.lastConfirmed
                Text(if (confirmed == null) text(R.string.headlight_mode_waiting) else
                    text(R.string.vehicle_automation_light_confirmation).format(text(headlightModeLabel(confirmed.mode)),
                        observationTime(confirmed.confirmedAtMillis),
                        text(if (current != null) R.string.vehicle_automation_current else R.string.vehicle_automation_history)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                if (state.capabilities.headlightActions.isEmpty()) {
                    Text(text(R.string.vehicle_automation_lights_blocked), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                factoryLighting?.let { CockpitAction(text(R.string.headlight_mode_open_factory), it) }
            }
        }, second = {
            CockpitSection(text(R.string.vehicle_automation_drive_memory), CockpitIcon.CAR) {
                CockpitSwitch(text(R.string.vehicle_automation_memory_enabled), state.settings?.drivingMode?.enabled == true,
                    enabled = enabled, change = setDrivingMemoryEnabled)
                val remembered = state.rememberedMode
                Text(if (remembered == null) text(R.string.vehicle_automation_memory_empty) else
                    text(R.string.vehicle_automation_memory_saved).format(modeLabel(remembered.mode), observationTime(remembered.confirmedAtMillis)),
                    style = MaterialTheme.typography.titleMedium)
                Text(state.actualMode?.let { text(R.string.vehicle_automation_actual_mode).format(modeLabel(it)) }
                    ?: text(R.string.vehicle_automation_actual_missing), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.capabilities.drivingModes.isEmpty()) Text(text(R.string.vehicle_automation_mode_blocked),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CockpitAction(text(R.string.vehicle_automation_restore), restore, Modifier.weight(1f),
                        enabled = enabled && state.restoreCandidate?.let { it.mode in state.capabilities.drivingModes } == true)
                    CockpitAction(text(R.string.vehicle_automation_remember_current), rememberCurrent, Modifier.weight(1f),
                        enabled = enabled && state.canRememberCurrent)
                }
                factoryDriving?.let { CockpitAction(text(R.string.vehicle_automation_open_driving), it) }
            }
        })
        if (state.settingsFailed || state.historyFailed || state.saveFailed) {
            CockpitFeedback(text(if (state.saveFailed) R.string.vehicle_automation_save_failed else R.string.vehicle_automation_load_failed), error = true)
        }
        state.outcome?.let { CockpitFeedback(text(outcomeLabel(it)), error = it in setOf(VehicleAutomationOutcome.FAILED, VehicleAutomationOutcome.UNCONFIRMED)) }
    }
}

private fun actionLabel(action: HeadlightGearAction): String = action.mode?.let { text(headlightModeLabel(it)) }
    ?: text(R.string.vehicle_automation_keep)

internal fun modeLabel(mode: DrivingMode): String = text(when (mode) {
    DrivingMode.ECO -> R.string.vehicle_automation_mode_eco
    DrivingMode.COMFORT -> R.string.vehicle_automation_mode_comfort
    DrivingMode.SPORT -> R.string.vehicle_automation_mode_sport
    DrivingMode.OFFROAD -> R.string.vehicle_automation_mode_offroad
    DrivingMode.INDIVIDUAL -> R.string.vehicle_automation_mode_individual
    DrivingMode.SNOW -> R.string.vehicle_automation_mode_snow
})

private fun outcomeLabel(outcome: VehicleAutomationOutcome): Int = when (outcome) {
    VehicleAutomationOutcome.BLOCKED -> R.string.vehicle_automation_action_blocked
    VehicleAutomationOutcome.REQUESTED -> R.string.vehicle_automation_requested
    VehicleAutomationOutcome.CONFIRMED -> R.string.vehicle_automation_confirmed
    VehicleAutomationOutcome.UNCONFIRMED -> R.string.vehicle_automation_unconfirmed
    VehicleAutomationOutcome.FAILED -> R.string.vehicle_automation_failed
}

private fun observationTime(millis: Long): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    .withLocale(Locale.forLanguageTag(UiText.language())).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))
private fun text(id: Int) = UiText.get(id)
