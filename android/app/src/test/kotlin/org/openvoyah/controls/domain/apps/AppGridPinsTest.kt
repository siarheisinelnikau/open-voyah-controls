package org.openvoyah.controls.domain.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGridPinsTest {
    @Test fun exactActivityPinsKeepTheirOrderAndUnpinnedAppsKeepTheirCatalogOrder() {
        val apps = listOf(
            "org.example.a/org.example.a.Main",
            "org.example.b/org.example.b.Main",
            "org.example.b/org.example.b.Alternate",
            "org.example.c/org.example.c.Main",
        )
        val saved = listOf(apps[3], "org.example.missing/org.example.missing.Main", apps[2])

        assertEquals(listOf(apps[3], apps[2], apps[0], apps[1]), AppGridPins.order(apps, saved) { it })
        assertSame(apps, AppGridPins.order(apps, emptyList()) { it })
    }

    @Test fun pinChangesAreIdempotentAndPreserveOtherSelections() {
        val first = "org.example.a/org.example.a.Main"
        val second = "org.example.b/org.example.b.Main"
        val pins = AppGridPins.change(listOf(first), second, pinned = true)

        assertEquals(listOf(first, second), pins)
        assertSame(pins, AppGridPins.change(pins, second, pinned = true))
        assertEquals(listOf(second), AppGridPins.change(pins, first, pinned = false))
    }

    @Test fun movingNeighborsKeepsUninstalledPinsInPlaceAndStaleEditsDoNothing() {
        val pins = listOf("first", "uninstalled", "second", "last")

        assertEquals(listOf("second", "uninstalled", "first", "last"), AppGridPins.swap(pins, "second", "first"))
        assertSame(pins, AppGridPins.swap(pins, "stale", "first"))
        assertSame(pins, AppGridPins.swap(pins, "first", "first"))
    }

    @Test fun restorationRejectsDuplicateMalformedOrUnboundedComponents() {
        val valid = "org.example.sample/org.example.sample.Launcher\$Alias"
        assertTrue(AppGridPins.supports(valid))
        assertFalse(AppGridPins.supports("org.example.sample/.Launcher"))
        assertFalse(AppGridPins.supports("org.openvoyah.controls/org.openvoyah.controls.MainActivity"))
        assertFalse(AppGridPins.supports("org.example.sample/org.example.sample.Main;test"))
        assertThrows(IllegalArgumentException::class.java) { AppGridPins.validate(listOf(valid, valid)) }
        assertThrows(IllegalArgumentException::class.java) {
            AppGridPins.validate(List(AppGridPins.MAX_PINNED_APPS + 1) { "$valid$it" })
        }
    }
}
