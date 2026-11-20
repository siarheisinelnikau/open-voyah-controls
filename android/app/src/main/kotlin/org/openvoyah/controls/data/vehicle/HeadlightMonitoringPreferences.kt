package org.openvoyah.controls.data.vehicle

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.openvoyah.controls.domain.vehicle.HeadlightMonitoringStore

internal class HeadlightMonitoringPreferences(context: Context) : HeadlightMonitoringStore {
    private val preferences = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    override fun observeWhileControlsOpen(): Flow<Boolean> = callbackFlow<Unit> {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == ENABLED) trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { read() }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override suspend fun setWhileControlsOpen(enabled: Boolean) = withContext(Dispatchers.IO) { write(enabled) }

    fun exportSettings(): JSONObject = JSONObject().put(ARCHIVE_ENABLED, read())

    fun restoreSettings(data: JSONObject) {
        decode(data)?.let(::write)
    }

    private fun read(): Boolean = synchronized(lock) { preferences.all[ENABLED] as? Boolean ?: false }

    private fun write(enabled: Boolean): Unit = synchronized(lock) {
        val before = preferences.all[ENABLED] as? Boolean
        if (before == enabled) return@synchronized
        if (!preferences.edit().putBoolean(ENABLED, enabled).commit()) {
            val rollback = preferences.edit()
            if (before == null) rollback.remove(ENABLED) else rollback.putBoolean(ENABLED, before)
            rollback.commit()
            Log.w("OpenVoyahControls", "event=headlight_monitoring_settings outcome=commit_failed")
            error("Headlight monitoring preference could not be written")
        }
    }

    companion object {
        const val STORE = "headlight_monitoring"
        private const val ENABLED = "while_controls_open"
        private const val ARCHIVE_ENABLED = "whileControlsOpen"
        private val lock = Any()

        @JvmStatic
        fun validateSettings(data: JSONObject): JSONObject = JSONObject().apply {
            decode(data)?.let { put(ARCHIVE_ENABLED, it) }
        }

        private fun decode(data: JSONObject): Boolean? {
            require(data.keys().asSequence().all { it == ARCHIVE_ENABLED })
            return if (data.has(ARCHIVE_ENABLED)) requireNotNull(data.get(ARCHIVE_ENABLED) as? Boolean) else null
        }
    }
}
