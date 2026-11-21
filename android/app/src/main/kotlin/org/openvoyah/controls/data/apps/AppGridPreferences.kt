package org.openvoyah.controls.data.apps

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.domain.apps.AppGridColumns
import org.openvoyah.controls.domain.apps.AppGridConfiguration
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.apps.AppGridDesktopNames
import org.openvoyah.controls.domain.apps.AppGridDesktops
import org.openvoyah.controls.domain.apps.AppGridIconStyle
import org.openvoyah.controls.domain.apps.AppGridManualOrder
import org.openvoyah.controls.domain.apps.AppGridOrderApp
import org.openvoyah.controls.domain.apps.AppGridPins
import org.openvoyah.controls.domain.apps.AppGridShortcut
import org.openvoyah.controls.domain.apps.AppGridSortOrder
import org.openvoyah.controls.domain.apps.AppGridTileSize
import org.openvoyah.controls.domain.apps.DesktopCardPlacement
import org.openvoyah.controls.domain.apps.FavoritesBackBehavior

internal class AppGridPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun observeSettings(): Flow<Result<AppGridConfiguration>> = callbackFlow<Unit> {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in OBSERVED_KEYS) trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { runCatching { synchronized(lock) { read() } } }.distinctUntilChanged().flowOn(Dispatchers.IO)

    suspend fun changePin(component: String, pinned: Boolean, desktop: AppGridDesktop = AppGridDesktop.MAIN) =
        updatePins(desktop) { AppGridPins.change(it, component, pinned) }

    suspend fun swapPins(source: String, target: String, desktop: AppGridDesktop) =
        updatePins(desktop) { AppGridPins.swap(it, source, target) }

    private suspend fun updatePins(desktop: AppGridDesktop, change: (List<String>) -> List<String>) = update { current ->
        check(desktop == AppGridDesktop.MAIN || current.desktops.enabled)
        val previous = current.pins(desktop)
        val next = change(previous)
        when {
            next == previous -> null
            desktop == AppGridDesktop.MAIN -> ArchivedSettings(pinnedApps = next)
            else -> ArchivedSettings(desktops = current.desktops.withPins(desktop, next))
        }
    }

    suspend fun setTileSize(tileSize: AppGridTileSize) = update { current ->
        if (current.tileSize != tileSize) ArchivedSettings(tileSize = tileSize) else null
    }

    suspend fun setShowSystemApps(show: Boolean) = update { current ->
        if (current.showSystemApps != show) ArchivedSettings(showSystemApps = show) else null
    }

    suspend fun setShowSearchField(show: Boolean) = update { current ->
        if (current.showSearchField != show) ArchivedSettings(showSearchField = show) else null
    }

    suspend fun setAppHidden(component: String, hidden: Boolean) = update { current ->
        val next = current.hiddenAppsWith(component, hidden)
        if (next != current.hiddenApps) ArchivedSettings(hiddenApps = next) else null
    }

    suspend fun setIconStyle(style: AppGridIconStyle) = update { current ->
        if (current.iconStyle != style) ArchivedSettings(iconStyle = style) else null
    }

    suspend fun setShowAppLabels(show: Boolean) = update { current ->
        if (current.showAppLabels != show) ArchivedSettings(showAppLabels = show) else null
    }

    suspend fun setSortOrder(order: AppGridSortOrder) = update { current ->
        if (current.sortOrder != order) ArchivedSettings(sortOrder = order) else null
    }

    suspend fun moveManualApp(catalog: List<AppGridOrderApp>, source: String, neighbor: String, earlier: Boolean) = update { current ->
        if (current.sortOrder != AppGridSortOrder.MANUAL) return@update null
        val visible = current.editableManualApps(catalog, AppGridOrderApp::component, AppGridOrderApp::system)
        val order = AppGridManualOrder.move(current.manualAppOrder, visible.map(AppGridOrderApp::component), source, neighbor, earlier)
        if (order != current.manualAppOrder) ArchivedSettings(manualAppOrder = order) else null
    }

    suspend fun resetManualAppOrder() = update { current ->
        if (current.manualAppOrder.isNotEmpty()) ArchivedSettings(manualAppOrder = emptyList()) else null
    }

    suspend fun setColumns(columns: AppGridColumns) = update { current ->
        if (current.columns != columns) ArchivedSettings(columns = columns) else null
    }

    suspend fun setShortcutVisible(shortcut: AppGridShortcut, show: Boolean) = update { current ->
        val previous = current.visibleShortcuts
        val next = if (show) previous + shortcut else previous - shortcut
        if (next != previous) ArchivedSettings(visibleShortcuts = next) else null
    }

    suspend fun setDesktopNames(first: String, second: String) = update { current ->
        val names = AppGridDesktopNames(AppGridDesktopNames.normalize(first), AppGridDesktopNames.normalize(second))
        if (names != current.desktopNames) ArchivedSettings(desktopNames = ArchivedDesktopNames(names.first, names.second)) else null
    }

    suspend fun setDesktopsEnabled(enabled: Boolean) = updateDesktops { it.copy(enabled = enabled) }

    suspend fun setDefaultDesktop(desktop: AppGridDesktop) = updateDesktops { it.copy(defaultDesktop = desktop) }

    suspend fun setDesktopCardOutlines(show: Boolean) = update { current ->
        if (current.showDesktopCardOutlines != show) ArchivedSettings(showDesktopCardOutlines = show) else null
    }

    suspend fun setSmoothDesktopPaging(enabled: Boolean) = update { current ->
        if (current.smoothDesktopPaging != enabled) ArchivedSettings(smoothDesktopPaging = enabled) else null
    }

    suspend fun setDesktopCardPlacement(placement: DesktopCardPlacement) = update { current ->
        if (current.desktopCardPlacement != placement) ArchivedSettings(desktopCardPlacement = placement) else null
    }

    suspend fun setFavoritesBackBehavior(behavior: FavoritesBackBehavior) = update { current ->
        if (current.favoritesBackBehavior != behavior) ArchivedSettings(favoritesBackBehavior = behavior) else null
    }

    private suspend fun updateDesktops(change: (AppGridDesktops) -> AppGridDesktops) = update { current ->
        val next = change(current.desktops)
        if (next != current.desktops) ArchivedSettings(desktops = next) else null
    }

    private suspend fun update(change: (AppGridConfiguration) -> ArchivedSettings?): Unit = withContext(Dispatchers.IO) {
        synchronized(lock) { change(read())?.let(::write) }
    }

    fun exportSettings(): JSONObject = encodeComplete(read())

    fun restoreSettings(data: JSONObject) {
        val settings = decode(data)
        synchronized(lock) { write(settings) }
    }

    private fun read(): AppGridConfiguration {
        val values = preferences.all
        return AppGridConfiguration(
            pinnedApps = decodePins(JSONArray(values[PINNED_APPS] as String? ?: "[]")),
            tileSize = (values[TILE_SIZE] as String?)?.let { AppGridTileSize.parse(it) } ?: AppGridTileSize.DEFAULT,
            showSystemApps = values[SHOW_SYSTEM_APPS] as Boolean? ?: true,
            desktops = (values[DESKTOPS] as String?)?.let { decodeDesktops(JSONObject(it)) } ?: AppGridDesktops(),
            iconStyle = (values[ICON_STYLE] as String?)?.let { AppGridIconStyle.parse(it) } ?: AppGridIconStyle.NATIVE,
            showAppLabels = values[SHOW_APP_LABELS] as Boolean? ?: true,
            sortOrder = (values[SORT_ORDER] as String?)?.let { AppGridSortOrder.parse(it) } ?: AppGridSortOrder.NAME,
            columns = (values[COLUMNS] as String?)?.let { AppGridColumns.parse(it) } ?: AppGridColumns.AUTO,
            visibleShortcuts = (values[VISIBLE_SHORTCUTS] as String?)?.let { decodeShortcuts(JSONArray(it)) } ?: AppGridShortcut.defaults,
            desktopNames = AppGridDesktopNames(values[FAVORITE_ONE_NAME] as String? ?: "", values[FAVORITE_TWO_NAME] as String? ?: ""),
            showDesktopCardOutlines = values[DESKTOP_CARD_OUTLINES] as Boolean? ?: true,
            smoothDesktopPaging = values[SMOOTH_DESKTOP_PAGING] as Boolean? ?: false,
            desktopCardPlacement = (values[DESKTOP_CARD_PLACEMENT] as String?)?.let(DesktopCardPlacement::parse)
                ?: DesktopCardPlacement.BEFORE_APPS,
            favoritesBackBehavior = (values[FAVORITES_BACK_BEHAVIOR] as String?)?.let(FavoritesBackBehavior::parse)
                ?: FavoritesBackBehavior.SYSTEM,
            manualAppOrder = decodeAppIds(JSONArray(values[MANUAL_APP_ORDER] as String? ?: "[]"), AppGridManualOrder.MAX_APPS),
            hiddenApps = decodeHiddenApps(JSONArray(values[HIDDEN_APPS] as String? ?: "[]")),
            showSearchField = values[SHOW_SEARCH_FIELD] as Boolean? ?: false,
        )
    }

    private fun write(settings: ArchivedSettings) {
        val previous = preferences.all
        val editor = preferences.edit()
        val rollback = preferences.edit()
        var changed = false

        fun stage(key: String, value: String?) {
            if (value == null || value == previous[key]) return
            changed = true
            editor.putString(key, value)
            rollback.putString(key, previous[key] as String?)
        }

        fun stage(key: String, value: Boolean?) {
            if (value == null || value == previous[key]) return
            changed = true
            editor.putBoolean(key, value)
            val old = previous[key] as Boolean?
            if (old == null) rollback.remove(key) else rollback.putBoolean(key, old)
        }

        stage(PINNED_APPS, settings.pinnedApps?.let { JSONArray(it).toString() })
        stage(TILE_SIZE, settings.tileSize?.storedValue)
        stage(SHOW_SYSTEM_APPS, settings.showSystemApps)
        stage(DESKTOPS, settings.desktops?.let { encodeDesktops(it).toString() })
        stage(ICON_STYLE, settings.iconStyle?.storedValue)
        stage(SHOW_APP_LABELS, settings.showAppLabels)
        stage(SORT_ORDER, settings.sortOrder?.storedValue)
        stage(COLUMNS, settings.columns?.storedValue)
        stage(VISIBLE_SHORTCUTS, settings.visibleShortcuts?.let { encodeShortcuts(it).toString() })
        stage(FAVORITE_ONE_NAME, settings.desktopNames?.first)
        stage(FAVORITE_TWO_NAME, settings.desktopNames?.second)
        stage(DESKTOP_CARD_OUTLINES, settings.showDesktopCardOutlines)
        stage(SMOOTH_DESKTOP_PAGING, settings.smoothDesktopPaging)
        stage(DESKTOP_CARD_PLACEMENT, settings.desktopCardPlacement?.storedValue)
        stage(FAVORITES_BACK_BEHAVIOR, settings.favoritesBackBehavior?.storedValue)
        stage(MANUAL_APP_ORDER, settings.manualAppOrder?.let { JSONArray(it).toString() })
        stage(HIDDEN_APPS, settings.hiddenApps?.let { JSONArray(it).toString() })
        stage(SHOW_SEARCH_FIELD, settings.showSearchField)
        if (changed && !editor.commit()) {
            // A failed disk write may still have updated the in-memory preferences.
            rollback.commit()
            error("App grid preferences could not be written")
        }
    }

    private data class ArchivedSettings(
        val pinnedApps: List<String>? = null,
        val tileSize: AppGridTileSize? = null,
        val showSystemApps: Boolean? = null,
        val desktops: AppGridDesktops? = null,
        val iconStyle: AppGridIconStyle? = null,
        val showAppLabels: Boolean? = null,
        val sortOrder: AppGridSortOrder? = null,
        val columns: AppGridColumns? = null,
        val visibleShortcuts: Set<AppGridShortcut>? = null,
        val desktopNames: ArchivedDesktopNames? = null,
        val showDesktopCardOutlines: Boolean? = null,
        val smoothDesktopPaging: Boolean? = null,
        val desktopCardPlacement: DesktopCardPlacement? = null,
        val favoritesBackBehavior: FavoritesBackBehavior? = null,
        val manualAppOrder: List<String>? = null,
        val hiddenApps: Set<String>? = null,
        val showSearchField: Boolean? = null,
    )

    private data class ArchivedDesktopNames(val first: String?, val second: String?)

    companion object {
        const val STORE = "app_grid"
        private const val PINNED_APPS = "pinned_apps"
        private const val TILE_SIZE = "tile_size"
        private const val SHOW_SYSTEM_APPS = "show_system_apps"
        private const val DESKTOPS = "desktops"
        private const val ICON_STYLE = "icon_style"
        private const val SHOW_APP_LABELS = "show_app_labels"
        private const val SORT_ORDER = "sort_order"
        private const val COLUMNS = "columns"
        private const val VISIBLE_SHORTCUTS = "visible_shortcuts"
        private const val FAVORITE_ONE_NAME = "favorite_one_name"
        private const val FAVORITE_TWO_NAME = "favorite_two_name"
        private const val DESKTOP_CARD_OUTLINES = "desktop_card_outlines"
        private const val SMOOTH_DESKTOP_PAGING = "smooth_desktop_paging"
        private const val DESKTOP_CARD_PLACEMENT = "desktop_card_placement"
        private const val FAVORITES_BACK_BEHAVIOR = "favorites_back_behavior"
        private const val MANUAL_APP_ORDER = "manual_app_order"
        private const val HIDDEN_APPS = "hidden_apps"
        private const val SHOW_SEARCH_FIELD = "show_search_field"
        private const val PINNED_FIELD = "pinnedApps"
        private const val TILE_SIZE_FIELD = "tileSize"
        private const val SHOW_SYSTEM_APPS_FIELD = "showSystemApps"
        private const val ICON_STYLE_FIELD = "iconStyle"
        private const val SHOW_APP_LABELS_FIELD = "showAppLabels"
        private const val SORT_ORDER_FIELD = "sortOrder"
        private const val VISIBLE_SHORTCUTS_FIELD = "visibleShortcuts"
        private const val DESKTOP_NAMES_FIELD = "desktopNames"
        private const val DESKTOP_CARD_OUTLINES_FIELD = "showDesktopCardOutlines"
        private const val SMOOTH_DESKTOP_PAGING_FIELD = "smoothDesktopPaging"
        private const val DESKTOP_CARD_PLACEMENT_FIELD = "desktopCardPlacement"
        private const val FAVORITES_BACK_BEHAVIOR_FIELD = "favoritesBackBehavior"
        private const val MANUAL_APP_ORDER_FIELD = "manualAppOrder"
        private const val HIDDEN_APPS_FIELD = "hiddenApps"
        private const val SHOW_SEARCH_FIELD_NAME = "showSearchField"
        private val OBSERVED_KEYS = setOf(PINNED_APPS, TILE_SIZE, SHOW_SYSTEM_APPS, DESKTOPS, ICON_STYLE, SHOW_APP_LABELS,
            SORT_ORDER, COLUMNS, VISIBLE_SHORTCUTS, FAVORITE_ONE_NAME, FAVORITE_TWO_NAME, DESKTOP_CARD_OUTLINES,
            SMOOTH_DESKTOP_PAGING, DESKTOP_CARD_PLACEMENT, FAVORITES_BACK_BEHAVIOR, MANUAL_APP_ORDER, HIDDEN_APPS, SHOW_SEARCH_FIELD)
        private val lock = DesktopLayoutStores.WRITE_LOCK

        @JvmStatic
        fun validateSettings(data: JSONObject): JSONObject = encode(decode(data))

        @JvmStatic
        fun validateLayout(data: JSONObject): JSONObject {
            val validated = validateSettings(data)
            val defaults = encodeComplete(AppGridConfiguration())
            // Older layouts preserve choices introduced after they were saved.
            if (!data.has(MANUAL_APP_ORDER_FIELD)) defaults.remove(MANUAL_APP_ORDER_FIELD)
            if (!data.has(HIDDEN_APPS_FIELD)) defaults.remove(HIDDEN_APPS_FIELD)
            if (!data.has(SHOW_SEARCH_FIELD_NAME)) defaults.remove(SHOW_SEARCH_FIELD_NAME)
            val names = validated.optJSONObject(DESKTOP_NAMES_FIELD)
            if (names != null) {
                val defaultNames = defaults.getJSONObject(DESKTOP_NAMES_FIELD)
                for (key in names.keys()) defaultNames.put(key, names.get(key))
                validated.put(DESKTOP_NAMES_FIELD, defaultNames)
            }
            for (key in validated.keys()) defaults.put(key, validated.get(key))
            return defaults
        }

        private fun encodeComplete(current: AppGridConfiguration): JSONObject = encode(ArchivedSettings(
            pinnedApps = current.pinnedApps,
            tileSize = current.tileSize,
            showSystemApps = current.showSystemApps,
            desktops = current.desktops,
            iconStyle = current.iconStyle,
            showAppLabels = current.showAppLabels,
            sortOrder = current.sortOrder,
            columns = current.columns,
            visibleShortcuts = current.visibleShortcuts,
            desktopNames = ArchivedDesktopNames(current.desktopNames.first, current.desktopNames.second),
            showDesktopCardOutlines = current.showDesktopCardOutlines,
            smoothDesktopPaging = current.smoothDesktopPaging,
            desktopCardPlacement = current.desktopCardPlacement,
            favoritesBackBehavior = current.favoritesBackBehavior,
            manualAppOrder = current.manualAppOrder,
            hiddenApps = current.hiddenApps,
            showSearchField = current.showSearchField,
        ))

        private fun decode(data: JSONObject) = ArchivedSettings(
            pinnedApps = decodePins(data.getJSONArray(PINNED_FIELD)),
            tileSize = data.optionalValue<String>(TILE_SIZE_FIELD)?.let { AppGridTileSize.parse(it) },
            showSystemApps = data.optionalValue<Boolean>(SHOW_SYSTEM_APPS_FIELD),
            desktops = data.optionalValue<JSONObject>(DESKTOPS)?.let(::decodeDesktops),
            iconStyle = data.optionalValue<String>(ICON_STYLE_FIELD)?.let { AppGridIconStyle.parse(it) },
            showAppLabels = data.optionalValue<Boolean>(SHOW_APP_LABELS_FIELD),
            sortOrder = data.optionalValue<String>(SORT_ORDER_FIELD)?.let { AppGridSortOrder.parse(it) },
            columns = data.optionalValue<String>(COLUMNS)?.let { AppGridColumns.parse(it) },
            visibleShortcuts = data.optionalValue<JSONArray>(VISIBLE_SHORTCUTS_FIELD)?.let(::decodeShortcuts),
            desktopNames = data.optionalValue<JSONObject>(DESKTOP_NAMES_FIELD)?.let(::decodeDesktopNames),
            showDesktopCardOutlines = data.optionalValue<Boolean>(DESKTOP_CARD_OUTLINES_FIELD),
            smoothDesktopPaging = data.optionalValue<Boolean>(SMOOTH_DESKTOP_PAGING_FIELD),
            desktopCardPlacement = data.optionalValue<String>(DESKTOP_CARD_PLACEMENT_FIELD)?.let(DesktopCardPlacement::parse),
            favoritesBackBehavior = data.optionalValue<String>(FAVORITES_BACK_BEHAVIOR_FIELD)?.let(FavoritesBackBehavior::parse),
            manualAppOrder = data.optionalValue<JSONArray>(MANUAL_APP_ORDER_FIELD)
                ?.let { decodeAppIds(it, AppGridManualOrder.MAX_APPS) },
            hiddenApps = data.optionalValue<JSONArray>(HIDDEN_APPS_FIELD)?.let(::decodeHiddenApps),
            showSearchField = data.optionalValue<Boolean>(SHOW_SEARCH_FIELD_NAME),
        )

        private inline fun <reified T> JSONObject.optionalValue(field: String): T? =
            if (has(field)) get(field) as? T ?: error("Invalid app grid setting: $field") else null

        private fun decodeDesktopNames(data: JSONObject) = ArchivedDesktopNames(
            first = data.optionalValue<String>(AppGridDesktop.FAVORITES_ONE.storedValue)?.let { AppGridDesktopNames.normalize(it) },
            second = data.optionalValue<String>(AppGridDesktop.FAVORITES_TWO.storedValue)?.let { AppGridDesktopNames.normalize(it) },
        )

        private fun encodeDesktopNames(names: ArchivedDesktopNames): JSONObject = JSONObject().apply {
            names.first?.let { put(AppGridDesktop.FAVORITES_ONE.storedValue, it) }
            names.second?.let { put(AppGridDesktop.FAVORITES_TWO.storedValue, it) }
        }

        private fun decodeDesktops(data: JSONObject) = AppGridDesktops(
            enabled = data.get("enabled") as? Boolean ?: error("Invalid desktop visibility"),
            defaultDesktop = AppGridDesktop.parse(data.get("defaultDesktop") as? String ?: error("Invalid default desktop")),
            firstFavorites = decodePins(data.getJSONArray(AppGridDesktop.FAVORITES_ONE.storedValue)),
            secondFavorites = decodePins(data.getJSONArray(AppGridDesktop.FAVORITES_TWO.storedValue)),
        )

        private fun encodeDesktops(desktops: AppGridDesktops): JSONObject = JSONObject()
            .put("enabled", desktops.enabled)
            .put("defaultDesktop", desktops.defaultDesktop.storedValue)
            .put(AppGridDesktop.FAVORITES_ONE.storedValue, JSONArray(desktops.firstFavorites))
            .put(AppGridDesktop.FAVORITES_TWO.storedValue, JSONArray(desktops.secondFavorites))

        private fun decodePins(data: JSONArray): List<String> = decodeAppIds(data, AppGridPins.MAX_PINNED_APPS)

        private fun decodeHiddenApps(data: JSONArray): Set<String> =
            decodeAppIds(data, AppGridConfiguration.MAX_HIDDEN_APPS).toSet()

        private fun decodeAppIds(data: JSONArray, maximum: Int): List<String> {
            require(data.length() <= maximum)
            return AppGridPins.validate(List(data.length()) { index ->
                data.get(index) as? String ?: error("Invalid app grid component")
            })
        }

        private fun decodeShortcuts(data: JSONArray): Set<AppGridShortcut> {
            require(data.length() <= AppGridShortcut.entries.size + 1)
            val values = List(data.length()) { index ->
                data.get(index) as? String ?: error("Invalid app grid shortcut")
            }
            require(values.toSet().size == values.size)
            return values.asSequence().filterNot { it == "androidSettings" }.map(AppGridShortcut::parse).toSet()
        }

        private fun encodeShortcuts(shortcuts: Set<AppGridShortcut>): JSONArray =
            JSONArray(AppGridShortcut.entries.filter { it in shortcuts }.map(AppGridShortcut::storedValue))

        private fun encode(settings: ArchivedSettings): JSONObject = JSONObject().apply {
            settings.pinnedApps?.let { put(PINNED_FIELD, JSONArray(it)) }
            settings.tileSize?.let { put(TILE_SIZE_FIELD, it.storedValue) }
            settings.showSystemApps?.let { put(SHOW_SYSTEM_APPS_FIELD, it) }
            settings.desktops?.let { put(DESKTOPS, encodeDesktops(it)) }
            settings.iconStyle?.let { put(ICON_STYLE_FIELD, it.storedValue) }
            settings.showAppLabels?.let { put(SHOW_APP_LABELS_FIELD, it) }
            settings.sortOrder?.let { put(SORT_ORDER_FIELD, it.storedValue) }
            settings.columns?.let { put(COLUMNS, it.storedValue) }
            settings.visibleShortcuts?.let { put(VISIBLE_SHORTCUTS_FIELD, encodeShortcuts(it)) }
            settings.desktopNames?.let { put(DESKTOP_NAMES_FIELD, encodeDesktopNames(it)) }
            settings.showDesktopCardOutlines?.let { put(DESKTOP_CARD_OUTLINES_FIELD, it) }
            settings.smoothDesktopPaging?.let { put(SMOOTH_DESKTOP_PAGING_FIELD, it) }
            settings.desktopCardPlacement?.let { put(DESKTOP_CARD_PLACEMENT_FIELD, it.storedValue) }
            settings.favoritesBackBehavior?.let { put(FAVORITES_BACK_BEHAVIOR_FIELD, it.storedValue) }
            settings.manualAppOrder?.let { put(MANUAL_APP_ORDER_FIELD, JSONArray(it)) }
            settings.hiddenApps?.let { put(HIDDEN_APPS_FIELD, JSONArray(it)) }
            settings.showSearchField?.let { put(SHOW_SEARCH_FIELD_NAME, it) }
        }
    }
}
