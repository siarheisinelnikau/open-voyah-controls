package org.openvoyah.controls.presentation.seatmemory

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.openvoyah.controls.R
import org.openvoyah.controls.data.frida.FridaSeatMemoryRecall
import org.openvoyah.controls.data.settings.SeatMemoryPreferences
import org.openvoyah.controls.platform.ui.FactorySeatHandoffOutcome
import org.openvoyah.controls.platform.ui.FactorySeatShortcut
import org.openvoyah.controls.platform.ui.VehicleSettingsLauncher
import org.openvoyah.controls.presentation.navigation.ControlsNavigationState
import org.openvoyah.controls.presentation.navigation.ControlsNavigationViewModel

private val FactorySeatShortcut.label: Int
    get() = when (this) {
        FactorySeatShortcut.SEATS -> R.string.seat_memory_seats_open
        FactorySeatShortcut.VEHICLE -> R.string.seat_memory_factory_open
    }

@Composable
internal fun SeatMemoryDestination(
    destination: ControlsNavigationState,
    navigation: ControlsNavigationViewModel,
    active: Boolean,
) {
    val activity = checkNotNull(LocalActivity.current as? ComponentActivity) { "Seat memory requires an activity host" }
    val app = activity.applicationContext
    val launcher = remember(app) { VehicleSettingsLauncher(app) }
    val model = remember(activity) {
        ViewModelProvider(activity, viewModelFactory {
            initializer { SeatMemoryViewModel(SeatMemoryPreferences(app), FridaSeatMemoryRecall(app)) }
        })[SeatMemoryViewModel::class.java]
    }
    val owner = LocalLifecycleOwner.current
    val lifecycle = owner.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val state by model.state.collectAsStateWithLifecycle(lifecycleOwner = owner)
    val factoryGuide = destination.driverMemoryGuide
    var factoryShortcut by remember { mutableStateOf<FactorySeatShortcut?>(null) }

    DisposableEffect(destination.seatMemoryVisit, lifecycle, model, launcher) {
        if (factoryGuide != null) model.clearInteraction()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    model.refresh()
                    factoryShortcut = runCatching { launcher.seatMemoryShortcut() }.getOrNull()
                }
                Lifecycle.Event.ON_PAUSE -> model.dismissPrompt()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            model.dismissPrompt()
        }
    }

    fun canAct() = active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        navigation.isSeatMemoryEntryCurrent(destination)

    fun openFactory(prompt: SeatMemoryPrompt.Factory, target: FactorySeatShortcut) {
        if (!active || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            !navigation.isDriverMemoryGuideCurrent(prompt)) return
        val outcome = launcher.requestSeatMemory(target, prompt.slot, prompt.action, activity::startActivity)
        navigation.finishDriverMemoryGuide(prompt, launchFailed = outcome != FactorySeatHandoffOutcome.REQUESTED)
    }

    val shortcut = factoryShortcut
    SeatMemoryScreen(
        state = state, factoryGuide = factoryGuide,
        active = active && lifecycleState.isAtLeast(Lifecycle.State.RESUMED), factoryLabel = shortcut?.label,
        back = navigation::back, refresh = model::refresh,
        action = { slot, action ->
            if (factoryGuide == null && canAct()) {
                val openOnTap = shortcut == FactorySeatShortcut.SEATS && model.state.value.settings?.openFactorySeatsOnTap == true
                val guide = model.prepare(slot, action)
                if (guide != null && navigation.showDriverMemoryGuide(destination, guide) && openOnTap) {
                    openFactory(guide, FactorySeatShortcut.SEATS)
                }
            }
        },
        setOpenFactorySeatsOnTap = { enabled ->
            if (factoryGuide == null && canAct()) model.setOpenFactorySeatsOnTap(enabled)
        },
        dismissPrompt = {
            if (canAct()) {
                if (factoryGuide != null) navigation.finishDriverMemoryGuide(factoryGuide, launchFailed = false)
                else if (model.state.value.recallPrompt === state.recallPrompt) model.dismissPrompt()
            }
        },
        confirmRecall = { prompt ->
            if (factoryGuide == null && canAct()) model.confirmRecall(prompt)
        },
        openFactory = { prompt -> if (shortcut != null) openFactory(prompt, shortcut) },
    )
}
