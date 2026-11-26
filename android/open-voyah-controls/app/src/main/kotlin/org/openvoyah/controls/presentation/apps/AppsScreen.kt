package org.openvoyah.controls.presentation.apps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.shape.CircleShape
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog as AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.openvoyah.controls.R
import org.openvoyah.controls.domain.display.AppPair
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.apps.AppGridConfiguration
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.apps.AppGridIconStyle
import org.openvoyah.controls.domain.apps.AppGridMoves
import org.openvoyah.controls.domain.apps.AppGridPages
import org.openvoyah.controls.domain.apps.AppGridRows
import org.openvoyah.controls.domain.apps.AppGridShortcut
import org.openvoyah.controls.domain.apps.AppGridTileSize
import org.openvoyah.controls.platform.apps.LocalApp
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitMotion
import org.openvoyah.controls.presentation.theme.CockpitReveal
import org.openvoyah.controls.presentation.theme.CockpitTextAction
import org.openvoyah.controls.presentation.theme.CockpitTextField
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout
import org.openvoyah.controls.presentation.theme.LocalCockpitMotion
import org.openvoyah.controls.presentation.theme.rememberPressMotion
import org.openvoyah.controls.presentation.theme.scaledText
import java.util.Locale
import org.openvoyah.controls.platform.display.rememberScreenLowered
import org.openvoyah.controls.platform.display.visibleWhenLowered

internal data class AppPairActions(val pairs: List<AppPair>, val manage: () -> Unit, val open: (AppPair) -> Unit)

/** The launcher root keeps apps and Controls shortcuts available on every desktop. */
@Composable
internal fun AppsScreen(
    state: AppsViewModel.State,
    entry: Long,
    desktop: AppGridDesktop,
    navigate: (ControlsRoute) -> Unit,
    listen: () -> Unit,
    animateDesktopPaging: Boolean,
    gridActive: Boolean,
    install: () -> Unit,
    canInstall: () -> Boolean,
    allowInstalls: () -> Unit,
    installEnabled: Boolean,
    discardInstall: (() -> Unit)?,
    selectDesktop: (AppGridDesktop) -> Unit,
    changeDesktopPin: (LocalApp, Boolean) -> Unit,
    swapDesktopPins: (LocalApp, LocalApp) -> Unit,
    showSettings: (LocalApp) -> Unit,
    open: (LocalApp) -> Unit,
    pairActions: AppPairActions? = null,
    manageTasks: (() -> Unit)? = null,
    move: AppMoveState? = null,
    endMove: () -> Unit = {},
    moveApp: (LocalApp, LocalApp, List<String>) -> Unit = { _, _, _ -> },
) {
    var query by rememberSaveable(entry) { mutableStateOf("") }
    var search by rememberSaveable(entry) { mutableStateOf(false) }
    var allow by rememberSaveable(entry) { mutableStateOf(false) }
    var savedEntry by rememberSaveable { mutableStateOf(entry) }
    var pickerDesktop by remember(entry) { mutableStateOf<AppGridDesktop?>(null) }
    var reorderDesktop by remember(entry) { mutableStateOf<AppGridDesktop?>(null) }
    // Saved input keys are not validated when an absent route is restored from its registry.
    if (savedEntry != entry) {
        query = ""
        search = false
        allow = false
        savedEntry = entry
    }
    val grid = state.grid?.getOrNull() ?: AppGridConfiguration()
    val alwaysShowSearch = desktop == AppGridDesktop.MAIN && grid.showSearchField
    val searching = search || query.isNotEmpty()
    val tiles = remember(grid.tileSize) { grid.tileSize.metrics }
    val optionalShortcuts = remember(grid.visibleShortcuts) {
        AppGridShortcut.entries.filter { it != AppGridShortcut.QUICK_VOICE && it in grid.visibleShortcuts }
    }
    val pins = grid.pins(desktop)
    val pinnedApps = remember(pins) { pins.toSet() }
    val orderedApps = remember(state.apps, pins, grid.hideByDefault, grid.hiddenApps, grid.shownApps, grid.sortOrder,
        grid.manualAppOrder, desktop) {
        grid.arrange(state.apps, LocalApp::id, LocalApp::hiddenByDefault, desktop, LocalApp::firstInstallTime)
    }
    val desktopPins = remember(orderedApps, pinnedApps) {
        orderedApps.filter { it.id in pinnedApps && it.launchEntry != null }
    }
    val visible = remember(orderedApps, query) {
        val filter = query.trim().lowercase(Locale.ROOT)
        orderedApps.filter { it.matchesAppFilter(filter) }
    }
    val openDesktop: (AppGridDesktop) -> Unit = { next ->
        if (next != desktop) {
            search = false
            query = ""
            pickerDesktop = null
            reorderDesktop = null
            selectDesktop(next)
        }
    }
    val showPicker = grid.desktops.enabled && desktop != AppGridDesktop.MAIN && pickerDesktop == desktop
    val showReorder = reorderDesktop == desktop && desktopPins.isNotEmpty()
    val handleFavoritesBack = gridActive && grid.opensMainOnBack(desktop) && !searching && !allow && !showPicker && !showReorder
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    // Read as a flag, so the keyboard animation does not recompose the whole grid on every frame.
    val imeShown by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    val keyboardVisible = handleFavoritesBack && (imeShown ||
        ViewCompat.getRootWindowInsets(LocalView.current)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
    BackHandler(handleFavoritesBack && !keyboardVisible) { openDesktop(AppGridDesktop.MAIN) }
    val clearSearch = {
        search = false
        query = ""
    }
    BackHandler(searching, onBack = clearSearch)
    // Moving works on the whole desktop, so it starts without a search and Back ends it.
    LaunchedEffect(move) { if (move != null) clearSearch() }
    BackHandler(move != null, onBack = endMove)
    LaunchedEffect(orderedApps) { move?.pending = null }
    LaunchedEffect(state.gridEdit?.error) { if (state.gridEdit?.error != null) move?.pending = null }
    val colors = MaterialTheme.colorScheme
    val layout = LocalCockpitLayout.current
    val smoothPaging = animateDesktopPaging && grid.smoothDesktopPaging &&
        state.selection?.let { it.entry == entry && it.desktop == desktop } == true
    val banner = rememberClosingStatus(state.status)
    // A lowered display shows only its top part: the grid ends there and keeps two rows of smaller tiles.
    val lowered by rememberScreenLowered()
    DesktopWallpaperBackdrop(desktop) {
        Column(Modifier.fillMaxSize().visibleWhenLowered(lowered).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
            .padding(horizontal = layout.spacing(24.dp), vertical = layout.spacing(20.dp)),
            verticalArrangement = Arrangement.spacedBy(layout.spacing(12.dp))) {
            if (grid.desktops.enabled || desktopPins.size > 1) {
                DesktopSwitcher(desktop, showDesktops = grid.desktops.enabled, names = grid.desktopNames, select = openDesktop, findApps = {
                    reorderDesktop = null
                    pickerDesktop = desktop
                }, reorder = if (desktopPins.size > 1) {
                    {
                        pickerDesktop = null
                        reorderDesktop = desktop
                    }
                } else null)
            }
            CockpitReveal(alwaysShowSearch || searching) {
                val clearIcon: (@Composable () -> Unit)? = if (alwaysShowSearch && query.isNotEmpty()) {
                    {
                        val clearLabel = text(R.string.apps_grid_clear_search)
                        IconButton(onClick = clearSearch, modifier = Modifier.semantics { contentDescription = clearLabel }) {
                            CockpitGlyph(CockpitIcon.CLOSE, tint = LocalContentColor.current)
                        }
                    }
                } else null
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CockpitTextField(query, { query = it }, text(R.string.ui_113), Modifier.weight(1f), trailingIcon = clearIcon)
                    if (!alwaysShowSearch) CockpitTextAction(text(R.string.ui_89), clearSearch)
                }
            }
            CockpitReveal(state.status != null) {
                banner?.let { message ->
                    Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text(message), Modifier.weight(1f))
                            discardInstall?.let { discard ->
                                CockpitAction(text(R.string.apps_install_discard), discard, destructive = true)
                            }
                        }
                    }
                }
            }
            DesktopPageTransition(entry, desktop, smoothPaging, Modifier.weight(1f)) { BoxWithConstraints(Modifier.fillMaxSize()) {
                val gridSpacing = layout.spacing(12.dp)
                val labelStyle = MaterialTheme.typography.titleMedium.scaledText(tiles.labelScale)
                val columnCount = with(density) {
                    grid.columns.fit(constraints.maxWidth, tiles.cellWidth.roundToPx(), gridSpacing.roundToPx())
                }
                val tileContent = tiles.contentHeight(labelStyle, grid.showAppLabels, density)
                // Rows that are lower than a tile's content shrink its icon and caption to fit; the normal display
                // never asks for that.
                val fitted: (Dp) -> GridTileMetrics = { rowHeight -> tiles.fittedTo(rowHeight, tileContent) }
                val layoutFor: (Dp) -> GridPageLayout = { height ->
                    tiles.pageLayout(height, gridSpacing, labelStyle, grid.showAppLabels, density,
                        minimumRows = if (lowered) 2 else 1)
                }
                val cards = if (searching) emptyList() else rememberDesktopInfoCards(desktop)
                val arranged = remember(visible) { visible.map(LocalApp::id) }
                // A move previews and keeps its order until the saved one arrives; other apps stay as arranged.
                val order = move?.order(arranged) ?: arranged
                val shown = remember(visible, order) {
                    if (order === arranged) visible else visible.associateBy(LocalApp::id).let { byId -> order.mapNotNull(byId::get) }
                }
                val appsBeforeCards = remember(cards, shown, pinnedApps, grid.desktopCardPlacement) {
                    if (cards.isEmpty()) 0 else grid.desktopCardPlacement.appsBeforeCards(shown) { it.id in pinnedApps }
                }
                val moving = move != null
                val sameGroup: (String, String) -> Boolean = { a, b ->
                    desktop != AppGridDesktop.MAIN || (a in pinnedApps) == (b in pinnedApps)
                }
                fun place(source: String, target: String) {
                    val app = visible.firstOrNull { it.id == source } ?: return
                    val other = visible.firstOrNull { it.id == target } ?: return
                    moveApp(app, other, arranged)
                }
                val tapToMove: (LocalApp) -> Unit = tap@{ app ->
                    val session = move ?: return@tap
                    val chosen = session.selected
                    when (chosen) {
                        null -> session.selected = app.id
                        app.id -> session.selected = null
                        else -> {
                            session.selected = null
                            val target = AppGridMoves.targetWithin(order, chosen, app.id, sameGroup)
                            if (target != chosen) {
                                session.pending = AppGridMoves.place(order, chosen, target)
                                place(chosen, target)
                            }
                        }
                    }
                }
                val appTile: @Composable LazyGridItemScope.(LocalApp, GridCellContext) -> Unit = { app, context ->
                    val pinned = app.id in pinnedApps
                    val pinnedLabel = if (pinned) {
                        text(if (desktop == AppGridDesktop.MAIN) R.string.apps_pinned else R.string.apps_desktop_pinned)
                    } else null
                    val announcement = if (pinnedLabel != null) Modifier.semantics { stateDescription = pinnedLabel } else Modifier
                    val moveModifier = if (move == null) Modifier else {
                        val index = order.indexOf(app.id)
                        val earlier = order.getOrNull(index - 1)?.takeIf { sameGroup(app.id, it) }
                        val later = order.getOrNull(index + 1)?.takeIf { sameGroup(app.id, it) }
                        val actions = listOfNotNull(
                            earlier?.let { neighbor -> CustomAccessibilityAction(text(R.string.apps_move_earlier)) {
                                move.pending = AppGridMoves.place(order, app.id, neighbor); place(app.id, neighbor); true
                            } },
                            later?.let { neighbor -> CustomAccessibilityAction(text(R.string.apps_move_later)) {
                                move.pending = AppGridMoves.place(order, app.id, neighbor); place(app.id, neighbor); true
                            } },
                        )
                        val chosen = move.selected == app.id
                        // No card behind the tiles: the picked app gets a ring around its icon, the dragged one fades.
                        Modifier.semantics { customActions = actions; selected = chosen }
                            .alpha(if (move.dragging == app.id) 0.25f else 1f)
                    }
                    val ringColor = colors.primary
                    val ring = if (move?.selected == app.id) Modifier.drawBehind {
                        drawCircle(ringColor, radius = size.minDimension / 2 + 6.dp.toPx(), style = Stroke(3.dp.toPx()))
                    } else Modifier
                    val metrics = fitted(context.rowHeight)
                    LauncherTile(app.label, metrics,
                        open = if (move == null) { { if (app.launchEntry == null) showSettings(app) else open(app) } } else { { tapToMove(app) } },
                        settings = if (move == null) { { showSettings(app) } } else null,
                        modifier = gridItemMotion().then(context.anchor).then(announcement).then(moveModifier),
                        showLabel = grid.showAppLabels) {
                        if (pinned) Box(ring.size(metrics.iconSize)) {
                            AppIcon(app, Modifier.fillMaxSize(), grid.iconStyle)
                            PinMark(Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-4).dp))
                        } else AppIcon(app, ring.size(metrics.iconSize), grid.iconStyle)
                    }
                }
                val cardCells = cards.map { tile ->
                    GridCell(tile.key, tile.size.span(columnCount)) { context ->
                        DesktopInfoCardItem(tile, context.visible, grid.showDesktopCardOutlines, navigate, enabled = !moving)
                    }
                }
                val appCells = shown.map { app -> GridCell("app:${app.id}", 1, app) { context -> appTile(app, context) } }
                val cells = buildList {
                    add(GridCell("controls-settings", 1) { context ->
                        val metrics = fitted(context.rowHeight)
                        LauncherTile(text(R.string.app_name), metrics, open = { navigate(ControlsRoute.SETTINGS) },
                            modifier = gridItemMotion(), enabled = !moving) {
                            Image(painterResource(R.drawable.ic_controls_art), contentDescription = null,
                                modifier = Modifier.size(metrics.iconSize).clip(CircleShape),
                                colorFilter = if (grid.iconStyle == AppGridIconStyle.MONOCHROME) monochromeIcons else null)
                        }
                    })
                    if (pairActions != null && !searching && desktop == AppGridDesktop.MAIN) {
                        for (pair in pairActions.pairs) add(GridCell("app-pair:${pair.id}", 1) { context ->
                            val metrics = fitted(context.rowHeight)
                            LauncherTile(pair.name, metrics, open = { pairActions.open(pair) }, settings = pairActions.manage,
                                modifier = gridItemMotion(), enabled = !moving, showLabel = grid.showAppLabels) {
                                CockpitGlyph(CockpitIcon.DISPLAY, Modifier.size(metrics.iconSize))
                            }
                        })
                        add(GridCell("controls-app-pairs", 1) { context ->
                            Shortcut(R.string.app_pairs_title, CockpitIcon.DISPLAY, fitted(context.rowHeight), gridItemMotion(),
                                enabled = !moving, open = pairActions.manage)
                        })
                    }
                    if (manageTasks != null && !searching && desktop == AppGridDesktop.MAIN) {
                        add(GridCell("controls-workspace-tasks", 1) { context ->
                            Shortcut(R.string.workspace_tasks_title, CockpitIcon.APPS, fitted(context.rowHeight), gridItemMotion(),
                                enabled = !moving, open = manageTasks)
                        })
                    }
                    if (appsBeforeCards == 0) addAll(cardCells)
                    if (AppGridShortcut.QUICK_VOICE in grid.visibleShortcuts) {
                        val quickVoice = AppGridShortcut.QUICK_VOICE.details
                        add(GridCell(quickVoice.key, 1) { context ->
                            Shortcut(quickVoice.label, quickVoice.icon, fitted(context.rowHeight), gridItemMotion(),
                                enabled = !moving, open = listen)
                        })
                    }
                    if (appsBeforeCards > 0) {
                        addAll(appCells.subList(0, appsBeforeCards))
                        addAll(cardCells)
                    }
                    addAll(appCells.subList(appsBeforeCards, appCells.size))
                    optionalShortcuts.forEach { shortcut ->
                        val details = shortcut.details
                        add(GridCell(details.key, 1) { context ->
                            Shortcut(details.label, details.icon, fitted(context.rowHeight), gridItemMotion(),
                                enabled = !moving && (shortcut != AppGridShortcut.INSTALL_APK || installEnabled)) {
                                when (shortcut) {
                                    AppGridShortcut.QUICK_VOICE -> listen()
                                    AppGridShortcut.INSTALL_APK -> if (canInstall()) install() else allow = true
                                    else -> navigate(checkNotNull(details.route))
                                }
                            }
                        })
                    }
                    if (!alwaysShowSearch) {
                        add(GridCell("controls-search", 1) { context ->
                            Shortcut(R.string.ui_113, CockpitIcon.SEARCH, fitted(context.rowHeight), gridItemMotion(),
                                enabled = !moving) { search = true }
                        })
                    }
                    if (state.loading || visible.isEmpty()) add(GridCell("app-list-state", columnCount) {
                        val label = when {
                            state.loading -> R.string.apps_loading
                            query.isNotBlank() -> R.string.apps_empty
                            desktop != AppGridDesktop.MAIN -> R.string.apps_desktop_empty
                            else -> R.string.apps_grid_empty_main
                        }
                        Text(text(label),
                            gridItemMotion().padding(18.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    })
                }
                PagedAppGrid(cells, columnCount, gridSpacing, layoutFor, resetKey = Triple(entry, desktop, query), move = move,
                    arranged = arranged, sameGroup = sameGroup, place = ::place,
                    floating = { app, modifier ->
                        LauncherTile(app.label, tiles, open = {}, modifier = modifier, showLabel = grid.showAppLabels) { AppIcon(app, Modifier.size(tiles.iconSize), grid.iconStyle) }
                    },
                    modifier = Modifier.fillMaxSize(),
                    footer = when {
                        move != null -> { dots -> MoveBar(move, visible, state.gridEdit?.takeIf { it.desktop == desktop }?.error, dots, endMove) }
                        state.notice != null -> { dots -> NoticeBar(state.notice, dots) }
                        else -> null
                    })
            } }
        }
    }
    if (allow) AlertDialog(onDismissRequest = { allow = false },
        title = { Text(text(R.string.install_local_apk)) }, text = { Text(text(R.string.allow_apk_install)) },
        confirmButton = { CockpitAction(text(R.string.ui_61), { allow = false; allowInstalls() }, primary = true) },
        dismissButton = { CockpitTextAction(text(R.string.ui_89)) { allow = false } })
    if (showPicker) {
        FavoriteAppsPicker(desktop, grid, state, close = { pickerDesktop = null }, changePin = changeDesktopPin)
    }
    if (showReorder) {
        val edit = state.gridEdit?.takeIf { it.desktop == desktop }
        PinnedAppsOrderDialog(desktopPins, grid.iconStyle, enabled = state.gridEdit?.working != true,
            failed = edit?.error != null, close = { reorderDesktop = null }, swap = swapDesktopPins)
    }
}

/** The last message stays on the banner while it closes, so the banner shrinks away instead of emptying first. */
@Composable
private fun rememberClosingStatus(status: Int?): Int? {
    val last = remember { mutableStateOf(status) }
    SideEffect { if (status != null) last.value = status }
    return status ?: last.value
}

/** Every grid item arrives, moves and leaves alike, so the grid moves as one while it is filtered. */
@Composable
internal fun LazyGridItemScope.gridItemMotion(leave: Boolean = true): Modifier =
    if (!LocalCockpitMotion.current.enabled) Modifier
    else Modifier.animateItem(CockpitMotion.enter<Float>(), CockpitMotion.change<IntOffset>(), CockpitMotion.exit<Float>().takeIf { leave })

@Composable
private fun Shortcut(label: Int, icon: CockpitIcon, tiles: GridTileMetrics, modifier: Modifier = Modifier, enabled: Boolean = true,
                     open: () -> Unit) {
    LauncherTile(text(label), tiles, open, modifier = modifier, enabled = enabled) {
        CockpitGlyph(icon, Modifier.size(tiles.iconSize))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LauncherTile(label: String, tiles: GridTileMetrics, open: () -> Unit, settings: (() -> Unit)? = null,
                         modifier: Modifier = Modifier, enabled: Boolean = true, showLabel: Boolean = true,
                         icon: @Composable () -> Unit) {
    val press = rememberPressMotion(enabled, depth = .06f)
    val accessibleName = if (showLabel) Modifier else Modifier.semantics { contentDescription = label }
    Column(modifier.then(press.modifier).fillMaxWidth().heightIn(min = tiles.height).clip(MaterialTheme.shapes.medium)
            .combinedClickable(enabled = enabled, interactionSource = press.source,
                indication = LocalIndication.current, role = Role.Button,
                onLongClick = settings, onLongClickLabel = settings?.let { text(R.string.app_settings_title) }, onClick = open)
            .then(accessibleName)
            .padding(horizontal = 10.dp, vertical = tiles.verticalPadding), horizontalAlignment = Alignment.CenterHorizontally,
            // A label reserves two lines and hangs from the top, so the icons of a row stay level when one label wraps.
            // A tile stretched to fill the grid's height centres that block, which keeps the icons level as well.
            verticalArrangement = Arrangement.spacedBy(tiles.labelSpacing,
                if (showLabel && !tiles.fillsHeight) Alignment.Top else Alignment.CenterVertically)) {
            icon()
            if (showLabel) {
                Text(label, style = MaterialTheme.typography.titleMedium.scaledText(tiles.labelScale), textAlign = TextAlign.Center,
                    minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f))
            }
    }
}

/**
 * [height] is the least height of a tile. With [fillsHeight] the rows are stretched to fill the page's height with
 * whole rows (see [pageLayout]); [labelScale] sizes the caption relative to the title style.
 */
internal data class GridTileMetrics(
    val cellWidth: Dp,
    val height: Dp,
    val iconSize: Dp,
    val verticalPadding: Dp,
    val labelSpacing: Dp,
    val labelScale: Float = 1f,
    val fillsHeight: Boolean = false,
) {
    /**
     * Whole rows of this size on a page [visibleHeight] tall and their height. A size that fills the grid stretches
     * its rows to end at the bottom edge; the others keep the height their icon and two caption lines need.
     */
    fun pageLayout(visibleHeight: Dp, spacing: Dp, label: TextStyle, showLabel: Boolean, density: Density,
                   minimumRows: Int = 1): GridPageLayout =
        with(density) {
            val content = contentHeight(label, showLabel, density)
            val available = if (visibleHeight.isFinite) visibleHeight.roundToPx() else content.roundToPx()
            val rowHeight = if (fillsHeight) {
                AppGridRows.fittedTileHeight(available, content.roundToPx(), spacing.roundToPx()).toDp()
            } else content
            val rows = AppGridPages.rows(available, rowHeight.roundToPx(), spacing.roundToPx())
            // Fewer rows than asked for share the height instead; their tiles shrink to fit (see [fittedTo]).
            if (rows >= minimumRows) GridPageLayout(rows, rowHeight)
            else GridPageLayout(minimumRows, AppGridRows.sharedHeight(available, minimumRows, spacing.roundToPx()).toDp())
        }

    /** The height a tile's padding, icon and caption need. */
    fun contentHeight(label: TextStyle, showLabel: Boolean, density: Density): Dp = with(density) {
        val labelHeight = if (showLabel) labelSpacing + label.lineHeight.toDp() * 2 else 0.dp
        maxOf(height, verticalPadding * 2 + iconSize + labelHeight)
    }

    /**
     * These metrics in a row [rowHeight] tall. A row lower than the tile's [content] scales the padding, icon,
     * spacing and caption down by the same factor, so the caption keeps both its lines in sight.
     */
    fun fittedTo(rowHeight: Dp, content: Dp): GridTileMetrics {
        if (rowHeight >= content || content <= 0.dp) return copy(height = rowHeight)
        val scale = rowHeight / content
        return copy(height = rowHeight, iconSize = iconSize * scale, verticalPadding = verticalPadding * scale,
            labelSpacing = labelSpacing * scale, labelScale = labelScale * scale)
    }
}

private val AppGridTileSize.metrics: GridTileMetrics
    get() = when (this) {
        AppGridTileSize.COMPACT -> GridTileMetrics(118.dp, 116.dp, 44.dp, 12.dp, 8.dp)
        AppGridTileSize.STANDARD -> GridTileMetrics(148.dp, 144.dp, 60.dp, 16.dp, 12.dp)
        // The factory launcher's grid on the 1920 x 720 central display: six columns, two rows, 120 px icons and
        // 28 px captions. At 240 dpi that is an 80 dp icon and a 19 sp caption.
        AppGridTileSize.LARGE -> GridTileMetrics(168.dp, 160.dp, 80.dp, 16.dp, 12.dp, labelScale = 19f / 17f, fillsHeight = true)
    }

@Composable
internal fun AppIcon(app: LocalApp, modifier: Modifier, style: AppGridIconStyle = AppGridIconStyle.NATIVE) {
    val icon = remember(app.icon) { app.icon?.asImageBitmap() }
    if (icon != null) Image(icon, null, modifier,
        colorFilter = if (style == AppGridIconStyle.MONOCHROME) monochromeIcons else null)
    else CockpitGlyph(CockpitIcon.APPS, modifier)
}

private val monochromeIcons by lazy {
    ColorFilter.colorMatrix(ColorMatrix(requireNotNull(AppGridIconStyle.MONOCHROME.colorMatrix)))
}

@Composable
private fun PinMark(modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val pin = remember {
        Path().apply {
            moveTo(8f, 5f)
            lineTo(16f, 5f)
            lineTo(15f, 10f)
            lineTo(17f, 13f)
            lineTo(17f, 15f)
            lineTo(7f, 15f)
            lineTo(7f, 13f)
            lineTo(9f, 10f)
            close()
            moveTo(12f, 15f)
            lineTo(12f, 19.5f)
        }
    }
    Canvas(modifier.size(22.dp).background(colors.surface, CircleShape)) {
        scale(size.width / 24f, size.height / 24f, Offset.Zero) {
            drawPath(pin, colors.primary, style = Stroke(1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** A short confirmation in place of the page dots' row, which keeps the grid's rows while it shows. */
@Composable
private fun NoticeBar(message: Int, dots: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text(message), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        dots()
    }
}

/**
 * The move mode's bar in place of the page dots: the hint, the dots and the end. With an app picked, the hint names it
 * and says that a tap places it.
 */
@Composable
private fun MoveBar(move: AppMoveState, apps: List<LocalApp>, error: Int?, dots: @Composable () -> Unit, done: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val picked = move.selected?.let { id -> apps.firstOrNull { it.id == id }?.label }
    // The bar keeps the height of the page dots' row, so the pages keep their rows; Compose widens the small Done
    // pill's touch area to the usual target size.
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val message = error?.let(::text)
            ?: if (picked != null) text(R.string.apps_move_picked).format(picked) else text(R.string.apps_move_hint)
        Text(message, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium, color = if (error != null) colors.error else colors.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        dots()
        val press = rememberPressMotion()
        Surface(onClick = done, modifier = press.modifier.fillMaxHeight(), shape = CircleShape, color = colors.primary,
            contentColor = colors.onPrimary, interactionSource = press.source) {
            Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                Text(text(R.string.apps_desktop_done), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun text(id: Int) = UiText.get(id)
