package org.openvoyah.controls.presentation.dock

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.domain.dock.QuickActionCompactEdge
import org.openvoyah.controls.domain.dock.QuickActionCompactHandleSide
import org.openvoyah.controls.domain.dock.QuickActionHandleSize
import org.openvoyah.controls.domain.dock.QuickActionRailWidth
import org.openvoyah.controls.domain.dock.QuickActionSettings
import org.openvoyah.controls.domain.dock.QuickActionSide

internal enum class DockEdge { HIDDEN, LEFT, RIGHT, TOP, BOTTOM }

internal val dockHandleSize = 48.dp

internal val QuickActionHandleSize.touchSize: Dp get() = when (this) {
    QuickActionHandleSize.STANDARD -> dockHandleSize
    QuickActionHandleSize.LARGE -> 60.dp
}

internal fun QuickActionCompactHandleSide.isAtStart(direction: LayoutDirection): Boolean = when (this) {
    QuickActionCompactHandleSide.DEFAULT -> true
    QuickActionCompactHandleSide.LEFT -> direction == LayoutDirection.Ltr
    QuickActionCompactHandleSide.RIGHT -> direction == LayoutDirection.Rtl
}

internal data class QuickActionLayout(val edge: DockEdge, val railWidth: Dp = 0.dp)

internal fun quickActionLayout(
    width: Dp, height: Dp, textScale: Float, settings: QuickActionSettings, apps: Boolean, keyboardOpen: Boolean,
    expanded: Boolean = true,
): QuickActionLayout {
    if (!settings.enabled || !settings.area.includes(apps) || keyboardOpen) return QuickActionLayout(DockEdge.HIDDEN)
    val scale = textScale.coerceAtLeast(1f)
    val compact = settings.railWidth == QuickActionRailWidth.COMPACT
    val rail = when {
        settings.showLabels -> (if (compact) 104.dp else 132.dp) * scale
        compact -> dockHandleSize + 8.dp
        else -> 68.dp
    }
    // Both states must fit at the chosen edge so expanding keeps the handle on that edge.
    val closedHandle = if (settings.canCollapse) settings.handleSize.touchSize else 0.dp
    val maximumRail = maxOf(rail, closedHandle)
    if (scale <= 1.25f && width >= height && height >= 120.dp && width - maximumRail >= 640.dp * scale) {
        val widthNeeded = when {
            settings.keepSideInset -> maximumRail
            settings.canCollapse && !expanded -> closedHandle
            else -> rail
        }
        return QuickActionLayout(if (settings.side == QuickActionSide.LEFT) DockEdge.LEFT else DockEdge.RIGHT, widthNeeded)
    }
    // Leave room for the pinned handle, a complete shortcut and the page's heading and scrollable body.
    val pinnedHandle = if (settings.canCollapse) dockHandleSize else 0.dp
    val buttonWidth = if (settings.compactLabelsVisible) 64.dp + 102.dp * scale else 64.dp
    val rowHeight = if (settings.compactLabelsVisible) maxOf(64.dp, 24.dp + 40.dp * scale) else 64.dp
    val minimumWidth = maxOf(pinnedHandle + buttonWidth, closedHandle)
    val minimumHeight = maxOf(rowHeight, closedHandle) + 172.dp * scale
    if (width < minimumWidth || height < minimumHeight) return QuickActionLayout(DockEdge.HIDDEN)
    return QuickActionLayout(if (settings.compactEdge == QuickActionCompactEdge.TOP) DockEdge.TOP else DockEdge.BOTTOM)
}
