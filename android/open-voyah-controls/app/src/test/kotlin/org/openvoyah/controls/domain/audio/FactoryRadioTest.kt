package org.openvoyah.controls.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FactoryRadioTest {
    private val fm = RadioQueueStation(5, FactoryStation("fm-one", RadioBand.FM, "FM One"))
    private val savedFm = RadioQueueStation(8, FactoryStation("fm-two", RadioBand.FM, "FM Two"))
    private val am = RadioQueueStation(1, FactoryStation("am-one", RadioBand.AM, "AM One"))

    @Test fun bandPrefersItsRememberedStation() {
        val queue = listOf(fm, savedFm, am)
        assertEquals(savedFm, radioBandTarget(queue, RadioBand.FM, savedFm.station.identity()))
        assertEquals(am, radioBandTarget(queue, RadioBand.AM, savedFm.station.identity()))
        assertNull(radioBandTarget(listOf(fm), RadioBand.AM, null))
    }

    @Test fun restoreUsesStableIdentityAndFreshQueueId() {
        val saved = savedFm.station.identity()
        val moved = savedFm.copy(queueId = 41)
        assertEquals(moved, radioSavedTarget(listOf(moved, fm), saved))
        assertNull(radioSavedTarget(listOf(fm.copy(queueId = savedFm.queueId)), saved))
    }

    @Test fun ambiguousQueueAndUnidentifiedStationsCannotBeSelected() {
        assertNull(radioSavedTarget(listOf(fm, fm.copy(queueId = am.queueId)), fm.station.identity()))
        assertNull(radioBandTarget(listOf(fm, am.copy(queueId = fm.queueId)), RadioBand.FM, null))
        assertNull(radioBandTarget(listOf(fm.copy(station = fm.station.copy(mediaId = null))), RadioBand.FM, null))
        assertFalse(validRadioMediaId("fm\none"))
    }
}
