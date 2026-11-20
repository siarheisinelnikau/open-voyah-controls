package org.openvoyah.controls.data.vehicle.automation

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
import org.json.JSONObject
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.vehicle.automation.DrivingModeMemorySettings
import org.openvoyah.controls.domain.vehicle.automation.HeadlightGearAction
import org.openvoyah.controls.domain.vehicle.automation.HeadlightGearSettings
import org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationSettings
import org.openvoyah.controls.domain.vehicle.automation.VehicleAutomationStore

/** One durable, bounded value; corrupt settings remain available for recovery instead of being replaced. */
internal class LocalVehicleAutomationPreferences(context: Context) : VehicleAutomationStore {
    private val preferences = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    override fun observe() = callbackFlow<Unit> {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == SETTINGS) trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { runCatching { synchronized(lock) { read() } } }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override suspend fun update(expected: VehicleAutomationSettings, replacement: VehicleAutomationSettings) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            check(read() == expected) { "Vehicle automation settings changed" }
            write(replacement)
        }
    }

    fun exportSettings(): JSONObject = synchronized(lock) { encode(read()) }

    fun restoreSettings(data: JSONObject) {
        val settings = decode(data)
        synchronized(lock) { write(settings) }
    }

    private fun read(): VehicleAutomationSettings {
        val raw = preferences.all[SETTINGS] ?: return VehicleAutomationSettings()
        require(raw is String && raw.length <= MAX_CHARS)
        return decode(JSONObject(raw))
    }

    private fun write(settings: VehicleAutomationSettings) {
        val raw = encode(settings).toString()
        val previous = preferences.all[SETTINGS]
        if (previous == raw) return
        if (!preferences.edit().putString(SETTINGS, raw).commit()) {
            val rollback = preferences.edit()
            when (previous) {
                null -> rollback.remove(SETTINGS)
                is String -> rollback.putString(SETTINGS, previous)
                is Boolean -> rollback.putBoolean(SETTINGS, previous)
                is Int -> rollback.putInt(SETTINGS, previous)
                is Long -> rollback.putLong(SETTINGS, previous)
                is Float -> rollback.putFloat(SETTINGS, previous)
                is Set<*> -> rollback.putStringSet(SETTINGS, previous.filterIsInstance<String>().toSet())
                else -> error("Unsupported original preference type")
            }
            rollback.commit()
            ControlsLog.warn("vehicle_automation_settings", "outcome", "commit_failed")
            error("Could not save vehicle automation settings")
        }
        ControlsLog.info("vehicle_automation_settings", "outcome", "saved")
    }

    companion object {
        const val STORE = "vehicle_automation"
        private const val SETTINGS = "settings"
        private const val MAX_CHARS = 4_096
        private val lock = Any()

        @JvmStatic fun validateSettings(data: JSONObject): JSONObject = encode(decode(data))

        private fun encode(settings: VehicleAutomationSettings): JSONObject = JSONObject()
            .put("schema", 1)
            .put("headlights", JSONObject().put("enabled", settings.headlights.enabled)
                .put("onDrive", settings.headlights.onDrive.key).put("onPark", settings.headlights.onPark.key))
            .put("drivingMode", JSONObject().put("enabled", settings.drivingMode.enabled))

        private fun decode(data: JSONObject): VehicleAutomationSettings {
            require(data.toString().length <= MAX_CHARS)
            exactKeys(data, setOf("schema", "headlights", "drivingMode"))
            require(data.get("schema") is Int && data.getInt("schema") == 1)
            val lights = data.getJSONObject("headlights")
            val memory = data.getJSONObject("drivingMode")
            exactKeys(lights, setOf("enabled", "onDrive", "onPark"))
            exactKeys(memory, setOf("enabled"))
            return VehicleAutomationSettings(
                HeadlightGearSettings(boolean(lights, "enabled"), action(lights, "onDrive"), action(lights, "onPark")),
                DrivingModeMemorySettings(boolean(memory, "enabled")),
            )
        }

        private fun boolean(data: JSONObject, key: String) = requireNotNull(data.get(key) as? Boolean)
        private fun action(data: JSONObject, key: String): HeadlightGearAction =
            requireNotNull(HeadlightGearAction.entries.singleOrNull { it.key == data.get(key) })
        private fun exactKeys(data: JSONObject, names: Set<String>) {
            require(data.keys().asSequence().toSet() == names)
        }
    }
}
