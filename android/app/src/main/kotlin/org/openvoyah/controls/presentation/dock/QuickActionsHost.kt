package org.openvoyah.controls.presentation.dock

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.dock.QuickAction
import org.openvoyah.controls.domain.dock.QuickActionHandleAccent
import org.openvoyah.controls.domain.dock.QuickActionHeadlightTap
import org.openvoyah.controls.domain.dock.QuickActionRailAlignment
import org.openvoyah.controls.domain.dock.QuickActionRailWidth
import org.openvoyah.controls.domain.dock.QuickActionSettings
import org.openvoyah.controls.domain.dock.QuickActionStartState
import org.openvoyah.controls.domain.routines.NamedRoutine
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitMotion
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout
import org.openvoyah.controls.presentation.theme.LocalCockpitMotion
import org.openvoyah.controls.presentation.theme.rememberPressMotion
import org.openvoyah.controls.presentation.vehicle.HeadlightSourcesPopup

@Composable
internal fun QuickActionsHost(
    settings: QuickActionSettings,
    route: ControlsRoute,
    open: (ControlsRoute) -> Unit,
    openApps: (AppGridDesktop?) -> Unit,
    model: QuickActionsViewModel,
    routineShortcut: NamedRoutine?,
    runRoutineShortcut: (NamedRoutine) -> Boolean,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val apps = route == ControlsRoute.APPS
    val hideForPreview = route == ControlsRoute.DISPLAY && settings.hideOnVirtualDisplay
    val wantsDock = settings.enabled && settings.area.includes(apps) && !hideForPreview
    val insets = if (wantsDock) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    val collapseOnRouteChange = route.takeIf {
        settings.collapseAfterUse && settings.startState == QuickActionStartState.DEFAULT
    }
    var opened by remember(collapseOnRouteChange, wantsDock, settings.canCollapse, settings.startState) {
        mutableStateOf(settings.initiallyExpanded)
    }
    var inspectHeadlights by rememberSaveable(route) { mutableStateOf(false) }
    val updateExpanded by rememberUpdatedState<(Boolean) -> Unit> { opened = it }
    val changeExpanded = remember { { expanded: Boolean -> updateExpanded(expanded) } }
    val runAction by rememberUpdatedState<(QuickAction) -> Unit> { action ->
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            val opensSources = action == QuickAction.VEHICLE && settings.showHeadlightMode &&
                settings.headlightTap == QuickActionHeadlightTap.SOURCES
            val destination = if (opensSources) "headlight_sources" else action.id
            ControlsLog.debug("quick_actions_activate", "action", destination, "collapse_after_use", settings.collapseAfterUse)
            if (settings.collapseAfterUse) opened = false
            when {
                opensSources -> inspectHeadlights = true
                action == QuickAction.APPS -> openApps(settings.appsDestination.desktop)
                else -> open(action.target.route)
            }
        }
    }
    val activate = remember { { action: QuickAction -> runAction(action) } }
    val ime = WindowInsets.ime
    // Read as a flag, so a keyboard animation recomposes the host when the keyboard shows or hides, not on every frame.
    val imeShown by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).then(insets)) {
        // Legacy decor can consume the IME inset while resizing the Compose content.
        val keyboardOpen = wantsDock && (imeShown ||
            ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
        LaunchedEffect(keyboardOpen) { if (keyboardOpen) opened = false }
        val expanded = !settings.canCollapse || opened
        val position = if (wantsDock) {
            quickActionLayout(maxWidth, maxHeight, density.fontScale * LocalCockpitLayout.current.textScale,
                settings, apps, keyboardOpen, expanded)
        } else QuickActionLayout(DockEdge.HIDDEN)
        val side = position.edge == DockEdge.LEFT || position.edge == DockEdge.RIGHT
        val atStart = (position.edge == DockEdge.LEFT) == (LocalLayoutDirection.current == LayoutDirection.Ltr)
        val barWidth = if (side) position.railWidth else maxWidth
        val bar: @Composable () -> Unit = {
            QuickActionBar(settings, route, position.edge, barWidth, expanded, changeExpanded, activate, model,
                routineShortcut) { routine ->
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && runRoutineShortcut(routine) &&
                    settings.collapseAfterUse) opened = false
            }
        }

        Column(Modifier.fillMaxSize()) {
            if (position.edge == DockEdge.TOP) bar()
            // Keep the destination mounted while the dock moves or changes its reserved size.
            Row(Modifier.fillMaxWidth().weight(1f)) {
                if (side && atStart) bar()
                Box(Modifier.weight(1f).fillMaxHeight().testTag("quickActionsContent")) { content() }
                if (side && !atStart) bar()
            }
            if (position.edge == DockEdge.BOTTOM) bar()
        }
    }
    if (inspectHeadlights) HeadlightSourcesPopup { inspectHeadlights = false }
}

@Composable
private fun QuickActionBar(settings: QuickActionSettings, route: ControlsRoute, edge: DockEdge, width: Dp,
                           expanded: Boolean, changeExpanded: (Boolean) -> Unit, activate: (QuickAction) -> Unit,
                           model: QuickActionsViewModel, routineShortcut: NamedRoutine?,
                           runRoutineShortcut: (NamedRoutine) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val layout = LocalCockpitLayout.current
    val rail = edge == DockEdge.LEFT || edge == DockEdge.RIGHT
    val showLabels = if (rail) settings.showLabels else settings.compactLabelsVisible
    if (BuildConfig.DEBUG) LaunchedEffect(edge, settings.canCollapse, expanded) {
        ControlsLog.debug("quick_actions_bar", "edge", edge, "collapsible", settings.canCollapse, "expanded", expanded)
    }
    val modifier = if (rail) Modifier.width(width).fillMaxHeight() else Modifier.fillMaxWidth()
    val pinnedWidth = if (settings.canCollapse) dockHandleSize else 0.dp
    val maxButtonWidth = width - pinnedWidth - 16.dp
    val scroll = key(edge, expanded) { rememberScrollState() }
    val timeout = if (expanded) settings.idleCollapse.seconds * 1_000L else 0L
    val barModifier = modifier.testTag("quickActionsBar").background(colors.surfaceContainerLow).border(1.dp, colors.outlineVariant)
        .collapseDockWhenIdle(timeout, route, showLabels, scroll) { changeExpanded(false) }
    // Opening the bar fades its shortcuts in. A bar that appears already open shows them at once, and closing removes them
    // in the same frame as the reserved space shrinks, so they are never squeezed into the handle's narrower slot.
    var reopened by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { if (!expanded) reopened = true }
    val fade = reopened && LocalCockpitMotion.current.enabled
    // Pass current settings into the expanded content so edits reach it after reopening the bar.
    if (rail) Column(
        barModifier,
        verticalArrangement = if (expanded) Arrangement.Top else Arrangement.spacedBy(0.dp, settings.collapsedHandleAlignment.alignment),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (settings.canCollapse) {
            val outerEnd = (edge == DockEdge.RIGHT) == (LocalLayoutDirection.current == LayoutDirection.Ltr)
            Box(Modifier.fillMaxWidth(), contentAlignment = if (outerEnd) Alignment.CenterEnd else Alignment.CenterStart) {
                DockHandle(edge, expanded, settings, changeExpanded)
            }
        }
        // The cross-axis padding stays fixed: QuickActionLayout reserves the rail width for 48 dp targets.
        if (expanded) Column(
            Modifier.fillMaxWidth().weight(1f).dockArrival(fade).verticalScroll(scroll).selectableGroup()
                .padding(horizontal = if (settings.railWidth == QuickActionRailWidth.COMPACT) 4.dp else 8.dp,
                    vertical = layout.spacing(8.dp)),
            verticalArrangement = Arrangement.spacedBy(layout.spacing(6.dp), settings.sideRailAlignment.alignment),
        ) {
            DockShortcuts(settings, route, rail, showLabels, maxButtonWidth, activate, model, routineShortcut, runRoutineShortcut,
                changeExpanded)
        }
    } else Row(barModifier, verticalAlignment = Alignment.CenterVertically) {
        val atStart = settings.compactHandleSide.isAtStart(LocalLayoutDirection.current)
        val handle: @Composable () -> Unit = {
            val slot = if (expanded) Modifier.width(dockHandleSize) else Modifier.fillMaxWidth()
            Box(slot, contentAlignment = if (atStart) Alignment.CenterStart else Alignment.CenterEnd) {
                DockHandle(edge, expanded, settings, changeExpanded, arrowAtStart = atStart)
            }
        }
        if (settings.canCollapse && atStart) handle()
        if (expanded) Row(
            Modifier.weight(1f).dockArrival(fade).horizontalScroll(scroll).selectableGroup()
                .padding(horizontal = layout.spacing(8.dp), vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(layout.spacing(6.dp), Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockShortcuts(settings, route, rail, showLabels, maxButtonWidth, activate, model, routineShortcut, runRoutineShortcut,
                changeExpanded)
        }
        if (settings.canCollapse && !atStart) handle()
    }
}

/** A short fade of the draw layer for shortcuts the handle has just revealed. It ends, and nothing is measured again. */
@Composable
private fun Modifier.dockArrival(fade: Boolean): Modifier {
    if (!fade) return this
    val arrival = remember { Animatable(0f) }
    LaunchedEffect(arrival) { arrival.animateTo(1f, CockpitMotion.enter<Float>()) }
    return graphicsLayer { alpha = arrival.value }
}

@Composable
private fun DockShortcuts(
    settings: QuickActionSettings,
    route: ControlsRoute,
    rail: Boolean,
    showLabels: Boolean,
    maxButtonWidth: Dp,
    activate: (QuickAction) -> Unit,
    model: QuickActionsViewModel,
    routineShortcut: NamedRoutine?,
    runRoutineShortcut: (NamedRoutine) -> Unit,
    changeExpanded: (Boolean) -> Unit,
) {
    val indicatorWidth = LocalCockpitLayout.current.columnsMinimum(108.dp, LocalDensity.current.fontScale)
    val actions = if (settings.showHeadlightMode && QuickAction.VEHICLE !in settings.actions) {
        settings.actions + QuickAction.VEHICLE
    } else settings.actions
    val button = if (rail) Modifier.fillMaxWidth() else Modifier.widthIn(max = maxButtonWidth)
    for (action in actions) key(action) {
        val target = action.target
        val selected = target.route == route
        val enlarged = action in settings.largeActions
        if (settings.showHeadlightMode && action == QuickAction.VEHICLE) {
            val badgeWidth = if (enlarged) indicatorWidth + largerShortcutGrowth else indicatorWidth
            val badge = if (rail) Modifier.fillMaxWidth() else Modifier.width(minOf(badgeWidth, maxButtonWidth))
            DockHeadlightIndicator(settings.headlightContent, settings.headlightTap, rail || maxButtonWidth < indicatorWidth,
                selected && settings.headlightTap == QuickActionHeadlightTap.VEHICLE, badge, enlargedRail = rail && enlarged) {
                activate(action)
            }
        } else {
            // Apps can reopen a specific desktop while the app grid is visible.
            val role = if (action == QuickAction.APPS) Role.Button else Role.Tab
            DockButton(UiText.get(target.title), if (showLabels) UiText.get(target.label) else null,
                rail, button, target.glyph, selected, role, enlarged = enlarged) { activate(action) }
        }
    }
    if (routineShortcut != null) key(routineShortcut.id) {
        DockButton(routineShortcut.name, routineShortcut.name.takeIf { showLabels }, rail, button,
            glyph = { CockpitGlyph(CockpitIcon.PLAY) },
            actionDescription = UiText.get(R.string.quick_actions_run_routine).format(routineShortcut.name)) {
            runRoutineShortcut(routineShortcut)
        }
    }
    if (settings.appShortcuts.isNotEmpty()) {
        DockAppButtons(model, settings.appShortcuts, route, showLabels, rail, button) {
            if (settings.collapseAfterUse) changeExpanded(false)
        }
    }
}

private val QuickActionRailAlignment.alignment: Alignment.Vertical get() = when (this) {
    QuickActionRailAlignment.TOP -> Alignment.Top
    QuickActionRailAlignment.CENTER -> Alignment.CenterVertically
    QuickActionRailAlignment.BOTTOM -> Alignment.Bottom
}

@Composable
private fun Modifier.collapseDockWhenIdle(
    timeout: Long,
    route: ControlsRoute,
    showLabels: Boolean,
    scroll: ScrollState,
    collapse: () -> Unit,
): Modifier {
    if (timeout == 0L) return this
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val accessibility = LocalAccessibilityManager.current
    val systemAccessibility = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    var touchExploration by remember(systemAccessibility) { mutableStateOf(systemAccessibility?.isTouchExplorationEnabled == true) }
    DisposableEffect(systemAccessibility) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { touchExploration = it }
        systemAccessibility?.addTouchExplorationStateChangeListener(listener)
        touchExploration = systemAccessibility?.isTouchExplorationEnabled == true
        onDispose { systemAccessibility?.removeTouchExplorationStateChangeListener(listener) }
    }
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val scrolling = scroll.isScrollInProgress
    val inUse = pressed || focused || scrolling || touchExploration
    val onCollapse by rememberUpdatedState(collapse)
    LaunchedEffect(lifecycle, timeout, route, inUse, accessibility, showLabels) {
        if (inUse) {
            ControlsLog.debug("quick_actions_idle", "outcome", "held", "pressed", pressed, "focused", focused,
                "scrolling", scrolling, "touch_exploration", touchExploration)
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val recommended = accessibility?.calculateRecommendedTimeoutMillis(timeout, containsIcons = true,
                containsText = showLabels, containsControls = true) ?: timeout
            ControlsLog.debug("quick_actions_idle", "outcome", "scheduled", "timeout_ms", recommended)
            if (recommended == Long.MAX_VALUE) return@repeatOnLifecycle
            delay(recommended)
            ControlsLog.debug("quick_actions_idle", "outcome", "collapsed")
            onCollapse()
        }
    }
    return onFocusChanged { focused = it.hasFocus }.focusGroup().pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            pressed = true
            try {
                while (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }) { // Wait for the last release.
                }
            } finally {
                pressed = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun DockHandle(edge: DockEdge, expanded: Boolean, settings: QuickActionSettings, changeExpanded: (Boolean) -> Unit,
                       arrowAtStart: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val animate = LocalCockpitMotion.current.enabled
    val name = UiText.get(if (expanded) R.string.quick_actions_collapse else R.string.quick_actions_expand)
    val label = UiText.get(R.string.quick_actions_bar)
    val stateLabel = UiText.get(if (expanded) R.string.quick_actions_expanded else R.string.quick_actions_collapsed)
    val tip = rememberTooltipState()
    val scope = rememberCoroutineScope()
    val showText = !expanded && settings.showCompactHandleLabel && (edge == DockEdge.TOP || edge == DockEdge.BOTTOM)
    val highContrast = !expanded && settings.highContrastHandle
    val size = if (expanded) dockHandleSize else settings.handleSize.touchSize
    val fullColors = if (expanded) QuickActionHandleColors(colors.surfaceContainerLow, colors.onPrimaryContainer)
        else settings.handleAccent.collapsedColors(colors, highContrast)
    val interaction = if (settings.dimIdleHandle) remember { MutableInteractionSource() } else null
    val handleColors = if (interaction != null) {
        val focused by interaction.collectIsFocusedAsState()
        val pressed by interaction.collectIsPressedAsState()
        if (expanded || focused || pressed) fullColors
        else remember(colors, fullColors, highContrast) { fullColors.dimmedForIdle(colors, highContrast) }
    } else fullColors
    // Opening and closing swap the colors at once, in step with the reserved space; dimming, focus and accent changes blend.
    val shown = key(expanded) {
        QuickActionHandleColors(handleColor(handleColors.fill, animate, "handle fill"), handleColor(handleColors.ink, animate, "handle ink"),
            handleColors.outline?.let { handleColor(it, animate, "handle outline") })
    }
    val outline = shown.outline?.let { Modifier.border(1.dp, it, shape) } ?: Modifier
    val tintArrow = !expanded && (highContrast || settings.dimIdleHandle || settings.handleAccent != QuickActionHandleAccent.THEME)
    val intoPage = when (edge) {
        DockEdge.LEFT -> 0f
        DockEdge.RIGHT -> 180f
        DockEdge.TOP -> 90f
        DockEdge.BOTTOM -> -90f
        DockEdge.HIDDEN -> return
    }
    val turn by animateFloatAsState(if (expanded) intoPage + 180f else intoPage,
        if (animate) CockpitMotion.change<Float>() else snap<Float>(), label = "handle arrow")
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(name) } }, state = tip, focusable = false, enableUserInput = false) {
        // One named Android accessibility node handles both touch and accessibility activation.
        Row(Modifier.testTag("quickActionsHandle").widthIn(min = size).heightIn(min = size).clip(shape)
            .background(shown.fill).then(outline)
            .combinedClickable(interactionSource = interaction, indication = LocalIndication.current,
                role = Role.Button, onClickLabel = name, onClick = { changeExpanded(!expanded) },
                onLongClick = if (showText) null else { { scope.launch { tip.show() } } })
            .semantics {
                contentDescription = label
                stateDescription = stateLabel
                toggleableState = ToggleableState(expanded)
            }, verticalAlignment = Alignment.CenterVertically) {
            val arrow: @Composable () -> Unit = {
                Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                    CockpitGlyph(CockpitIcon.ARROW, Modifier.rotate(turn), tint = if (tintArrow) shown.ink else Color.Unspecified)
                }
            }
            if (arrowAtStart) arrow()
            if (showText) {
                val padding = if (arrowAtStart) Modifier.padding(end = 12.dp) else Modifier.padding(start = 12.dp)
                Text(name, Modifier.weight(1f, fill = false).then(padding).clearAndSetSemantics {},
                    style = MaterialTheme.typography.labelLarge, color = shown.ink)
            }
            if (!arrowAtStart) arrow()
        }
    }
}

@Composable
private fun handleColor(target: Color, animate: Boolean, label: String): Color =
    animateColorAsState(target, if (animate) CockpitMotion.change<Color>() else snap<Color>(), label = label).value

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DockButton(title: String, label: String?, rail: Boolean, modifier: Modifier, glyph: @Composable () -> Unit,
                        selected: Boolean? = null, role: Role = Role.Button, actionDescription: String? = null,
                        enlarged: Boolean = false, click: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val active = selected == true
    val name = actionDescription ?: UiText.get(if (selected == null) R.string.quick_actions_open_app else R.string.quick_actions_open).format(title)
    val press = rememberPressMotion()
    val spec = if (LocalCockpitMotion.current.enabled) CockpitMotion.change<Color>() else snap<Color>()
    // The selection blends between pages in one hue: the unselected state is the same color, fully transparent.
    val selection by animateColorAsState(colors.primaryContainer.copy(alpha = if (active) 1f else 0f), spec, label = "dock selection")
    val selectionEdge by animateColorAsState(colors.primary.copy(alpha = if (active) 1f else 0f), spec, label = "dock selection edge")
    val ink by animateColorAsState(if (active) colors.onPrimaryContainer else colors.onSurface, spec, label = "dock label")
    val button: @Composable () -> Unit = {
        val action = if (selected == null) {
            Modifier.clickable(interactionSource = press.source, indication = LocalIndication.current, role = role, onClick = click)
        } else Modifier.selectable(selected, interactionSource = press.source, indication = LocalIndication.current, role = role,
            onClick = click)
        val hit = modifier.then(press.modifier).widthIn(min = 48.dp).heightIn(min = 48.dp).clip(shape)
            .background(selection, shape).border(1.dp, selectionEdge, shape).then(action)
            .semantics { contentDescription = name }
            .then(if (enlarged) Modifier.enlargeDockShortcut(rail) else Modifier)
            .padding(horizontal = if (rail) 4.dp else 10.dp, vertical = 6.dp)
        val caption: @Composable () -> Unit = {
            if (label != null) Text(label, Modifier.clearAndSetSemantics {},
                maxLines = if (selected == null) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, color = ink)
        }
        if (rail) Column(hit, horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
            glyph()
            caption()
        } else Row(hit, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally)) {
            glyph()
            caption()
        }
    }
    if (label != null) button() else TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(title) } }, state = rememberTooltipState(), content = button,
    )
}

private val largerShortcutGrowth = 24.dp

internal fun Modifier.enlargeDockShortcut(rail: Boolean): Modifier = layout { measurable, constraints ->
    // Keep the measured content and add space inside the clickable bounds along the scroll axis.
    val content = measurable.measure(constraints)
    val growth = largerShortcutGrowth.roundToPx()
    val width = if (rail) content.width else minOf(content.width + growth, constraints.maxWidth)
    val height = if (rail) minOf(content.height + growth, constraints.maxHeight) else content.height
    layout(width, height) {
        content.placeRelative((width - content.width) / 2, (height - content.height) / 2)
    }
}
