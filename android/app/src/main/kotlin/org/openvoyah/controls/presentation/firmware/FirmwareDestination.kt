package org.openvoyah.controls.presentation.firmware

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.withStarted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.appWindowConfiguration
import org.openvoyah.controls.core.Firmware
import org.openvoyah.controls.data.settings.FirmwareMaintenance
import org.openvoyah.controls.domain.display.WindowConnection
import org.openvoyah.controls.presentation.maintenance.FirmwareAction
import org.openvoyah.controls.presentation.maintenance.FirmwareScreen
import org.openvoyah.controls.presentation.maintenance.MaintenanceViewModel
import org.openvoyah.controls.presentation.maintenance.MaintenanceWork
import org.openvoyah.controls.presentation.theme.CockpitDialogs

/** The destination observes maintenance work; leaving it closes only its confirmation dialogs. */
@Composable
internal fun FirmwareDestination(back: () -> Unit, openCluster: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.componentActivity() }
    val app = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val adapter = remember(app) { FirmwareMaintenance(app) }
    val windows = remember(app) { appWindowConfiguration(app) }
    val model = remember(activity) {
        ViewModelProvider(activity, viewModelFactory {
            initializer { MaintenanceViewModel(firmwareWork) }
        }).get("firmware-maintenance", MaintenanceViewModel::class.java)
    }
    val dialogs = remember(activity) { CockpitDialogs(activity) }
    DisposableEffect(lifecycleOwner, dialogs) {
        val observer = LifecycleEventObserver { _, _ ->
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) dialogs.dismiss()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            dialogs.dismiss()
        }
    }
    val state by model.state.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
    val lifecycle by lifecycleOwner.lifecycle.currentStateAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val windowStatus by produceState<Int?>(null, active, state) {
        if (windows.supported && active && !state.working) {
            value = when (windows.connection()) {
                WindowConnection.DISCONNECTED -> R.string.cockpit_setup_required
                WindowConnection.DISABLED -> R.string.cockpit_disabled
                WindowConnection.ENABLED -> R.string.cockpit_enabled
            }
        }
    }
    // Reading the flag parses the stored dictionary (up to 48 KB of JSON), so it stays off the main thread and is read
    // again only when an operation changes the maintenance state or the route returns to the foreground.
    val translationEnabled by produceState<Boolean?>(null, active, state) {
        value = withContext(Dispatchers.IO) { adapter.translationEnabled() }
    }
    val routeScope = rememberCoroutineScope()
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) routeScope.launch {
            lifecycleOwner.lifecycle.withStarted { model.run { adapter.importDictionary(uri) } }
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) routeScope.launch {
            lifecycleOwner.lifecycle.withStarted { model.run { adapter.exportDictionary(uri) } }
        }
    }

    fun confirm(title: Int, message: Int, operation: () -> String) {
        dialogs.message(UiText.get(title), UiText.get(message), UiText.get(R.string.ui_187), Runnable {
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.run(operation)
        }, UiText.get(R.string.ui_89))
    }

    fun select(action: FirmwareAction) {
        if (model.state.value.working || !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        try {
            when (action) {
                FirmwareAction.CLUSTER -> openCluster()
                FirmwareAction.WINDOW_SERVICE -> {
                    val enable = !adapter.windowEnabled()
                    confirm(R.string.window_hook_title, if (enable) R.string.window_hook_enable else R.string.window_hook_disable) {
                        adapter.windowEnabled(enable)
                    }
                }
                FirmwareAction.RUSSIAN -> model.run { adapter.preset("ru") }
                FirmwareAction.ENGLISH -> model.run { adapter.preset("en") }
                FirmwareAction.DISABLE_TRANSLATION -> model.run(adapter::disable)
                FirmwareAction.IMPORT -> importFile.launch(arrayOf("*/*"))
                FirmwareAction.EXPORT -> exportFile.launch("controls-localization.json")
                FirmwareAction.RESTORE_TRANSLATION -> model.run(adapter::restore)
                FirmwareAction.RESTORE_PROFILE -> confirm(R.string.profile_restore, R.string.profile_restore_info, adapter::restoreProfile)
            }
        } catch (error: RuntimeException) {
            model.report(error.message ?: UiText.get(R.string.ui_85))
        }
    }

    val product = remember { "${Build.PRODUCT} · ${Firmware.identify(Build.PRODUCT)}" }
    FirmwareScreen(product, translationEnabled, windowStatus, state, back, ::select)
}

private tailrec fun Context.componentActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.componentActivity()
    else -> error("Firmware destination requires a ComponentActivity")
}

private val firmwareWork = MaintenanceWork(null, { UiText.get(R.string.ui_80) }, {
    UiText.get(R.string.ui_205) + it.message.orEmpty()
})
