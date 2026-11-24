package org.openvoyah.controls.presentation.apps

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.apps.AppGridPreferences
import org.openvoyah.controls.domain.apps.AppGridColumns
import org.openvoyah.controls.domain.apps.AppGridConfiguration
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.apps.AppGridDesktopNames
import org.openvoyah.controls.data.frida.FridaBridge
import org.openvoyah.controls.domain.apps.AppGridIconStyle
import org.openvoyah.controls.domain.apps.AppGridManualOrderFull
import org.openvoyah.controls.domain.apps.AppGridOrderApp
import org.openvoyah.controls.domain.apps.AppGridShortcut
import org.openvoyah.controls.domain.apps.AppGridSortOrder
import org.openvoyah.controls.domain.apps.AppGridTileSize
import org.openvoyah.controls.domain.apps.DesktopCardPlacement
import org.openvoyah.controls.domain.apps.FavoritesBackBehavior
import org.openvoyah.controls.platform.apps.AppsCatalog
import org.openvoyah.controls.platform.apps.InstalledAppPermissions
import org.openvoyah.controls.platform.apps.LocalApp
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitOptionDialog
import org.openvoyah.controls.presentation.theme.CockpitReveal
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.theme.CockpitTextAction
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout

internal class AppGridSettingsViewModel(
    private val preferences: AppGridPreferences,
    private val catalog: AppsCatalog,
    /** Sends the panel configuration to the launcher agent, which draws its icons in the same style. */
    private val publishPanel: () -> Unit = {},
) : ViewModel() {
    data class State(
        val settings: Result<AppGridConfiguration>? = null,
        val saving: Boolean = false,
        val saveError: Int? = null,
    )

    private data class Save(val working: Boolean = false, val error: Int? = null)

    private val saveState = MutableStateFlow(Save())
    val launcherApps = flow {
        emit(Result.success(catalog.load(launcherOnly = true)))
    }.catch { error ->
        if (error !is RuntimeException || error is CancellationException) throw error
        emit(Result.failure(error))
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), null)

    val state = combine(preferences.observeSettings(), saveState) { settings, save ->
        State(settings, save.working, save.error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), State())

    fun setTileSize(size: AppGridTileSize) = save { preferences.setTileSize(size) }

    fun setColumns(columns: AppGridColumns) = save { preferences.setColumns(columns) }

    fun setShortcutVisible(shortcut: AppGridShortcut, show: Boolean) = save { preferences.setShortcutVisible(shortcut, show) }

    fun setDesktopNames(first: String, second: String) = save { preferences.setDesktopNames(first, second) }

    fun setHideByDefault(hide: Boolean) = save { preferences.setHideByDefault(hide) }

    fun setShowSearchField(show: Boolean) = save { preferences.setShowSearchField(show) }

    fun setAppHidden(app: LocalApp, hidden: Boolean) = save { preferences.setAppHidden(app.id, hidden, app.hiddenByDefault) }

    fun resetHiddenApps() = save { preferences.resetHiddenApps() }

    fun setIconStyle(style: AppGridIconStyle) = save {
        preferences.setIconStyle(style)
        publishPanel()
    }

    fun setShowAppLabels(show: Boolean) = save { preferences.setShowAppLabels(show) }

    fun setSortOrder(order: AppGridSortOrder) = save { preferences.setSortOrder(order) }

    fun moveManualApp(catalog: List<AppGridOrderApp>, app: String, neighbor: String, earlier: Boolean) = save {
        preferences.moveManualApp(catalog, app, neighbor, earlier)
    }

    fun resetManualAppOrder() = save { preferences.resetManualAppOrder() }

    fun closeManualOrder() {
        saveState.update { if (it.error == R.string.apps_grid_manual_order_full) it.copy(error = null) else it }
    }

    fun setDesktopsEnabled(enabled: Boolean) = save { preferences.setDesktopsEnabled(enabled) }

    fun setDefaultDesktop(desktop: AppGridDesktop) = save { preferences.setDefaultDesktop(desktop) }
    fun setDesktopCardOutlines(show: Boolean) = save { preferences.setDesktopCardOutlines(show) }
    fun setSmoothDesktopPaging(enabled: Boolean) = save { preferences.setSmoothDesktopPaging(enabled) }
    fun setDesktopCardPlacement(placement: DesktopCardPlacement) = save { preferences.setDesktopCardPlacement(placement) }
    fun setFavoritesBackBehavior(behavior: FavoritesBackBehavior) = save { preferences.setFavoritesBackBehavior(behavior) }

    private fun save(write: suspend () -> Unit) {
        val previous = saveState.value
        if (previous.working || !saveState.compareAndSet(previous, Save(working = true))) return
        viewModelScope.launch {
            saveState.value = try {
                write()
                Save()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: AppGridManualOrderFull) {
                Save(error = R.string.apps_grid_manual_order_full)
            } catch (_: Exception) {
                Save(error = R.string.apps_grid_settings_save_failed)
            }
        }
    }
}

/** The parts of the app grid settings: the grid itself, the desktops and their cards. */
internal enum class AppGridPart { GRID, DESKTOPS, CARDS }

private enum class GridPicker {
    TILE_SIZE, COLUMNS, ICON_STYLE, SORT_ORDER, MANUAL_ORDER, SHORTCUTS, DEFAULT_DESKTOP, DESKTOP_NAMES, CARD_PLACEMENT,
    FAVORITES_BACK, HIDDEN_APPS
}

@Composable
internal fun AppGridSettingsSection(part: AppGridPart) {
    val activity = checkNotNull(LocalActivity.current as? ComponentActivity) { "App grid settings require a ComponentActivity host" }
    val model = remember(activity) {
        val app = activity.applicationContext
        ViewModelProvider(activity, viewModelFactory {
            initializer {
                AppGridSettingsViewModel(AppGridPreferences(app), AppsCatalog(app, InstalledAppPermissions(app))) {
                    FridaBridge(app).publishAsync { }
                }
            }
        })[AppGridSettingsViewModel::class.java]
    }
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateAsState()
    val state by model.state.collectAsStateWithLifecycle(lifecycleOwner = owner)
    val current = state.settings?.getOrNull()
    val editable = current != null && !state.saving
    val layout = LocalCockpitLayout.current
    var picker by remember { mutableStateOf<GridPicker?>(null) }
    fun active(): Boolean = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    val desktops = current?.desktops
    val desktopNames = current?.desktopNames ?: AppGridDesktopNames()
    val desktopLabel: (AppGridDesktop) -> String = { it.desktopLabel(desktopNames, includeSlot = true) }
    val failure = when {
        state.settings?.isFailure == true -> R.string.apps_grid_settings_read_failed
        picker != GridPicker.MANUAL_ORDER -> state.saveError?.takeUnless { it == R.string.apps_grid_manual_order_full }
        else -> null
    }
    when (part) {
        AppGridPart.GRID -> CockpitSection(text(R.string.apps_grid_settings), CockpitIcon.APPS) {
            CockpitChoice(R.string.apps_grid_tile_size, current?.tileSize?.label(), enabled = editable) {
                if (active()) picker = GridPicker.TILE_SIZE
            }
            CockpitChoice(R.string.apps_grid_columns, current?.columns?.label(), enabled = editable) {
                if (active()) picker = GridPicker.COLUMNS
            }
            CockpitChoice(R.string.apps_grid_icon_style, current?.iconStyle?.label(), enabled = editable) {
                if (active()) picker = GridPicker.ICON_STYLE
            }
            CockpitSwitch(R.string.apps_grid_show_labels, current?.showAppLabels ?: true, enabled = editable) { show ->
                if (active()) model.setShowAppLabels(show)
            }
            CockpitChoice(R.string.apps_grid_sort_order, current?.sortOrder?.label(), enabled = editable) {
                if (active()) picker = GridPicker.SORT_ORDER
            }
            CockpitReveal(current?.sortOrder == AppGridSortOrder.MANUAL) {
                CockpitChoice(R.string.apps_grid_edit_manual_order, enabled = editable) {
                    if (active()) picker = GridPicker.MANUAL_ORDER
                }
            }
            CockpitChoice(R.string.apps_grid_shortcuts, enabled = editable) {
                if (active()) picker = GridPicker.SHORTCUTS
            }
            CockpitChoice(R.string.apps_grid_hidden_apps, enabled = editable) {
                if (active()) picker = GridPicker.HIDDEN_APPS
            }
            CockpitSwitch(R.string.apps_grid_show_search_field, current?.showSearchField ?: false, enabled = editable) { show ->
                if (active()) model.setShowSearchField(show)
            }
            CockpitSwitch(R.string.apps_grid_hide_by_default, current?.hideByDefault ?: true, enabled = editable) { hide ->
                if (active()) model.setHideByDefault(hide)
            }
            if (failure != null) CockpitFeedback(text(failure), error = true)
        }
        AppGridPart.DESKTOPS -> CockpitSection(text(R.string.settings_part_desktops), CockpitIcon.HOME) {
            CockpitSwitch(R.string.apps_desktop_enable, desktops?.enabled == true, enabled = editable) { enabled ->
                if (active()) model.setDesktopsEnabled(enabled)
            }
            CockpitReveal(current != null && current.desktops.enabled) {
                // The rows of one reveal need their own column; the gap is the one between the rows of a CockpitSection.
                if (current != null) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(layout.spacing(11.dp))) {
                    val defaultLabel = current.desktops.defaultDesktop.desktopLabel(current.desktopNames)
                    CockpitChoice(R.string.apps_desktop_default, defaultLabel, enabled = editable) {
                        if (active()) picker = GridPicker.DEFAULT_DESKTOP
                    }
                    CockpitChoice(R.string.apps_grid_desktop_names, enabled = editable) {
                        if (active()) picker = GridPicker.DESKTOP_NAMES
                    }
                    CockpitChoice(R.string.apps_grid_favorites_back, current.favoritesBackBehavior.label(), enabled = editable) {
                        if (active()) picker = GridPicker.FAVORITES_BACK
                    }
                    CockpitSwitch(R.string.apps_grid_smooth_paging, current.smoothDesktopPaging, enabled = editable) { smooth ->
                        if (active()) model.setSmoothDesktopPaging(smooth)
                    }
                }
            }
            DesktopWallpaperSettings(extraDesktops = desktops?.enabled == true, desktopName = desktopLabel)
            DesktopLayoutsSettings(editable)
            if (failure != null) CockpitFeedback(text(failure), error = true)
        }
        AppGridPart.CARDS -> CockpitSection(text(R.string.settings_cards_heading), CockpitIcon.APPS) {
            DesktopInfoSettings(extraDesktops = desktops?.enabled == true, desktopName = desktopLabel, editable = current != null)
            CockpitChoice(R.string.apps_grid_card_placement, current?.desktopCardPlacement?.label(), enabled = editable) {
                if (active()) picker = GridPicker.CARD_PLACEMENT
            }
            CockpitSwitch(R.string.apps_grid_card_outlines, current?.showDesktopCardOutlines ?: true, enabled = editable) { show ->
                if (active()) model.setDesktopCardOutlines(show)
            }
            if (failure != null) CockpitFeedback(text(failure), error = true)
        }
    }

    if (current != null && lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
        when (picker) {
            GridPicker.TILE_SIZE -> GridOptionDialog(R.string.apps_grid_tile_size, AppGridTileSize.entries,
                current.tileSize, AppGridTileSize::label, close = { picker = null }) { size ->
                if (active()) model.setTileSize(size)
            }
            GridPicker.COLUMNS -> GridOptionDialog(R.string.apps_grid_columns, AppGridColumns.entries,
                current.columns, AppGridColumns::label, close = { picker = null }, note = R.string.apps_grid_columns_fit) { columns ->
                if (active()) model.setColumns(columns)
            }
            GridPicker.ICON_STYLE -> GridOptionDialog(R.string.apps_grid_icon_style, AppGridIconStyle.entries,
                current.iconStyle, AppGridIconStyle::label, close = { picker = null }) { style ->
                if (active()) model.setIconStyle(style)
            }
            GridPicker.SORT_ORDER -> GridOptionDialog(R.string.apps_grid_sort_order, AppGridSortOrder.entries,
                current.sortOrder, AppGridSortOrder::label, close = { picker = null }) { order ->
                if (active()) model.setSortOrder(order)
            }
            GridPicker.MANUAL_ORDER -> if (current.sortOrder == AppGridSortOrder.MANUAL) {
                ManualAppsOrderDialog(model, current, editable, state.saveError, canEdit = ::active, close = { picker = null })
            }
            GridPicker.SHORTCUTS -> ShortcutVisibilityDialog(current.visibleShortcuts, editable, state.saveError != null,
                close = { picker = null }) { shortcut, show ->
                if (active()) model.setShortcutVisible(shortcut, show)
            }
            GridPicker.HIDDEN_APPS -> HiddenAppsDialog(model, current, editable, state.saveError == R.string.apps_grid_settings_save_failed,
                close = { picker = null }, setHidden = { app, hidden -> if (active()) model.setAppHidden(app, hidden) },
                reset = { if (active()) model.resetHiddenApps() })
            GridPicker.DEFAULT_DESKTOP -> if (current.desktops.enabled) {
                GridOptionDialog(R.string.apps_desktop_default, AppGridDesktop.entries, current.desktops.defaultDesktop,
                    label = { it.desktopLabel(current.desktopNames, includeSlot = true) }, close = { picker = null }) { desktop ->
                    if (active()) model.setDefaultDesktop(desktop)
                }
            }
            GridPicker.DESKTOP_NAMES -> if (current.desktops.enabled) {
                DesktopNamesDialog(current.desktopNames, close = { picker = null }) { first, second ->
                    if (active()) model.setDesktopNames(first, second)
                }
            }
            GridPicker.CARD_PLACEMENT -> GridOptionDialog(R.string.apps_grid_card_placement, DesktopCardPlacement.entries,
                current.desktopCardPlacement, DesktopCardPlacement::label, close = { picker = null }) { placement ->
                if (active()) model.setDesktopCardPlacement(placement)
            }
            GridPicker.FAVORITES_BACK -> if (current.desktops.enabled) {
                GridOptionDialog(R.string.apps_grid_favorites_back, FavoritesBackBehavior.entries,
                    current.favoritesBackBehavior, FavoritesBackBehavior::label, close = { picker = null }) { behavior ->
                    if (active()) model.setFavoritesBackBehavior(behavior)
                }
            }
            null -> Unit
        }
    }
}

@Composable
private fun <T> GridOptionDialog(
    title: Int,
    options: List<T>,
    current: T,
    label: (T) -> String,
    close: () -> Unit,
    note: Int? = null,
    choose: (T) -> Unit,
) {
    CockpitOptionDialog(text(title), options, current, label, onSelect = choose, onDismiss = close, description = note?.let(::text))
}

@Composable
private fun ShortcutVisibilityDialog(
    visible: Set<AppGridShortcut>,
    editable: Boolean,
    saveFailed: Boolean,
    close: () -> Unit,
    change: (AppGridShortcut, Boolean) -> Unit,
) {
    CockpitAlertDialog(
        onDismissRequest = close,
        title = { Text(text(R.string.apps_grid_shortcuts)) },
        confirmButton = { CockpitTextAction(text(R.string.apps_desktop_done), close) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text(R.string.apps_grid_always_available), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (saveFailed) CockpitFeedback(text(R.string.apps_grid_settings_save_failed), error = true)
                LazyColumn(Modifier.heightIn(max = 244.dp)) {
                    items(AppGridShortcut.entries, key = AppGridShortcut::storedValue) { shortcut ->
                        CockpitSwitch(text(shortcut.details.label), shortcut in visible, enabled = editable,
                            guideId = R.string.apps_grid_shortcuts) { show ->
                            change(shortcut, show)
                        }
                    }
                }
            }
        },
    )
}

private fun AppGridColumns.label(): String = requested?.toString() ?: text(R.string.apps_grid_columns_auto)

private fun AppGridTileSize.label(): String = text(when (this) {
    AppGridTileSize.COMPACT -> R.string.apps_grid_size_compact
    AppGridTileSize.STANDARD -> R.string.apps_grid_size_standard
    AppGridTileSize.LARGE -> R.string.apps_grid_size_large
})

private fun AppGridIconStyle.label(): String = text(when (this) {
    AppGridIconStyle.NATIVE -> R.string.apps_grid_icon_native
    AppGridIconStyle.MONOCHROME -> R.string.apps_grid_icon_monochrome
})

private fun AppGridSortOrder.label(): String = text(when (this) {
    AppGridSortOrder.NAME -> R.string.apps_grid_sort_name
    AppGridSortOrder.RECENTLY_INSTALLED -> R.string.apps_grid_sort_recent
    AppGridSortOrder.MANUAL -> R.string.apps_grid_sort_manual
})

private fun DesktopCardPlacement.label(): String = text(when (this) {
    DesktopCardPlacement.BEFORE_APPS -> R.string.apps_grid_cards_first
    DesktopCardPlacement.AFTER_PINNED_APPS -> R.string.apps_grid_pins_first
})

private fun FavoritesBackBehavior.label(): String = text(when (this) {
    FavoritesBackBehavior.SYSTEM -> R.string.apps_grid_favorites_back_system
    FavoritesBackBehavior.MAIN_DESKTOP -> R.string.apps_grid_favorites_back_main
})

private fun text(id: Int): String = UiText.get(id)
