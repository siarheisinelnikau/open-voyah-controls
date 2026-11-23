package org.openvoyah.controls.data.vehicle

import android.content.Context
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.vehicle.ExteriorLamp
import org.openvoyah.controls.domain.vehicle.HeadlightHistory
import org.openvoyah.controls.domain.vehicle.HeadlightMode
import org.openvoyah.controls.domain.vehicle.HeadlightObservation
import org.openvoyah.controls.domain.vehicle.HeadlightSource
import org.openvoyah.controls.domain.vehicle.LampActivity
import org.openvoyah.controls.domain.vehicle.LampObservation

/** Private local observation. Portable settings archives do not use this store. */
internal class LocalHeadlightHistory(context: Context) : HeadlightHistory {
    private val preferences = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    override fun read(): HeadlightObservation? {
        val values = synchronized(lock) { preferences.all }
        val mode = HeadlightMode.entries.firstOrNull { it.key == values[KEY_MODE] } ?: return null
        val time = (values[KEY_TIME] as? Long)?.takeIf { it > 0 } ?: return null
        return HeadlightObservation(mode, time, readSource(values[KEY_SOURCE]))
    }

    override fun save(observation: HeadlightObservation): Boolean = write(
        KEY_MODE, KEY_TIME, KEY_SOURCE, observation.mode.key, observation.confirmedAtMillis, observation.source, "selector",
    )

    override fun readLamps(): Map<ExteriorLamp, LampObservation> {
        val values = synchronized(lock) { preferences.all }
        return buildMap {
            for (lamp in ExteriorLamp.entries) {
                val activity = LampActivity.entries.firstOrNull { it.key == values[activityKey(lamp)] && lamp.supports(it) } ?: continue
                val time = (values[activityTimeKey(lamp)] as? Long)?.takeIf { it > 0 } ?: continue
                put(lamp, LampObservation(activity, time, readSource(values[activitySourceKey(lamp)])))
            }
        }
    }

    override fun saveLamp(lamp: ExteriorLamp, observation: LampObservation): Boolean {
        require(lamp.supports(observation.activity))
        return write(
            activityKey(lamp), activityTimeKey(lamp), activitySourceKey(lamp),
            observation.activity.key, observation.confirmedAtMillis, observation.source, lamp.key,
        )
    }

    private fun readSource(value: Any?): HeadlightSource? = HeadlightSource.entries.firstOrNull { it.key == value }

    private fun write(
        stateKey: String, timeKey: String, sourceKey: String,
        state: String, time: Long, source: HeadlightSource?, signal: String,
    ): Boolean = synchronized(lock) {
        val previous = preferences.all
        val saved = preferences.edit()
            .putString(stateKey, state).putLong(timeKey, time).putString(sourceKey, source?.key).commit()
        if (!saved) {
            val restore = preferences.edit()
            val oldState = previous[stateKey] as? String
            val oldTime = previous[timeKey] as? Long
            if (oldState != null && oldTime != null) {
                restore.putString(stateKey, oldState).putLong(timeKey, oldTime)
                    .putString(sourceKey, previous[sourceKey] as? String)
            } else {
                restore.remove(stateKey).remove(timeKey).remove(sourceKey)
            }
            val restored = restore.commit()
            ControlsLog.warn("headlight_history", "signal", signal, "outcome", "commit_failed", "restored", restored)
        }
        saved
    }

    private fun activityKey(lamp: ExteriorLamp) = "lamp_${lamp.key}_activity"
    private fun activityTimeKey(lamp: ExteriorLamp) = "lamp_${lamp.key}_confirmed_at_millis"
    private fun activitySourceKey(lamp: ExteriorLamp) = "lamp_${lamp.key}_source"

    companion object {
        const val STORE = "headlight_history"
        private const val KEY_MODE = "confirmed_mode"
        private const val KEY_TIME = "confirmed_at_millis"
        private const val KEY_SOURCE = "confirmed_source"
        private val lock = Any()
    }
}
