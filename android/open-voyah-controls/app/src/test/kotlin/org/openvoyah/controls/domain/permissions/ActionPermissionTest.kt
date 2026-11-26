package org.openvoyah.controls.domain.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.domain.permissions.ActionPermission.ALLOWED
import org.openvoyah.controls.domain.permissions.ActionPermission.FORBIDDEN
import org.openvoyah.controls.domain.permissions.ActionPermission.UNVERIFIED

class ActionPermissionTest {
    @Test fun onlyForbiddenStopsAnAction() {
        assertTrue(ALLOWED.runs)
        assertTrue(UNVERIFIED.runs)
        assertFalse(FORBIDDEN.runs)
        assertEquals(UNVERIFIED, ActionPermission.parse("UNVERIFIED"))
        assertNull(ActionPermission.parse("unverified"))
        assertNull(ActionPermission.parse(null))
    }

    @Test fun keysRoundTripAndRejectUnknownItems() {
        for (item in ActionCatalog.operations.map(ActionCatalog::operation) + ActionCatalog.agents.map(ActionCatalog::agent) +
            ActionCatalog.voice("LowBeam")) {
            assertEquals(item, ActionCatalog.parseKey(item.key))
        }
        assertEquals("operation.media-restart", ActionCatalog.operation(ActionCatalog.MEDIA_RESTART).key)
        assertNull(ActionCatalog.parseKey("operation.unknown"))
        assertNull(ActionCatalog.parseKey("agent.locale-other"))
        assertNull(ActionCatalog.parseKey("voice.MediaPlay"))
        assertNull(ActionCatalog.parseKey("voice.Call_1234"))
        assertNull(ActionCatalog.parseKey("LowBeam"))
        assertThrows(IllegalArgumentException::class.java) { ActionCatalog.voice("not a command") }
    }

    @Test fun everyReviewedOperationIsListed() {
        assertEquals(11, ActionCatalog.operations.size)
        assertEquals(ActionCatalog.operations.size, ActionCatalog.operations.toSet().size)
        assertEquals(listOf("navigation", "cluster-card", "locale-setting", "locale-vehicle", "locale-bluetooth", "locale-hiboard"),
            ActionCatalog.agents)
        assertEquals("agent.cluster-card", ActionCatalog.agent(ActionCatalog.CLUSTER_CARD).key)
        assertEquals("agent.apollo", ActionCatalog.parseKey("agent.apollo")?.key)
    }

    @Test fun appAndMediaCommandsAreNotVehicleCommands() {
        for (id in listOf("LowBeam", "OpenTrunkCommand", "DriverA", "RebootTbox", "SetDriverTemperature", "GetFromMemoryCellOne")) {
            assertTrue(id, ActionCatalog.isVehicleCommand(id))
        }
        for (id in listOf("MediaPlay", "VolumeUp", "SetVolume", "Weather", "Music", "SystemSettings", "Call_abc")) {
            assertFalse(id, ActionCatalog.isVehicleCommand(id))
        }
    }

    @Test fun theTerminalCanProtocolIsTheDefaultAndAVerifiedOneWins() {
        val terminal = ActionCatalog.CAN_STEERING
        val edges = ActionCatalog.CAN_STEERING_EDGES
        assertEquals(terminal, ActionCatalog.canSteeringSource(ALLOWED, ALLOWED, emptySet()))
        assertEquals(terminal, ActionCatalog.canSteeringSource(ALLOWED, ALLOWED, setOf(terminal, edges)))
        assertEquals(edges, ActionCatalog.canSteeringSource(ALLOWED, ALLOWED, setOf(edges)))
        assertEquals(edges, ActionCatalog.canSteeringSource(FORBIDDEN, UNVERIFIED, emptySet()))
        assertEquals(terminal, ActionCatalog.canSteeringSource(UNVERIFIED, FORBIDDEN, setOf(edges)))
        assertNull(ActionCatalog.canSteeringSource(FORBIDDEN, FORBIDDEN, setOf(terminal, edges)))
    }
}
