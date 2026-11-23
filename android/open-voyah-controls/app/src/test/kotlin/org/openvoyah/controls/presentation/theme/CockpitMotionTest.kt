package org.openvoyah.controls.presentation.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.domain.settings.RouteTransitionSpeed

class CockpitMotionTest {
    @Test fun screenTransitionPresetsKeepTheOriginalStandardTiming() {
        assertEquals(RouteTransitionDurations(120, 75), CockpitMotion.routeDurations(RouteTransitionSpeed.SHORT))
        assertEquals(RouteTransitionDurations(180, 110), CockpitMotion.routeDurations(RouteTransitionSpeed.STANDARD))
        assertEquals(RouteTransitionDurations(240, 150), CockpitMotion.routeDurations(RouteTransitionSpeed.SMOOTH))
    }

    @Test fun anArrivingPageSettlesWithinHalfASecondAndFollowsTheChosenSpeed() {
        val totals = RouteTransitionSpeed.entries.map { CockpitMotion.cascade(it).totalMillis }
        assertEquals(totals.sorted(), totals)
        assertTrue(totals.all { it in 200..520 })
        for (speed in RouteTransitionSpeed.entries) {
            // The first block must not arrive faster than the route change that carries it in.
            assertTrue(CockpitMotion.cascade(speed).riseMillis >= CockpitMotion.routeDurations(speed).enterMillis)
            assertEquals(CockpitMotion.cascade(speed), CockpitMotionSettings(speed = speed).cascade)
            assertEquals(CockpitMotion.routeDurations(speed), CockpitMotionSettings(speed = speed).route)
        }
    }

    @Test fun laterBlocksShareTheLastStepOfTheCascade() {
        val timing = CockpitMotion.cascade(RouteTransitionSpeed.STANDARD)
        assertEquals(0, timing.delayMillis(0))
        assertEquals(timing.staggerMillis * 3, timing.delayMillis(3))
        assertEquals(timing.delayMillis(CockpitMotion.MAX_STAGGERED), timing.delayMillis(40))
        assertEquals(0, timing.delayMillis(-2))
    }

    @Test fun blocksTakePositionsInOrderUntilThePageHasSettled() {
        val cascade = EntranceCascade()
        assertEquals(listOf(0, 1, 2), List(3) { cascade.take() })
        repeat(20) { cascade.take() }
        assertEquals(CockpitMotion.MAX_STAGGERED, cascade.take())
        cascade.settle()
        // A block that appears later, such as a result card, arrives on its own without a delay.
        assertEquals(0, cascade.take())
    }
}
