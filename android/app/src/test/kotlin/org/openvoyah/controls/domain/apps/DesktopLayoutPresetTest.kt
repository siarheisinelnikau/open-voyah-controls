package org.openvoyah.controls.domain.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopLayoutPresetTest {
    @Test fun labelsAreTrimmedAndNormalizedWithoutBreakingSupplementaryCharacters() {
        assertEquals("Город 🚗", DesktopLayoutPreset.normalizeName("  Город 🚗  "))
        assertEquals("Café", DesktopLayoutPreset.normalizeName("Cafe\u0301"))
        val longName = "🚗".repeat(DesktopLayoutPreset.MAX_NAME_LENGTH)
        assertEquals(longName, DesktopLayoutPreset.normalizeName(longName))
        assertNull(DesktopLayoutPreset.normalizeName(longName + "🚗"))
    }

    @Test fun namesRequireVisibleTextOnOneLine() {
        for (name in listOf("", "   ", "\u0301", "A\nB", "A\u202eB", "\ud800")) {
            assertNull(DesktopLayoutPreset.normalizeName(name))
        }
    }
}
