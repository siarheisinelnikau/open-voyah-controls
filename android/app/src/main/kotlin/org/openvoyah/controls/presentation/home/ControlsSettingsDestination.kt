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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import org.openvoyah.controls.presentation.apps.AppGridPart
import org.openvoyah.controls.presentation.display.WindowSettingsControls
import org.openvoyah.controls.presentation.firmware.FirmwareSection
import org.openvoyah.controls.presentation.maintenance.FirmwarePart
import org.openvoyah.controls.presentation.settings.DuckingSettings
import org.openvoyah.controls.presentation.settings.MediaAccessSettings
import org.openvoyah.controls.presentation.settings.SettingsHub
import org.openvoyah.controls.presentation.settings.SettingsPartId
import org.openvoyah.controls.presentation.settings.SettingsSection
import org.openvoyah.controls.presentation.settings.SettingsSectionPage
import org.openvoyah.controls.presentation.settings.StartupVolumeSettings
import org.openvoyah.controls.presentation.settings.VisibleSettingsPart
import org.openvoyah.controls.presentation.settings.settingsPart
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitReveal

/**
 * Settings and its sections built from parts ([ControlsRoute.SETTINGS], [ControlsRoute.BUTTONS] and the section
 * routes). One model and coordinator serve them all, so the bridge dialogs and notices behave the same on every page.
 */
@Composable
internal fun ControlsSettingsDestination(
    route: ControlsRoute,
    focus: String?,
    back: () -> Unit,
    open: (ControlsRoute) -> Unit,
    openPart: (ControlsRoute, String?) -> Unit,
    appearance: AppearanceViewModel,
    onPaletteSelected: () -> Unit,
    vehicleReport: () -> Unit,
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
    val look by appearance.state.collectAsStateWithLifecycle(lifecycleOwner = owner)
    val changeLook: (AppearanceChange) -> Unit = { change ->
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            if (change is AppearanceChange.Palette) onPaletteSelected()
            appearance.change(change)
        }
    }
    val go: (ControlsRoute) -> Unit = { coordinator.open(it, open) }
    val updateRow: @Composable () -> Unit = {
        CockpitReveal(candidate != null) {
            (candidate ?: lastCandidate)?.let { shown ->
                HomeActionRow(UiText.get(R.string.updates_available_version).format(shown.version),
                    UiText.get(R.string.updates_settings_summary), CockpitIcon.DOWNLOAD, { go(ControlsRoute.UPDATES) })
            }
        }
    }
    val section = SettingsSection.of(route)
    if (section == null) {
        CockpitDetail(UiText.get(R.string.cockpit_settings), null, back) {
            updateRow()
            SettingsHub { target, part -> coordinator.open(target) { openPart(it, part) } }
        }
        return
    }
    val parts = when (section) {
        SettingsSection.HOME_SCREEN -> listOf(
            settingsPart(SettingsPartId.HOME_GRID, 11) { AppGridSettingsSection(AppGridPart.GRID) },
            settingsPart(SettingsPartId.HOME_DESKTOPS, 9) {
                AppGridSettingsSection(AppGridPart.DESKTOPS)
                AppearanceSettings(look, AppearancePart.WALLPAPER, changeLook)
            },
            settingsPart(SettingsPartId.HOME_CARDS, 4) { AppGridSettingsSection(AppGridPart.CARDS) },
            settingsPart(SettingsPartId.HOME_FACTORY, 4) { HomeFactoryScreen(state, coordinator) },
        )
        SettingsSection.WINDOWS -> listOf(
            settingsPart(SettingsPartId.WINDOWS_APPS, 6) {
                FirmwareSection(FirmwarePart.WINDOWS)
                CockpitSection(UiText.get(R.string.settings_app_scale_title), CockpitIcon.DISPLAY) {
                    WindowSettingsControls()
                    Text(UiText.get(R.string.settings_app_scale_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            settingsPart(SettingsPartId.WINDOWS_DISPLAYS, 2) {
                CockpitSection(UiText.get(R.string.settings_part_displays), CockpitIcon.DISPLAY) {
                    CockpitChoice(UiText.get(R.string.virtual_display), UiText.get(R.string.settings_virtual_display_summary)) {
                        go(ControlsRoute.DISPLAY)
                    }
                }
            },
        )
        SettingsSection.BUTTONS -> listOf(
            settingsPart(SettingsPartId.BUTTONS_PANEL, 6) { HomePanel(state, coordinator) },
            settingsPart(SettingsPartId.BUTTONS_HARDWARE, 10) { HomeHardwareButtons(state, coordinator) },
            settingsPart(SettingsPartId.BUTTONS_STEERING, 2) { HomeSteering(state, coordinator) },
            settingsPart(SettingsPartId.BUTTONS_DOCK, 2) {
                CockpitSection(UiText.get(R.string.settings_part_dock), CockpitIcon.APPS) {
                    CockpitChoice(UiText.get(R.string.quick_actions_title), UiText.get(R.string.quick_actions_summary)) {
                        go(ControlsRoute.QUICK_ACTIONS)
                    }
                }
            },
        )
        SettingsSection.SOUND -> listOf(
            settingsPart(SettingsPartId.SOUND_STARTUP, 5) {
                StartupVolumeSettings()
                AppearanceSettings(look, AppearancePart.LAUNCH_VOLUME, changeLook)
            },
            settingsPart(SettingsPartId.SOUND_DUCKING, 4) { DuckingSettings() },
            settingsPart(SettingsPartId.SOUND_MEDIA, 4) {
                MediaAccessSettings()
                SystemMaintenanceSection(SystemMaintenancePart.MEDIA)
            },
        )
        SettingsSection.APPEARANCE -> listOf(
            settingsPart(SettingsPartId.APPEARANCE_THEME, 4) { AppearanceSettings(look, AppearancePart.THEME, changeLook) },
            settingsPart(SettingsPartId.APPEARANCE_TEXT, 5) { AppearanceSettings(look, AppearancePart.TEXT, changeLook) },
            settingsPart(SettingsPartId.APPEARANCE_SHAPE, 2) { AppearanceSettings(look, AppearancePart.SHAPE, changeLook) },
            settingsPart(SettingsPartId.APPEARANCE_MOTION, 2) { AppearanceSettings(look, AppearancePart.MOTION, changeLook) },
        )
        SettingsSection.LANGUAGE -> listOf(
            settingsPart(SettingsPartId.LANGUAGE_APP, 3) {
                CockpitSection(UiText.get(R.string.settings_part_app_language), CockpitIcon.LANGUAGE) {
                    CockpitChoice(UiText.get(R.string.cockpit_language),
                        UiText.get(if (UiText.language() == "ru") R.string.language_name_russian else R.string.language_name_english),
                        onClick = coordinator::language)
                    CockpitChoice(UiText.get(R.string.keyboard_title), UiText.get(R.string.keyboard_page_summary)) {
                        go(ControlsRoute.KEYBOARD)
                    }
                }
            },
            settingsPart(SettingsPartId.LANGUAGE_FACTORY, 9) {
                CockpitSection(UiText.get(R.string.settings_part_factory_language), CockpitIcon.FIRMWARE) {
                    CockpitChoice(UiText.get(R.string.text_overlays_title), UiText.get(R.string.text_overlays_row)) {
                        go(ControlsRoute.TEXT_OVERLAYS)
                    }
                    CockpitChoice(UiText.get(R.string.cluster_title), UiText.get(R.string.settings_cluster_summary)) {
                        go(ControlsRoute.CLUSTER)
                    }
                }
                FirmwareSection(FirmwarePart.TRANSLATION)
            },
        )
        SettingsSection.SYSTEM -> listOf(
            settingsPart(SettingsPartId.SYSTEM_UPDATES, 4) {
                updateRow()
                CockpitSection(UiText.get(R.string.settings_part_service), CockpitIcon.DOWNLOAD) {
                    CockpitChoice(UiText.get(R.string.updates_title), UiText.get(R.string.updates_settings_summary)) {
                        go(ControlsRoute.UPDATES)
                    }
                    CockpitChoice(UiText.get(R.string.action_permissions_title), UiText.get(R.string.action_permissions_summary)) {
                        go(ControlsRoute.ACTION_PERMISSIONS)
                    }
                    CockpitChoice(UiText.get(R.string.ui_61), UiText.get(R.string.cockpit_system_description),
                        onClick = coordinator::systemSettings)
                }
            },
            settingsPart(SettingsPartId.SYSTEM_ABOUT, 9) { SystemMaintenanceSection(SystemMaintenancePart.ABOUT) },
            settingsPart(SettingsPartId.SYSTEM_BACKUP, 2) { HomeBackup(coordinator) },
            settingsPart(SettingsPartId.SYSTEM_START, 2) { AppearanceSettings(look, AppearancePart.START, changeLook) },
            settingsPart(SettingsPartId.SYSTEM_CONNECTION, 6) { HomeConnection(state, coordinator) },
            settingsPart(SettingsPartId.SYSTEM_DIAGNOSTICS, 3) {
                CockpitSection(UiText.get(R.string.settings_part_diagnostics), CockpitIcon.SEARCH) {
                    CockpitChoice(UiText.get(R.string.diagnostics_title), UiText.get(R.string.diagnostics_link_summary)) {
                        go(ControlsRoute.DIAGNOSTICS)
                    }
                    CockpitChoice(UiText.get(R.string.vehicle_automation_diagnostics),
                        UiText.get(R.string.vehicle_automation_diagnostics_hint)) {
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) vehicleReport()
                    }
                }
            },
            settingsPart(SettingsPartId.SYSTEM_FACTORY, 2) { FirmwareSection(FirmwarePart.PROFILE) },
        )
        // These sections are pages of their own and never reach this destination.
        SettingsSection.VOICE, SettingsSection.VEHICLE, SettingsSection.AUTOMATION -> emptyList()
    }
    SettingsSectionPage(UiText.get(section.title), UiText.get(section.summary), back, parts, focus) { key ->
        VisibleSettingsPart.show(route, key)
    }
}

private tailrec fun Context.controlsSettingsActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.controlsSettingsActivity()
    else -> error("Controls settings requires a ComponentActivity host")
}
