package org.openvoyah.controls.presentation.vehicle.automation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.openvoyah.controls.domain.routines.NamedRoutine
import org.openvoyah.controls.platform.vehicle.automation.VehicleWorkflowRuntime
import org.openvoyah.controls.presentation.navigation.ControlsNavigationState
import org.openvoyah.controls.presentation.navigation.ControlsRoute

/** UI adds a lease and a navigation handler; automatic work belongs to the shared runtime. */
@Composable
internal fun ObserveVehicleRoutines(
    activity: ComponentActivity,
    route: ControlsRoute,
    entry: () -> ControlsNavigationState,
    runRoutine: suspend (NamedRoutine, ControlsNavigationState, () -> Boolean) -> Int?,
) {
    val run = rememberUpdatedState(runRoutine)
    val currentEntry = rememberUpdatedState(entry)
    val currentRoute = rememberUpdatedState(route)
    LaunchedEffect(activity) {
        val runtime = VehicleWorkflowRuntime.get(activity.applicationContext)
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val owner = Any()
            launch {
                snapshotFlow { currentRoute.value }.collect { current ->
                    runtime.foregroundStarted(owner, current == ControlsRoute.VEHICLE_AUTOMATION) { decision ->
                        run.value(decision.routine, currentEntry.value()) { runtime.staysCurrent(decision) }
                    }
                }
            }
            try { awaitCancellation() } finally { runtime.foregroundStopped(owner) }
        }
    }
}
