package org.openvoyah.controls.domain.vehicle.comfort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IgnitionSamplesTest {
    @Test fun anEarlierValueIsDistinctFromACallbackInTheCurrentConnection() {
        val samples = IgnitionSamples(100)
        assertEquals(IgnitionStatus.Reported(IgnitionMode.ACCESSORY, false), samples.accept(3, 90, 105))
        assertEquals(IgnitionStatus.Reported(IgnitionMode.ON, true), samples.accept(4, 110, 115))
        assertNull(samples.accept(3, 110, 120))
        assertNull(samples.accept(3, 107, 120))
    }

    @Test fun unsupportedAndUnavailableValuesClearTheReading() {
        val samples = IgnitionSamples(100)
        assertNull(samples.accept(3, 105, 104))
        assertEquals(IgnitionStatus.Waiting, samples.accept(null, 0, 109))
        assertNull(samples.accept(3, 108, 110))
        assertEquals(IgnitionStatus.Waiting, samples.accept(0, 110, 120))
        assertEquals(IgnitionStatus.Reported(IgnitionMode.OFF, true), samples.accept(2, 120, 121))
    }
}
