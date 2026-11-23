package org.openvoyah.controls.domain.display

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlsDensityTest {
    @Test fun theHeadUnitDisplayDrawsControlsAtTheCanvasDensity() {
        // The central display of the supported head unit: 1920 x 720 at 160 dpi.
        assertEquals(240, ControlsDensity.dpi(160, 1920, 720))
        assertEquals(240, ControlsDensity.dpi(160, 720, 1920))
        assertEquals(1280, ControlsDensity.rescaleDp(1920, 160, 240))
        assertEquals(426, ControlsDensity.rescaleDp(640, 160, 240))
    }

    @Test fun aDisplayThatAlreadyFitsTheCanvasKeepsItsDensity() {
        assertEquals(240, ControlsDensity.dpi(240, 1920, 720)) // The emulator preview and the screen tests.
        assertEquals(160, ControlsDensity.dpi(160, 1280, 480))
        assertEquals(420, ControlsDensity.dpi(420, 1080, 2400))
        assertEquals(320, ControlsDensity.dpi(320, 1920, 720))
    }

    @Test fun theShorterSideLimitsAWideDisplay() {
        assertEquals(180, ControlsDensity.dpi(160, 1902, 540))
        assertEquals(240, ControlsDensity.dpi(160, 1920, 1080))
    }

    @Test fun unknownValuesLeaveTheDensityAlone() {
        assertEquals(0, ControlsDensity.dpi(0, 1920, 720))
        assertEquals(160, ControlsDensity.dpi(160, 0, 720))
        assertEquals(0, ControlsDensity.rescaleDp(0, 160, 240))
        assertEquals(640, ControlsDensity.rescaleDp(640, 0, 240))
    }
}
