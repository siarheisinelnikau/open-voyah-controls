package org.openvoyah.controls

import android.test.AndroidTestCase
import org.json.JSONObject
import org.openvoyah.controls.data.display.PassengerWorkspacePreferences
import org.openvoyah.controls.data.settings.LocalSettingsArchive
import org.openvoyah.controls.domain.display.PassengerWorkspace

class PassengerWorkspacePreferencesTest : AndroidTestCase() {
    fun testIndependentSlotsWidgetsAndAutostartSurviveAnOlderArchiveRestore() {
        val preferences = PassengerWorkspacePreferences(context)
        val archive = LocalSettingsArchive(context)
        val original = archive.prepare(archive.exportJson())
        val media = "org.example.media/org.example.media.MainActivity"
        val maps = "org.example.maps/org.example.maps.MainActivity"
        try {
            preferences.update {
                PassengerWorkspace(compact = true, clock = false, date = true, openOnStartup = true)
                    .setApp(inDock = false, slot = 1, component = maps)
                    .setApp(inDock = true, slot = 0, component = media)
            }
            val configured = PassengerWorkspacePreferences(context).load()
            assertEquals(maps, configured.slots[1])
            assertNull(configured.dock[1])
            assertEquals(media, configured.dock[0])
            val older = JSONObject(archive.exportJson())
            val saved = older.getJSONObject("passengerWorkspace")
            older.remove("passengerWorkspace")
            archive.apply(archive.prepare(older.toString()))
            assertEquals(saved.toString(), JSONObject(archive.exportJson()).getJSONObject("passengerWorkspace").toString())
            assertEquals(configured, PassengerWorkspacePreferences(context).load())
            assertTrue(configured.openOnStartup)
        } finally {
            archive.apply(original)
        }
    }

    fun testProtectedAndDuplicateComponentsAreRejected() {
        val media = "org.example.media/org.example.media.MainActivity"
        val settings = PassengerWorkspace().setApp(inDock = false, slot = 1, component = media)
        for (component in listOf(media, "com.qinggan.cluster/com.qinggan.cluster.ClusterActivity")) {
            try {
                settings.setApp(inDock = false, slot = 2, component = component)
                fail("A duplicate or protected component must be rejected")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(media, settings.setApp(inDock = true, slot = 0, component = media).dock[0])
    }
}
