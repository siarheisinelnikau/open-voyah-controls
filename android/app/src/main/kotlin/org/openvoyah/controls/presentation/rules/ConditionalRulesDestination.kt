package org.openvoyah.controls.presentation.rules

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
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.routines.RoutinePreferences
import org.openvoyah.controls.data.rules.ConditionalRulesPreferences
import org.openvoyah.controls.domain.rules.ConditionalRule
import org.openvoyah.controls.domain.rules.ConditionalRuleLibrary
import org.openvoyah.controls.domain.rules.DoorRequirement
import org.openvoyah.controls.domain.vehicle.automation.VehicleGear
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignalKind
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
    val message = availabilityMessage ?: if (VehicleSignalKind.GEAR in telemetry.unavailable ||
        VehicleSignalKind.DOORS in telemetry.unavailable) text(R.string.rules_unavailable) else null
    BackHandler(enabled = state.form.draft != null, onBack = model::closeEditor)
    val motion = LocalCockpitMotion.current
    val offset = with(LocalDensity.current) { 20.dp.roundToPx() }
    // The whole state is the target so that the view sliding out keeps the content it showed, such as a draft just saved.
    AnimatedContent(state, transitionSpec = { cockpitSwap(motion, offset, forward = targetState.form.draft != null) },
        label = "rule views", contentKey = { it.form.draft != null }) { shown ->
        val draft = shown.form.draft
        if (draft != null) {
            ConditionalRuleEditor(draft, shown, model)
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
                    message?.let { CockpitFeedback(it) }
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
                        CockpitColumns(first = {
                            CockpitChoice(rule.name, rule.summary(), enabled = !shown.form.saving) { model.edit(rule) }
                        }, second = {
                            CockpitSwitch(text(R.string.rules_enabled), rule.enabled, enabled = !shown.form.saving &&
                                (currentRoutine != null || rule.enabled)) { model.setEnabled(rule, it) }
                            Text(text(R.string.flows_rule_routine).format(rule.routine.name), style = MaterialTheme.typography.bodyMedium)
                            Text(text(if (currentRoutine == null) R.string.rules_routine_changed else if (rule.enabled)
                                R.string.rules_waiting else R.string.rules_paused),
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
private fun ConditionalRuleEditor(draft: ConditionalRuleDraft, state: ConditionalRulesUiState, model: ConditionalRulesViewModel) {
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
    CockpitDetail(text(if (draft.original == null) R.string.rules_create else R.string.rules_edit), null, model::closeEditor) {
        CockpitTextField(draft.name, model::name, text(R.string.rules_name), Modifier.fillMaxWidth(), enabled = enabled,
            error = draft.name.isNotEmpty() && (!draft.validName || duplicateName),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
        CockpitColumns(first = {
            CockpitSection(text(R.string.rules_if), CockpitIcon.CAR) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CockpitChoice(text(R.string.rules_gear), draft.gear.label(), enabled,
                        modifier = Modifier.weight(1f)) { choose(RulePicker.GEAR) }
                    CockpitChoice(text(R.string.rules_doors), draft.doors.label(), enabled,
                        modifier = Modifier.weight(1f)) { choose(RulePicker.DOORS) }
                }
                Text(text(R.string.rules_and_hint), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }, second = {
            CockpitSection(text(R.string.rules_then), CockpitIcon.BUTTONS) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CockpitChoice(text(R.string.rules_routine), draft.routine?.name ?: text(R.string.rules_no_routine),
                        enabled && state.routines?.entries?.isNotEmpty() == true,
                        modifier = Modifier.weight(1f)) { choose(RulePicker.ROUTINE) }
                    CockpitChoice(text(R.string.rules_cooldown), text(R.string.rules_seconds).format(draft.cooldownSeconds), enabled,
                        modifier = Modifier.weight(1f)) { choose(RulePicker.INTERVAL) }
                }
                CockpitSwitch(text(R.string.rules_enabled), draft.enabled, enabled, model::enabled)
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

private fun ConditionalRule.summary(): String = listOfNotNull(gear?.label(), doors?.label()).joinToString(" · ")
private fun text(id: Int): String = UiText.get(id)
