package org.openvoyah.controls

import android.test.AndroidTestCase
import org.openvoyah.controls.data.apps.RootPermissionWriter

class RootPermissionWriterTest : AndroidTestCase() {
    fun testOnlyQualifiedNamesReachTheShell() {
        val commands = mutableListOf<String>()
        val writer = RootPermissionWriter { commands.add(it); "" }
        writer.set(0, "org.example.media", "android.permission.RECORD_AUDIO", true)
        writer.set(10, "org.example.media", "android.permission.RECORD_AUDIO", false)
        for (unsafe in listOf("org.example.media; id", "android.permission.RECORD_AUDIO\nreboot", "*")) {
            try {
                writer.set(0, "org.example.media", unsafe, true)
                fail("Unsafe value reached the shell")
            } catch (_: IllegalArgumentException) {
                // Rejected before dispatch.
            }
        }
        assertEquals(listOf(
            "test \"\$(id -u)\" = 0 && pm grant --user 0 org.example.media android.permission.RECORD_AUDIO",
            "test \"\$(id -u)\" = 0 && pm revoke --user 10 org.example.media android.permission.RECORD_AUDIO",
        ), commands)
    }
}
