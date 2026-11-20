package org.openvoyah.controls.data.vehicle.automation

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.openvoyah.controls.domain.vehicle.automation.DrivingMode
import org.openvoyah.controls.domain.vehicle.automation.DrivingModeHistory
import org.openvoyah.controls.domain.vehicle.automation.DrivingModeRecord
import org.openvoyah.controls.domain.vehicle.automation.VehicleIdentity

/** Controller history is deliberately local and is never restored from a user settings archive. */
internal class LocalDrivingModeHistory(context: Context) : DrivingModeHistory {
    private val preferences = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    override fun observe() = callbackFlow<Unit> {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == RECORD) trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { runCatching { synchronized(lock) { read() } } }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override suspend fun save(record: DrivingModeRecord) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val previous = preferences.all[RECORD]
            val before = read()
            if (before == record) return@synchronized
            check(before == null || record.identity != before.identity || record.confirmedAtMillis >= before.confirmedAtMillis) {
                "Driving mode observation predates saved history"
            }
            val raw = encode(record).toString()
            require(raw.length <= 4_096)
            if (!preferences.edit().putString(RECORD, raw).commit()) {
                val rollback = preferences.edit()
                if (previous == null) rollback.remove(RECORD) else rollback.putString(RECORD, previous as String)
                rollback.commit()
                Log.w("OpenVoyahControls", "event=driving_mode_history outcome=commit_failed")
                error("Could not save actual driving mode")
            }
            Log.i("OpenVoyahControls", "event=driving_mode_history outcome=confirmed mode=${record.mode.key} source=${record.sourceId}")
        }
    }

    private fun read(): DrivingModeRecord? {
        val raw = preferences.all[RECORD] ?: return null
        require(raw is String && raw.length <= 4_096)
        val data = JSONObject(raw)
        require(data.keys().asSequence().toSet() == setOf("schema", "mode", "identity", "source", "session", "confirmedAtMillis"))
        require(data.get("schema") is Int && data.getInt("schema") == 1)
        val identity = data.getJSONObject("identity")
        require(identity.keys().asSequence().toSet() == setOf("product", "fingerprint", "sdk", "abi"))
        val product = string(identity, "product", 96)
        val fingerprint = string(identity, "fingerprint", 256)
        val abi = string(identity, "abi", 32)
        require(identity.get("sdk") is Int && identity.getInt("sdk") in 28..100)
        val time = data.get("confirmedAtMillis")
        require(time is Long || time is Int)
        return DrivingModeRecord(
            requireNotNull(DrivingMode.entries.singleOrNull { it.key == data.get("mode") }),
            VehicleIdentity(product, fingerprint, identity.getInt("sdk"), abi),
            string(data, "source", 96), string(data, "session", 96), (time as Number).toLong(),
        )
    }

    private fun string(data: JSONObject, key: String, maximum: Int): String {
        val value = requireNotNull(data.get(key) as? String)
        require(value.isNotBlank() && value.length <= maximum && value.none { it.isISOControl() })
        return value
    }

    private fun encode(record: DrivingModeRecord): JSONObject = JSONObject()
        .put("schema", 1).put("mode", record.mode.key)
        .put("identity", JSONObject().put("product", record.identity.product).put("fingerprint", record.identity.fingerprint)
            .put("sdk", record.identity.sdk).put("abi", record.identity.abi))
        .put("source", record.sourceId).put("session", record.sessionId).put("confirmedAtMillis", record.confirmedAtMillis)

    companion object {
        const val STORE = "driving_mode_history"
        private const val RECORD = "confirmed"
        private val lock = Any()
    }
}
