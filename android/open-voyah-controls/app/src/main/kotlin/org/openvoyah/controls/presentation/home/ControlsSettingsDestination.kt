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
    focusRequest: Long,
    back: () -> Unit,
    open: (ControlsRoute) -> Unit,
    openPart: (ControlsRoute, String?) -> Unit,
    appearance: AppearanceViewModel,
    onPaletteSelected: () -> Unit,
    vehicleReport: () -> Unit,
    /** Draws a page of its own inside this section (see ControlsHost); a section has no pages below it. */
    embed: @Composable (ControlsRoute) -> Unit = {},
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
            settingsPart(SettingsPartId.HOME_GRID) { AppGridSettingsSection(AppGridPart.GRID) },
            settingsPart(SettingsPartId.HOME_DESKTOPS) {
                AppGridSettingsSection(AppGridPart.DESKTOPS)
                AppearanceSettings(look, AppearancePart.WALLPAPER, changeLook)
            },
            settingsPart(SettingsPartId.HOME_CARDS) { AppGridSettingsSection(AppGridPart.CARDS) },
            settingsPart(SettingsPartId.HOME_FACTORY) { HomeFactoryScreen(state, coordinator) },
        )
        SettingsSection.WINDOWS -> listOf(
            settingsPart(SettingsPartId.WINDOWS_APPS) {
                FirmwareSection(FirmwarePart.WINDOWS)
                CockpitSection(UiText.get(R.string.settings_app_scale_title), CockpitIcon.DISPLAY) {
                    WindowSettingsControls()
                    Text(UiText.get(R.string.settings_app_scale_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            settingsPart(SettingsPartId.WINDOWS_DISPLAYS) { embed(ControlsRoute.DISPLAY) },
        )
        SettingsSection.BUTTONS -> listOf(
            settingsPart(SettingsPartId.BUTTONS_PANEL) { HomePanel(state, coordinator) },
            settingsPart(SettingsPartId.BUTTONS_HARDWARE) { HomeHardwareButtons(state, coordinator) },
            settingsPart(SettingsPartId.BUTTONS_STEERING) { HomeSteering(state, coordinator) },
            settingsPart(SettingsPartId.BUTTONS_DOCK) { embed(ControlsRoute.QUICK_ACTIONS) },
        )
        SettingsSection.SOUND -> listOf(
            settingsPart(SettingsPartId.SOUND_STARTUP) {
                StartupVolumeSettings()
                AppearanceSettings(look, AppearancePart.LAUNCH_VOLUME, changeLook)
            },
            settingsPart(SettingsPartId.SOUND_DUCKING) { DuckingSettings() },
            settingsPart(SettingsPartId.SOUND_MEDIA) {
                // The multimedia restart lives beside the system reboot; this is only a way there.
                MediaAccessSettings {
                    CockpitChoice(UiText.get(R.string.system_restart_media), UiText.get(R.string.settings_media_restart_link)) {
                        coordinator.open(ControlsRoute.SYSTEM) { openPart(it, SettingsPartId.SYSTEM_ABOUT.name) }
                    }
                }
            },
        )
        SettingsSection.APPEARANCE -> listOf(
            settingsPart(SettingsPartId.APPEARANCE_THEME) { AppearanceSettings(look, AppearancePart.THEME, changeLook) },
            settingsPart(SettingsPartId.APPEARANCE_TEXT) { AppearanceSettings(look, AppearancePart.TEXT, changeLook) },
            settingsPart(SettingsPartId.APPEARANCE_SHAPE) { AppearanceSettings(look, AppearancePart.SHAPE, changeLook) },
            settingsPart(SettingsPartId.APPEARANCE_MOTION) { AppearanceSettings(look, AppearancePart.MOTION, changeLook) },
        )
        SettingsSection.LANGUAGE -> listOf(
            settingsPart(SettingsPartId.LANGUAGE_APP) {
                CockpitSection(UiText.get(R.string.settings_part_app_language), CockpitIcon.LANGUAGE) {
                    CockpitChoice(R.string.cockpit_language,
                        UiText.get(UiText.languageName(UiText.language())),
                        onClick = coordinator::language)
                }
                embed(ControlsRoute.KEYBOARD)
            },
            settingsPart(SettingsPartId.LANGUAGE_FACTORY) {
                embed(ControlsRoute.TEXT_OVERLAYS)
                FirmwareSection(FirmwarePart.TRANSLATION)
                embed(ControlsRoute.CLUSTER)
            },
        )
        SettingsSection.SYSTEM -> listOf(
            settingsPart(SettingsPartId.SYSTEM_UPDATES) {
                embed(ControlsRoute.UPDATES)
                CockpitSection(UiText.get(R.string.settings_android_title), CockpitIcon.SYSTEM) {
                    CockpitChoice(UiText.get(R.string.ui_61), UiText.get(R.string.cockpit_system_description),
                        onClick = coordinator::systemSettings)
                }
            },
            settingsPart(SettingsPartId.SYSTEM_ABOUT) { SystemMaintenanceSection() },
            settingsPart(SettingsPartId.SYSTEM_COMPANIONS) {
                CockpitSection(UiText.get(R.string.companion_apps_title), CockpitIcon.APPS,
                    subtitle = UiText.get(R.string.companion_apps_github)) {
                    CockpitChoice(R.string.companion_youtube, UiText.get(R.string.companion_youtube_summary)) {
                        coordinator.openCompanionPage("https://github.com/siarheisinelnikau/youtube-web")
                    }
                    CockpitChoice(R.string.companion_music, UiText.get(R.string.companion_music_summary)) {
                        coordinator.openCompanionPage("https://github.com/siarheisinelnikau/voyah-music")
                    }
                    CockpitChoice(R.string.companion_radio, UiText.get(R.string.companion_radio_summary)) {
                        coordinator.openCompanionPage("https://github.com/siarheisinelnikau/voyah-radio")
                    }
                }
            },
            settingsPart(SettingsPartId.SYSTEM_BACKUP) { HomeBackup(coordinator) },
            settingsPart(SettingsPartId.SYSTEM_START) { AppearanceSettings(look, AppearancePart.START, changeLook) },
            settingsPart(SettingsPartId.SYSTEM_CONNECTION) { HomeConnection(state, coordinator) },
            settingsPart(SettingsPartId.SYSTEM_DIAGNOSTICS) {
                embed(ControlsRoute.DIAGNOSTICS)
                CockpitSection(UiText.get(R.string.vehicle_automation_diagnostics), CockpitIcon.CAR) {
                    CockpitChoice(UiText.get(R.string.vehicle_automation_diagnostics),
                        UiText.get(R.string.vehicle_automation_diagnostics_hint)) {
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) vehicleReport()
                    }
                }
            },
            settingsPart(SettingsPartId.SYSTEM_FACTORY) { FirmwareSection(FirmwarePart.PROFILE) },
            settingsPart(SettingsPartId.SYSTEM_PERMISSIONS) { embed(ControlsRoute.ACTION_PERMISSIONS) },
        )
        SettingsSection.VEHICLE -> listOf(
            settingsPart(SettingsPartId.VEHICLE_MAIN) { embed(ControlsRoute.VEHICLE) },
            settingsPart(SettingsPartId.VEHICLE_SERVICE) { embed(ControlsRoute.SERVICE_REMINDER) },
            settingsPart(SettingsPartId.VEHICLE_ASSISTANCE) { embed(ControlsRoute.FACTORY_ASSISTANCE) },
        )
        SettingsSection.AUTOMATION -> listOf(
            settingsPart(SettingsPartId.AUTOMATION_VEHICLE) { embed(ControlsRoute.VEHICLE_AUTOMATION) },
            settingsPart(SettingsPartId.AUTOMATION_ROUTINES) { embed(ControlsRoute.ROUTINES) },
            settingsPart(SettingsPartId.AUTOMATION_RULES) { embed(ControlsRoute.CONDITIONAL_RULES) },
            settingsPart(SettingsPartId.AUTOMATION_CLIMATE) { embed(ControlsRoute.SEASONAL_CLIMATE) },
        )
        // Voice is a page of its own and never reaches this destination.
        SettingsSection.VOICE -> emptyList()
    }
    SettingsSectionPage(UiText.get(section.title), UiText.get(section.summary), back, parts, focus, focusRequest) { key ->
        VisibleSettingsPart.show(route, key)
    }
}

private tailrec fun Context.controlsSettingsActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.controlsSettingsActivity()
    else -> error("Controls settings requires a ComponentActivity host")
}
