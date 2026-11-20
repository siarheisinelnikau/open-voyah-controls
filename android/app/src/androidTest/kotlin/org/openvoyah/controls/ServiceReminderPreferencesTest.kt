package org.openvoyah.controls

import android.test.AndroidTestCase
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.openvoyah.controls.data.settings.LocalSettingsArchive
import org.openvoyah.controls.data.vehicle.ServiceReminderPreferences
import org.openvoyah.controls.domain.vehicle.ServiceCalendarLead
import org.openvoyah.controls.domain.vehicle.ServiceCalendarSchedule
import org.openvoyah.controls.domain.vehicle.ServiceReminderLead
import org.openvoyah.controls.domain.vehicle.ServiceReminderSettings
import org.openvoyah.controls.domain.vehicle.ServiceSchedule

class ServiceReminderPreferencesTest : AndroidTestCase() {
    fun testIndependentChoicesAndReadingsSurviveRestartAndSettingsArchive() {
        val archive = LocalSettingsArchive(context)
        val store = ServiceReminderPreferences(context)
        val previous = store.exportSettings()
        val schedule = ServiceSchedule(12_000, 71_750, 60_000)
        val calendar = ServiceCalendarSchedule(12, LocalDate.of(2025, 11, 30))
        val expected = ServiceReminderSettings(true, schedule, ServiceReminderLead.KM_1000, calendar, ServiceCalendarLead.DAYS_30)
        try {
            runBlocking {
                store.setSchedule(schedule)
                store.setEnabled(true)
                store.setLeadDistance(ServiceReminderLead.KM_1000)
                store.setCalendar(calendar)
                store.setCalendarLead(ServiceCalendarLead.DAYS_30)
            }
            val saved = archive.exportJson()
            val backup = JSONObject(saved).getJSONObject("serviceReminder")
            assertEquals(1_000, backup.getInt("leadDistanceKm"))
            assertEquals(12, backup.getInt("calendarIntervalMonths"))
            assertEquals("2025-11-30", backup.getString("lastServiceDate"))
            assertEquals(30, backup.getInt("calendarLeadDays"))
            assertEquals(expected, runBlocking { ServiceReminderPreferences(context).observe().first() })

            runBlocking { store.setEnabled(false) }
            assertEquals(expected.copy(enabled = false), runBlocking { store.observe().first() })
            runBlocking {
                store.setSchedule(ServiceSchedule())
                store.setLeadDistance(ServiceReminderLead.KM_100)
                store.setCalendar(ServiceCalendarSchedule())
                store.setCalendarLead(ServiceCalendarLead.ON_DUE_DATE)
            }
            archive.apply(archive.prepare(saved))
            assertEquals(expected, runBlocking { ServiceReminderPreferences(context).observe().first() })

            val earlier = JSONObject(saved).apply { remove("serviceReminder") }
            archive.apply(archive.prepare(earlier.toString()))
            assertEquals(expected, runBlocking { store.observe().first() })

            val earlierPlan = JSONObject(saved).apply { getJSONObject("serviceReminder").remove("leadDistanceKm") }
            val prepared = archive.prepare(earlierPlan.toString())
            runBlocking {
                store.setSchedule(ServiceSchedule())
                store.setLeadDistance(ServiceReminderLead.KM_250)
            }
            archive.apply(prepared)
            assertEquals(expected.copy(leadDistance = ServiceReminderLead.KM_250), runBlocking { store.observe().first() })

            val automatic = JSONObject(saved).apply { getJSONObject("serviceReminder").put("leadDistanceKm", JSONObject.NULL) }
            archive.apply(archive.prepare(automatic.toString()))
            assertEquals(expected.copy(leadDistance = ServiceReminderLead.AUTOMATIC), runBlocking { store.observe().first() })

            val earlierCalendar = JSONObject(saved).apply {
                getJSONObject("serviceReminder").apply {
                    remove("calendarIntervalMonths")
                    remove("lastServiceDate")
                    remove("calendarLeadDays")
                }
            }
            val replacement = ServiceCalendarSchedule(6, LocalDate.of(2025, 12, 1))
            runBlocking {
                store.setCalendar(replacement)
                store.setCalendarLead(ServiceCalendarLead.DAYS_7)
            }
            archive.apply(archive.prepare(earlierCalendar.toString()))
            assertEquals(expected.copy(calendar = replacement, calendarLead = ServiceCalendarLead.DAYS_7),
                runBlocking { store.observe().first() })

            val noCalendar = JSONObject(saved).apply {
                getJSONObject("serviceReminder").apply {
                    put("calendarIntervalMonths", JSONObject.NULL)
                    put("lastServiceDate", JSONObject.NULL)
                }
            }
            archive.apply(archive.prepare(noCalendar.toString()))
            assertEquals(expected.copy(calendar = ServiceCalendarSchedule()), runBlocking { store.observe().first() })
        } finally {
            store.restoreSettings(previous)
        }
    }

    fun testPrepareRejectsInvalidScheduleBeforeWritingAnySettings() {
        val archive = LocalSettingsArchive(context)
        val original = archive.exportJson()
        val incoming = JSONObject(original)
        val invalidPlans = listOf(
            JSONObject("""{"enabled":"true","intervalKm":10000,"currentKm":69000,"lastServiceKm":60000}"""),
            JSONObject("""{"enabled":true,"intervalKm":10000,"currentKm":59000,"lastServiceKm":60000}"""),
            JSONObject("""{"enabled":true,"intervalKm":0,"currentKm":69000,"lastServiceKm":60000}"""),
            JSONObject("""{"enabled":true,"intervalKm":10000.5,"currentKm":69000,"lastServiceKm":60000}"""),
            JSONObject("""{"enabled":true,"intervalKm":10000,"currentKm":69000,"lastServiceKm":60000,"leadDistanceKm":750}"""),
            JSONObject("""{"enabled":true,"intervalKm":10000,"currentKm":69000,"lastServiceKm":60000,"leadDistanceKm":"250"}"""),
            JSONObject("""{"enabled":true,"intervalKm":null,"currentKm":null,"lastServiceKm":null,"calendarIntervalMonths":121}"""),
            JSONObject("""{"enabled":true,"intervalKm":null,"currentKm":null,"lastServiceKm":null,"calendarIntervalMonths":0}"""),
            JSONObject("""{"enabled":true,"intervalKm":null,"currentKm":null,"lastServiceKm":null,"calendarIntervalMonths":"12"}"""),
            JSONObject("""{"enabled":true,"intervalKm":null,"currentKm":null,"lastServiceKm":null,"lastServiceDate":"2026-02-29"}"""),
            JSONObject("""{"enabled":true,"intervalKm":null,"currentKm":null,"lastServiceKm":null,"lastServiceDate":20260101}"""),
            JSONObject("""{"enabled":true,"intervalKm":null,"currentKm":null,"lastServiceKm":null,"calendarLeadDays":2}"""),
        )
        for (invalid in invalidPlans) {
            incoming.put("serviceReminder", invalid)
            assertTrue(runCatching { archive.prepare(incoming.toString()) }.isFailure)
            assertEquals(original, archive.exportJson())
        }
    }
}
