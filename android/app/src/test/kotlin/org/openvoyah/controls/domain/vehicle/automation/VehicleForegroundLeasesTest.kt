package org.openvoyah.controls.domain.vehicle.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleForegroundLeasesTest {
    @Test fun closingMostRecentDisplayRestoresTheStillResumedDisplaysDispatcher() {
        val left = Any()
        val right = Any()
        var calls = ""
        val first = VehicleForegroundLeases<() -> Unit>().started(left, false) { calls += "left" }
        val both = first.started(right, false) { calls += "right" }
        val rightLease = checkNotNull(both.latest)
        rightLease.dispatch()
        val afterClose = both.stopped(right)
        assertFalse(afterClose.retains(rightLease))
        checkNotNull(afterClose.latest).dispatch()
        assertEquals("rightleft", calls)
        assertTrue(afterClose.hasOwners)
        assertSame(left, afterClose.latest?.owner)
    }

    @Test fun lastConfirmationWindowClosingRevokesConsentEvenWithAnotherWindowResumed() {
        val vehicleScreen = Any()
        val otherScreen = Any()
        val both = VehicleForegroundLeases<String>().started(vehicleScreen, true, "vehicle")
            .started(otherScreen, false, "other")
        assertTrue(both.hasConfirmationOwner)
        val afterClose = both.stopped(vehicleScreen)
        assertTrue(afterClose.hasOwners)
        assertFalse(afterClose.hasConfirmationOwner)
        assertEquals("other", afterClose.latest?.dispatch)
    }

    @Test fun leavingConfirmationRouteRevokesItWithoutDroppingTheWindowLease() {
        val owner = Any()
        val shown = VehicleForegroundLeases<String>().started(owner, true, "confirmation")
        val old = checkNotNull(shown.latest)
        val navigated = shown.started(owner, false, "settings")
        assertTrue(navigated.hasOwners)
        assertFalse(navigated.hasConfirmationOwner)
        assertFalse(navigated.retains(old))
        assertEquals("settings", navigated.latest?.dispatch)
    }

    @Test fun oneConfirmationWindowClosingPreservesTheOtherAndLastStopRemovesAllDispatchers() {
        val left = Any()
        val right = Any()
        val both = VehicleForegroundLeases<String>().started(left, true, "left").started(right, true, "right")
        val leftOnly = both.stopped(right)
        assertTrue(leftOnly.hasConfirmationOwner)
        val none = leftOnly.stopped(left)
        assertFalse(none.hasOwners)
        assertFalse(none.hasConfirmationOwner)
        assertNull(none.latest)
        assertSame(none, none.stopped(Any()))
    }
}
