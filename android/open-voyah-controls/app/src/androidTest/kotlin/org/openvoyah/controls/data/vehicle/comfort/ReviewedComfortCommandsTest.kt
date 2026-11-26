package org.openvoyah.controls.data.vehicle.comfort

import android.test.AndroidTestCase
import kotlinx.coroutines.runBlocking
import org.openvoyah.controls.data.settings.ActionPermissions
import org.openvoyah.controls.domain.permissions.ActionCatalog
import org.openvoyah.controls.domain.permissions.ActionPermission

class ReviewedComfortCommandsTest : AndroidTestCase() {
    fun testExplicitOwnerRefusalPreventsDispatch() {
        val permissions = ActionPermissions(context)
        val dispatcher = ReviewedComfortCommands(context)
        for (command in listOf("PowerHold", "CarWashMode")) {
            val item = ActionCatalog.voice(command)
            val previous = permissions.voice(command)
            try {
                permissions.set(item, ActionPermission.FORBIDDEN)
                assertEquals(ComfortCommandAvailability.FORBIDDEN, dispatcher.available(command))
                assertNull(runBlocking { dispatcher.dispatch(command) { true } })
            } finally {
                permissions.set(item, previous)
            }
        }
    }

    fun testUnknownCommandsNeverReachTheTransport() {
        val dispatcher = ReviewedComfortCommands(context)
        assertEquals(ComfortCommandAvailability.FIRMWARE_UNREVIEWED, dispatcher.available("ComfortFixtureUnknown"))
        assertNull(runBlocking { dispatcher.dispatch("ComfortFixtureUnknown") { true } })
    }
}
