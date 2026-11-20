package org.openvoyah.controls.platform.vehicle

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openvoyah.controls.domain.vehicle.AutomotiveHeadlightSamples
import org.openvoyah.controls.domain.vehicle.AutomotiveHeadlightSignal
import org.openvoyah.controls.domain.vehicle.AutomotiveHeadlightSignal.HEADLIGHTS
import org.openvoyah.controls.domain.vehicle.AutomotiveHeadlightSignal.HIGH_BEAM
import org.openvoyah.controls.domain.vehicle.AutomotiveHeadlightSignal.SELECTOR
import org.openvoyah.controls.domain.vehicle.HeadlightFeedback

class AutomotiveHeadlightReaderTest {
    @Test fun stateOnlyGrantKeepsBothLampReadersIndependentOfTheSelector() = runBlocking {
        val registered = mutableSetOf<AutomotiveHeadlightSignal>()
        val reports = automotiveHeadlightChannels(selectorAllowed = false, activityAllowed = true) { signal ->
            registered += signal
            flowOf(confirmation(signal))
        }.toList()
        assertEquals(setOf(HEADLIGHTS, HIGH_BEAM), registered)
        assertEquals(setOf(HeadlightFeedback.SelectorUnavailable, confirmation(HEADLIGHTS), confirmation(HIGH_BEAM)), reports.toSet())
    }

    @Test fun selectorOnlyGrantDoesNotAttemptLampReads() = runBlocking {
        val registered = mutableSetOf<AutomotiveHeadlightSignal>()
        val reports = automotiveHeadlightChannels(selectorAllowed = true, activityAllowed = false) { signal ->
            registered += signal
            flowOf(confirmation(signal))
        }.toList()
        assertEquals(setOf(SELECTOR), registered)
        assertEquals(setOf(confirmation(SELECTOR), HEADLIGHTS.unavailable, HIGH_BEAM.unavailable), reports.toSet())
    }

    @Test fun deniedOrUnsupportedPropertyLeavesOtherReadersActive() = runBlocking {
        val reports = automotiveHeadlightChannels(selectorAllowed = true, activityAllowed = true) { signal ->
            if (signal == HIGH_BEAM) throw SecurityException("Fixture property denied")
            flowOf(confirmation(signal))
        }.toList()
        assertEquals(setOf(confirmation(SELECTOR), confirmation(HEADLIGHTS), HIGH_BEAM.unavailable), reports.toSet())
    }

    private fun confirmation(signal: AutomotiveHeadlightSignal): HeadlightFeedback =
        checkNotNull(AutomotiveHeadlightSamples(1, signal).accept(1, 2, 3, 4))
}
