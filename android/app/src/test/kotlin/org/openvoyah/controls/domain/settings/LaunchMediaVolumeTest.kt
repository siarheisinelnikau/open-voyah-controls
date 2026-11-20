package org.openvoyah.controls.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaunchMediaVolumeTest {
    @Test
    fun `an excessive volume is capped at the highest step within the configured percentage`() {
        assertEquals(4, launcherMediaVolumeTarget(minimum = 0, maximum = 15, current = 11, percent = 30))
        assertEquals(5, launcherMediaVolumeTarget(minimum = 2, maximum = 9, current = 9, percent = 50))
    }

    @Test
    fun `a level at or below the ceiling has no target`() {
        assertNull(launcherMediaVolumeTarget(minimum = 0, maximum = 15, current = 3, percent = 30))
        assertNull(launcherMediaVolumeTarget(minimum = 0, maximum = 15, current = 4, percent = 30))
    }

    @Test
    fun `invalid or fixed ranges and unsupported choices have no target`() {
        assertNull(launcherMediaVolumeTarget(minimum = -1, maximum = 15, current = 13, percent = 30))
        assertNull(launcherMediaVolumeTarget(minimum = 4, maximum = 4, current = 4, percent = 30))
        assertNull(launcherMediaVolumeTarget(minimum = 0, maximum = 15, current = 16, percent = 30))
        assertNull(launcherMediaVolumeTarget(minimum = 0, maximum = 15, current = 13, percent = 35))
    }
}
