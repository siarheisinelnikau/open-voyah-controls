package org.openvoyah.controls.domain.vehicle

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceScheduleTest {
    @Test fun incompleteAndInvalidReadingsDoNotProduceAServiceEstimate() {
        assertNull(ServiceSchedule(intervalKm = 10_000, currentKm = 65_000).progress())
        assertNull(ServiceSchedule.fromInput("0", "65000", "60000"))
        assertNull(ServiceSchedule.fromInput("10000", "65000", "66000"))
        assertNull(ServiceSchedule.fromInput("10000", "65000.5", "60000"))
        assertNull(ServiceSchedule.fromInput("10000000", "", ""))
        assertEquals(0, ServiceSchedule.fromInput("10000", "0", "0")?.currentKm)
    }

    @Test fun exactDueAndOverdueDistancesKeepTheSameServiceOdometer() {
        assertEquals(ServiceProgress(70_000, 501, false), ServiceSchedule(10_000, 69_499, 60_000).progress())
        assertEquals(ServiceProgress(70_000, 500, true), ServiceSchedule(10_000, 69_500, 60_000).progress())
        assertEquals(ServiceProgress(70_000, 0, true), ServiceSchedule(10_000, 70_000, 60_000).progress())
        assertEquals(ServiceProgress(70_000, -500, true), ServiceSchedule(10_000, 70_500, 60_000).progress())
    }

    @Test fun shortIntervalsUseTheirOwnReminderDistanceAndLargeSumsStayExact() {
        assertFalse(checkNotNull(ServiceSchedule(100, 89, 0).progress()).approachingService)
        assertTrue(checkNotNull(ServiceSchedule(100, 90, 0).progress()).approachingService)
        assertEquals(19_999_998, ServiceSchedule(9_999_999, 9_999_999, 9_999_999).progress()?.nextServiceKm)
    }

    @Test fun presetChangesTheInclusiveWarningThresholdWithoutChangingServiceMileage() {
        val lead = ServiceReminderLead.KM_1000
        val outside = ServiceSchedule(10_000, 68_999, 60_000)
        val atThreshold = ServiceSchedule(10_000, 69_000, 60_000)
        assertEquals(ServiceProgress(70_000, 1_001, false), outside.progress(lead))
        assertEquals(ServiceProgress(70_000, 1_000, true), atThreshold.progress(lead))
        assertEquals(ServiceProgress(70_000, 1_000, false), atThreshold.progress())
    }

    @Test fun theSelectedPresetIsCappedAtTheSavedInterval() {
        val lead = ServiceReminderLead.KM_500
        val settings = ServiceReminderSettings(schedule = ServiceSchedule(450, 10_075, 10_000), leadDistance = lead)
        assertEquals(500, lead.kilometers)
        assertEquals(450, lead.forInterval(450))
        assertEquals(1, ServiceReminderLead.AUTOMATIC.forInterval(1))
        assertEquals(ServiceProgress(10_450, 375, true), settings.progress())
        assertEquals(ServiceProgress(10_450, 375, false), settings.copy(leadDistance = ServiceReminderLead.KM_250).progress())
    }

    @Test fun calendarScheduleClampsEndOfMonthAndDistinguishesWarningDueAndOverdue() {
        val last = LocalDate.of(2026, 1, 31)
        val schedule = ServiceCalendarSchedule(1, last)
        val due = LocalDate.of(2026, 2, 28)
        val lead = ServiceCalendarLead.DAYS_7

        assertEquals(ServiceCalendarProgress(due, ServiceUrgency.PLANNED), schedule.progress(due.minusDays(8), lead))
        assertEquals(ServiceCalendarProgress(due, ServiceUrgency.APPROACHING), schedule.progress(due.minusDays(7), lead))
        assertEquals(ServiceCalendarProgress(due, ServiceUrgency.DUE), schedule.progress(due, lead))
        assertEquals(ServiceCalendarProgress(due, ServiceUrgency.OVERDUE), schedule.progress(due.plusDays(1), lead))
        assertEquals(LocalDate.of(2024, 2, 29),
            ServiceCalendarSchedule(1, LocalDate.of(2024, 1, 31)).progress(LocalDate.of(2024, 2, 1), lead)?.nextServiceDate)
    }

    @Test fun calendarInputSupportsEitherMissingFieldAndRejectsOutOfRangeOrFutureValues() {
        val today = LocalDate.of(2026, 4, 1)
        assertEquals(ServiceCalendarSchedule(120, today), ServiceCalendarSchedule.fromInput("120", "2026-04-01", today))
        assertEquals(ServiceCalendarSchedule(null, today), ServiceCalendarSchedule.fromInput("", "2026-04-01", today))
        assertEquals(ServiceCalendarSchedule(1, null), ServiceCalendarSchedule.fromInput("1", "", today))
        assertEquals(ServiceCalendarSchedule(), ServiceCalendarSchedule.fromInput("", "", today))
        assertNull(ServiceCalendarSchedule.fromInput("121", "2026-04-01", today))
        assertNull(ServiceCalendarSchedule.fromInput("0", "2026-04-01", today))
        assertNull(ServiceCalendarSchedule.fromInput("1", "2026-02-29", today))
        assertNull(ServiceCalendarSchedule.fromInput("1", "2026-04-02", today))
        assertNull(ServiceCalendarSchedule.fromInput("1", "0000-01-01", today))
        assertNull(ServiceCalendarSchedule(1, today.plusDays(1)).progress(today, ServiceCalendarLead.DAYS_30))
    }

    @Test fun eitherLimitCanWarnAndTheMoreUrgentLimitTakesPriority() {
        val today = LocalDate.of(2026, 4, 26)
        val due = LocalDate.of(2026, 4, 30)
        val settings = ServiceReminderSettings(
            schedule = ServiceSchedule(1_000, 10_000, 10_000),
            calendar = ServiceCalendarSchedule(1, LocalDate.of(2026, 3, 30)),
            calendarLead = ServiceCalendarLead.DAYS_7,
        )
        assertEquals(ServiceCalendarProgress(due, ServiceUrgency.APPROACHING), settings.progress(today).warning)
        assertNull(settings.copy(calendarLead = ServiceCalendarLead.ON_DUE_DATE).progress(today).warning)

        val mileageDue = settings.copy(schedule = ServiceSchedule(1_000, 11_000, 10_000))
        assertEquals(ServiceProgress(11_000, 0, true), mileageDue.progress(today).warning)
        assertEquals(ServiceCalendarProgress(due, ServiceUrgency.OVERDUE), mileageDue.progress(due.plusDays(1)).warning)

        val calendarOnly = settings.copy(schedule = ServiceSchedule())
        assertEquals(ServiceCalendarProgress(due, ServiceUrgency.DUE), calendarOnly.progress(due).primary)
    }
}
