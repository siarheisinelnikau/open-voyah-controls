package org.openvoyah.controls.presentation.vehicle

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.openvoyah.controls.UiText
import org.openvoyah.controls.platform.ui.VehicleSettingsLauncher
import org.openvoyah.controls.platform.ui.VehicleSettingsDestination as StockVehicleDestination

@Composable
internal fun VehicleSettingsDestination(back: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.vehicleSettingsActivity() }
    val launcher = remember(activity) { VehicleSettingsLauncher(activity.applicationContext) }
    val model = remember(activity) {
        ViewModelProvider(activity, viewModelFactory { initializer {
            VehicleSettingsViewModel { destination -> launcher.intent(destination) != null }
        } })["vehicle.settings.destination", VehicleSettingsViewModel::class.java]
    }
    val owner = LocalLifecycleOwner.current
    val lifecycle = owner.lifecycle
    val state by model.state.collectAsStateWithLifecycle(lifecycleOwner = owner)

    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun open(intent: Intent) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        try { activity.startActivity(intent); model.opened() }
        catch (_: RuntimeException) { model.openFailed() }
    }

    VehicleSettingsScreen(state.available, state.status?.let(UiText::get), back,
        open = { destination: StockVehicleDestination ->
            val intent = try { launcher.intent(destination) } catch (_: RuntimeException) { null }
            if (intent == null) model.unavailable(destination) else open(intent)
        }, manual = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.voyah-global.com/service"))) })
}

private tailrec fun Context.vehicleSettingsActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.vehicleSettingsActivity()
    else -> error("Vehicle settings requires a ComponentActivity host")
}
