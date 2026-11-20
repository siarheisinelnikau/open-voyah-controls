package org.openvoyah.controls.presentation.apps

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.apps.AppGridColumns
import org.openvoyah.controls.domain.apps.AppGridConfiguration
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.apps.AppGridIconStyle
import org.openvoyah.controls.domain.apps.AppGridShortcut
import org.openvoyah.controls.domain.apps.AppGridTileSize
import org.openvoyah.controls.platform.apps.InstallOrigin
import org.openvoyah.controls.platform.apps.LocalApp
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.rememberPressMotion
import java.util.Locale

/** The launcher root keeps apps and Controls shortcuts available on every desktop. */
@Composable
internal fun AppsScreen(
    state: AppsViewModel.State,
    entry: Long,
    desktop: AppGridDesktop,
    navigate: (ControlsRoute) -> Unit,
    listen: () -> Unit,
    system: () -> Unit,
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
    val columns = remember(grid.columns, tiles.cellWidth) {
        if (grid.columns == AppGridColumns.AUTO) GridCells.Adaptive(tiles.cellWidth)
        else FittingGridColumns(grid.columns, tiles.cellWidth)
    }
    val optionalShortcuts = remember(grid.visibleShortcuts) {
        AppGridShortcut.entries.filter { it != AppGridShortcut.QUICK_VOICE && it in grid.visibleShortcuts }
    }
    val pins = grid.pins(desktop)
    val pinnedApps = remember(pins) { pins.toSet() }
    val orderedApps = remember(state.apps, pins, grid.showSystemApps, grid.hiddenApps, grid.sortOrder, grid.manualAppOrder, desktop) {
        grid.arrange(state.apps, LocalApp::id, { it.origin == InstallOrigin.SYSTEM }, desktop, LocalApp::firstInstallTime)
    }
    val desktopPins = remember(orderedApps, pinnedApps) {
        orderedApps.filter { it.id in pinnedApps && it.launchEntry != null }
    }
    val visible = remember(orderedApps, query) {
        val filter = query.trim().lowercase(Locale.ROOT)
        orderedApps.filter { it.packageName != "org.openvoyah.controls" && it.matchesAppFilter(filter) }
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
    val keyboardVisible = handleFavoritesBack && (WindowInsets.ime.getBottom(LocalDensity.current) > 0 ||
        ViewCompat.getRootWindowInsets(LocalView.current)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
    BackHandler(handleFavoritesBack && !keyboardVisible) { openDesktop(AppGridDesktop.MAIN) }
    val clearSearch = {
        search = false
        query = ""
    }
    BackHandler(searching, onBack = clearSearch)
    val colors = MaterialTheme.colorScheme
    val smoothPaging = animateDesktopPaging && grid.smoothDesktopPaging &&
        state.selection?.let { it.entry == entry && it.desktop == desktop } == true
    DesktopWallpaperBackdrop(desktop) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
            .padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            AnimatedVisibility(alwaysShowSearch || searching, enter = fadeIn(tween(150)), exit = fadeOut(tween(100))) {
                val clearIcon: (@Composable () -> Unit)? = if (alwaysShowSearch && query.isNotEmpty()) {
                    {
                        IconButton(onClick = clearSearch) {
                            Icon(Icons.Filled.Close, contentDescription = text(R.string.apps_grid_clear_search))
                        }
                    }
                } else null
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true,
                        label = { Text(text(R.string.ui_113)) }, trailingIcon = clearIcon, shape = MaterialTheme.shapes.medium)
                    if (!alwaysShowSearch) {
                        TextButton(clearSearch, Modifier.heightIn(min = 52.dp)) { Text(text(R.string.ui_89)) }
                    }
                }
            }
            AnimatedVisibility(state.status != null, enter = fadeIn(tween(150)), exit = fadeOut(tween(100))) {
                state.status?.let { message ->
                    Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text(message), Modifier.weight(1f))
                            discardInstall?.let { discard ->
                                TextButton(discard, Modifier.heightIn(min = 48.dp)) { Text(text(R.string.apps_install_discard)) }
                            }
                        }
                    }
                }
            }
            DesktopPageTransition(entry, desktop, smoothPaging, Modifier.weight(1f)) {
                val gridState = rememberLazyGridState()
                val cards = if (searching) emptyList() else rememberDesktopInfoCards(desktop)
                val appsBeforeCards = remember(cards, visible, pinnedApps, grid.desktopCardPlacement) {
                    if (cards.isEmpty()) 0 else grid.desktopCardPlacement.appsBeforeCards(visible) { it.id in pinnedApps }
                }
                LazyVerticalGrid(columns, Modifier.fillMaxSize(),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(key = "controls-settings") { Shortcut(R.string.ui_111, CockpitIcon.SETTINGS, tiles) { navigate(ControlsRoute.SETTINGS) } }
                    if (appsBeforeCards == 0) desktopInfoCards(cards, gridState, grid.showDesktopCardOutlines, navigate)
                    if (AppGridShortcut.QUICK_VOICE in grid.visibleShortcuts) {
                        val quickVoice = AppGridShortcut.QUICK_VOICE.details
                        item(key = quickVoice.key) { Shortcut(quickVoice.label, quickVoice.icon, tiles, open = listen) }
                    }
                    val appTile: @Composable LazyGridItemScope.(LocalApp) -> Unit = { app ->
                        val pinned = app.id in pinnedApps
                        val pinnedLabel = if (pinned) {
                            text(if (desktop == AppGridDesktop.MAIN) R.string.apps_pinned else R.string.apps_desktop_pinned)
                        } else null
                        val announcement = if (pinnedLabel != null) Modifier.semantics { stateDescription = pinnedLabel } else Modifier
                        LauncherTile(app.label, tiles, { if (app.launchEntry == null) showSettings(app) else open(app) },
                            { showSettings(app) }, Modifier.animateItem(tween(150), tween(190), tween(110)).then(announcement),
                            showLabel = grid.showAppLabels) {
                            if (pinned) Box(Modifier.size(tiles.iconSize)) {
                                AppIcon(app, Modifier.fillMaxSize(), grid.iconStyle)
                                PinMark(Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-4).dp))
                            } else AppIcon(app, Modifier.size(tiles.iconSize), grid.iconStyle)
                        }
                    }
                    if (appsBeforeCards > 0) {
                        items(visible.subList(0, appsBeforeCards), key = { "app:${it.id}" }, itemContent = appTile)
                        desktopInfoCards(cards, gridState, grid.showDesktopCardOutlines, navigate)
                    }
                    items(visible.subList(appsBeforeCards, visible.size), key = { "app:${it.id}" }, itemContent = appTile)
                    optionalShortcuts.forEach { shortcut ->
                        val details = shortcut.details
                        item(key = details.key) {
                            Shortcut(details.label, details.icon, tiles, enabled = shortcut != AppGridShortcut.INSTALL_APK || installEnabled) {
                                when (shortcut) {
                                    AppGridShortcut.QUICK_VOICE -> listen()
                                    AppGridShortcut.ANDROID_SETTINGS -> system()
                                    AppGridShortcut.INSTALL_APK -> if (canInstall()) install() else allow = true
                                    else -> navigate(checkNotNull(details.route))
                                }
                            }
                        }
                    }
                    if (!alwaysShowSearch) {
                        item(key = "controls-search") { Shortcut(R.string.ui_113, CockpitIcon.APPS, tiles) { search = true } }
                    }
                    if (state.loading || visible.isEmpty()) item(key = "app-list-state") {
                        val label = when {
                            state.loading -> R.string.apps_loading
                            query.isNotBlank() -> R.string.apps_empty
                            desktop != AppGridDesktop.MAIN -> R.string.apps_desktop_empty
                            else -> R.string.apps_grid_empty_main
                        }
                        Text(text(label),
                            Modifier.padding(18.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (allow) AlertDialog(onDismissRequest = { allow = false },
        title = { Text(text(R.string.install_local_apk)) }, text = { Text(text(R.string.allow_apk_install)) },
        confirmButton = { Button({ allow = false; allowInstalls() }, Modifier.heightIn(min = 50.dp)) { Text(text(R.string.ui_61)) } },
        dismissButton = { TextButton({ allow = false }, Modifier.heightIn(min = 50.dp)) { Text(text(R.string.ui_89)) } })
    if (showPicker) {
        FavoriteAppsPicker(desktop, grid, state, close = { pickerDesktop = null }, changePin = changeDesktopPin)
    }
    if (showReorder) {
        val edit = state.gridEdit?.takeIf { it.desktop == desktop }
        PinnedAppsOrderDialog(desktopPins, grid.iconStyle, enabled = state.gridEdit?.working != true,
            failed = edit?.error != null, close = { reorderDesktop = null }, swap = swapDesktopPins)
    }
}

@Composable
private fun Shortcut(label: Int, icon: CockpitIcon, tiles: GridTileMetrics, enabled: Boolean = true, open: () -> Unit) {
    LauncherTile(text(label), tiles, open, enabled = enabled) {
        CockpitGlyph(icon, Modifier.size(tiles.iconSize))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LauncherTile(label: String, tiles: GridTileMetrics, open: () -> Unit, settings: (() -> Unit)? = null,
                         modifier: Modifier = Modifier, enabled: Boolean = true, showLabel: Boolean = true,
                         icon: @Composable () -> Unit) {
    val press = rememberPressMotion()
    val accessibleName = if (showLabel) Modifier else Modifier.semantics { contentDescription = label }
    Column(modifier.then(press.modifier).fillMaxWidth().heightIn(min = tiles.height)
            .combinedClickable(enabled = enabled, interactionSource = press.source,
                indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button,
                onLongClick = settings, onLongClickLabel = settings?.let { text(R.string.app_settings_title) }, onClick = open)
            .then(accessibleName)
            .padding(horizontal = 10.dp, vertical = tiles.verticalPadding), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(tiles.labelSpacing, Alignment.CenterVertically)) {
            icon()
            if (showLabel) {
                Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f))
            }
    }
}

private data class GridTileMetrics(val cellWidth: Dp, val height: Dp, val iconSize: Dp, val verticalPadding: Dp, val labelSpacing: Dp)

private data class FittingGridColumns(val columns: AppGridColumns, val minimumTileWidth: Dp) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val count = columns.fit(availableSize, minimumTileWidth.roundToPx().coerceAtLeast(1), spacing)
        return with(GridCells.Fixed(count)) { calculateCrossAxisCellSizes(availableSize, spacing) }
    }
}

private val AppGridTileSize.metrics: GridTileMetrics
    get() = when (this) {
        AppGridTileSize.COMPACT -> GridTileMetrics(118.dp, 116.dp, 44.dp, 12.dp, 8.dp)
        AppGridTileSize.STANDARD -> GridTileMetrics(148.dp, 144.dp, 60.dp, 16.dp, 12.dp)
        AppGridTileSize.LARGE -> GridTileMetrics(188.dp, 164.dp, 72.dp, 16.dp, 12.dp)
    }

@Composable
internal fun AppIcon(app: LocalApp, modifier: Modifier, style: AppGridIconStyle = AppGridIconStyle.NATIVE) {
    val icon = remember(app.icon) { app.icon?.asImageBitmap() }
    if (icon != null) Image(icon, null, modifier,
        colorFilter = if (style == AppGridIconStyle.MONOCHROME) monochromeIcons else null)
    else CockpitGlyph(CockpitIcon.APPS, modifier)
}

private val monochromeIcons by lazy {
    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
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

private fun text(id: Int) = UiText.get(id)
