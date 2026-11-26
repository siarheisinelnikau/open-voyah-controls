package org.openvoyah.controls.data.vehicle.comfort

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
import org.openvoyah.controls.domain.vehicle.comfort.AdaptiveBrightnessSettings

internal class AdaptiveBrightnessPreferences(context: Context) {
    private val storage = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun observe() = callbackFlow<Unit> {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == SETTINGS) trySend(Unit)
        }
        storage.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { storage.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { runCatching(::read) }.distinctUntilChanged().flowOn(Dispatchers.IO)

    suspend fun update(expected: AdaptiveBrightnessSettings, replacement: AdaptiveBrightnessSettings) =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                check(read() == expected) { "Brightness settings changed" }
                write(replacement)
            }
        }

    fun exportSettings(): JSONObject = encode(read())

    fun restoreSettings(data: JSONObject) = synchronized(lock) { write(decode(data)) }

    private fun read(): AdaptiveBrightnessSettings = synchronized(lock) {
        val raw = storage.getString(SETTINGS, null) ?: return AdaptiveBrightnessSettings()
        decode(JSONObject(raw))
    }

    private fun write(settings: AdaptiveBrightnessSettings) {
        val before = storage.getString(SETTINGS, null)
        val next = encode(settings).toString()
        if (next == before) return
        if (!storage.edit().putString(SETTINGS, next).commit()) {
            val recovered = storage.edit().putString(SETTINGS, before).commit()
            error("Could not save brightness settings (rolledBack=$recovered)")
        }
    }

    companion object {
        const val STORE = "adaptive_controls_brightness"
        private const val SETTINGS = "settings"
        private val lock = Any()

        @JvmStatic fun validateSettings(data: JSONObject): JSONObject = encode(decode(data))

        private fun encode(settings: AdaptiveBrightnessSettings) = JSONObject().put("schema", 1)
            .put("enabled", settings.enabled).put("lowPercent", settings.lowPercent).put("highPercent", settings.highPercent)

        private fun decode(data: JSONObject): AdaptiveBrightnessSettings {
            require(data.keys().asSequence().toSet() == setOf("schema", "enabled", "lowPercent", "highPercent"))
            require(data.get("schema") == 1)
            return AdaptiveBrightnessSettings(requireNotNull(data.get("enabled") as? Boolean),
                requireNotNull(data.get("lowPercent") as? Int), requireNotNull(data.get("highPercent") as? Int))
        }
    }
}
