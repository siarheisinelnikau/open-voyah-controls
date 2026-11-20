package org.openvoyah.controls.data.vehicle

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.vehicle.ServiceCalendarLead
import org.openvoyah.controls.domain.vehicle.ServiceCalendarSchedule
import org.openvoyah.controls.domain.vehicle.ServiceReminderLead
import org.openvoyah.controls.domain.vehicle.ServiceReminderSettings
import org.openvoyah.controls.domain.vehicle.ServiceReminderSettingsStore
import org.openvoyah.controls.domain.vehicle.ServiceSchedule

internal class ServiceReminderPreferences(context: Context) : ServiceReminderSettingsStore {
    private val preferences = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    override fun observe(): Flow<ServiceReminderSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PLAN || key == null) trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { synchronized(lock) { read() } }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override suspend fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    override suspend fun setSchedule(schedule: ServiceSchedule) = update { it.copy(schedule = schedule) }
    override suspend fun setLeadDistance(lead: ServiceReminderLead) = update { it.copy(leadDistance = lead) }
    override suspend fun setCalendar(schedule: ServiceCalendarSchedule) = update { it.copy(calendar = schedule) }
    override suspend fun setCalendarLead(lead: ServiceCalendarLead) = update { it.copy(calendarLead = lead) }

    fun exportSettings(): JSONObject = synchronized(lock) { encode(read()) }

    fun restoreSettings(source: JSONObject) {
        synchronized(lock) {
            write(decode(source, read()))
        }
    }

    private suspend fun update(change: (ServiceReminderSettings) -> ServiceReminderSettings) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = read()
            val next = change(current)
            if (current != next) write(next)
        }
    }

    private fun read(): ServiceReminderSettings {
        val raw = preferences.all[PLAN] as? String ?: return ServiceReminderSettings()
        return try {
            decode(JSONObject(raw))
        } catch (_: JSONException) {
            ControlsLog.warn("service_reminder_settings", "outcome", "invalid_saved_plan")
            ServiceReminderSettings()
        }
    }

    private fun write(settings: ServiceReminderSettings) {
        val previous = preferences.all[PLAN] as? String
        if (!preferences.edit().putString(PLAN, encode(settings).toString()).commit()) {
            val restored = preferences.edit().putString(PLAN, previous).commit()
            ControlsLog.warn("service_reminder_settings", "outcome", "commit_failed", "rolledBack", restored)
            throw IllegalStateException("Could not save service reminder settings")
        }
    }

    companion object {
        const val STORE = "service_reminder"
        private const val PLAN = "plan"
        private const val LEAD_DISTANCE = "leadDistanceKm"
        private const val CALENDAR_INTERVAL = "calendarIntervalMonths"
        private const val LAST_SERVICE_DATE = "lastServiceDate"
        private const val CALENDAR_LEAD = "calendarLeadDays"
        private val lock = Any()

        @JvmStatic
        fun validateSettings(source: JSONObject): JSONObject = encode(decode(source)).apply {
            for (key in listOf(LEAD_DISTANCE, CALENDAR_INTERVAL, LAST_SERVICE_DATE, CALENDAR_LEAD)) {
                if (!source.has(key)) remove(key)
            }
        }

        private fun decode(
            source: JSONObject,
            fallback: ServiceReminderSettings = ServiceReminderSettings(),
        ): ServiceReminderSettings {
            val enabled = source.get("enabled") as? Boolean ?: throw JSONException("Invalid service reminder switch")
            val interval = integer(source, "intervalKm")
            val current = integer(source, "currentKm")
            val lastService = integer(source, "lastServiceKm")
            val schedule = try {
                ServiceSchedule(interval, current, lastService)
            } catch (_: IllegalArgumentException) {
                throw JSONException("Invalid service schedule")
            }
            val lead = if (source.has(LEAD_DISTANCE)) {
                val kilometers = integer(source, LEAD_DISTANCE)
                ServiceReminderLead.entries.firstOrNull { it.kilometers == kilometers }
                    ?: throw JSONException("Invalid service reminder lead distance")
            } else fallback.leadDistance
            val months = if (source.has(CALENDAR_INTERVAL)) integer(source, CALENDAR_INTERVAL)
                else fallback.calendar.intervalMonths
            val lastDate = if (source.has(LAST_SERVICE_DATE)) when (val value = source.get(LAST_SERVICE_DATE)) {
                JSONObject.NULL -> null
                is String -> ServiceCalendarSchedule.parseDate(value) ?: throw JSONException("Invalid service date")
                else -> throw JSONException("Invalid service date")
            } else fallback.calendar.lastServiceDate
            val calendar = try {
                ServiceCalendarSchedule(months, lastDate)
            } catch (_: IllegalArgumentException) {
                throw JSONException("Invalid calendar schedule")
            }
            val calendarLead = if (source.has(CALENDAR_LEAD)) {
                val days = integer(source, CALENDAR_LEAD)
                ServiceCalendarLead.entries.firstOrNull { it.days == days }
                    ?: throw JSONException("Invalid calendar reminder lead")
            } else fallback.calendarLead
            return ServiceReminderSettings(enabled, schedule, lead, calendar, calendarLead)
        }

        private fun integer(source: JSONObject, key: String): Int? = when (val value = source.get(key)) {
            JSONObject.NULL -> null
            is Int -> value
            else -> throw JSONException("Invalid service interval, reading or lead")
        }

        private fun encode(settings: ServiceReminderSettings): JSONObject = JSONObject()
            .put("enabled", settings.enabled)
            .put("intervalKm", settings.schedule.intervalKm ?: JSONObject.NULL)
            .put("currentKm", settings.schedule.currentKm ?: JSONObject.NULL)
            .put("lastServiceKm", settings.schedule.lastServiceKm ?: JSONObject.NULL)
            .put(LEAD_DISTANCE, settings.leadDistance.kilometers ?: JSONObject.NULL)
            .put(CALENDAR_INTERVAL, settings.calendar.intervalMonths ?: JSONObject.NULL)
            .put(LAST_SERVICE_DATE, settings.calendar.lastServiceDate?.toString() ?: JSONObject.NULL)
            .put(CALENDAR_LEAD, settings.calendarLead.days)
    }
}
