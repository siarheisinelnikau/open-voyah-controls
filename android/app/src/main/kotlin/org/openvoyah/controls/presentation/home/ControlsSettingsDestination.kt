package org.openvoyah.controls.presentation.home

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.openvoyah.controls.ButtonAccessibilityService
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.frida.FridaPanelControls
import org.openvoyah.controls.data.settings.SettingsStore
import org.openvoyah.controls.presentation.apps.AppGridSettingsSection
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitReveal

@Composable
internal fun ControlsSettingsDestination(
    buttons: Boolean,
    back: () -> Unit,
    open: (ControlsRoute) -> Unit,
    appearance: AppearanceViewModel,
    onPaletteSelected: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.controlsSettingsActivity() }
    val model = remember(activity) {
        val store = SettingsStore(activity.applicationContext)
        ViewModelProvider(activity, viewModelFactory { initializer {
            HomeControlsViewModel(store, FridaPanelControls(activity.applicationContext, store), Build.PRODUCT,
                Build.VERSION.SDK_INT, ButtonAccessibilityService::isConnected)
        } })["controls.settings.destination", HomeControlsViewModel::class.java]
    }
    val owner = LocalLifecycleOwner.current
    val lifecycle = owner.lifecycle
    val coordinator = remember(activity, model, owner) { HomeSettingsCoordinator(activity, model, owner) }
    val state by model.state.collectAsStateWithLifecycle(lifecycleOwner = owner)
    val lifecycleState by lifecycle.currentStateAsState()
    val updates = remember(activity) {
        ViewModelProvider(activity)[HomeUpdatesViewModel::class.java]
    }
    val update by updates.checks.collectAsStateWithLifecycle(lifecycleOwner = owner)
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        if (it != null) coordinator.exportDocument(it)
    }
    val importDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) coordinator.importDocument(it)
    }
    SideEffect { coordinator.documents({ export.launch("open-voyah-controls.json") }, { importDocument.launch(arrayOf("application/json")) }) }
    DisposableEffect(coordinator, lifecycle) {
        coordinator.attach()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> coordinator.resume()
                Lifecycle.Event.ON_PAUSE -> coordinator.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); coordinator.dispose() }
    }
    LaunchedEffect(state, lifecycleState, coordinator) { coordinator.render(state) }
    BackHandler(onBack = back)
    val candidate = update.candidate
    // The row keeps its last version while it collapses, so its text does not empty out on the way.
    var lastCandidate by remember { mutableStateOf(candidate) }
    SideEffect { if (candidate != null) lastCandidate = candidate }
    CockpitDetail(UiText.get(if (buttons) R.string.cockpit_buttons else R.string.cockpit_settings), null, back) {
        CockpitReveal(candidate != null) {
            (candidate ?: lastCandidate)?.let { shown ->
                HomeActionRow(UiText.get(R.string.updates_available_version).format(shown.version),
                    UiText.get(R.string.updates_settings_summary), CockpitIcon.DOWNLOAD,
                    { coordinator.open(ControlsRoute.UPDATES, open) })
            }
        }
        if (buttons) HomeButtons(state, coordinator)
        else {
            HomeSettingsLinks({ coordinator.open(it, open) }, coordinator::language, coordinator::systemSettings)
            val look by appearance.state.collectAsStateWithLifecycle(lifecycleOwner = owner)
            AppearanceSettings(look) { change ->
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    if (change is AppearanceChange.Palette) onPaletteSelected()
                    appearance.change(change)
                }
            }
            AppGridSettingsSection()
            HomeSettings(state, coordinator)
        }
    }
}

private tailrec fun Context.controlsSettingsActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.controlsSettingsActivity()
    else -> error("Controls settings requires a ComponentActivity host")
}
