package org.openvoyah.controls.presentation.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.routines.RoutineTimeWindow
import org.openvoyah.controls.domain.rules.RuleComparison
import org.openvoyah.controls.domain.rules.RuleConditions
import org.openvoyah.controls.domain.rules.RuleThreshold
import org.openvoyah.controls.domain.vehicle.automation.DrivingMode
import org.openvoyah.controls.presentation.routines.RoutineClockDialog
import org.openvoyah.controls.presentation.routines.RoutinePicker
import org.openvoyah.controls.presentation.routines.deviceClockFormat
import org.openvoyah.controls.presentation.routines.minute
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitReveal
import org.openvoyah.controls.presentation.theme.CockpitSwitch

private enum class NumberCondition(val title: Int, val unit: Int, val values: List<Int>) {
    LIGHT(R.string.rules_light, R.string.rules_lux, listOf(5, 10, 25, 50, 100, 200, 500, 1_000, 5_000, 10_000)),
    CHARGE(R.string.rules_charge, R.string.rules_percent, (10..100 step 5).toList()),
    TEMPERATURE(R.string.rules_temperature, R.string.rules_celsius, (-30..50 step 5).toList());

    fun selected(inConditions: RuleConditions): RuleThreshold? = when (this) {
        LIGHT -> inConditions.ambientLux
        CHARGE -> inConditions.chargePercent
        TEMPERATURE -> inConditions.outsideCelsius
    }

    fun change(conditions: RuleConditions, value: RuleThreshold?) = when (this) {
        LIGHT -> conditions.copy(ambientLux = value)
        CHARGE -> conditions.copy(chargePercent = value)
        TEMPERATURE -> conditions.copy(outsideCelsius = value)
    }

    fun label(value: RuleThreshold?): String = if (value == null) UiText.get(R.string.rules_condition_any) else {
        val comparison = if (value.comparison == RuleComparison.AT_LEAST) R.string.rules_at_least else R.string.rules_at_most
        UiText.get(comparison).format(UiText.get(unit).format(value.amount))
    }
}

@Composable
internal fun RuleConditionsEditor(id: String, conditions: RuleConditions, enabled: Boolean, lightAvailable: Boolean,
                                  change: (RuleConditions) -> Unit) {
    var expanded by rememberSaveable(id) { mutableStateOf(!conditions.isEmpty) }
    var threshold by rememberSaveable(id) { mutableStateOf<NumberCondition?>(null) }
    var editingMode by rememberSaveable(id) { mutableStateOf(false) }
    var editingTime by rememberSaveable(id) { mutableStateOf<Boolean?>(null) }
    var equalTimes by rememberSaveable(id) { mutableStateOf(false) }
    CockpitChoice(R.string.rules_extra_conditions, conditions.labels().joinToString(" · ")
        .ifEmpty { UiText.get(R.string.rules_condition_none) }, enabled) { expanded = !expanded }
    CockpitReveal(expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val clock = deviceClockFormat()
            NumberCondition.entries.forEach { kind ->
                CockpitChoice(kind.title, kind.label(kind.selected(conditions)), enabled) { threshold = kind }
            }
            if (!lightAvailable) Text(UiText.get(R.string.rules_no_light_sensor),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            CockpitChoice(R.string.rules_driving_mode, conditions.drivingMode.ruleLabel(), enabled) { editingMode = true }
            CockpitSwitch(R.string.routines_limit_hours, conditions.timeWindow != null, enabled) {
                equalTimes = false
                change(conditions.copy(timeWindow = if (it) RoutineTimeWindow(420, 1_320) else null))
            }
            conditions.timeWindow?.let { range ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CockpitChoice(R.string.routines_time_start, clock.minute(range.startMinute), enabled,
                        modifier = Modifier.weight(1f)) { equalTimes = false; editingTime = true }
                    CockpitChoice(R.string.routines_time_end, clock.minute(range.endMinute), enabled,
                        modifier = Modifier.weight(1f)) { equalTimes = false; editingTime = false }
                }
                if (equalTimes) CockpitFeedback(UiText.get(R.string.routines_equal_times), error = true)
                Text(UiText.get(if (range.startMinute > range.endMinute) R.string.rules_overnight else R.string.routines_local_clock),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    threshold?.let { kind ->
        val current = kind.selected(conditions)
        val choices = RuleComparison.entries.flatMap { comparison ->
            (kind.values + listOfNotNull(current?.amount)).distinct().sorted().map { RuleThreshold(it, comparison) }
        }
        RoutinePicker(kind.title, listOf(null) + choices, current, kind::label, dismiss = { threshold = null }) {
            threshold = null
            change(kind.change(conditions, it))
        }
    }
    if (editingMode) RoutinePicker(R.string.rules_driving_mode, listOf(null) + DrivingMode.entries, conditions.drivingMode,
        { it.ruleLabel() }, dismiss = { editingMode = false }) {
        editingMode = false
        change(conditions.copy(drivingMode = it))
    }
    editingTime?.let { starting ->
        conditions.timeWindow?.let { range ->
            RoutineClockDialog(if (starting) range.startMinute else range.endMinute, dismiss = { editingTime = null }) { minute ->
                val start = if (starting) minute else range.startMinute
                val end = if (starting) range.endMinute else minute
                equalTimes = start == end
                if (!equalTimes) change(conditions.copy(timeWindow = RoutineTimeWindow(start, end)))
            }
        }
    }
}

@Composable
internal fun RuleConditions.labels(): List<String> {
    val clock = deviceClockFormat()
    return listOfNotNull(
        ambientLux?.let(NumberCondition.LIGHT::label),
        chargePercent?.let(NumberCondition.CHARGE::label),
        outsideCelsius?.let(NumberCondition.TEMPERATURE::label),
        drivingMode?.ruleLabel(),
        timeWindow?.let { UiText.get(R.string.routines_time_span).format(clock.minute(it.startMinute), clock.minute(it.endMinute)) },
    )
}

private fun DrivingMode?.ruleLabel() = UiText.get(when (this) {
    DrivingMode.ECO -> R.string.vehicle_automation_mode_eco
    DrivingMode.COMFORT -> R.string.vehicle_automation_mode_comfort
    DrivingMode.SPORT -> R.string.vehicle_automation_mode_sport
    DrivingMode.OFFROAD -> R.string.vehicle_automation_mode_offroad
    DrivingMode.INDIVIDUAL -> R.string.vehicle_automation_mode_individual
    DrivingMode.SNOW -> R.string.vehicle_automation_mode_snow
    null -> R.string.rules_condition_any
})
