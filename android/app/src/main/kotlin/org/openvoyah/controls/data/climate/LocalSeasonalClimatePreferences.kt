package org.openvoyah.controls.data.climate

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
import org.openvoyah.controls.domain.climate.ClimateGearCondition
import org.openvoyah.controls.domain.climate.SeasonalClimateChanged
import org.openvoyah.controls.domain.climate.SeasonalClimatePreset
import org.openvoyah.controls.domain.climate.SeasonalClimateSettings
import org.openvoyah.controls.domain.climate.SeasonalClimateStore

internal class LocalSeasonalClimatePreferences(context: Context) : SeasonalClimateStore {
    private val preferences = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    override fun observe(): Flow<Result<SeasonalClimateSettings>> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == SETTINGS) trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { runCatching { synchronized(lock) { read() } } }
        .distinctUntilChanged().flowOn(Dispatchers.IO)

    override suspend fun update(expected: SeasonalClimateSettings, replacement: SeasonalClimateSettings): Unit =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (read() != expected) throw SeasonalClimateChanged()
                write(replacement)
            }
        }

    fun exportSettings(): JSONObject = synchronized(lock) { encode(read()) }

    fun restoreSettings(source: JSONObject) {
        val validated = decode(source)
        synchronized(lock) { write(validated) }
    }

    private fun read(): SeasonalClimateSettings {
        val raw = preferences.all[SETTINGS] ?: return SeasonalClimateSettings()
        require(raw is String && raw.length <= MAX_CHARACTERS) { "Invalid saved climate settings" }
        return decode(JSONObject(raw))
    }

    private fun write(settings: SeasonalClimateSettings) {
        val raw = encode(settings).toString()
        val previous = preferences.all[SETTINGS]
        if (raw == previous) return
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
                else -> error("Unsupported original climate preference type")
            }
            val restored = rollback.commit()
            ControlsLog.warn("seasonal_climate_settings", "outcome", "commit_failed", "rolledBack", restored)
            error("Could not save seasonal climate settings")
        }
        ControlsLog.info("seasonal_climate_settings", "outcome", "saved")
    }

    companion object {
        const val STORE = "seasonal_climate"
        private const val SETTINGS = "settings"
        private const val MAX_CHARACTERS = 4_096
        private val lock = Any()

        @JvmStatic fun validateSettings(source: JSONObject): JSONObject = encode(decode(source))

        private fun encode(value: SeasonalClimateSettings): JSONObject = JSONObject()
            .put("schema", 1).put("summer", encodePreset(value.summer)).put("winter", encodePreset(value.winter))

        private fun encodePreset(value: SeasonalClimatePreset): JSONObject = JSONObject()
            .put("enabled", value.enabled).put("outsideThresholdCelsius", value.outsideThresholdCelsius)
            .put("driverCelsius", value.driverCelsius).put("passengerCelsius", value.passengerCelsius)
            .put("fanLevel", value.fanLevel).put("gearCondition", value.gearCondition.key)
            .put("requireDoorsClosed", value.requireDoorsClosed)
            .put("driverSeatLevel", value.driverSeatLevel).put("passengerSeatLevel", value.passengerSeatLevel)

        private fun decode(source: JSONObject): SeasonalClimateSettings {
            if (source.toString().length > MAX_CHARACTERS || source.opt("schema") != 1)
                throw JSONException("Unsupported seasonal climate settings")
            return try {
                SeasonalClimateSettings(decodePreset(source.getJSONObject("summer")), decodePreset(source.getJSONObject("winter")))
            } catch (_: IllegalArgumentException) {
                throw JSONException("Invalid seasonal climate thresholds or setpoints")
            }
        }

        private fun decodePreset(source: JSONObject): SeasonalClimatePreset = SeasonalClimatePreset(
            enabled = boolean(source, "enabled"),
            outsideThresholdCelsius = integer(source, "outsideThresholdCelsius"),
            driverCelsius = integer(source, "driverCelsius"),
            passengerCelsius = integer(source, "passengerCelsius"),
            fanLevel = integer(source, "fanLevel"),
            gearCondition = ClimateGearCondition.entries.firstOrNull { it.key == source.get("gearCondition") }
                ?: throw JSONException("Invalid seasonal climate gear condition"),
            requireDoorsClosed = boolean(source, "requireDoorsClosed"),
            driverSeatLevel = integer(source, "driverSeatLevel"),
            passengerSeatLevel = integer(source, "passengerSeatLevel"),
        )

        private fun integer(source: JSONObject, key: String): Int = source.get(key) as? Int
            ?: throw JSONException("Invalid seasonal climate integer")
        private fun boolean(source: JSONObject, key: String): Boolean = source.get(key) as? Boolean
            ?: throw JSONException("Invalid seasonal climate switch")
    }
}
