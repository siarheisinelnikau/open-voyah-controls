package org.openvoyah.controls.domain.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfortDesktopCardsTest {
    @Test fun eachCardHasAnIndependentDesktopSlotAndStableArchiveKey() {
        val main = AppGridDesktop.MAIN
        val favorite = AppGridDesktop.FAVORITES_ONE
        val hold = DesktopInfoCard.POWER_HOLD
        val wash = DesktopInfoCard.CAR_WASH
        val fresh = DesktopInfoConfiguration()
        assertTrue(fresh.tiles(main).isEmpty())
        val selected = fresh.set(main, hold, DesktopInfoSize.COMPACT).set(favorite, wash, DesktopInfoSize.WIDE)
        assertEquals(hold, DesktopInfoCard.fromStoredValue("powerHold"))
        assertEquals(wash, DesktopInfoCard.fromStoredValue("carWash"))
        assertEquals(DesktopInfoTile(hold, DesktopInfoSize.COMPACT), selected.tiles(main).single())
        assertEquals(DesktopInfoTile(wash, DesktopInfoSize.WIDE), selected.tiles(favorite).single())
    }
}
