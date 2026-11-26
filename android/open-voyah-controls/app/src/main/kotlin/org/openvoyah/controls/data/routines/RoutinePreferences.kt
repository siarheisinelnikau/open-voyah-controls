package org.openvoyah.controls.data.routines

import android.content.Context
import android.content.SharedPreferences
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.routines.NamedRoutine
import org.openvoyah.controls.domain.routines.RoutineLibrary
import org.openvoyah.controls.domain.routines.RoutineOrder
import org.openvoyah.controls.domain.routines.RoutinePlan
import org.openvoyah.controls.domain.routines.RoutineStartupDelay
import org.openvoyah.controls.domain.routines.RoutineStartupFrequency
import org.openvoyah.controls.domain.routines.RoutineStartupRequest
import org.openvoyah.controls.domain.routines.RoutineStartupSchedule
import org.openvoyah.controls.domain.routines.RoutineTarget
import org.openvoyah.controls.domain.routines.RoutineTimeWindow
import org.openvoyah.controls.domain.settings.AppPalette

internal class RoutinePreferences(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    private val history by lazy { app.getSharedPreferences(HISTORY_STORE, Context.MODE_PRIVATE) }

    fun observe() = callbackFlow<Unit> {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == SETTINGS) trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { runCatching { synchronized(lock) { read() } } }.distinctUntilChanged().flowOn(Dispatchers.IO)

    fun startupRequest(at: LocalDateTime = LocalDateTime.now()): RoutineStartupRequest? = try {
        synchronized(lock) { eligible(read(), at) }
    } catch (failure: Exception) {
        // An unreadable store skips the startup routine; without this entry that looks like "no routine selected".
        ControlsLog.warn("routine_startup", failure, "outcome", "store_unreadable")
        null
    }

    @JvmOverloads fun startupPlan(at: LocalDateTime = LocalDateTime.now()): RoutinePlan? = startupRequest(at)?.routine?.plan

    // At most six Long values per routine per year, including dates revisited after changing time zones.
    private fun dayBlock(id: String, date: LocalDate) = "$id.${date.year}.${(date.dayOfYear - 1) / Long.SIZE_BITS}"
    private fun dayBit(date: LocalDate) = 1L shl ((date.dayOfYear - 1) % Long.SIZE_BITS)

    private fun eligible(library: RoutineLibrary, at: LocalDateTime): RoutineStartupRequest? {
        val id = library.startupId ?: return null
        val date = at.toLocalDate()
        val ranToday = library.schedule.frequency == RoutineStartupFrequency.ONCE_DAILY &&
            history.getLong(dayBlock(id, date), 0L) and dayBit(date) != 0L
        return library.startupRequest(at, ranToday)
    }

    fun runStartup(expected: RoutineStartupRequest, at: LocalDateTime = LocalDateTime.now(), apply: (RoutinePlan) -> Unit): Boolean =
        synchronized(lock) {
            val library = runCatching { read() }
                .onFailure { ControlsLog.warn("routine_startup", it, "outcome", "store_unreadable") }
                .getOrNull() ?: return false
            if (eligible(library, at) != expected) return false
            apply(expected.routine.plan)
            val id = expected.routine.id
            val today = at.toLocalDate()
            val key = dayBlock(id, today)
            val recorded = history.getLong(key, 0L)
            val bit = dayBit(today)
            if (recorded and bit == 0L) {
                // Reimporting the same routine ID must retain its completed days.
                history.edit().putLong(key, recorded or bit).apply()
            }
            true
        }

    fun planFor(routine: NamedRoutine): RoutinePlan = withCurrentPlan(routine) { it }

    fun findRoutine(id: String): NamedRoutine? = synchronized(lock) { read().entries.firstOrNull { it.id == id } }

    fun voiceRoutines(): Map<String, NamedRoutine> = synchronized(lock) {
        val library = read()
        library.entries.mapNotNull { routine -> library.voicePhrases[routine.id]?.let { it to routine } }.toMap()
    }

    fun hasVoiceRoutine(phrase: String, routine: NamedRoutine): Boolean = synchronized(lock) {
        val library = read()
        library.voicePhrases[routine.id] == phrase && library.entries.firstOrNull { it.id == routine.id } == routine
    }

    fun <T> withCurrentPlan(routine: NamedRoutine, use: (RoutinePlan) -> T): T =
        synchronized(lock) { use(read().planFor(routine)) }

    suspend fun update(change: (RoutineLibrary) -> RoutineLibrary): Unit = withContext(Dispatchers.IO) {
        synchronized(lock) { write(change(read())) }
    }

    fun exportSettings(): JSONObject = synchronized(lock) { Patch.full(read()).encode() }

    fun restoreSettings(data: JSONObject) {
        val patch = decode(data)
        if (patch == Patch()) return
        synchronized(lock) {
            val before = if (patch.complete && !patch.preservesEntryActions) RoutineLibrary() else try { read() } catch (ex: Exception) {
                // Older complete backups can still recover a damaged store.
                if (patch.hasOriginalFields) RoutineLibrary() else throw ex
            }
            write(patch.applyTo(before))
        }
    }

    private fun read(): RoutineLibrary {
        val raw = preferences.getString(SETTINGS, null) ?: return RoutineLibrary()
        require(raw.length <= 32_768)
        return decode(JSONObject(raw)).applyTo(RoutineLibrary())
    }

    private fun write(value: RoutineLibrary) {
        val raw = Patch.full(value).encode().toString()
        val previous = preferences.getString(SETTINGS, null)
        if (raw == previous) return
        if (!preferences.edit().putString(SETTINGS, raw).commit()) {
            preferences.edit().putString(SETTINGS, previous).commit()
            error("Could not save routines")
        }
    }

    private data class EntryPatch(val routine: NamedRoutine, val hasInstalledApp: Boolean, val hasDesktopLayout: Boolean,
                                  val hasSteps: Boolean) {
        val preservesActions: Boolean get() = !hasInstalledApp || !hasDesktopLayout || !hasSteps
    }

    private data class Patch(
        val entries: List<EntryPatch>? = null,
        val sort: RoutineOrder? = null,
        val hasStartup: Boolean = false,
        val startup: String? = null,
        val days: Set<DayOfWeek>? = null,
        val hasWindow: Boolean = false,
        val window: RoutineTimeWindow? = null,
        val pins: Set<String>? = null,
        val frequency: RoutineStartupFrequency? = null,
        val delay: RoutineStartupDelay? = null,
        val enabled: Boolean? = null,
        val voicePhrases: Map<String, String>? = null,
    ) {
        val hasOriginalFields: Boolean get() = entries != null && sort != null && hasStartup
        val complete: Boolean get() = hasOriginalFields && days != null && hasWindow && pins != null && frequency != null &&
            delay != null && enabled != null && voicePhrases != null
        val preservesEntryActions: Boolean get() = entries?.any { it.preservesActions } == true

        fun applyTo(before: RoutineLibrary): RoutineLibrary {
            val items = entries?.map { entry ->
                val (routine, hasApp, hasDesktopLayout, hasSteps) = entry
                val old = if (entry.preservesActions) before.entries.firstOrNull { it.id == routine.id }?.plan else null
                if (old == null) routine else routine.copy(plan = routine.plan.copy(
                    installedApp = if (hasApp) routine.plan.installedApp else old.installedApp,
                    desktopLayoutId = if (hasDesktopLayout) routine.plan.desktopLayoutId else old.desktopLayoutId,
                    steps = if (hasSteps) routine.plan.steps else old.steps,
                ))
            } ?: before.entries
            val selected = if (hasStartup) startup else before.startupId?.takeIf { id -> items.any { it.id == id } }
            val schedule = RoutineStartupSchedule(days ?: before.schedule.days, if (hasWindow) window else before.schedule.window,
                frequency ?: before.schedule.frequency, delay ?: before.schedule.delay)
            val pinned = pins ?: before.pinnedIds.filterTo(mutableSetOf()) { id -> items.any { it.id == id } }
            val aliases = voicePhrases ?: before.voicePhrases.filterKeys { id -> items.any { it.id == id } }
            return RoutineLibrary(items, sort ?: before.order, selected, schedule, pinned, enabled ?: before.startupEnabled, aliases)
        }

        fun encode(): JSONObject = JSONObject().apply {
            entries?.let { list ->
                put("entries", JSONArray().apply {
                    list.forEach { (routine, hasApp, hasDesktopLayout, hasSteps) ->
                        put(JSONObject().put("id", routine.id).put("name", routine.name).put("target", routine.plan.target.key)
                            .apply {
                                routine.plan.palette?.let { put("palette", it.preferenceId) }
                                if (hasApp) put("installedApp", routine.plan.installedApp ?: JSONObject.NULL)
                                if (hasDesktopLayout) put("desktopLayoutId", routine.plan.desktopLayoutId ?: JSONObject.NULL)
                                if (hasSteps) put("steps", encodeRoutineSteps(routine.plan.steps))
                            })
                    }
                })
            }
            sort?.let { put("sort", it.key) }
            if (hasStartup) put("startup", startup ?: JSONObject.NULL)
            days?.let { put("startupDays", JSONArray(it.map { day -> day.value }.sorted())) }
            if (hasWindow) put("startupWindow", window?.let { JSONObject().put("startMinute", it.startMinute).put("endMinute", it.endMinute) }
                ?: JSONObject.NULL)
            pins?.let { put("pinnedIds", JSONArray(it.sorted())) }
            frequency?.let { put("startupFrequency", it.key) }
            delay?.let { put("startupDelaySeconds", it.seconds) }
            enabled?.let { put("startupEnabled", it) }
            voicePhrases?.let { aliases -> put("voicePhrases", JSONObject().apply {
                aliases.toSortedMap().forEach { (id, phrase) -> put(id, phrase) }
            }) }
        }

        companion object {
            fun full(value: RoutineLibrary) = Patch(
                value.entries.map { EntryPatch(it, hasInstalledApp = true, hasDesktopLayout = true, hasSteps = true) },
                value.order, true, value.startupId,
                value.schedule.days, true, value.schedule.window, value.pinnedIds, value.schedule.frequency, value.schedule.delay,
                value.startupEnabled, value.voicePhrases)
        }
    }

    companion object {
        const val STORE = "routines"
        const val HISTORY_STORE = "routine-startup-history"
        private const val SETTINGS = "settings"
        private val lock = Any()
        private val archiveKeys = setOf("entries", "sort", "startup", "startupDays", "startupWindow", "pinnedIds",
            "startupFrequency", "startupDelaySeconds", "startupEnabled", "voicePhrases")
        private val entryKeys = setOf("id", "name", "target", "palette", "installedApp", "desktopLayoutId", "steps")

        @JvmStatic fun validateSettings(data: JSONObject): JSONObject = decode(data).encode()

        private fun decode(data: JSONObject): Patch {
            require(data.keys().asSequence().all { it in archiveKeys })
            val entries = if (data.has("entries")) {
                val list = data.getJSONArray("entries")
                require(list.length() <= RoutineLibrary.MAX_ROUTINES)
                List(list.length()) { i -> decodeEntry(list.getJSONObject(i)) }
            } else null
            val sort = if (data.has("sort")) requireNotNull(RoutineOrder.entries.find { it.key == data.get("sort") }) else null
            val startup = if (data.has("startup") && data.get("startup") !== JSONObject.NULL) {
                data.string("startup").also { require(NamedRoutine.validId(it)) }
            } else null
            val days = if (data.has("startupDays")) {
                val list = data.getJSONArray("startupDays")
                require(list.length() in 1..7)
                List(list.length()) { i -> DayOfWeek.of(requireNotNull(list.get(i) as? Int)) }.toSet()
                    .also { require(it.size == list.length()) }
            } else null
            val window = if (data.has("startupWindow") && data.get("startupWindow") !== JSONObject.NULL) {
                val range = data.getJSONObject("startupWindow")
                require(range.length() == 2)
                RoutineTimeWindow(requireNotNull(range.get("startMinute") as? Int), requireNotNull(range.get("endMinute") as? Int))
            } else null
            val pins = if (data.has("pinnedIds")) {
                val list = data.getJSONArray("pinnedIds")
                require(list.length() <= RoutineLibrary.MAX_ROUTINES)
                List(list.length()) { i -> requireNotNull(list.get(i) as? String).also { require(NamedRoutine.validId(it)) } }
                    .toSet().also { require(it.size == list.length()) }
            } else null
            val frequency = if (data.has("startupFrequency"))
                requireNotNull(RoutineStartupFrequency.entries.find { it.key == data.get("startupFrequency") }) else null
            val delay = if (data.has("startupDelaySeconds"))
                requireNotNull(RoutineStartupDelay.entries.find { it.seconds == data.get("startupDelaySeconds") }) else null
            val enabled = if (data.has("startupEnabled")) requireNotNull(data.get("startupEnabled") as? Boolean) else null
            val aliases = if (data.has("voicePhrases")) {
                val items = data.getJSONObject("voicePhrases")
                require(items.length() <= RoutineLibrary.MAX_ROUTINES)
                items.keys().asSequence().associateWith { id ->
                    require(NamedRoutine.validId(id))
                    items.string(id).also { require(org.openvoyah.controls.domain.routines.normalizeRoutinePhrase(it) == it) }
                }.also { require(it.values.toSet().size == it.size) }
            } else null
            val patch = Patch(entries, sort, data.has("startup"), startup, days, data.has("startupWindow"), window, pins,
                frequency, delay, enabled, aliases)
            if (entries != null) patch.applyTo(RoutineLibrary())
            return patch
        }

        private fun decodeEntry(data: JSONObject): EntryPatch {
            require(data.keys().asSequence().all { it in entryKeys })
            val target = requireNotNull(RoutineTarget.entries.find { it.key == data.string("target") })
            val palette = if (data.has("palette")) when (data.string("palette")) {
                AppPalette.DARK.preferenceId -> AppPalette.DARK
                AppPalette.DAY.preferenceId -> AppPalette.DAY
                else -> error("Unknown routine theme")
            } else null
            val hasApp = data.has("installedApp")
            val app = if (hasApp && !data.isNull("installedApp")) data.string("installedApp") else null
            val hasDesktopLayout = data.has("desktopLayoutId")
            val desktopLayout = if (hasDesktopLayout && !data.isNull("desktopLayoutId")) data.string("desktopLayoutId") else null
            val hasSteps = data.has("steps")
            val steps = if (hasSteps) decodeRoutineSteps(data.getJSONArray("steps")) else emptyList()
            val routine = NamedRoutine(data.string("id"), requireNotNull(NamedRoutine.normalizeName(data.string("name"))),
                RoutinePlan(target, palette, app, desktopLayout, steps))
            return EntryPatch(routine, hasApp, hasDesktopLayout, hasSteps)
        }

        private fun JSONObject.string(key: String) = requireNotNull(get(key) as? String) { "Invalid routine field: $key" }
    }
}
