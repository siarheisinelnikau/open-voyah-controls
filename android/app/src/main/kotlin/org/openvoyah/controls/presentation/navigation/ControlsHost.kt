package org.openvoyah.controls.presentation.navigation

import org.openvoyah.controls.presentation.overlays.TextOverlaysDestination
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openvoyah.controls.domain.dock.QuickActionSettings
import org.openvoyah.controls.domain.routines.NamedRoutine
import org.openvoyah.controls.domain.settings.RouteTransitionSpeed
import org.openvoyah.controls.presentation.apps.AppsDestination
import org.openvoyah.controls.presentation.cluster.ClusterDestination
import org.openvoyah.controls.presentation.diagnostics.DiagnosticsDestination
import org.openvoyah.controls.presentation.climate.SeasonalClimateDestination
import org.openvoyah.controls.presentation.display.DisplayDestination
import org.openvoyah.controls.presentation.dock.QuickActionsDestination
import org.openvoyah.controls.presentation.dock.QuickActionsHost
import org.openvoyah.controls.presentation.dock.quickActionsModel
import org.openvoyah.controls.presentation.firmware.FirmwareDestination
import org.openvoyah.controls.presentation.home.AppearanceViewModel
import org.openvoyah.controls.presentation.home.ControlsSettingsDestination
import org.openvoyah.controls.presentation.permissions.ActionPermissionsDestination
import org.openvoyah.controls.presentation.keyboard.KeyboardDestination
import org.openvoyah.controls.presentation.routines.RoutinesDestination
import org.openvoyah.controls.presentation.rules.ConditionalRulesDestination
import org.openvoyah.controls.presentation.seatmemory.SeatMemoryDestination
import org.openvoyah.controls.presentation.theme.CockpitMotion
import org.openvoyah.controls.presentation.theme.LocalCockpitEntrance
import org.openvoyah.controls.presentation.updates.UpdatesDestination
import org.openvoyah.controls.presentation.vehicle.KeepHeadlightsObserved
import org.openvoyah.controls.presentation.vehicle.ServiceReminderDestination
import org.openvoyah.controls.presentation.vehicle.VehicleSettingsDestination
import org.openvoyah.controls.presentation.vehicle.FactoryAssistanceDestination
import org.openvoyah.controls.presentation.vehicle.vehicleSettingsViewModel
import org.openvoyah.controls.platform.ui.VehicleSettingsLauncher
import org.openvoyah.controls.platform.ui.VehicleSettingsDestination as FactoryVehicleDestination
import org.openvoyah.controls.presentation.vehicle.automation.ObserveVehicleRoutines
import org.openvoyah.controls.presentation.vehicle.automation.VehicleAutomationDestination
import org.openvoyah.controls.presentation.vehicle.automation.VehicleDiagnosticsPopup
import org.openvoyah.controls.presentation.vehicle.automation.seasonalClimateModel
import org.openvoyah.controls.presentation.vehicle.automation.vehicleAutomationModel
import org.openvoyah.controls.presentation.voice.VoiceDestination

@Composable
internal fun ControlsHost(
    navigation: ControlsNavigationViewModel,
    appearance: AppearanceViewModel,
    listen: () -> Unit,
    runRoutine: suspend (NamedRoutine, ControlsNavigationState) -> Int?,
    runRoutineShortcut: (NamedRoutine, ControlsNavigationState) -> Boolean,
    runConditionalRoutine: suspend (NamedRoutine, ControlsNavigationState, () -> Boolean) -> Int?,
    restored: Boolean = false,
) {
    val state by navigation.state.collectAsStateWithLifecycle()
    // A host rebuilt after a language or theme change redraws the page it already showed, without an arrival.
    val shownBefore = remember { if (restored) navigation.state.value.arrival.number else -1L }
    val look by appearance.state.collectAsStateWithLifecycle()
    val dock = quickActionsModel()
    val dockState by dock.state.collectAsStateWithLifecycle()
    val screens = rememberSaveableStateHolder()
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val mirror = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val enterOffset = with(density) { 20.dp.roundToPx() } * mirror
    val exitOffset = with(density) { 9.dp.roundToPx() } * mirror

    LaunchedEffect(state.route) { focus.clearFocus(force = true) }
    BackHandler(state.stack.size > 1, onBack = navigation::back)
    val activity = checkNotNull(LocalActivity.current as? ComponentActivity)
    KeepHeadlightsObserved(activity)
    ObserveVehicleRoutines(activity, state.route, { navigation.state.value }, runConditionalRoutine)
    var showVehicleDiagnostics by remember { mutableStateOf(false) }
    val factory = remember(activity) { VehicleSettingsLauncher(activity.applicationContext) }
    fun openFactory(destination: FactoryVehicleDestination) {
        val intent = runCatching { factory.intent(destination) }.getOrNull() ?: return
        val lamps = activity.vehicleSettingsViewModel()
        if (destination == FactoryVehicleDestination.LIGHTING) lamps.beginFactoryLightingVisit()
        try { activity.startActivity(intent) }
        catch (_: RuntimeException) { lamps.endFactoryLightingVisit(); lamps.openFailed() }
    }
    val dockEntry = state
    QuickActionsHost(dockState.settings ?: QuickActionSettings(), dockEntry.route, navigation::open, navigation::enterApps, dock,
        dockState.routineShortcut, runRoutineShortcut = { routine ->
            dock.state.value.routineShortcut == routine && runRoutineShortcut(routine, dockEntry)
        }) {
        AnimatedContent(
            targetState = state,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).clipToBounds(),
            contentKey = { it.route },
            transitionSpec = {
                routeTransition(initialState.route, targetState, enterOffset, exitOffset, look.settings?.animateRoutes != false,
                    look.settings?.transitionSpeed ?: RouteTransitionSpeed.STANDARD)
            },
            label = "controls destination",
        ) { destination ->
            val route = destination.route
            // The virtual display hosts a SurfaceView, which a draw-layer fade cannot move on Android 9 and 11.
            val arrives = destination.direction != RouteDirection.BACK && destination.arrival.number > shownBefore &&
                route != ControlsRoute.DISPLAY
            DestinationFrame(active = route == state.route, arrives = arrives) {
                screens.SaveableStateProvider(route.name) {
                    when (route) {
                        ControlsRoute.APPS -> AppsDestination(destination.entry, destination.entryDesktop, navigation::open,
                            listen, animateDesktopPaging = look.settings?.animateRoutes == true)
                        ControlsRoute.SETTINGS, ControlsRoute.BUTTONS -> ControlsSettingsDestination(
                            route == ControlsRoute.BUTTONS, navigation::back, navigation::open, appearance, navigation::clearTemporaryPalette,
                        )
                        ControlsRoute.VOICE -> VoiceDestination(navigation::back, listen)
                        ControlsRoute.DISPLAY -> DisplayDestination(navigation::back)
                        ControlsRoute.VEHICLE -> VehicleSettingsDestination(navigation::back,
                            seatMemory = { navigation.open(ControlsRoute.SEAT_MEMORY) },
                            serviceReminder = { navigation.open(ControlsRoute.SERVICE_REMINDER) },
                            automation = { navigation.open(ControlsRoute.VEHICLE_AUTOMATION) },
                            assistance = { navigation.open(ControlsRoute.FACTORY_ASSISTANCE) },
                        )
                        ControlsRoute.VEHICLE_AUTOMATION -> VehicleAutomationDestination(activity.vehicleAutomationModel(), navigation::back,
                            climate = { navigation.open(ControlsRoute.SEASONAL_CLIMATE) },
                            rules = { navigation.open(ControlsRoute.CONDITIONAL_RULES) },
                            apollo = { navigation.open(ControlsRoute.FACTORY_ASSISTANCE) },
                            factoryLighting = factory.intent(FactoryVehicleDestination.LIGHTING)?.let { { openFactory(FactoryVehicleDestination.LIGHTING) } },
                            factoryDriving = factory.intent(FactoryVehicleDestination.VEHICLE)?.let { { openFactory(FactoryVehicleDestination.VEHICLE) } },
                            diagnostics = { showVehicleDiagnostics = true },
                        )
                        ControlsRoute.SEASONAL_CLIMATE -> SeasonalClimateDestination(activity.seasonalClimateModel(), navigation::back)
                        ControlsRoute.CONDITIONAL_RULES -> ConditionalRulesDestination(navigation::back,
                            editRoutines = { navigation.open(ControlsRoute.ROUTINES) })
                        ControlsRoute.FACTORY_ASSISTANCE -> FactoryAssistanceDestination(navigation::back) { showVehicleDiagnostics = true }
                        ControlsRoute.SEAT_MEMORY -> SeatMemoryDestination(destination, navigation, active = destination === state)
                        ControlsRoute.SERVICE_REMINDER -> ServiceReminderDestination(navigation::back)
                        ControlsRoute.QUICK_ACTIONS -> QuickActionsDestination(dock, dockState, navigation::back)
                        ControlsRoute.ROUTINES -> RoutinesDestination(navigation::back) { routine -> runRoutine(routine, destination) }
                        ControlsRoute.FIRMWARE -> FirmwareDestination(navigation::back, { navigation.open(ControlsRoute.CLUSTER) }) {
                            navigation.open(ControlsRoute.TEXT_OVERLAYS)
                        }
                        ControlsRoute.CLUSTER -> ClusterDestination(navigation::back)
                        ControlsRoute.UPDATES -> UpdatesDestination(navigation::back)
                        ControlsRoute.DIAGNOSTICS -> DiagnosticsDestination(navigation::back)
                        ControlsRoute.KEYBOARD -> KeyboardDestination(navigation::back)
                        ControlsRoute.ACTION_PERMISSIONS -> ActionPermissionsDestination(navigation::back)
                        ControlsRoute.TEXT_OVERLAYS -> TextOverlaysDestination(navigation::back)
                    }
                }
            }
        }
    }
    if (showVehicleDiagnostics) VehicleDiagnosticsPopup(activity) { showVehicleDiagnostics = false }
}

private fun routeTransition(
    from: ControlsRoute,
    to: ControlsNavigationState,
    enterOffset: Int,
    exitOffset: Int,
    animate: Boolean,
    speed: RouteTransitionSpeed,
): ContentTransform {
    // Android handles task entry. SurfaceView also needs an immediate Compose transition.
    if (!animate || to.direction == RouteDirection.REPLACE || from == to.route ||
        from == ControlsRoute.DISPLAY || to.route == ControlsRoute.DISPLAY
    ) {
        return ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
    }
    val direction = if (to.direction == RouteDirection.FORWARD) 1 else -1
    val durations = CockpitMotion.routeDurations(speed)
    val fadeIn = fadeIn(tween(durations.enterMillis, easing = LinearOutSlowInEasing))
    val fadeOut = fadeOut(tween(durations.exitMillis, easing = FastOutLinearInEasing))

    return ContentTransform(
        fadeIn + slideInHorizontally(tween(durations.enterMillis, easing = LinearOutSlowInEasing)) { enterOffset * direction },
        fadeOut + slideOutHorizontally(tween(durations.exitMillis, easing = FastOutLinearInEasing)) { -exitOffset * direction },
        sizeTransform = null,
    )
}

/** [arrives] is true only for a newly opened page; a page the driver returns to appears without its cascade. */
@Composable
private fun DestinationFrame(active: Boolean, arrives: Boolean, content: @Composable () -> Unit) {
    val acceptsInput by rememberUpdatedState(active)
    val semantics = if (active) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() }
    // Keep the pointer filter in the hit path to cancel touches that started before the route changed.
    Box(Modifier.fillMaxSize().then(semantics).onPreviewKeyEvent { !acceptsInput }.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (!acceptsInput) event.changes.forEach { it.consume() }
            }
        }
    }) {
        CompositionLocalProvider(LocalCockpitEntrance provides arrives) { DestinationLifecycle(active, content) }
    }
}
