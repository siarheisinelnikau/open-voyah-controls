package org.openvoyah.controls.platform.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.platform.packages.PackageSessionRemoval.Observation

class PackageSessionRemovalTest {
    @Test fun asynchronousRemovalMustBePositivelyObserved() {
        var now = 0L
        var observations = 0
        val removed = PackageSessionRemoval.await(nowMillis = { now }, pause = { now += it }) {
            if (++observations < 3) Observation.PRESENT else Observation.ABSENT
        }
        assertTrue(removed)
        assertEquals(3, observations)
        assertEquals(100L, now)
    }

    @Test fun aRemainingSessionCannotExceedTheTwoSecondDeadline() {
        var now = 0L
        var observations = 0
        assertFalse(PackageSessionRemoval.await(nowMillis = { now }, pause = { now += it }) {
            observations += 1
            Observation.PRESENT
        })
        assertEquals(2_000L, now)
        assertEquals(41, observations)
    }

    @Test fun unknownOrFailedEvidenceNeverProvesRemoval() {
        var waits = 0
        assertFalse(PackageSessionRemoval.await(pause = { waits += 1 }) { Observation.UNKNOWN })
        assertFalse(PackageSessionRemoval.await(pause = { waits += 1 }) { throw IllegalStateException("Query failed") })
        assertEquals(0, waits)
    }

    @Test fun interruptionPreservesTheThreadFlagAndStopsWaiting() {
        try {
            assertFalse(PackageSessionRemoval.await(pause = { throw InterruptedException("Cancelled") }) {
                Observation.PRESENT
            })
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
}
