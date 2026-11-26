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
import org.openvoyah.controls.domain.vehicle.comfort.PlaybackComfortSettings

internal class PlaybackComfortPreferences(context: Context) {
    private val storage = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun read(): PlaybackComfortSettings = synchronized(lock) {
        val raw = storage.getString(SETTINGS, null) ?: return PlaybackComfortSettings()
        decode(JSONObject(raw))
    }

    fun observe() = callbackFlow<Unit> {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == SETTINGS) trySend(Unit)
        }
        storage.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { storage.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { runCatching(::read) }.distinctUntilChanged().flowOn(Dispatchers.IO)

    suspend fun update(expected: PlaybackComfortSettings, replacement: PlaybackComfortSettings) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            check(read() == expected) { "Playback comfort settings changed" }
            write(replacement)
        }
    }

    fun exportSettings(): JSONObject = encode(read())

    fun restoreSettings(data: JSONObject) = synchronized(lock) { write(decode(data)) }

    private fun write(settings: PlaybackComfortSettings) {
        val before = storage.getString(SETTINGS, null)
        val next = encode(settings).toString()
        if (next == before) return
        if (!storage.edit().putString(SETTINGS, next).commit()) {
            val restored = storage.edit().putString(SETTINGS, before).commit()
            error("Could not save playback comfort settings (rolledBack=$restored)")
        }
    }

    companion object {
        const val STORE = "playback_comfort"
        private const val SETTINGS = "settings"
        private val lock = Any()

        @JvmStatic fun validateSettings(data: JSONObject): JSONObject = encode(decode(data))

        private fun encode(settings: PlaybackComfortSettings) = JSONObject()
            .put("schema", 2).put("pauseOnDriverDoor", settings.pauseOnDriverDoor)
            .put("restoreAfterSleep", settings.restoreAfterSleep).put("duckOnDoor", settings.duckOnDoor)

        private fun decode(data: JSONObject): PlaybackComfortSettings {
            val schema = data.get("schema")
            require(schema == 1 || schema == 2)
            val originalKeys = setOf("schema", "pauseOnDriverDoor", "restoreAfterSleep")
            val keys = if (schema == 2) originalKeys + "duckOnDoor" else originalKeys
            require(data.keys().asSequence().toSet() == keys)
            return PlaybackComfortSettings(
                pauseOnDriverDoor = requireNotNull(data.get("pauseOnDriverDoor") as? Boolean),
                restoreAfterSleep = requireNotNull(data.get("restoreAfterSleep") as? Boolean),
                duckOnDoor = schema == 2 && requireNotNull(data.get("duckOnDoor") as? Boolean),
            )
        }
    }
}
