package org.openvoyah.controls.domain.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGridVisibilityTest {
    private fun reason(component: String, preinstalled: Boolean = false, inputMethod: Boolean = false, mainEntry: Boolean = true) =
        AppGridVisibility.defaultHideReason(AppGridEntryFacts(component, preinstalled, inputMethod, mainEntry))

    @Test fun firmwareAppsAndTheirInternalActionsStartHidden() {
        assertEquals(DefaultHideReason.FIRMWARE, reason("com.android.calendar/.AllInOneActivity", preinstalled = true))
        assertEquals(DefaultHideReason.FIRMWARE,
            reason("com.qinggan.app.vehicle/com.qinggan.app.vehicletype.ui.activity.AirActivity", preinstalled = true))
        // A maker app installed into /data is still the maker's.
        assertEquals(DefaultHideReason.MAKER, reason("com.qinggan.app.music/.activity.MusicActivity"))
        assertEquals(DefaultHideReason.MAKER, reason("com.pateo.vuivendor/.activity.MainActivity"))
    }

    @Test fun keyboardsServiceAppsAndExtraEntriesStartHidden() {
        assertEquals(DefaultHideReason.KEYBOARD,
            reason("ru.yandex.androidkeyboard/com.android.inputmethod.latin.setup.SetupYandexKeyboardActivityAlias", inputMethod = true))
        assertEquals(DefaultHideReason.SERVICES, reason("com.mgoogle.android.gms/org.microg.gms.ui.SettingsActivity"))
        assertEquals(DefaultHideReason.EXTRA_ENTRY, reason("org.example.player/.Equalizer", mainEntry = false))
    }

    @Test fun ordinaryInstalledAppsStartVisible() {
        assertNull(reason("ru.yandex.yandexnavi/.core.NavigatorActivity"))
        assertNull(reason("org.example.youtube.web/.MainActivity"))
        // Similar names outside the maker's namespaces are not the maker's.
        assertNull(reason("com.qinggang.example/.Main"))
        assertFalse(HeadUnitMaker.owns("com.qinggan"))
        assertTrue(HeadUnitMaker.owns("com.qinggan.app.launcher"))
    }

    @Test fun aChoiceOverridesTheDefaultEitherWayAndAMatchingChoiceClearsIt() {
        val grid = AppGridConfiguration()
        val maps = "org.example.maps/org.example.maps.Main"
        assertTrue(grid.hides(maps, hiddenByDefault = true))
        assertFalse(grid.hides(maps, hiddenByDefault = false))

        val kept = grid.withHidden(maps, false, hiddenByDefault = true)
        assertEquals(setOf(maps), kept.shownApps)
        assertTrue(kept.hiddenApps.isEmpty())
        assertFalse(kept.hides(maps, hiddenByDefault = true))
        assertEquals(grid, kept.withHidden(maps, true, hiddenByDefault = true))

        val hidden = grid.withHidden(maps, true, hiddenByDefault = false)
        assertEquals(setOf(maps), hidden.hiddenApps)
        assertTrue(hidden.shownApps.isEmpty())
        assertTrue(hidden.hides(maps, hiddenByDefault = false))
        assertEquals(grid, hidden.withHidden(maps, false, hiddenByDefault = false))

        // An old explicit hide of an app that is now hidden by default moves to the default when it is shown.
        val legacy = AppGridConfiguration(hiddenApps = setOf(maps))
        val shown = legacy.withHidden(maps, false, hiddenByDefault = true)
        assertFalse(shown.hides(maps, hiddenByDefault = true))
        assertEquals(setOf(maps), shown.shownApps)
    }

    @Test fun turningTheDefaultRuleOffShowsDefaultsButKeepsTheUsersHides() {
        val calendar = "com.android.calendar/com.android.calendar.AllInOneActivity"
        val maps = "org.example.maps/org.example.maps.Main"
        val grid = AppGridConfiguration(hideByDefault = false, hiddenApps = setOf(maps))
        assertFalse(grid.hides(calendar, hiddenByDefault = true))
        assertTrue(grid.hides(maps, hiddenByDefault = false))
        // With the rule off, the default is visible, so hiding a default-hidden app is an explicit hide.
        assertEquals(setOf(maps, calendar), grid.withHidden(calendar, true, hiddenByDefault = true).hiddenApps)
    }

    @Test fun aPinKeepsAnAppHiddenByDefaultButNotOneTheUserHid() {
        val calendar = "com.android.calendar/com.android.calendar.AllInOneActivity"
        val maps = "org.example.maps/org.example.maps.Main"
        val entries = listOf(calendar, maps)
        val grid = AppGridConfiguration(pinnedApps = listOf(calendar, maps), hiddenApps = setOf(maps))
        assertEquals(listOf(calendar), grid.arrange(entries, { it }, { it == calendar }))
        assertEquals(emptyList<String>(), grid.copy(pinnedApps = emptyList()).arrange(entries, { it }, { it == calendar }))
    }
}
