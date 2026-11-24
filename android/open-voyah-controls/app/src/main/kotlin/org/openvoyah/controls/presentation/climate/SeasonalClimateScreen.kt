package org.openvoyah.controls.presentation.climate

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.climate.ClimateApplyResult
import org.openvoyah.controls.domain.climate.ClimateApplyStatus
import org.openvoyah.controls.domain.climate.ClimateConditionState
import org.openvoyah.controls.domain.climate.ClimateGearCondition
import org.openvoyah.controls.domain.climate.ClimateSeason
import org.openvoyah.controls.domain.climate.SeasonalClimatePreset
import org.openvoyah.controls.domain.climate.SeasonalClimateSettings
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitColumns
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitOptionDialog
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.theme.CockpitTextAction
import org.openvoyah.controls.presentation.theme.CockpitTextField
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout
import org.openvoyah.controls.presentation.theme.LocalCockpitMotion
import org.openvoyah.controls.presentation.theme.cockpitSwap

@Composable
internal fun SeasonalClimateDestination(model: SeasonalClimateViewModel, back: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    var editing by rememberSaveable { mutableStateOf<ClimateSeason?>(null) }
    LifecycleResumeEffect(model) { onPauseOrDispose { model.dismissConfirmation() } }
    BackHandler(editing != null) { editing = null }
    SeasonalClimateScreen(state, editing, { editing = it }, back, model::savePreset, model::requestApply)
    state.confirmation?.let { pending ->
        CockpitAlertDialog(onDismissRequest = model::dismissConfirmation,
            title = { Text(text(R.string.seasonal_climate_confirm_title)) },
            text = { Text(text(R.string.seasonal_climate_confirm_body).format(pending.plan.season.label(),
                pending.plan.preset.driverCelsius, pending.plan.preset.passengerCelsius, pending.plan.preset.fanLevel) +
                "\n" + seatSummary(pending.plan.season, pending.plan.preset)) },
            confirmButton = { CockpitAction(text(R.string.seasonal_climate_apply), model::confirmApply, primary = true) },
            dismissButton = { CockpitTextAction(text(R.string.seasonal_climate_cancel), model::dismissConfirmation) })
    }
}

@Composable
internal fun SeasonalClimateScreen(
    state: SeasonalClimateUiState,
    editing: ClimateSeason?,
    edit: (ClimateSeason?) -> Unit,
    back: () -> Unit,
    save: (ClimateSeason, SeasonalClimatePreset) -> Unit,
    requestApply: (ClimateSeason) -> Unit,
) {
    val settings = state.settings
    val selected = editing?.takeIf { settings != null }
    val motion = LocalCockpitMotion.current
    val offset = with(LocalDensity.current) { 20.dp.roundToPx() }
    AnimatedContent(selected, transitionSpec = { cockpitSwap(motion, offset, forward = targetState != null) },
        label = "climate views") { season ->
        CockpitDetail(season?.let { text(R.string.seasonal_climate_edit_title).format(it.label()) }
            ?: text(R.string.seasonal_climate_title), text(R.string.seasonal_climate_summary),
            back = { if (season != null) edit(null) else back() }, nested = season != null) {
            if (state.loadFailed) CockpitFeedback(text(R.string.seasonal_climate_load_failed), error = true)
            if (settings != null) {
                if (season == null) {
                    CockpitColumns(first = {
                        PresetOverview(ClimateSeason.SUMMER, settings.summer, state, { edit(ClimateSeason.SUMMER) }, save, requestApply)
                    }, second = {
                        PresetOverview(ClimateSeason.WINTER, settings.winter, state, { edit(ClimateSeason.WINTER) }, save, requestApply)
                    })
                    if (state.approved.size != ClimateSeason.entries.size)
                        CockpitFeedback(text(R.string.seasonal_climate_unreviewed))
                } else key(season, settings) {
                    SeasonalClimateEditor(season, settings, state.form.busy, save)
                }
            }
            when {
                state.form.saving -> CockpitFeedback(text(R.string.seasonal_climate_saving))
                state.form.applying -> CockpitFeedback(text(R.string.seasonal_climate_applying))
                state.form.saveFailed -> CockpitFeedback(text(R.string.seasonal_climate_save_failed), error = true)
                state.form.changed -> CockpitFeedback(text(R.string.seasonal_climate_changed), error = true)
            }
            state.form.lastResult?.let { CockpitFeedback(it.label(), error = it.status !in setOf(ClimateApplyStatus.VERIFIED, ClimateApplyStatus.ACCEPTED)) }
        }
    }
}

@Composable
private fun PresetOverview(
    season: ClimateSeason,
    preset: SeasonalClimatePreset,
    state: SeasonalClimateUiState,
    edit: () -> Unit,
    save: (ClimateSeason, SeasonalClimatePreset) -> Unit,
    apply: (ClimateSeason) -> Unit,
) {
    val condition = state.conditions[season] ?: ClimateConditionState.DISABLED
    CockpitSection(season.label(), CockpitIcon.SETTINGS) {
        Text(text(if (season == ClimateSeason.SUMMER) R.string.seasonal_climate_above else R.string.seasonal_climate_below)
            .format(preset.outsideThresholdCelsius), style = MaterialTheme.typography.titleMedium)
        Text(text(R.string.seasonal_climate_setpoints).format(preset.driverCelsius, preset.passengerCelsius, preset.fanLevel),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val gear = preset.gearCondition.label()
        Text(if (preset.requireDoorsClosed) text(R.string.seasonal_climate_closed_summary).format(gear) else gear,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (preset.driverSeatLevel > 0 || preset.passengerSeatLevel > 0)
            Text(seatSummary(season, preset), color = MaterialTheme.colorScheme.onSurfaceVariant)
        CockpitSwitch(R.string.seasonal_climate_enabled, preset.enabled, !state.form.busy) { save(season, preset.copy(enabled = it)) }
        CockpitFeedback(condition.label())
        Row(horizontalArrangement = Arrangement.spacedBy(LocalCockpitLayout.current.spacing(8.dp))) {
            CockpitAction(text(R.string.seasonal_climate_edit), edit, enabled = !state.form.busy, primary = true)
            CockpitAction(text(R.string.seasonal_climate_apply), { apply(season) },
                enabled = !state.form.busy && season in state.approved && condition == ClimateConditionState.READY)
        }
    }
}

@Composable
private fun SeasonalClimateEditor(
    season: ClimateSeason,
    settings: SeasonalClimateSettings,
    busy: Boolean,
    save: (ClimateSeason, SeasonalClimatePreset) -> Unit,
) {
    val preset = settings.preset(season)
    var threshold by rememberSaveable { mutableStateOf(preset.outsideThresholdCelsius.toString()) }
    var driver by rememberSaveable { mutableStateOf(preset.driverCelsius.toString()) }
    var passenger by rememberSaveable { mutableStateOf(preset.passengerCelsius.toString()) }
    var fan by rememberSaveable { mutableStateOf(preset.fanLevel.toString()) }
    var gear by rememberSaveable { mutableStateOf(preset.gearCondition) }
    var doors by rememberSaveable { mutableStateOf(preset.requireDoorsClosed) }
    var driverSeat by rememberSaveable { mutableStateOf(preset.driverSeatLevel) }
    var passengerSeat by rememberSaveable { mutableStateOf(preset.passengerSeatLevel) }
    val candidate = try {
        SeasonalClimatePreset(preset.enabled, threshold.toInt(), driver.toInt(), passenger.toInt(), fan.toInt(), gear, doors, driverSeat, passengerSeat)
            .also { settings.withPreset(season, it) }
    } catch (_: IllegalArgumentException) { null }
    CockpitColumns(first = {
        CockpitSection(text(R.string.seasonal_climate_conditions), CockpitIcon.CAR) {
            ClimateNumberField(if (season == ClimateSeason.SUMMER) R.string.seasonal_climate_above_field
                else R.string.seasonal_climate_below_field, threshold, !busy, signed = true) { threshold = it }
            ClimateSettingChoice(R.string.seasonal_climate_gear, gear, ClimateGearCondition.entries,
                ClimateGearCondition::label, !busy) { gear = it }
            CockpitSwitch(R.string.seasonal_climate_closed_doors, doors, !busy) { doors = it }
        }
    }, second = {
        CockpitSection(text(R.string.seasonal_climate_parameters), CockpitIcon.SETTINGS) {
            Row(horizontalArrangement = Arrangement.spacedBy(LocalCockpitLayout.current.spacing(8.dp))) {
                ClimateNumberField(R.string.seasonal_climate_driver, driver, !busy, modifier = Modifier.weight(1f)) { driver = it }
                ClimateNumberField(R.string.seasonal_climate_passenger, passenger, !busy, modifier = Modifier.weight(1f)) { passenger = it }
            }
            ClimateNumberField(R.string.seasonal_climate_fan, fan, !busy) { fan = it }
            Row(horizontalArrangement = Arrangement.spacedBy(LocalCockpitLayout.current.spacing(8.dp))) {
                SeatLevelChoice(season, R.string.seasonal_climate_driver_seat, driverSeat, !busy,
                    Modifier.weight(1f)) { driverSeat = it }
                SeatLevelChoice(season, R.string.seasonal_climate_passenger_seat, passengerSeat, !busy,
                    Modifier.weight(1f)) { passengerSeat = it }
            }
            if (candidate == null) CockpitFeedback(text(R.string.seasonal_climate_invalid), error = true)
            CockpitAction(text(R.string.seasonal_climate_save), { candidate?.let { save(season, it) } },
                enabled = !busy && candidate != null && candidate != preset, primary = true)
        }
    })
}

@Composable
private fun SeatLevelChoice(season: ClimateSeason, title: Int, level: Int, enabled: Boolean,
                            modifier: Modifier = Modifier, change: (Int) -> Unit) {
    val format = text(if (season == ClimateSeason.SUMMER) R.string.seasonal_climate_ventilation_level else R.string.seasonal_climate_heating_level)
    ClimateSettingChoice(title, level, (0..3).toList(), { format.format(seatLevel(it)) }, enabled, modifier, change)
}

@Composable
private fun <T> ClimateSettingChoice(title: Int, value: T, options: List<T>, label: (T) -> String,
                                     enabled: Boolean, modifier: Modifier = Modifier, change: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val interactive = enabled && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    CockpitChoice(title, label(value), interactive, modifier) { open = true }
    if (open && interactive) CockpitOptionDialog(text(title), options, value, label, onDismiss = { open = false }, onSelect = { option ->
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) change(option)
    })
}

private fun seatSummary(season: ClimateSeason, preset: SeasonalClimatePreset): String =
    text(if (season == ClimateSeason.SUMMER) R.string.seasonal_climate_ventilation_summary else R.string.seasonal_climate_heating_summary)
        .format(seatLevel(preset.driverSeatLevel), seatLevel(preset.passengerSeatLevel))

private fun seatLevel(level: Int): String = if (level == 0) text(R.string.seasonal_climate_seat_unchanged) else level.toString()

@Composable
private fun ClimateNumberField(title: Int, value: String, enabled: Boolean, modifier: Modifier = Modifier,
                               signed: Boolean = false, change: (String) -> Unit) {
    CockpitTextField(value, { input ->
        if (input.length <= 3 && input.withIndex().all { (index, character) -> character.isDigit() || signed && index == 0 && character == '-' })
            change(input)
    }, text(title), modifier.fillMaxWidth(), enabled = enabled, guideId = title,
        keyboardOptions = KeyboardOptions(keyboardType = if (signed) KeyboardType.Ascii else KeyboardType.Number))
}

private fun ClimateSeason.label(): String = text(if (this == ClimateSeason.SUMMER) R.string.seasonal_climate_summer else R.string.seasonal_climate_winter)
private fun ClimateGearCondition.label(): String = text(if (this == ClimateGearCondition.PARK) R.string.seasonal_climate_park else R.string.seasonal_climate_drive)
private fun ClimateConditionState.label(): String = text(when (this) {
    ClimateConditionState.DISABLED -> R.string.seasonal_climate_disabled
    ClimateConditionState.WAITING_GEAR -> R.string.seasonal_climate_waiting_gear
    ClimateConditionState.WAITING_SPEED -> R.string.seasonal_climate_waiting_speed
    ClimateConditionState.PARK_REQUIRED -> R.string.seasonal_climate_park_required
    ClimateConditionState.DRIVE_REQUIRED -> R.string.seasonal_climate_drive_required
    ClimateConditionState.WAITING_TEMPERATURE -> R.string.seasonal_climate_waiting_temperature
    ClimateConditionState.TEMPERATURE_NOT_MET -> R.string.seasonal_climate_temperature_not_met
    ClimateConditionState.WAITING_DOORS -> R.string.seasonal_climate_waiting_doors
    ClimateConditionState.DOORS_OPEN -> R.string.seasonal_climate_doors_open
    ClimateConditionState.READY -> R.string.seasonal_climate_ready
})
private fun ClimateApplyResult.label(): String = text(when (status) {
    ClimateApplyStatus.BLOCKED -> R.string.seasonal_climate_blocked
    ClimateApplyStatus.ACCEPTED -> R.string.seasonal_climate_accepted
    ClimateApplyStatus.VERIFIED -> R.string.seasonal_climate_verified
    ClimateApplyStatus.PARTIAL -> R.string.seasonal_climate_partial
    ClimateApplyStatus.UNCONFIRMED -> R.string.seasonal_climate_unconfirmed
    ClimateApplyStatus.BUSY -> R.string.seasonal_climate_busy
    ClimateApplyStatus.RECENT -> R.string.seasonal_climate_recent
})
private fun text(id: Int): String = UiText.get(id)
