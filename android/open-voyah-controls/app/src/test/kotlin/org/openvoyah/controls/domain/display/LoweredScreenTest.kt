package org.openvoyah.controls.domain.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoweredScreenTest {
    @Test fun onlyContentBelowTheVisibleBandIsHidden() {
        assertEquals(160, LoweredScreen.hiddenBelow(720))
        assertEquals(110, LoweredScreen.hiddenBelow(670))
        assertEquals(0, LoweredScreen.hiddenBelow(560))
        assertEquals(0, LoweredScreen.hiddenBelow(400))
    }

    @Test fun onlyAnExplicitLoweredValueOrTypeLowersTheLayout() {
        assertTrue(LoweredScreen.fromSetting("1"))
        for (value in listOf(null, "", "0", "2", "true")) assertFalse(value.toString(), LoweredScreen.fromSetting(value))
        assertTrue(LoweredScreen.fromLayoutType(1))
        for (type in listOf(0, 2, 3, -1)) assertFalse(LoweredScreen.fromLayoutType(type))
    }
}
