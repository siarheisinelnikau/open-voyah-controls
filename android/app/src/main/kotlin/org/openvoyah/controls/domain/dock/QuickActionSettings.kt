package org.openvoyah.controls.domain.dock

import kotlinx.coroutines.flow.Flow
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.apps.AppGridPins
import org.openvoyah.controls.domain.routines.NamedRoutine

internal const val MAX_DOCK_APP_SHORTCUTS = 4

internal enum class QuickAction(val id: String) {
    APPS("apps"), BUTTONS("buttons"), VOICE_SETTINGS("voiceSettings"),
    DISPLAY("display"), VEHICLE("vehicle"), SETTINGS("settings"),
    SEAT_MEMORY("seatMemory"), ROUTINES("routines"),
}

internal enum class QuickActionSide(val id: String) { LEFT("left"), RIGHT("right") }

internal enum class QuickActionCompactEdge(val id: String) { BOTTOM("bottom"), TOP("top") }

internal enum class QuickActionRailWidth(val id: String) { STANDARD("standard"), COMPACT("compact") }

internal enum class QuickActionRailAlignment(val id: String) { TOP("top"), CENTER("center"), BOTTOM("bottom") }

internal enum class QuickActionHandleSize(val id: String) { STANDARD("standard"), LARGE("large") }

internal enum class QuickActionHandleAccent(val id: String) { THEME("theme"), AMBER("amber"), TEAL("teal") }

internal enum class QuickActionCompactHandleSide(val id: String) { DEFAULT("default"), LEFT("left"), RIGHT("right") }

internal enum class QuickActionIdleCollapse(val seconds: Int) { OFF(0), TEN_SECONDS(10), THIRTY_SECONDS(30) }

internal enum class QuickActionStartState(val id: String) {
    DEFAULT("default"), EXPANDED("expanded"), COLLAPSED("collapsed"),
}

internal enum class QuickActionCompactLabels(val id: String) {
    DEFAULT("default"), ICONS("icons"), LABELS("labels"),
}

internal enum class QuickActionAppsDestination(val id: String, val desktop: AppGridDesktop?) {
    DEFAULT_DESKTOP("defaultDesktop", null), ALL_APPS("allApps", AppGridDesktop.MAIN),
}

internal enum class QuickActionHeadlightContent(val id: String) {
    SWITCH("switch"), HEADLIGHTS("headlights"), HIGH_BEAM("highBeam"),
}

internal enum class QuickActionHeadlightTap(val id: String) { VEHICLE("vehicle"), SOURCES("sources") }

internal enum class QuickActionArea(val id: String) {
    ALL("all"), APPS("apps"), OTHER_SCREENS("otherScreens");

    fun includes(apps: Boolean): Boolean = when (this) {
        ALL -> true
        APPS -> apps
        OTHER_SCREENS -> !apps
    }
}

internal data class QuickActionSettings(
    val enabled: Boolean = false,
    val side: QuickActionSide = QuickActionSide.RIGHT,
    val showLabels: Boolean = true,
    val area: QuickActionArea = QuickActionArea.ALL,
    val actions: List<QuickAction> = listOf(QuickAction.APPS, QuickAction.BUTTONS, QuickAction.SETTINGS),
    val compactEdge: QuickActionCompactEdge = QuickActionCompactEdge.BOTTOM,
    val collapseAfterUse: Boolean = false,
    val highContrastHandle: Boolean = false,
    val showCompactHandleLabel: Boolean = true,
    val appsDestination: QuickActionAppsDestination = QuickActionAppsDestination.DEFAULT_DESKTOP,
    val railWidth: QuickActionRailWidth = QuickActionRailWidth.STANDARD,
    val idleCollapse: QuickActionIdleCollapse = QuickActionIdleCollapse.OFF,
    val startState: QuickActionStartState = QuickActionStartState.DEFAULT,
    val compactLabels: QuickActionCompactLabels = QuickActionCompactLabels.DEFAULT,
    val showHeadlightMode: Boolean = false,
    val handleSize: QuickActionHandleSize = QuickActionHandleSize.STANDARD,
    val compactHandleSide: QuickActionCompactHandleSide = QuickActionCompactHandleSide.DEFAULT,
    val headlightContent: QuickActionHeadlightContent = QuickActionHeadlightContent.SWITCH,
    val appShortcuts: List<String> = emptyList(),
    val sideRailAlignment: QuickActionRailAlignment = QuickActionRailAlignment.CENTER,
    val hideOnVirtualDisplay: Boolean = false,
    val keepSideInset: Boolean = false,
    val headlightTap: QuickActionHeadlightTap = QuickActionHeadlightTap.VEHICLE,
    val handleAccent: QuickActionHandleAccent = QuickActionHandleAccent.THEME,
    val collapsedHandleAlignment: QuickActionRailAlignment = QuickActionRailAlignment.TOP,
    val dimIdleHandle: Boolean = false,
    val routineShortcutId: String? = null,
    val largeActions: Set<QuickAction> = emptySet(),
) {
    init {
        require(actions.isNotEmpty() && actions.size == actions.distinct().size)
        require(appShortcuts.size <= MAX_DOCK_APP_SHORTCUTS)
        AppGridPins.validate(appShortcuts)
        require(routineShortcutId == null || NamedRoutine.validId(routineShortcutId))
    }

    val canCollapse: Boolean get() = startState != QuickActionStartState.DEFAULT || collapseAfterUse || idleCollapse != QuickActionIdleCollapse.OFF

    val initiallyExpanded: Boolean get() = when (startState) {
        QuickActionStartState.DEFAULT -> !collapseAfterUse
        QuickActionStartState.EXPANDED -> true
        QuickActionStartState.COLLAPSED -> false
    }

    val compactLabelsVisible: Boolean get() = when (compactLabels) {
        QuickActionCompactLabels.DEFAULT -> showLabels
        QuickActionCompactLabels.ICONS -> false
        QuickActionCompactLabels.LABELS -> true
    }

    fun select(action: QuickAction, show: Boolean): QuickActionSettings = when {
        show && action !in actions -> copy(actions = actions + action)
        !show && action in actions && actions.size > 1 -> copy(actions = actions - action)
        else -> this
    }

    fun move(action: QuickAction, earlier: Boolean): QuickActionSettings {
        val from = actions.indexOf(action)
        val to = if (earlier) from - 1 else from + 1
        if (from < 0 || to !in actions.indices) return this
        val reordered = actions.toMutableList()
        reordered[from] = actions[to]
        reordered[to] = action
        return copy(actions = reordered)
    }

    fun addApp(component: String): QuickActionSettings {
        if (component in appShortcuts || appShortcuts.size >= MAX_DOCK_APP_SHORTCUTS) return this
        return copy(appShortcuts = AppGridPins.change(appShortcuts, component, pinned = true))
    }

    fun removeApp(component: String): QuickActionSettings =
        copy(appShortcuts = AppGridPins.change(appShortcuts, component, pinned = false))

    fun moveApp(component: String, earlier: Boolean): QuickActionSettings {
        val index = appShortcuts.indexOf(component)
        if (index == -1) return this
        val neighbor = appShortcuts.getOrNull(index + if (earlier) -1 else 1) ?: return this
        return copy(appShortcuts = AppGridPins.swap(appShortcuts, component, neighbor))
    }
}

internal interface QuickActionStore {
    fun observe(): Flow<QuickActionSettings>
    suspend fun update(change: (QuickActionSettings) -> QuickActionSettings)
}
