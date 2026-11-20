package org.openvoyah.controls.domain.vehicle

import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.Flow

internal enum class ServiceReminderLead(val kilometers: Int?) {
    AUTOMATIC(null),
    KM_100(100),
    KM_250(250),
    KM_500(500),
    KM_1000(1_000);

    fun forInterval(intervalKm: Int): Int = minOf(intervalKm, kilometers ?: (intervalKm / 10).coerceIn(1, 500))
}

internal data class ServiceSchedule(
    val intervalKm: Int? = null,
    val currentKm: Int? = null,
    val lastServiceKm: Int? = null,
) {
    init {
        require(intervalKm == null || intervalKm in 1..MAX_KM)
        require(currentKm == null || currentKm in 0..MAX_KM)
        require(lastServiceKm == null || lastServiceKm in 0..MAX_KM)
        require(currentKm == null || lastServiceKm == null || currentKm >= lastServiceKm)
    }

    fun progress(lead: ServiceReminderLead = ServiceReminderLead.AUTOMATIC): ServiceProgress? {
        val interval = intervalKm ?: return null
        val lastService = lastServiceKm ?: return null
        val current = currentKm ?: return null
        val next = lastService + interval
        val remaining = next - current
        return ServiceProgress(next, remaining, remaining <= lead.forInterval(interval))
    }

    companion object {
        const val MAX_KM = 9_999_999

        fun fromInput(interval: String, current: String, lastService: String): ServiceSchedule? {
            val intervalKm = interval.toIntOrNull()
            val currentKm = current.toIntOrNull()
            val lastServiceKm = lastService.toIntOrNull()
            if ((interval.isNotEmpty() && (intervalKm == null || intervalKm !in 1..MAX_KM)) ||
                (current.isNotEmpty() && (currentKm == null || currentKm !in 0..MAX_KM)) ||
                (lastService.isNotEmpty() && (lastServiceKm == null || lastServiceKm !in 0..MAX_KM)) ||
                (currentKm != null && lastServiceKm != null && currentKm < lastServiceKm)
            ) return null
            return ServiceSchedule(intervalKm, currentKm, lastServiceKm)
        }
    }
}

internal enum class ServiceUrgency { PLANNED, APPROACHING, DUE, OVERDUE }

internal sealed interface ServiceEstimate {
    val urgency: ServiceUrgency
}

internal data class ServiceProgress(val nextServiceKm: Int, val remainingKm: Int, val approachingService: Boolean) :
    ServiceEstimate {
    override val urgency: ServiceUrgency get() = serviceUrgency(remainingKm.toLong(), approachingService)
}

internal enum class ServiceCalendarLead(val days: Int) {
    ON_DUE_DATE(0), DAYS_7(7), DAYS_14(14), DAYS_30(30),
}

internal data class ServiceCalendarSchedule(val intervalMonths: Int? = null, val lastServiceDate: LocalDate? = null) {
    init {
        require(intervalMonths == null || intervalMonths in 1..MAX_MONTHS)
        require(lastServiceDate == null || lastServiceDate.year in 1..9999)
    }

    fun progress(today: LocalDate, lead: ServiceCalendarLead): ServiceCalendarProgress? {
        val months = intervalMonths ?: return null
        val last = lastServiceDate?.takeUnless { it.isAfter(today) } ?: return null
        val due = last.plusMonths(months.toLong())
        val remaining = ChronoUnit.DAYS.between(today, due)
        return ServiceCalendarProgress(due, serviceUrgency(remaining, remaining <= lead.days.toLong()))
    }

    companion object {
        const val MAX_MONTHS = 120

        fun fromInput(interval: String, lastService: String, today: LocalDate): ServiceCalendarSchedule? {
            val months = if (interval.isEmpty()) null else
                interval.toIntOrNull()?.takeIf { it in 1..MAX_MONTHS } ?: return null
            val last = if (lastService.isEmpty()) null else
                parseDate(lastService)?.takeUnless { it.isAfter(today) } ?: return null
            return ServiceCalendarSchedule(months, last)
        }

        fun parseDate(value: String): LocalDate? {
            if (value.length != 10) return null
            return try {
                LocalDate.parse(value).takeIf { it.year in 1..9999 }
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}

internal data class ServiceCalendarProgress(val nextServiceDate: LocalDate, override val urgency: ServiceUrgency) :
    ServiceEstimate

internal data class ServiceReminderProgress(val distance: ServiceProgress?, val calendar: ServiceCalendarProgress?) {
    val primary: ServiceEstimate? get() = when {
        distance == null -> calendar
        calendar == null || distance.urgency >= calendar.urgency -> distance
        else -> calendar
    }

    val warning: ServiceEstimate? get() = primary?.takeIf { it.urgency >= ServiceUrgency.APPROACHING }
}

private fun serviceUrgency(remaining: Long, approaching: Boolean): ServiceUrgency = when {
    remaining < 0 -> ServiceUrgency.OVERDUE
    remaining == 0L -> ServiceUrgency.DUE
    approaching -> ServiceUrgency.APPROACHING
    else -> ServiceUrgency.PLANNED
}

internal data class ServiceReminderSettings(
    val enabled: Boolean = false,
    val schedule: ServiceSchedule = ServiceSchedule(),
    val leadDistance: ServiceReminderLead = ServiceReminderLead.AUTOMATIC,
    val calendar: ServiceCalendarSchedule = ServiceCalendarSchedule(),
    val calendarLead: ServiceCalendarLead = ServiceCalendarLead.DAYS_14,
) {
    fun progress(): ServiceProgress? = schedule.progress(leadDistance)
    fun progress(today: LocalDate): ServiceReminderProgress =
        ServiceReminderProgress(progress(), calendar.progress(today, calendarLead))
}

internal interface ServiceReminderSettingsStore {
    fun observe(): Flow<ServiceReminderSettings>
    suspend fun setEnabled(enabled: Boolean)
    suspend fun setSchedule(schedule: ServiceSchedule)
    suspend fun setLeadDistance(lead: ServiceReminderLead)
    suspend fun setCalendar(schedule: ServiceCalendarSchedule)
    suspend fun setCalendarLead(lead: ServiceCalendarLead)
}
