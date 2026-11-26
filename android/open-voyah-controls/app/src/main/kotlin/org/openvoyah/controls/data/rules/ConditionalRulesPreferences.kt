package org.openvoyah.controls.data.rules

import android.content.Context
import android.content.SharedPreferences
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
import org.openvoyah.controls.data.routines.decodeRoutineSteps
import org.openvoyah.controls.data.routines.encodeRoutineSteps
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.rules.ConditionalRule
import org.openvoyah.controls.domain.rules.ConditionalRuleLibrary
import org.openvoyah.controls.domain.rules.DoorRequirement
import org.openvoyah.controls.domain.rules.RuleComparison
import org.openvoyah.controls.domain.rules.RuleConditions
import org.openvoyah.controls.domain.rules.RuleThreshold
import org.openvoyah.controls.domain.routines.NamedRoutine
import org.openvoyah.controls.domain.routines.RoutinePlan
import org.openvoyah.controls.domain.routines.RoutineTarget
import org.openvoyah.controls.domain.routines.RoutineTimeWindow
import org.openvoyah.controls.domain.settings.AppPalette
import org.openvoyah.controls.domain.vehicle.automation.DrivingMode
import org.openvoyah.controls.domain.vehicle.automation.VehicleGear

/** Settings are one validated durable record. Live inputs and command attempts are never archived. */
internal class ConditionalRulesPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun observe() = callbackFlow<Unit> {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == SETTINGS) trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { runCatching { synchronized(lock) { read() } } }.distinctUntilChanged().flowOn(Dispatchers.IO)

    suspend fun update(change: (ConditionalRuleLibrary) -> ConditionalRuleLibrary): Unit = withContext(Dispatchers.IO) {
        synchronized(lock) { write(change(read())) }
    }

    fun current(): ConditionalRuleLibrary = synchronized(lock) { read() }

    fun exportSettings(): JSONObject = synchronized(lock) { encode(read()) }

    fun restoreSettings(data: JSONObject) {
        val decoded = requireNotNull(decodePatch(data)) { "Incomplete rules record" }
        // A full validated record may recover a damaged store. Partial/invalid records never erase it.
        synchronized(lock) { write(decoded) }
    }

    private fun read(): ConditionalRuleLibrary {
        val raw = preferences.all[SETTINGS] ?: return ConditionalRuleLibrary()
        require(raw is String && raw.length <= MAX_CHARS) { "Invalid saved rules record" }
        return requireNotNull(decodePatch(JSONObject(raw))) { "Incomplete rules record" }
    }

    private fun write(library: ConditionalRuleLibrary) {
        val next = encode(library).toString().also { require(it.length <= MAX_CHARS) }
        val previous = preferences.all[SETTINGS]
        if (next == previous) return
        if (!preferences.edit().putString(SETTINGS, next).commit()) {
            val rollback = preferences.edit()
            when (previous) {
                null -> rollback.remove(SETTINGS)
                is String -> rollback.putString(SETTINGS, previous)
                is Boolean -> rollback.putBoolean(SETTINGS, previous)
                is Int -> rollback.putInt(SETTINGS, previous)
                is Long -> rollback.putLong(SETTINGS, previous)
                is Float -> rollback.putFloat(SETTINGS, previous)
                is Set<*> -> rollback.putStringSet(SETTINGS, previous.filterIsInstance<String>().toSet())
                else -> error("Unsupported original rule preference type")
            }
            val rolledBack = rollback.commit()
            ControlsLog.warn("conditional_rules_settings", "outcome", "commit_failed", "rolledBack", rolledBack)
            error("Could not save conditional rules")
        }
    }

    companion object {
        const val STORE = "conditional-rules"
        private const val SETTINGS = "settings"
        private const val MAX_CHARS = 65_536
        private val lock = Any()
        private val rootKeys = setOf("schema", "entries")
        private val ruleKeys = setOf("id", "name", "enabled", "gear", "doors", "cooldownSeconds", "routine")
        private val conditionKeys = setOf("ambientLux", "chargePercent", "outsideCelsius", "drivingMode", "timeWindow")
        private val routineKeys = setOf("id", "name", "target", "palette", "installedApp", "desktopLayoutId")

        @JvmStatic
        fun validateSettings(data: JSONObject): JSONObject =
            requireNotNull(decodePatch(data)) { "Incomplete rules record" }.let(::encode)

        private fun decodePatch(data: JSONObject): ConditionalRuleLibrary? {
            require(data.toString().length <= MAX_CHARS)
            require(data.keys().asSequence().all { it in rootKeys })
            if (data.length() == 0) return null
            require(data.length() == 2 && data.get("schema") == 1) { "Unsupported rules schema" }
            val entries = data.getJSONArray("entries")
            require(entries.length() <= ConditionalRuleLibrary.MAX_RULES)
            return ConditionalRuleLibrary(List(entries.length()) { decodeRule(entries.getJSONObject(it)) })
        }

        private fun decodeRule(data: JSONObject): ConditionalRule {
            require(ruleKeys.all(data::has) && data.keys().asSequence().all { it in ruleKeys || it == "conditions" })
            val gear = data.nullableString("gear")?.let { key -> requireNotNull(VehicleGear.entries.find { it.key == key }) }
            val doors = data.nullableString("doors")?.let { key -> requireNotNull(DoorRequirement.entries.find { it.key == key }) }
            return ConditionalRule(
                id = data.string("id"), name = data.string("name"), routine = decodeRoutine(data.getJSONObject("routine")),
                gear = gear, doors = doors, enabled = requireNotNull(data.get("enabled") as? Boolean),
                cooldownSeconds = requireNotNull(data.get("cooldownSeconds") as? Int),
                conditions = if (data.has("conditions")) decodeConditions(data.getJSONObject("conditions")) else RuleConditions.EMPTY,
            )
        }

        private fun decodeConditions(data: JSONObject): RuleConditions {
            require(data.keys().asSequence().all { it in conditionKeys })
            fun threshold(key: String): RuleThreshold? = if (!data.has(key)) null else data.getJSONObject(key).let { entry ->
                require(entry.length() == 2)
                RuleThreshold(requireNotNull(entry.get("amount") as? Int),
                    requireNotNull(RuleComparison.entries.find { it.key == entry.string("comparison") }))
            }
            val mode = if (!data.has("drivingMode")) null else
                requireNotNull(DrivingMode.entries.find { it.key == data.string("drivingMode") })
            val range = if (!data.has("timeWindow")) null else data.getJSONObject("timeWindow").let { entry ->
                require(entry.length() == 2)
                RoutineTimeWindow(requireNotNull(entry.get("startMinute") as? Int), requireNotNull(entry.get("endMinute") as? Int))
            }
            return RuleConditions(threshold("ambientLux"), threshold("chargePercent"), threshold("outsideCelsius"), mode, range)
        }

        private fun encodeConditions(conditions: RuleConditions) = JSONObject().apply {
            fun putThreshold(key: String, value: RuleThreshold?) {
                if (value != null) put(key, JSONObject().put("amount", value.amount).put("comparison", value.comparison.key))
            }
            putThreshold("ambientLux", conditions.ambientLux)
            putThreshold("chargePercent", conditions.chargePercent)
            putThreshold("outsideCelsius", conditions.outsideCelsius)
            conditions.drivingMode?.let { put("drivingMode", it.key) }
            conditions.timeWindow?.let { put("timeWindow", JSONObject().put("startMinute", it.startMinute).put("endMinute", it.endMinute)) }
        }

        private fun decodeRoutine(data: JSONObject): NamedRoutine {
            require(routineKeys.all(data::has) && data.keys().asSequence().all { it in routineKeys || it == "steps" })
            val target = requireNotNull(RoutineTarget.entries.find { it.key == data.string("target") })
            val palette = data.nullableString("palette")?.let { key ->
                requireNotNull(listOf(AppPalette.DAY, AppPalette.DARK).find { it.preferenceId == key })
            }
            return NamedRoutine(data.string("id"), data.string("name"), RoutinePlan(target, palette,
                data.nullableString("installedApp"), data.nullableString("desktopLayoutId"),
                if (data.has("steps")) decodeRoutineSteps(data.getJSONArray("steps")) else emptyList()))
        }

        private fun encode(library: ConditionalRuleLibrary) = JSONObject().put("schema", 1).put("entries", JSONArray().apply {
            library.entries.forEach { rule ->
                put(JSONObject().put("id", rule.id).put("name", rule.name).put("enabled", rule.enabled)
                    .put("gear", rule.gear?.key ?: JSONObject.NULL).put("doors", rule.doors?.key ?: JSONObject.NULL)
                    .put("cooldownSeconds", rule.cooldownSeconds).put("routine", encodeRoutine(rule.routine)).apply {
                        if (!rule.conditions.isEmpty) put("conditions", encodeConditions(rule.conditions))
                    })
            }
        })

        private fun encodeRoutine(routine: NamedRoutine) = JSONObject().put("id", routine.id).put("name", routine.name)
            .put("target", routine.plan.target.key).put("palette", routine.plan.palette?.preferenceId ?: JSONObject.NULL)
            .put("installedApp", routine.plan.installedApp ?: JSONObject.NULL)
            .put("desktopLayoutId", routine.plan.desktopLayoutId ?: JSONObject.NULL)
            .apply { if (routine.plan.steps.isNotEmpty()) put("steps", encodeRoutineSteps(routine.plan.steps)) }

        private fun JSONObject.string(key: String) = requireNotNull(get(key) as? String) { "Invalid rule field: $key" }
        private fun JSONObject.nullableString(key: String): String? = if (get(key) === JSONObject.NULL) null else string(key)
    }
}
