package org.openvoyah.controls.presentation.vehicle

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.vehicle.ServiceReminderPreferences
import org.openvoyah.controls.domain.vehicle.ServiceCalendarLead
import org.openvoyah.controls.domain.vehicle.ServiceCalendarProgress
import org.openvoyah.controls.domain.vehicle.ServiceCalendarSchedule
import org.openvoyah.controls.domain.vehicle.ServiceEstimate
import org.openvoyah.controls.domain.vehicle.ServiceProgress
import org.openvoyah.controls.domain.vehicle.ServiceReminderLead
import org.openvoyah.controls.domain.vehicle.ServiceSchedule
import org.openvoyah.controls.domain.vehicle.ServiceUrgency
import org.openvoyah.controls.platform.vehicle.serviceReminderDates
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitColumns
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.CockpitSwitch

@Composable
internal fun rememberServiceReminderViewModel(activity: ComponentActivity): ServiceReminderViewModel = remember(activity) {
    ViewModelProvider(activity, viewModelFactory { initializer {
        val app = activity.applicationContext
        ServiceReminderViewModel(ServiceReminderPreferences(app), serviceReminderDates(app))
    } })[ServiceReminderViewModel::class.java]
}

@Composable
internal fun ServiceReminderDestination(back: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.vehicleSettingsActivity() }
    val model = rememberServiceReminderViewModel(activity)
    val state by model.state.collectAsStateWithLifecycle()
    val status by model.saveStatus.collectAsStateWithLifecycle()
    CockpitDetail(UiText.get(R.string.service_reminder_title), UiText.get(R.string.service_reminder_summary), back) {
        state?.let { current ->
            val settings = current.settings
            val saving = status == ServiceReminderViewModel.SaveStatus.SAVING
            CockpitColumns(first = {
                ServiceReminderOverview(current, saving, model::setEnabled, model::setLeadDistance)
            }, second = {
                key(settings.schedule) { ServiceScheduleEditor(settings.schedule, saving, model::setSchedule) }
                key(settings.calendar) {
                    ServiceReminderCalendarEditor(settings.calendar, settings.calendarLead, current.today, saving,
                        model::setCalendar, model::setCalendarLead)
                }
            })
            if (status == ServiceReminderViewModel.SaveStatus.FAILED) {
                CockpitFeedback(UiText.get(R.string.service_reminder_save_failed), error = true)
            }
        }
    }
}

@Composable
private fun ServiceReminderOverview(
    state: ServiceReminderViewModel.State,
    saving: Boolean,
    setEnabled: (Boolean) -> Unit,
    setLeadDistance: (ServiceReminderLead) -> Unit,
) {
    val settings = state.settings
    val distance = state.progress.distance
    val calendar = state.progress.calendar
    CockpitSection(UiText.get(R.string.service_reminder_next), CockpitIcon.CAR) {
        if (distance == null && calendar == null) {
            CockpitFeedback(UiText.get(R.string.service_reminder_missing_readings))
        }
        if (distance != null) {
            Text(serviceDistance(R.string.service_reminder_distance, distance.nextServiceKm),
                style = MaterialTheme.typography.headlineLarge)
            CockpitFeedback(distance.serviceSummary(), error = distance.urgency == ServiceUrgency.OVERDUE)
        }
        if (calendar != null) {
            CockpitFeedback(calendar.serviceSummary(), error = calendar.urgency == ServiceUrgency.OVERDUE)
        }
        CockpitSwitch(UiText.get(R.string.service_reminder_show_on_vehicle), settings.enabled, !saving, setEnabled)
        ServiceReminderChoice(R.string.service_reminder_lead_title, settings.leadDistance, ServiceReminderLead.entries,
            settings.leadDistance.summary(settings.schedule.intervalKm), ServiceReminderLead::label, !saving, setLeadDistance)
    }
}

@Composable
private fun <T> ServiceReminderChoice(
    @StringRes title: Int,
    value: T,
    options: List<T>,
    summary: String,
    optionLabel: (T) -> String,
    enabled: Boolean,
    choose: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateAsState()
    val interactive = enabled && state.isAtLeast(Lifecycle.State.RESUMED)
    Box {
        CockpitChoice(UiText.get(title), summary, enabled = interactive) {
            open = true
        }
        DropdownMenu(expanded = open && interactive, onDismissRequest = { open = false }) {
            for (option in options) {
                val active = option == value
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        open = false
                        if (!active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) choose(option)
                    },
                    modifier = Modifier.semantics { selected = active },
                    leadingIcon = { RadioButton(active, onClick = null) },
                )
            }
        }
    }
}

private fun ServiceReminderLead.label(): String = kilometers?.let { serviceDistance(R.string.service_reminder_distance, it) }
    ?: UiText.get(R.string.service_reminder_lead_auto)

private fun ServiceReminderLead.summary(intervalKm: Int?): String {
    val chosen = label()
    if (intervalKm == null) return chosen
    val effective = forInterval(intervalKm)
    return when {
        kilometers == null -> serviceDistance(R.string.service_reminder_lead_auto_distance, effective)
        effective < kilometers -> UiText.get(R.string.service_reminder_lead_limited)
            .format(chosen, serviceDistance(R.string.service_reminder_distance, effective))
        else -> chosen
    }
}

@Composable
private fun ServiceScheduleEditor(schedule: ServiceSchedule, saving: Boolean, save: (ServiceSchedule) -> Unit) {
    var interval by rememberSaveable { mutableStateOf(schedule.intervalKm?.toString().orEmpty()) }
    var current by rememberSaveable { mutableStateOf(schedule.currentKm?.toString().orEmpty()) }
    var lastService by rememberSaveable { mutableStateOf(schedule.lastServiceKm?.toString().orEmpty()) }
    val plan = remember(interval, current, lastService) { ServiceSchedule.fromInput(interval, current, lastService) }
    val focus = LocalFocusManager.current
    CockpitSection(UiText.get(R.string.service_reminder_readings_title), CockpitIcon.SETTINGS) {
        ServiceNumberField(R.string.service_reminder_interval, interval, !saving) { interval = it }
        ServiceNumberField(R.string.service_reminder_current, current, !saving) { current = it }
        ServiceNumberField(R.string.service_reminder_last, lastService, !saving) { lastService = it }
        if (plan == null) CockpitFeedback(UiText.get(R.string.service_reminder_invalid), error = true)
        CockpitAction(UiText.get(R.string.service_reminder_save), {
            if (plan != null) {
                focus.clearFocus()
                save(plan)
            }
        }, enabled = !saving && plan != null && plan != schedule, primary = true)
    }
}

@Composable
private fun ServiceNumberField(
    @StringRes title: Int, text: String, enabled: Boolean, maxDigits: Int = 7, change: (String) -> Unit,
) {
    OutlinedTextField(text, { input ->
        if (input.length <= maxDigits && input.all(Char::isDigit)) change(input)
    }, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text(UiText.get(title)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
private fun ServiceReminderCalendarEditor(
    schedule: ServiceCalendarSchedule, lead: ServiceCalendarLead, today: LocalDate, saving: Boolean,
    save: (ServiceCalendarSchedule) -> Unit, setLead: (ServiceCalendarLead) -> Unit,
) {
    var months by rememberSaveable { mutableStateOf(schedule.intervalMonths?.toString().orEmpty()) }
    var lastDate by rememberSaveable { mutableStateOf(schedule.lastServiceDate?.toString().orEmpty()) }
    val plan = remember(months, lastDate, today) { ServiceCalendarSchedule.fromInput(months, lastDate, today) }
    val focus = LocalFocusManager.current
    CockpitSection(UiText.get(R.string.service_reminder_calendar_title), CockpitIcon.SETTINGS,
        UiText.get(R.string.service_reminder_calendar_summary)) {
        ServiceNumberField(R.string.service_reminder_calendar_interval, months, !saving, maxDigits = 3) { months = it }
        OutlinedTextField(lastDate, { input ->
            if (input.length <= 10 && input.all { it in '0'..'9' || it == '-' }) lastDate = input
        }, Modifier.fillMaxWidth(), enabled = !saving,
            label = { Text(UiText.get(R.string.service_reminder_calendar_last_date)) }, singleLine = true,
            supportingText = { Text(UiText.get(R.string.service_reminder_calendar_date_format)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
        if (plan == null) CockpitFeedback(UiText.get(R.string.service_reminder_calendar_invalid), error = true)
        CockpitAction(UiText.get(R.string.service_reminder_calendar_save), {
            if (plan != null) {
                focus.clearFocus()
                save(plan)
            }
        }, enabled = !saving && plan != null && plan != schedule, primary = true)
        ServiceReminderChoice(R.string.service_reminder_calendar_lead_title, lead, ServiceCalendarLead.entries,
            lead.label(), ServiceCalendarLead::label, !saving, setLead)
    }
}

private fun ServiceCalendarLead.label(): String = if (days == 0) {
    UiText.get(R.string.service_reminder_calendar_lead_due)
} else {
    UiText.get(R.string.service_reminder_calendar_lead_days).format(days)
}

internal fun vehicleServiceReminder(state: ServiceReminderViewModel.State?): String? {
    if (state?.settings?.enabled != true) return null
    return state.progress.warning?.serviceSummary()
}

internal fun ServiceEstimate.serviceSummary(): String = when (this) {
    is ServiceProgress -> when {
        remainingKm < 0 -> serviceDistance(R.string.service_reminder_overdue, -remainingKm)
        remainingKm == 0 -> UiText.get(R.string.service_reminder_due)
        else -> serviceDistance(R.string.service_reminder_remaining, remainingKm)
    }
    is ServiceCalendarProgress -> when (urgency) {
        ServiceUrgency.OVERDUE -> serviceDate(R.string.service_reminder_calendar_overdue, nextServiceDate)
        ServiceUrgency.DUE -> UiText.get(R.string.service_reminder_calendar_due_today)
        else -> serviceDate(R.string.service_reminder_calendar_due_date, nextServiceDate)
    }
}

private fun serviceDate(@StringRes format: Int, date: LocalDate): String {
    val locale = Locale.forLanguageTag(UiText.language())
    val value = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)
    return String.format(locale, UiText.get(format), value)
}

private fun serviceDistance(@StringRes format: Int, distance: Int): String {
    val locale = Locale.forLanguageTag(UiText.language())
    val amount = NumberFormat.getIntegerInstance(locale).format(distance)
    return String.format(locale, UiText.get(format), amount)
}
