package org.openvoyah.controls.presentation.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openvoyah.controls.domain.settings.RouteTransitionSpeed

class CockpitMotionTest {
    @Test fun screenTransitionPresetsKeepTheOriginalStandardTiming() {
        assertEquals(RouteTransitionDurations(120, 75), CockpitMotion.routeDurations(RouteTransitionSpeed.SHORT))
        assertEquals(RouteTransitionDurations(180, 110), CockpitMotion.routeDurations(RouteTransitionSpeed.STANDARD))
        assertEquals(RouteTransitionDurations(240, 150), CockpitMotion.routeDurations(RouteTransitionSpeed.SMOOTH))
    }
}
