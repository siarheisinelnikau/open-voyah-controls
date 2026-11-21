package org.openvoyah.controls.domain.apps

internal enum class AppGridTileSize(val storedValue: String) {
    COMPACT("compact"),
    STANDARD("standard"),
    LARGE("large");

    companion object {
        /**
         * A new grid uses Large tiles: six columns and two rows on the central display, sized like the factory
         * launcher's own app grid. A grid whose tile size was never changed follows this default after an update;
         * a saved size stays, also one kept in a desktop layout or a settings archive.
         */
        val DEFAULT = LARGE

        fun parse(value: String): AppGridTileSize =
            requireNotNull(entries.firstOrNull { it.storedValue == value }) { "Invalid app grid tile size" }
    }
}

internal enum class AppGridIconStyle(val storedValue: String) {
    NATIVE("native"),
    MONOCHROME("monochrome");

    companion object {
        fun parse(value: String): AppGridIconStyle =
            requireNotNull(entries.firstOrNull { it.storedValue == value }) { "Invalid app grid icon style" }
    }
}

internal enum class AppGridSortOrder(val storedValue: String) {
    NAME("name"),
    RECENTLY_INSTALLED("recentlyInstalled"),
    MANUAL("manual");

    companion object {
        fun parse(value: String): AppGridSortOrder =
            requireNotNull(entries.firstOrNull { it.storedValue == value }) { "Invalid app grid sort order" }
    }
}

internal enum class AppGridDesktop(val storedValue: String) {
    MAIN("main"),
    FAVORITES_ONE("favorites1"),
    FAVORITES_TWO("favorites2");

    companion object {
        fun parse(value: String): AppGridDesktop =
            requireNotNull(entries.firstOrNull { it.storedValue == value }) { "Invalid app grid desktop" }
    }
}

internal data class AppGridDesktops(
    val enabled: Boolean = false,
    val defaultDesktop: AppGridDesktop = AppGridDesktop.MAIN,
    val firstFavorites: List<String> = emptyList(),
    val secondFavorites: List<String> = emptyList(),
) {
    fun selected(active: AppGridDesktop?): AppGridDesktop =
        if (enabled) active ?: defaultDesktop else AppGridDesktop.MAIN

    fun withPins(desktop: AppGridDesktop, pins: List<String>): AppGridDesktops = when (desktop) {
        AppGridDesktop.FAVORITES_ONE -> copy(firstFavorites = pins)
        AppGridDesktop.FAVORITES_TWO -> copy(secondFavorites = pins)
        AppGridDesktop.MAIN -> error("Main grid has separate pins")
    }
}

internal class AppGridHiddenAppsFull : IllegalStateException("Hidden app shortcut list is full")

internal data class AppGridConfiguration(
    val pinnedApps: List<String> = emptyList(),
    val tileSize: AppGridTileSize = AppGridTileSize.DEFAULT,
    val showSystemApps: Boolean = true,
    val desktops: AppGridDesktops = AppGridDesktops(),
    val iconStyle: AppGridIconStyle = AppGridIconStyle.NATIVE,
    val showAppLabels: Boolean = true,
    val sortOrder: AppGridSortOrder = AppGridSortOrder.NAME,
    val columns: AppGridColumns = AppGridColumns.AUTO,
    val visibleShortcuts: Set<AppGridShortcut> = AppGridShortcut.defaults,
    val desktopNames: AppGridDesktopNames = AppGridDesktopNames(),
    val showDesktopCardOutlines: Boolean = true,
    val smoothDesktopPaging: Boolean = false,
    val desktopCardPlacement: DesktopCardPlacement = DesktopCardPlacement.BEFORE_APPS,
    val favoritesBackBehavior: FavoritesBackBehavior = FavoritesBackBehavior.SYSTEM,
    val manualAppOrder: List<String> = emptyList(),
    val hiddenApps: Set<String> = emptySet(),
    val showSearchField: Boolean = false,
) {
    fun hiddenAppsWith(component: String, hidden: Boolean): Set<String> {
        require(AppGridPins.supports(component))
        if ((component in hiddenApps) == hidden) return hiddenApps
        if (hidden && hiddenApps.size >= MAX_HIDDEN_APPS) throw AppGridHiddenAppsFull()
        return if (hidden) hiddenApps + component else hiddenApps - component
    }

    fun opensMainOnBack(desktop: AppGridDesktop): Boolean =
        desktops.enabled && desktop != AppGridDesktop.MAIN && favoritesBackBehavior == FavoritesBackBehavior.MAIN_DESKTOP

    fun pins(desktop: AppGridDesktop): List<String> = when (desktop) {
        AppGridDesktop.MAIN -> pinnedApps
        AppGridDesktop.FAVORITES_ONE -> desktops.firstFavorites
        AppGridDesktop.FAVORITES_TWO -> desktops.secondFavorites
    }

    fun <T> arrange(items: List<T>, component: (T) -> String, isSystem: (T) -> Boolean,
                    desktop: AppGridDesktop = AppGridDesktop.MAIN, installedAt: (T) -> Long? = { null }): List<T> {
        val effective = desktops.selected(desktop)
        val pins = pins(effective)
        val visible = when {
            effective != AppGridDesktop.MAIN -> {
                val pinned = pins.toSet()
                items.filter { component(it) in pinned }
            }
            showSystemApps && hiddenApps.isEmpty() -> items
            else -> {
                val pinned = pins.toSet()
                items.filter {
                    val id = component(it)
                    id !in hiddenApps && (showSystemApps || !isSystem(it) || id in pinned)
                }
            }
        }
        // The catalog's name order is the fallback for unranked apps and equal install times.
        val unpinnedOrder = if (effective == AppGridDesktop.MAIN) when (sortOrder) {
            AppGridSortOrder.NAME -> null
            AppGridSortOrder.RECENTLY_INSTALLED -> compareByDescending<T> { installedAt(it)?.coerceAtLeast(0) ?: 0L }
            AppGridSortOrder.MANUAL -> manualAppOrder.takeIf { it.isNotEmpty() }?.let { AppGridPins.byPosition(it, component) }
        } else null
        return AppGridPins.order(visible, pins, unpinnedOrder, component)
    }

    fun <T> editableManualApps(items: List<T>, component: (T) -> String, isSystem: (T) -> Boolean): List<T> {
        val pinned = pinnedApps.toHashSet()
        val eligible = items.filter { row ->
            val id = component(row)
            id !in hiddenApps && id !in pinned && AppGridPins.supports(id) && (showSystemApps || !isSystem(row))
        }.distinctBy(component)
        return AppGridPins.order(eligible, manualAppOrder, component = component)
    }

    companion object {
        const val MAX_HIDDEN_APPS = AppGridPins.MAX_PINNED_APPS
    }
}
