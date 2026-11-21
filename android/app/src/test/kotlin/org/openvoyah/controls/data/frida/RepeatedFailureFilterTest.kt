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

    @Test fun aDifferentFailureIsReportedAndStartsItsOwnWindow() {
        assertFalse(filter.isRepeat("command:78"))
        assertFalse(filter.isRepeat("evidence:77"))
        assertTrue(filter.isRepeat("evidence:77"))
        assertFalse(filter.isRepeat("command:78"))
    }
}
