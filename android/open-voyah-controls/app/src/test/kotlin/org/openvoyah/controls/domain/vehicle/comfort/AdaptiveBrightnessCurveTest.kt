package org.openvoyah.controls.domain.vehicle.comfort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBrightnessCurveTest {
    @Test fun clampsToTheChosenDarkAndBrightEndpoints() {
        val curve = AdaptiveBrightnessCurve(AdaptiveBrightnessSettings(true, 15, 100))
        assertEquals(15, curve.level(0f))
        assertEquals(15, curve.level(10f))
        assertEquals(100, curve.level(2_000f))
        assertEquals(100, curve.level(50_000f))
    }

    @Test fun ignoresSmallFluctuationsAndDropsHistoryAfterAnInvalidSample() {
        val curve = AdaptiveBrightnessCurve(AdaptiveBrightnessSettings())
        val baseline = requireNotNull(curve.level(100f))
        assertEquals(baseline, curve.level(102f))
        assertEquals(baseline, curve.level(98f))
        val brighter = requireNotNull(curve.level(200f))
        assertTrue(brighter >= baseline + 5)
        assertEquals(brighter, curve.level(175f))
        assertNull(curve.level(Float.NaN))
        assertTrue(requireNotNull(curve.level(175f)) < brighter)
    }
}
