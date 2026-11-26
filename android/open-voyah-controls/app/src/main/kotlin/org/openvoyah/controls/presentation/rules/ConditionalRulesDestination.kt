package org.openvoyah.controls.presentation.rules

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.routines.RoutinePreferences
import org.openvoyah.controls.data.rules.ConditionalRulesPreferences
import org.openvoyah.controls.domain.rules.ConditionalRule
import org.openvoyah.controls.domain.rules.ConditionalRuleLibrary
import org.openvoyah.controls.domain.rules.DoorRequirement
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoor
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoorState
import org.openvoyah.controls.domain.vehicle.automation.VehicleGear
import org.openvoyah.controls.platform.vehicle.automation.VehicleWorkflowRuntime
import org.openvoyah.controls.platform.vehicle.comfort.AmbientLight
import org.openvoyah.controls.presentation.vehicle.automation.vehicleSignalsModel
import org.openvoyah.controls.presentation.routines.RoutinePicker
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
import org.openvoyah.controls.presentation.theme.CockpitTextField
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout
import org.openvoyah.controls.presentation.theme.LocalCockpitMotion
import org.openvoyah.controls.presentation.theme.cockpitSwap

@Composable
internal fun ConditionalRulesDestination(back: () -> Unit, editRoutines: () -> Unit, availabilityMessage: String? = null) {
    val activity = checkNotNull(LocalActivity.current as? ComponentActivity)
    val model = remember(activity) {
        val app = activity.applicationContext
        ViewModelProvider(activity, viewModelFactory { initializer {
            ConditionalRulesViewModel(ConditionalRulesPreferences(app), RoutinePreferences(app).observe())
        } })[ConditionalRulesViewModel::class.java]
    }
    val state by model.state.collectAsStateWithLifecycle()
    val signals = remember(activity) { activity.vehicleSignalsModel() }
    val telemetry by signals.telemetry.collectAsStateWithLifecycle()
    val app = activity.applicationContext
    val lightAvailable = remember(app) { AmbientLight.available(app) }
    val rules = state.library?.entries.orEmpty()
    val ambient = if (lightAvailable && rules.any { it.enabled && it.conditions.ambientLux != null }) {
        AmbientLight.readings(app).collectAsStateWithLifecycle().value
    } else null
    val charge = if (rules.any { it.enabled && it.conditions.chargePercent != null }) {
        val runtime = remember(app) { VehicleWorkflowRuntime.get(app) }
        val samples = remember(runtime) { runtime.trips.state.map { it.live.levels.chargePercent }.distinctUntilChanged() }
        samples.collectAsStateWithLifecycle(null).value
    } else null
    val now = SystemClock.elapsedRealtimeNanos()
    BackHandler(enabled = state.form.draft != null, onBack = model::closeEditor)
    val motion = LocalCockpitMotion.current
    val offset = with(LocalDensity.current) { 20.dp.roundToPx() }
    // The whole state is the target so that the view sliding out keeps the content it showed, such as a draft just saved.
    AnimatedContent(state, transitionSpec = { cockpitSwap(motion, offset, forward = targetState.form.draft != null) },
        label = "rule views", contentKey = { it.form.draft != null }) { shown ->
        val draft = shown.form.draft
        if (draft != null) {
            ConditionalRuleEditor(draft, shown, model, lightAvailable)
        } else {
            CockpitDetail(text(R.string.rules_title), text(R.string.rules_description), back) {
                val spacing = LocalCockpitLayout.current.spacing(10.dp)
                CockpitColumns(first = {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing), verticalArrangement = Arrangement.spacedBy(spacing)) {
                        CockpitAction(text(R.string.rules_create), model::create, primary = true,
                            enabled = !shown.form.saving && shown.library != null &&
                                shown.library.entries.size < ConditionalRuleLibrary.MAX_RULES && shown.routines?.entries?.isNotEmpty() == true)
                        CockpitAction(text(R.string.rules_manage_routines), editRoutines, enabled = !shown.form.saving)
                    }
                    if (shown.library?.entries?.isEmpty() == true) Text(text(R.string.rules_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (shown.library?.entries?.size == ConditionalRuleLibrary.MAX_RULES) Text(text(R.string.rules_limit))
                }, second = {
                    availabilityMessage?.let { CockpitFeedback(it) }
                    Text(text(R.string.rules_scope_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }, minimumWidth = 570.dp)
                if (shown.loadFailed) CockpitFeedback(text(R.string.rules_load_failed), error = true)
                if (shown.routinesFailed) CockpitFeedback(text(R.string.rules_routines_failed), error = true)
                shown.form.error?.let { CockpitFeedback(text(it), error = true) }
                val rules = shown.library?.entries.orEmpty()
                if (rules.isNotEmpty()) CockpitSection(text(R.string.flows_rules_saved), CockpitIcon.BUTTONS) {
                    rules.forEachIndexed { index, rule -> key(rule.id) {
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        val currentRoutine = shown.routines?.let { rule.currentRoutine(it) }
                        val readings = telemetry.doors.values.filter { it.isCurrentIn(telemetry, now) }
                        val doorUnavailable = readings.none { it.value == VehicleDoorState.OPEN } && readings.size < VehicleDoor.entries.size
                        val missing = buildList {
                            if (rule.gear != null && telemetry.gear?.isCurrentIn(telemetry, now) != true) add(text(R.string.rules_gear))
                            if (rule.doors != null && doorUnavailable) add(text(R.string.rules_doors))
                            if (rule.conditions.ambientLux != null && ambient == null) add(text(R.string.rules_light))
                            if (rule.conditions.chargePercent != null && charge == null) add(text(R.string.rules_charge))
                            if (rule.conditions.outsideCelsius != null && telemetry.outsideCelsius?.isCurrentIn(telemetry, now) != true)
                                add(text(R.string.rules_temperature))
                            if (rule.conditions.drivingMode != null && telemetry.drivingMode?.isCurrentIn(telemetry, now) != true)
                                add(text(R.string.rules_driving_mode))
                        }
                        CockpitColumns(first = {
                            CockpitChoice(rule.name, rule.summary(), enabled = !shown.form.saving) { model.edit(rule) }
                        }, second = {
                            CockpitSwitch(R.string.rules_enabled, rule.enabled, enabled = !shown.form.saving &&
                                (currentRoutine != null || rule.enabled)) { model.setEnabled(rule, it) }
                            Text(text(R.string.flows_rule_routine).format(rule.routine.name), style = MaterialTheme.typography.bodyMedium)
                            val status = when {
                                currentRoutine == null -> text(R.string.rules_routine_changed)
                                !rule.enabled -> text(R.string.rules_paused)
                                missing.isNotEmpty() -> text(R.string.rules_missing_readings).format(missing.joinToString(", "))
                                else -> text(R.string.rules_waiting)
                            }
                            Text(status,
                                color = if (currentRoutine == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium)
                        }, minimumWidth = 570.dp)
                    } }
                }
            }
        }
    }
}

private enum class RulePicker { GEAR, DOORS, ROUTINE, INTERVAL }

@Composable
private fun ConditionalRuleEditor(draft: ConditionalRuleDraft, state: ConditionalRulesUiState,
                                  model: ConditionalRulesViewModel, lightAvailable: Boolean) {
    var picker by rememberSaveable(draft.id) { mutableStateOf<RulePicker?>(null) }
    var confirmingDelete by rememberSaveable(draft.id) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val enabled = !state.form.saving && state.library != null
    val routineCurrent = draft.routine != null && state.routines?.entries?.firstOrNull { it.id == draft.routine.id } == draft.routine
    val duplicateName = state.library?.entries?.any { it.id != draft.id && it.name.equals(draft.name.trim(), ignoreCase = true) } == true
    val problem = when {
        state.form.error != null -> state.form.error
        draft.name.isNotEmpty() && (!draft.validName || duplicateName) -> R.string.rules_invalid_name
        !draft.hasCondition -> R.string.rules_no_condition
        draft.routine == null -> R.string.rules_no_routine
        !routineCurrent -> R.string.rules_routine_changed
        else -> null
    }
    fun choose(value: RulePicker) { focus.clearFocus(); picker = value }
    CockpitDetail(text(if (draft.original == null) R.string.rules_create else R.string.rules_edit), null, model::closeEditor, nested = true) {
        CockpitTextField(draft.name, model::name, text(R.string.rules_name), Modifier.fillMaxWidth(), enabled = enabled, guideId = R.string.rules_name,
            error = draft.name.isNotEmpty() && (!draft.validName || duplicateName),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
        CockpitColumns(first = {
            CockpitSection(text(R.string.rules_if), CockpitIcon.CAR) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CockpitChoice(R.string.rules_gear, draft.gear.label(), enabled,
                        modifier = Modifier.weight(1f)) { choose(RulePicker.GEAR) }
                    CockpitChoice(R.string.rules_doors, draft.doors.label(), enabled,
                        modifier = Modifier.weight(1f)) { choose(RulePicker.DOORS) }
                }
                RuleConditionsEditor(draft.id, draft.conditions, enabled, lightAvailable, model::conditions)
                Text(text(R.string.rules_and_hint), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }, second = {
            CockpitSection(text(R.string.rules_then), CockpitIcon.BUTTONS) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CockpitChoice(R.string.rules_routine, draft.routine?.name ?: text(R.string.rules_no_routine),
                        enabled && state.routines?.entries?.isNotEmpty() == true,
                        modifier = Modifier.weight(1f)) { choose(RulePicker.ROUTINE) }
                    CockpitChoice(R.string.rules_cooldown, text(R.string.rules_seconds).format(draft.cooldownSeconds), enabled,
                        modifier = Modifier.weight(1f)) { choose(RulePicker.INTERVAL) }
                }
                CockpitSwitch(R.string.rules_enabled, draft.enabled, enabled, model::enabled)
            }
        }, minimumWidth = 570.dp)
        problem?.let { CockpitFeedback(text(it), error = true) }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            if (draft.original != null) CockpitAction(text(R.string.rules_delete), { focus.clearFocus(); confirmingDelete = true },
                enabled = enabled, destructive = true)
            CockpitAction(text(R.string.rules_save), { focus.clearFocus(); model.save() }, primary = true,
                enabled = enabled && draft.validName && !duplicateName && draft.hasCondition && routineCurrent)
        }
    }
    val original = draft.original
    if (confirmingDelete && original != null) {
        CockpitAlertDialog(onDismissRequest = { confirmingDelete = false },
            title = { Text(text(R.string.flows_rule_delete_prompt).format(original.name)) },
            text = { Text(text(R.string.flows_rule_delete_detail)) },
            confirmButton = {
                CockpitAction(text(R.string.rules_delete), { confirmingDelete = false; model.remove() }, primary = true, destructive = true)
            },
            dismissButton = { CockpitTextAction(text(R.string.ui_89)) { confirmingDelete = false } })
    }
    when (picker) {
        RulePicker.GEAR -> RoutinePicker(R.string.rules_gear, listOf(null) + VehicleGear.entries, draft.gear,
            label = { it.label() }, dismiss = { picker = null }) { picker = null; model.gear(it) }
        RulePicker.DOORS -> RoutinePicker(R.string.rules_doors, listOf(null) + DoorRequirement.entries, draft.doors,
            label = { it.label() }, dismiss = { picker = null }) { picker = null; model.doors(it) }
        // The saved routines themselves are the options. A changed routine then equals none of them, so choosing it
        // again accepts its current actions, as the routine-changed message asks, instead of counting as no change.
        RulePicker.ROUTINE -> RoutinePicker(R.string.rules_routine, state.routines?.entries.orEmpty(), draft.routine,
            label = { it.name }, dismiss = { picker = null }) { routine ->
            picker = null
            model.routine(routine)
        }
        RulePicker.INTERVAL -> RoutinePicker(R.string.rules_cooldown, (listOf(10, 30, 60, 300, 600) + draft.cooldownSeconds).distinct().sorted(),
            draft.cooldownSeconds, label = { text(R.string.rules_seconds).format(it) }, dismiss = { picker = null }) {
            picker = null; model.cooldown(it)
        }
        null -> Unit
    }
}

private fun VehicleGear?.label(): String = text(when (this) {
    VehicleGear.PARK -> R.string.rules_park
    VehicleGear.DRIVE -> R.string.rules_drive
    VehicleGear.REVERSE -> R.string.rules_reverse
    VehicleGear.NEUTRAL -> R.string.rules_neutral
    null -> R.string.rules_any_gear
})

private fun DoorRequirement?.label(): String = text(when (this) {
    DoorRequirement.ALL_CLOSED -> R.string.rules_all_closed
    DoorRequirement.ANY_OPEN -> R.string.rules_any_open
    null -> R.string.rules_any_doors
})

@Composable
private fun ConditionalRule.summary(): String =
    (listOfNotNull(gear?.label(), doors?.label()) + conditions.labels()).joinToString(" · ")
private fun text(id: Int): String = UiText.get(id)
