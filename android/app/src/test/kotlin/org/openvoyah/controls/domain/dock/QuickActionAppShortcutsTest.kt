package org.openvoyah.controls.domain.dock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class QuickActionAppShortcutsTest {
    private val main = "org.example.player/org.example.player.Main"
    private val radio = "org.example.player/org.example.player.Radio"
    private val missing = "org.example.missing/org.example.missing.Launcher"

    @Test fun editingExactActivitiesPreservesBuiltInsAndMissingEntries() {
        val defaults = QuickActionSettings()
        val saved = defaults.addApp(main).addApp(missing).addApp(radio)
        assertEquals(listOf(main, missing, radio), saved.appShortcuts)
        assertSame(saved, saved.addApp(main))
        assertSame(saved, saved.moveApp(main, earlier = true))
        assertSame(saved, saved.moveApp(radio, earlier = false))
        assertSame(saved, saved.moveApp("org.example.other/org.example.other.Start", earlier = false))
        val moved = saved.moveApp(radio, earlier = true)
        assertEquals(listOf(main, radio, missing), moved.appShortcuts)
        assertEquals(saved, moved.moveApp(radio, earlier = false))
        assertEquals(listOf(radio, missing), moved.removeApp(main).appShortcuts)
        assertEquals(listOf(main, radio), moved.removeApp(missing).appShortcuts)
        assertEquals(defaults.actions, moved.actions)
    }

    @Test fun selectionsAreBoundedAndMalformedArchivesCannotConstructSettings() {
        val full = QuickActionSettings(appShortcuts = List(MAX_DOCK_APP_SHORTCUTS) { "$main$it" })
        assertSame(full, full.addApp(radio))
        assertThrows(IllegalArgumentException::class.java) { full.copy(appShortcuts = full.appShortcuts + radio) }
        for (invalid in listOf(listOf(main, main), listOf("org.example.player"), listOf("org.example.player/.Main"),
            listOf("org.openvoyah.controls/org.openvoyah.controls.MainActivity"))) {
            assertThrows(IllegalArgumentException::class.java) { full.copy(appShortcuts = invalid) }
        }
    }
}
