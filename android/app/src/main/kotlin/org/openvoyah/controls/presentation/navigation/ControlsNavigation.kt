package org.openvoyah.controls.presentation.navigation

import android.content.Context
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.openvoyah.controls.MainActivity
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.seatmemory.SeatMemoryAction
import org.openvoyah.controls.domain.seatmemory.SeatMemorySlot
import org.openvoyah.controls.domain.settings.AppPalette
import org.openvoyah.controls.presentation.seatmemory.SeatMemoryPrompt

internal enum class ControlsRoute {
    APPS, SETTINGS, BUTTONS, VOICE, DISPLAY, VEHICLE, SEAT_MEMORY, SERVICE_REMINDER, QUICK_ACTIONS, ROUTINES,
    FIRMWARE, CLUSTER, UPDATES, VEHICLE_AUTOMATION, SEASONAL_CLIMATE, CONDITIONAL_RULES, FACTORY_ASSISTANCE,
    DIAGNOSTICS, KEYBOARD, ACTION_PERMISSIONS, TEXT_OVERLAYS,
}

internal enum class RouteDirection { FORWARD, BACK, REPLACE }

/**
 * Counts the pages opened in this process, so a rebuilt host can tell a new page from one it redraws. It is left
 * out of equality on purpose: two states that show the same page are the same state whenever they arrived.
 */
internal class RouteArrival(val number: Long) {
    override fun equals(other: Any?): Boolean = other is RouteArrival
    override fun hashCode(): Int = 0
    override fun toString(): String = "RouteArrival($number)"
}

internal data class ControlsNavigationState(
    val stack: List<ControlsRoute>,
    val entry: Long,
    val direction: RouteDirection = RouteDirection.REPLACE,
    val entryDesktop: AppGridDesktop? = null,
    val temporaryPalette: AppPalette? = null,
    // A visit and its read-only guide are kept in memory for the active route.
    val driverMemoryGuide: SeatMemoryPrompt.Factory? = null,
    val seatMemoryVisit: Any? = null,
    val arrival: RouteArrival = RouteArrival(0),
) {
    val route: ControlsRoute get() = stack.last()
}

/** One normal task, with explicit entry routes for button assignments and screen navigation. */
internal object ControlsNavigation {
    const val OPEN_APPS = "org.openvoyah.controls.action.OPEN_APPS"
    const val OPEN_SETTINGS = "org.openvoyah.controls.action.OPEN_SETTINGS"
    const val START_CONTROLS = "org.openvoyah.controls.action.START_CONTROLS"
    private const val ROUTE = "org.openvoyah.controls.extra.ROUTE"
    private const val DRIVER_MEMORY_SLOT = "org.openvoyah.controls.extra.DRIVER_MEMORY_SLOT"

    fun intent(context: Context, route: ControlsRoute): Intent = Intent(context, MainActivity::class.java)
        .setAction(if (route == ControlsRoute.APPS) OPEN_APPS else OPEN_SETTINGS)
        .putExtra(ROUTE, route.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun driverMemoryIntent(context: Context, slot: SeatMemorySlot): Intent =
        intent(context, ControlsRoute.SEAT_MEMORY).putExtra(DRIVER_MEMORY_SLOT, slot.number)

    fun driverMemorySlot(intent: Intent): SeatMemorySlot? {
        if (intent.action != OPEN_SETTINGS || route(intent) != ControlsRoute.SEAT_MEMORY) return null
        return runCatching { SeatMemorySlot.forNumber(intent.getIntExtra(DRIVER_MEMORY_SLOT, 0)) }.getOrNull()
    }

    fun route(intent: Intent): ControlsRoute {
        if (intent.action == OPEN_APPS) return ControlsRoute.APPS
        if (intent.action == OPEN_SETTINGS) {
            return ControlsRoute.entries.firstOrNull { it.name == intent.getStringExtra(ROUTE) }
                ?: ControlsRoute.SETTINGS
        }
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) return ControlsRoute.SETTINGS
        return ControlsRoute.APPS
    }
}

internal class ControlsNavigationViewModel(private val saved: SavedStateHandle) : ViewModel() {
    private val current = MutableStateFlow(ControlsNavigationState(
        restore(saved.get<ArrayList<String>>("routes")), saved.get<Long>("entries") ?: 0L,
        entryDesktop = AppGridDesktop.entries.firstOrNull { it.storedValue == saved.get<String>("entryDesktop") },
        temporaryPalette = AppPalette.entries.firstOrNull { it.preferenceId == saved.get<String>("temporaryPalette") },
    ))
    val state = current.asStateFlow()
    val temporaryPalette = state.map { it.temporaryPalette }.distinctUntilChanged()

    fun enter(route: ControlsRoute, seatSlot: SeatMemorySlot? = null) {
        val previous = current.value
        if (route == ControlsRoute.SEAT_MEMORY && seatSlot != null) {
            val stack = previous.stack.filterNot { it == route } + route
            publish(ControlsNavigationState(stack, previous.entry + 1, temporaryPalette = previous.temporaryPalette,
                driverMemoryGuide = SeatMemoryPrompt.Factory(seatSlot, SeatMemoryAction.RESTORE)))
        } else {
            replace(route, null, previous.temporaryPalette)
        }
    }

    fun enterApps(desktop: AppGridDesktop?) = replace(ControlsRoute.APPS, desktop, current.value.temporaryPalette)

    fun isSeatMemoryEntryCurrent(entry: ControlsNavigationState): Boolean {
        val active = current.value
        return entry.route == ControlsRoute.SEAT_MEMORY && active.route == entry.route &&
            active.seatMemoryVisit === entry.seatMemoryVisit && active.driverMemoryGuide === entry.driverMemoryGuide
    }

    fun showDriverMemoryGuide(entry: ControlsNavigationState, guide: SeatMemoryPrompt.Factory): Boolean {
        if (!isSeatMemoryEntryCurrent(entry) || entry.driverMemoryGuide != null) return false
        current.value = current.value.copy(driverMemoryGuide = guide)
        return true
    }

    fun isDriverMemoryGuideCurrent(guide: SeatMemoryPrompt.Factory): Boolean =
        current.value.let { it.route == ControlsRoute.SEAT_MEMORY && it.driverMemoryGuide === guide }

    fun finishDriverMemoryGuide(guide: SeatMemoryPrompt.Factory, launchFailed: Boolean) {
        if (!isDriverMemoryGuideCurrent(guide)) return
        val previous = current.value
        val failed = if (launchFailed) SeatMemoryPrompt.Factory(guide.slot, guide.action, launchFailed = true) else null
        current.value = previous.copy(driverMemoryGuide = failed)
    }

    fun enterFromLauncher(route: ControlsRoute, desktop: AppGridDesktop?, palette: AppPalette?) =
        replace(route, desktop, palette)

    fun runRoutine(route: ControlsRoute?, desktop: AppGridDesktop?, palette: AppPalette?) {
        val activePalette = palette ?: current.value.temporaryPalette
        when (route) {
            null -> if (activePalette != current.value.temporaryPalette) publish(current.value.copy(temporaryPalette = activePalette))
            ControlsRoute.APPS -> replace(route, desktop, activePalette)
            else -> openRoute(route, activePalette)
        }
    }

    fun clearTemporaryPalette() {
        val previous = current.value
        if (previous.temporaryPalette != null) publish(previous.copy(temporaryPalette = null))
    }

    private fun replace(route: ControlsRoute, desktop: AppGridDesktop?, palette: AppPalette?) {
        val previous = current.value
        val stack = if (route == ControlsRoute.APPS) listOf(route) else listOf(ControlsRoute.APPS, route)
        publish(ControlsNavigationState(stack, previous.entry + 1, entryDesktop = desktop, temporaryPalette = palette))
    }

    fun open(route: ControlsRoute) = openRoute(route, current.value.temporaryPalette)

    private fun openRoute(route: ControlsRoute, palette: AppPalette?) {
        val previous = current.value
        if (route == previous.route) {
            if (palette != previous.temporaryPalette) publish(previous.copy(temporaryPalette = palette))
            return
        }
        val existing = previous.stack.indexOf(route)
        val stack = if (existing >= 0) previous.stack.take(existing + 1) else previous.stack + route
        val direction = if (existing >= 0) RouteDirection.BACK else RouteDirection.FORWARD
        publish(previous.copy(stack = stack, direction = direction, temporaryPalette = palette, driverMemoryGuide = null))
    }

    fun back() {
        val previous = current.value
        if (previous.stack.size > 1) {
            publish(previous.copy(stack = previous.stack.dropLast(1), direction = RouteDirection.BACK, driverMemoryGuide = null))
        }
    }

    private fun publish(state: ControlsNavigationState) {
        val previous = current.value
        val visit = when {
            state.route != ControlsRoute.SEAT_MEMORY -> null
            previous.route == state.route && previous.entry == state.entry -> previous.seatMemoryVisit
            else -> Any()
        }
        saved["routes"] = ArrayList(state.stack.map { it.name })
        saved["entries"] = state.entry
        saved["entryDesktop"] = state.entryDesktop?.storedValue
        saved["temporaryPalette"] = state.temporaryPalette?.preferenceId
        val opened = state.route != previous.route || state.entry != previous.entry
        current.value = state.copy(seatMemoryVisit = visit,
            arrival = if (opened) RouteArrival(previous.arrival.number + 1) else previous.arrival)
    }

    private fun restore(names: List<String>?): List<ControlsRoute> {
        val routes = names?.mapNotNull { name -> ControlsRoute.entries.firstOrNull { it.name == name } }
            ?.distinct().orEmpty()
        return if (routes.firstOrNull() == ControlsRoute.APPS) routes else listOf(ControlsRoute.APPS)
    }
}
