package org.openvoyah.controls.presentation.routines

import android.app.TimePickerDialog
import android.text.format.DateFormat
import android.view.ContextThemeWrapper
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.routines.RoutineStartupDelay
import org.openvoyah.controls.domain.routines.RoutineStartupFrequency
import org.openvoyah.controls.domain.routines.RoutineStartupSchedule
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitColumns
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitReveal
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout
import org.openvoyah.controls.presentation.theme.LocalControlsAccentOverlay

private fun weekInDisplayOrder(locale: Locale): List<DayOfWeek> {
    val first = WeekFields.of(locale).firstDayOfWeek
    return (0L..6L).map(first::plus)
}

@Composable
private fun deviceClockFormat(): DateTimeFormatter {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val use24 = DateFormat.is24HourFormat(context)
    return remember(locale, use24) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (use24) "Hm" else "hm"), locale) }
}

private fun DateTimeFormatter.minute(value: Int): String = format(LocalTime.of(value / 60, value % 60))

private fun RoutineStartupFrequency.label() = UiText.get(when (this) {
    RoutineStartupFrequency.EVERY_ENTRY -> R.string.routines_each_entry
    RoutineStartupFrequency.ONCE_DAILY -> R.string.routines_once_daily
})

private fun RoutineStartupDelay.label() = if (this == RoutineStartupDelay.IMMEDIATE) UiText.get(R.string.routines_immediate)
    else UiText.get(R.string.routines_delay_seconds).format(seconds)

@Composable
internal fun routineScheduleLabel(schedule: RoutineStartupSchedule): String? {
    if (schedule == RoutineStartupSchedule()) return null
    val locale = Locale.forLanguageTag(UiText.language())
    val days = if (schedule.days == RoutineStartupSchedule.EVERY_DAY) null else
        weekInDisplayOrder(LocalConfiguration.current.locales[0]).filter { it in schedule.days }
            .joinToString(", ") { it.getDisplayName(TextStyle.SHORT_STANDALONE, locale) }
    val clock = deviceClockFormat()
    val time = schedule.window?.let { UiText.get(R.string.routines_time_span).format(clock.minute(it.startMinute), clock.minute(it.endMinute)) }
    return listOfNotNull(days, time, schedule.frequency.takeIf { it == RoutineStartupFrequency.ONCE_DAILY }?.label(),
        schedule.delay.takeIf { it != RoutineStartupDelay.IMMEDIATE }?.label()).joinToString(" · ")
}

@Composable
internal fun RoutineScheduleEditor(draft: RoutineScheduleDraft, state: RoutinesUiState, model: RoutinesViewModel) {
    val locale = Locale.forLanguageTag(UiText.language())
    val context = LocalContext.current
    val dayOrder = weekInDisplayOrder(LocalConfiguration.current.locales[0])
    val clock = deviceClockFormat()
    val enabled = !state.form.saving && state.library != null
    val spacing = Arrangement.spacedBy(LocalCockpitLayout.current.spacing(8.dp))
    var editingStart by rememberSaveable(draft.routine.id) { mutableStateOf<Boolean?>(null) }
    var choosingFrequency by rememberSaveable(draft.routine.id) { mutableStateOf(false) }
    var choosingDelay by rememberSaveable(draft.routine.id) { mutableStateOf(false) }
    CockpitDetail(UiText.get(R.string.routines_schedule), UiText.get(R.string.routines_schedule_for).format(draft.routine.name), model::closeEditor,
        nested = true) {
        CockpitColumns(first = {
            Text(UiText.get(R.string.routines_days), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = spacing, verticalArrangement = spacing) {
                dayOrder.forEach { day ->
                    val selected = day in draft.days
                    val full = day.getDisplayName(TextStyle.FULL_STANDALONE, locale)
                    FilterChip(selected, { model.toggleDay(day) }, enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = full },
                        leadingIcon = { Checkbox(selected, onCheckedChange = null, enabled = enabled, modifier = Modifier.size(18.dp)) },
                        label = { Text(day.getDisplayName(TextStyle.SHORT_STANDALONE, locale)) })
                }
            }
            CockpitChoice(UiText.get(R.string.routines_frequency), draft.frequency.label(), enabled) { choosingFrequency = true }
            CockpitChoice(UiText.get(R.string.routines_delay), draft.delay.label(), enabled) { choosingDelay = true }
        }, second = {
            CockpitSwitch(UiText.get(R.string.routines_limit_hours), draft.limited, enabled, model::limitTime)
            CockpitReveal(draft.limited) {
                Column(verticalArrangement = Arrangement.spacedBy(LocalCockpitLayout.current.spacing(14.dp))) {
                    CockpitChoice(UiText.get(R.string.routines_time_start), clock.minute(draft.start), enabled) { editingStart = true }
                    CockpitChoice(UiText.get(R.string.routines_time_end), clock.minute(draft.end), enabled) { editingStart = false }
                    Text(UiText.get(if (draft.start > draft.end) R.string.routines_overnight else R.string.routines_local_clock),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }, minimumWidth = 640.dp)
        val error = when {
            state.loadFailed -> R.string.routines_load_failed
            state.form.error != null -> state.form.error
            draft.days.isEmpty() -> R.string.routines_select_days
            draft.limited && draft.start == draft.end -> R.string.routines_equal_times
            else -> null
        }
        error?.let { CockpitFeedback(UiText.get(it), error = true) }
        CockpitAction(UiText.get(R.string.routines_save_schedule), model::saveSchedule, enabled = enabled && draft.valid, primary = true)
    }
    if (choosingFrequency) RoutinePicker(R.string.routines_frequency, RoutineStartupFrequency.entries, draft.frequency,
        label = { it.label() }, hint = R.string.routines_frequency_hint, dismiss = { choosingFrequency = false }) {
        model.frequency(it)
        choosingFrequency = false
    }
    if (choosingDelay) RoutinePicker(R.string.routines_delay, RoutineStartupDelay.entries, draft.delay,
        label = { it.label() }, hint = R.string.routines_delay_hint, dismiss = { choosingDelay = false }) {
        model.startupDelay(it)
        choosingDelay = false
    }
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val use24 = DateFormat.is24HourFormat(context)
    val accentOverlay = LocalControlsAccentOverlay.current
    val dialogTheme = if (MaterialTheme.colorScheme.background.luminance() > .5f)
        android.R.style.Theme_Material_Light_Dialog_Alert else android.R.style.Theme_Material_Dialog_Alert
    editingStart?.let { starting ->
        if (activity != null) DisposableEffect(activity, lifecycle, starting) {
            val minute = if (starting) draft.start else draft.end
            // The platform dialog takes its theme from the wrapper and only the accent from the overlay on top of it.
            val dialog = TimePickerDialog(ContextThemeWrapper(activity, dialogTheme), accentOverlay, { _, hour, minutes ->
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) model.chooseTime(starting, hour * 60 + minutes)
            }, minute / 60, minute % 60, use24)
            dialog.setOnDismissListener { editingStart = null }
            dialog.show()
            onDispose { dialog.setOnDismissListener(null); dialog.dismiss() }
        }
    }
}
