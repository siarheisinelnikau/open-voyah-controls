package org.openvoyah.controls.data.frida

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatedFailureFilterTest {
    private var now = 1_000L
    private val filter = RepeatedFailureFilter(60_000L) { now }

    @Test fun theSameFailureIsARepeatOnlyInsideTheWindow() {
        assertFalse(filter.isRepeat("command:78"))
        now += 59_999L
        assertTrue(filter.isRepeat("command:78"))
        now += 60_000L
        assertFalse(filter.isRepeat("command:78"))
    }

    @Test fun aDifferentFailureIsReportedOnceAndAlternatingFailuresStayRepeats() {
        assertFalse(filter.isRepeat("command:78"))
        assertFalse(filter.isRepeat("evidence:77"))
        assertTrue(filter.isRepeat("evidence:77"))
        // Two failures taking turns must not present each other as new on every attempt.
        assertTrue(filter.isRepeat("command:78"))
        assertTrue(filter.isRepeat("evidence:77"))
        now += 60_000L
        assertFalse(filter.isRepeat("command:78"))
        assertFalse(filter.isRepeat("evidence:77"))
    }

    @Test fun theNumberOfRememberedKindsIsBounded() {
        for (kind in 0 until 8) assertFalse(filter.isRepeat("kind:$kind"))
        for (kind in 0 until 8) assertTrue(filter.isRepeat("kind:$kind"))
        // A ninth kind takes the place of the oldest one, which is then reported again.
        assertFalse(filter.isRepeat("kind:8"))
        assertFalse(filter.isRepeat("kind:0"))
        assertTrue(filter.isRepeat("kind:8"))
    }
}
