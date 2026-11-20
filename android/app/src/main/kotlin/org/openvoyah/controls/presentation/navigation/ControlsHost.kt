package org.openvoyah.controls.presentation.navigation

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import org.openvoyah.controls.presentation.display.DisplayDestination
import org.openvoyah.controls.presentation.dock.QuickActionsDestination
import org.openvoyah.controls.presentation.dock.QuickActionsHost
import org.openvoyah.controls.presentation.dock.quickActionsModel
import org.openvoyah.controls.presentation.firmware.FirmwareDestination
import org.openvoyah.controls.presentation.home.AppearanceViewModel
import org.openvoyah.controls.presentation.home.ControlsSettingsDestination
import org.openvoyah.controls.presentation.routines.RoutinesDestination
import org.openvoyah.controls.presentation.seatmemory.SeatMemoryDestination
import org.openvoyah.controls.presentation.theme.CockpitMotion
import org.openvoyah.controls.presentation.updates.UpdatesDestination
import org.openvoyah.controls.presentation.vehicle.KeepHeadlightsObserved
import org.openvoyah.controls.presentation.vehicle.ServiceReminderDestination
import org.openvoyah.controls.presentation.vehicle.VehicleSettingsDestination
import org.openvoyah.controls.presentation.voice.VoiceDestination

@Composable
internal fun ControlsHost(
    navigation: ControlsNavigationViewModel,
    appearance: AppearanceViewModel,
    listen: () -> Unit,
    systemSettings: () -> Unit,
    runRoutine: suspend (NamedRoutine, ControlsNavigationState) -> Int?,
    runRoutineShortcut: (NamedRoutine, ControlsNavigationState) -> Boolean,
) {
    val state by navigation.state.collectAsStateWithLifecycle()
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
    (LocalActivity.current as? ComponentActivity)?.let { KeepHeadlightsObserved(it) }
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
            DestinationFrame(route == state.route) {
                screens.SaveableStateProvider(route.name) {
                    when (route) {
                        ControlsRoute.APPS -> AppsDestination(destination.entry, destination.entryDesktop, navigation::open,
                            listen, systemSettings, animateDesktopPaging = look.settings?.animateRoutes == true)
                        ControlsRoute.SETTINGS, ControlsRoute.BUTTONS -> ControlsSettingsDestination(
                            route == ControlsRoute.BUTTONS, navigation::back, navigation::open, appearance, navigation::clearTemporaryPalette,
                        )
                        ControlsRoute.VOICE -> VoiceDestination(navigation::back, listen)
                        ControlsRoute.DISPLAY -> DisplayDestination(navigation::back)
                        ControlsRoute.VEHICLE -> VehicleSettingsDestination(navigation::back,
                            seatMemory = { navigation.open(ControlsRoute.SEAT_MEMORY) },
                            serviceReminder = { navigation.open(ControlsRoute.SERVICE_REMINDER) },
                        )
                        ControlsRoute.SEAT_MEMORY -> SeatMemoryDestination(destination, navigation, active = destination === state)
                        ControlsRoute.SERVICE_REMINDER -> ServiceReminderDestination(navigation::back)
                        ControlsRoute.QUICK_ACTIONS -> QuickActionsDestination(dock, dockState, navigation::back)
                        ControlsRoute.ROUTINES -> RoutinesDestination(navigation::back) { routine -> runRoutine(routine, destination) }
                        ControlsRoute.FIRMWARE -> FirmwareDestination(navigation::back) { navigation.open(ControlsRoute.CLUSTER) }
                        ControlsRoute.CLUSTER -> ClusterDestination(navigation::back)
                        ControlsRoute.UPDATES -> UpdatesDestination(navigation::back)
                    }
                }
            }
        }
    }
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

@Composable
private fun DestinationFrame(active: Boolean, content: @Composable () -> Unit) {
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
        DestinationLifecycle(active, content)
    }
}
