package org.openvoyah.controls

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import org.openvoyah.controls.core.Action
import org.openvoyah.controls.core.PanelSlots
import org.openvoyah.controls.data.frida.FridaBridge
import org.openvoyah.controls.data.routines.RoutinePreferences
import org.openvoyah.controls.data.settings.SettingsStore
import org.openvoyah.controls.domain.routines.NamedRoutine
import org.openvoyah.controls.platform.ui.RoutineActionLaunch

/** Only the approved Android launch permission allows the installed steering route to reach this entry. */
class SteeringRoutineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (savedInstanceState != null || intent.action != "org.openvoyah.controls.action.STEERING_ROUTINE") return
            val slot = intent.getStringExtra("org.openvoyah.controls.extra.STEERING_SLOT") ?: return
            val id = intent.getStringExtra("org.openvoyah.controls.extra.STEERING_ROUTINE_ID") ?: return
            if (slot !in PanelSlots.STEERING || !NamedRoutine.validId(id)) return
            val app = applicationContext
            val allowed = {
                runCatching {
                    val state = SettingsStore(app).load()
                    val action = state.steeringActions[slot]
                    state.enabled && action?.kind == Action.Kind.RUN_ROUTINE && action.component == id &&
                        FridaBridge.pendingUpdate(app) == null
                }.getOrDefault(false)
            }
            if (!allowed()) return
            val routine = RoutinePreferences(app).findRoutine(id)
            if (routine == null) {
                Toast.makeText(this, UiText.get(R.string.routine_steering_missing), Toast.LENGTH_SHORT).show()
                return
            }
            RoutineActionLaunch.request(this, RoutineActionLaunch.Receipt(routine, allowed))
        } catch (_: Exception) {
            // A malformed, stale or unreadable assignment cannot reach routine execution.
        } finally {
            finish()
        }
    }
}
