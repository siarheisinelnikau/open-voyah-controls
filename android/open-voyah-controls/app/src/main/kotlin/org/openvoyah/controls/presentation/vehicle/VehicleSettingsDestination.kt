package org.openvoyah.controls.presentation.vehicle

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.vehicle.HeadlightMonitoringPreferences
import org.openvoyah.controls.data.vehicle.LocalHeadlightHistory
import org.openvoyah.controls.domain.vehicle.HeadlightMonitor
import org.openvoyah.controls.platform.ui.VehicleSettingsLauncher
import org.openvoyah.controls.platform.ui.VehicleSettingsDestination as StockVehicleDestination
import org.openvoyah.controls.platform.vehicle.factoryHeadlightFeedback
import org.openvoyah.controls.platform.vehicle.automation.VehicleWorkflowRuntime

internal const val VEHICLE_SETTINGS_MODEL_KEY = "vehicle.settings.destination"

internal fun ComponentActivity.vehicleSettingsViewModel(
    launcher: VehicleSettingsLauncher = VehicleSettingsLauncher(applicationContext),
): VehicleSettingsViewModel = ViewModelProvider(this, viewModelFactory { initializer {
    VehicleSettingsViewModel(
        isAvailable = { destination -> launcher.intent(destination) != null },
        headlightMonitor = HeadlightMonitor(factoryHeadlightFeedback(applicationContext), LocalHeadlightHistory(applicationContext)),
        monitoringStore = HeadlightMonitoringPreferences(applicationContext),
        sharedHeadlightStatus = VehicleWorkflowRuntime.get(applicationContext).headlights,
    )
} })[VEHICLE_SETTINGS_MODEL_KEY, VehicleSettingsViewModel::class.java]

@Composable
internal fun VehicleSettingsDestination(back: () -> Unit, seatMemory: () -> Unit, serviceReminder: () -> Unit,
    assistance: () -> Unit, trips: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.vehicleSettingsActivity() }
    val launcher = remember(activity) { VehicleSettingsLauncher(activity.applicationContext) }
    val model = remember(activity, launcher) { activity.vehicleSettingsViewModel(launcher) }
    val owner = LocalLifecycleOwner.current
    val lifecycle = owner.lifecycle
    val state by model.state.collectAsStateWithLifecycle(lifecycleOwner = owner)
    val lighting by model.headlightStatus.collectAsStateWithLifecycle(lifecycleOwner = owner)
    val monitoring by model.headlightMonitoring.collectAsStateWithLifecycle(lifecycleOwner = owner)
    val service by rememberServiceReminderViewModel(activity).state.collectAsStateWithLifecycle(lifecycleOwner = owner)
    var showLightingSources by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                model.endFactoryLightingVisit()
                model.refresh()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            model.endFactoryLightingVisit()
        }
    }

    fun open(intent: Intent, lightingControls: Boolean = false) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (lightingControls) model.beginFactoryLightingVisit()
        try {
            activity.startActivity(intent)
            model.opened()
        } catch (_: RuntimeException) {
            if (lightingControls) model.endFactoryLightingVisit()
            model.openFailed()
        }
    }

    VehicleSettingsScreen(state.available, state.status?.let(UiText::get), lighting, back,
        open = { destination: StockVehicleDestination ->
            val intent = try { launcher.intent(destination) } catch (_: RuntimeException) { null }
            if (intent == null) model.unavailable(destination)
            else open(intent, lightingControls = destination == StockVehicleDestination.LIGHTING)
        }, manual = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.voyah-global.com/service"))) },
        seatMemory = { if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) seatMemory() },
        serviceReminder = { if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) serviceReminder() },
        serviceStatus = vehicleServiceReminder(service),
        monitoring = monitoring,
        setMonitoring = { enabled ->
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) model.setHeadlightMonitoring(enabled)
        },
        inspectLightingSources = {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) showLightingSources = true
        },
        assistance = assistance,
        trips = trips,
    )
    if (showLightingSources) {
        HeadlightSourcesPopup { showLightingSources = false }
    }
}

internal tailrec fun Context.vehicleSettingsActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.vehicleSettingsActivity()
    else -> error("Vehicle settings requires a ComponentActivity host")
}
