package org.openvoyah.controls.domain.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGridConfigurationTest {
    private data class Entry(val component: String, val system: Boolean = false, val installedAt: Long? = null)

    @Test fun defaultsUseLargeTilesAndTheEntireCatalog() {
        val entries = listOf(Entry("system", system = true), Entry("installed"))
        val grid = AppGridConfiguration()

        assertEquals(AppGridTileSize.LARGE, AppGridTileSize.DEFAULT)
        assertEquals(AppGridTileSize.DEFAULT, grid.tileSize)
        assertEquals(AppGridColumns.AUTO, grid.columns)
        assertTrue(grid.showSystemApps)
        assertEquals(AppGridIconStyle.NATIVE, grid.iconStyle)
        assertTrue(grid.showAppLabels)
        assertEquals(AppGridSortOrder.NAME, grid.sortOrder)
        assertEquals(entries, grid.arrange(entries, Entry::component, Entry::system))
    }

    @Test fun filteringKeepsPinnedSystemEntriesAndPreservesBothOrders() {
        val hidden = Entry("system_a", system = true)
        val pinned = Entry("system_b", system = true)
        val installedA = Entry("installed_a")
        val installedB = Entry("installed_b")
        val installedC = Entry("installed_c")
        val entries = listOf(hidden, pinned, installedA, installedB, installedC)
        val grid = AppGridConfiguration(pinnedApps = listOf("missing", installedB.component, pinned.component), showSystemApps = false)

        assertEquals(listOf(installedB, pinned, installedA, installedC), grid.arrange(entries, Entry::component, Entry::system))
    }

    @Test fun serializedTileSizesAreExplicitAndRejectUnknownValues() {
        assertEquals(AppGridTileSize.COMPACT, AppGridTileSize.parse("compact"))
        assertEquals(AppGridTileSize.STANDARD, AppGridTileSize.parse("standard"))
        assertEquals(AppGridTileSize.LARGE, AppGridTileSize.parse("large"))
        assertThrows(IllegalArgumentException::class.java) { AppGridTileSize.parse("unsupported") }
    }

    @Test fun favoritesKeepIndependentOrdersAndLeaveMainPinsInPlace() {
        val main = Entry("main")
        val navigation = Entry("navigation", system = true)
        val media = Entry("media")
        val settings = Entry("settings")
        val apps = listOf(media, settings, navigation, main)
        val grid = AppGridConfiguration(pinnedApps = listOf(main.component), showSystemApps = false,
            desktops = AppGridDesktops(enabled = true, firstFavorites = listOf(navigation.component, media.component),
                secondFavorites = listOf(settings.component, navigation.component)))

        assertEquals(listOf(main, media, settings), grid.arrange(apps, Entry::component, Entry::system))
        assertEquals(listOf(navigation, media), grid.arrange(apps, Entry::component, Entry::system, AppGridDesktop.FAVORITES_ONE))
        assertEquals(listOf(settings, navigation), grid.arrange(apps, Entry::component, Entry::system, AppGridDesktop.FAVORITES_TWO))
    }

    @Test fun hidingAnExactShortcutKeepsItsAppAliasesFavoritePinsAndManualPosition() {
        val maps = Entry("org.example.maps/org.example.maps.Main")
        val places = Entry("org.example.maps/org.example.maps.Places")
        val radio = Entry("org.example.radio/org.example.radio.Main", system = true)
        val files = Entry("org.example.files/org.example.files.Main")
        val entries = listOf(files, maps, places, radio)
        val base = AppGridConfiguration(
            pinnedApps = listOf(radio.component),
            desktops = AppGridDesktops(enabled = true, firstFavorites = listOf(maps.component, radio.component),
                secondFavorites = listOf(places.component)),
            sortOrder = AppGridSortOrder.MANUAL,
            manualAppOrder = listOf(places.component, maps.component, files.component),
        )
        for (showSystem in listOf(true, false)) {
            val grid = base.copy(hiddenApps = setOf(maps.component, radio.component), showSystemApps = showSystem)
            assertEquals(listOf(places, files), grid.arrange(entries, Entry::component, Entry::system))
            assertEquals(listOf(places, files), grid.editableManualApps(entries, Entry::component, Entry::system))
            assertEquals(listOf(maps, radio), grid.arrange(entries, Entry::component, Entry::system, AppGridDesktop.FAVORITES_ONE))
            assertEquals(listOf(places), grid.arrange(entries, Entry::component, Entry::system, AppGridDesktop.FAVORITES_TWO))
            val restored = grid.copy(hiddenApps = emptySet())
            assertEquals(listOf(radio, places, maps, files), restored.arrange(entries, Entry::component, Entry::system))
        }
    }

    @Test fun hiddenShortcutLimitAllowsShowingAnAppAndRejectsControlsOrPackages() {
        fun component(index: Int) = "org.example.apps/org.example.apps.Shortcut$index"
        val saved = List(AppGridConfiguration.MAX_HIDDEN_APPS, ::component).toSet()
        val full = AppGridConfiguration(hiddenApps = saved)
        val extra = component(saved.size)
        assertEquals(saved, full.hiddenAppsWith(component(0), true))
        assertThrows(AppGridHiddenAppsFull::class.java) { full.hiddenAppsWith(extra, true) }
        assertThrows(IllegalArgumentException::class.java) { full.hiddenAppsWith("org.openvoyah.controls/org.openvoyah.controls.MainActivity", true) }
        assertThrows(IllegalArgumentException::class.java) { full.hiddenAppsWith("org.example.apps", true) }
        val restored = full.copy(hiddenApps = full.hiddenAppsWith(component(0), false))
        assertEquals(saved - component(0) + extra, restored.hiddenAppsWith(extra, true))
    }

    @Test fun enabledDesktopUsesSavedDefaultUnlessThereIsAnActiveSelection() {
        val desktops = AppGridDesktops(enabled = true, defaultDesktop = AppGridDesktop.FAVORITES_TWO)

        assertEquals(AppGridDesktop.FAVORITES_TWO, desktops.selected(null))
        assertEquals(AppGridDesktop.FAVORITES_ONE, desktops.selected(AppGridDesktop.FAVORITES_ONE))
        assertEquals(AppGridDesktop.MAIN, desktops.copy(enabled = false).selected(AppGridDesktop.FAVORITES_ONE))
        assertEquals(AppGridDesktop.FAVORITES_TWO, AppGridDesktop.parse("favorites2"))
        assertThrows(IllegalArgumentException::class.java) { AppGridDesktop.parse("favorites3") }
    }

    @Test fun backOpensMainOnlyFromEnabledFavoritesWhenOptedIn() {
        val configured = AppGridConfiguration(desktops = AppGridDesktops(enabled = true))
        assertFalse(configured.opensMainOnBack(AppGridDesktop.FAVORITES_ONE))
        val favoritesBack = configured.copy(favoritesBackBehavior = FavoritesBackBehavior.MAIN_DESKTOP)
        assertTrue(favoritesBack.opensMainOnBack(AppGridDesktop.FAVORITES_ONE))
        assertTrue(favoritesBack.opensMainOnBack(AppGridDesktop.FAVORITES_TWO))
        assertFalse(favoritesBack.opensMainOnBack(AppGridDesktop.MAIN))
        assertFalse(favoritesBack.copy(desktops = AppGridDesktops()).opensMainOnBack(AppGridDesktop.FAVORITES_ONE))
    }

    @Test fun installTimeSortKeepsPinsFirstAndFallsBackToStableNameOrder() {
        val alpha = Entry("Alpha", installedAt = 20)
        val beta = Entry("Beta", installedAt = 40)
        val camera = Entry("Camera", installedAt = 40)
        val drive = Entry("Drive", installedAt = -1)
        val gallery = Entry("Gallery")
        val maps = Entry("Maps", installedAt = 1)
        val player = Entry("Player", installedAt = 999)
        val catalog = listOf(alpha, beta, camera, drive, gallery, maps, player)
        val grid = AppGridConfiguration(pinnedApps = listOf(maps.component, player.component),
            sortOrder = AppGridSortOrder.RECENTLY_INSTALLED)

        assertEquals(listOf(maps, player, beta, camera, alpha, drive, gallery),
            grid.arrange(catalog, Entry::component, Entry::system, installedAt = Entry::installedAt))
        assertEquals(listOf(maps, player, alpha, beta, camera, drive, gallery),
            grid.copy(sortOrder = AppGridSortOrder.NAME)
                .arrange(catalog, Entry::component, Entry::system, installedAt = Entry::installedAt))
    }

    @Test fun recentInstallsOnlyAffectTheMainCatalog() {
        val maps = Entry("Maps", installedAt = 30)
        val player = Entry("Player", installedAt = 900)
        val desktops = AppGridDesktops(enabled = true, firstFavorites = listOf(maps.component, player.component))
        val grid = AppGridConfiguration(desktops = desktops, sortOrder = AppGridSortOrder.RECENTLY_INSTALLED)

        assertEquals(listOf(player, maps), grid.arrange(listOf(maps, player), Entry::component, Entry::system,
            installedAt = Entry::installedAt))
        assertEquals(listOf(maps, player), grid.arrange(listOf(maps, player), Entry::component, Entry::system,
            AppGridDesktop.FAVORITES_ONE, Entry::installedAt))
    }
}
