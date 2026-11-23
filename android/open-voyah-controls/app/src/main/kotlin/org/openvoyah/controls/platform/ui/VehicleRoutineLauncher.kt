package org.openvoyah.controls.platform.ui

import android.content.Context
import org.openvoyah.controls.domain.routines.RoutineTarget
import org.openvoyah.controls.presentation.navigation.ControlsNavigation
import org.openvoyah.controls.presentation.navigation.ControlsRoute

/** Activity launch boundary. Only the process-owned runtime can resolve the opaque handoff token. */
internal class VehicleRoutineLauncher(private val context: Context) {
    fun launch(target: RoutineTarget, token: String) {
        val route = when (target) {
            RoutineTarget.CURRENT, RoutineTarget.APP_GRID, RoutineTarget.FAVORITES_ONE, RoutineTarget.FAVORITES_TWO -> ControlsRoute.APPS
            RoutineTarget.SETTINGS -> ControlsRoute.SETTINGS
            RoutineTarget.BUTTONS -> ControlsRoute.BUTTONS
            RoutineTarget.VOICE -> ControlsRoute.VOICE
            RoutineTarget.QUICK_ACTIONS -> ControlsRoute.QUICK_ACTIONS
        }
        context.startActivity(ControlsNavigation.intent(context, route).putExtra(HANDOFF, token))
    }

    companion object { const val HANDOFF = "org.openvoyah.controls.extra.VEHICLE_RULE_HANDOFF" }
}
