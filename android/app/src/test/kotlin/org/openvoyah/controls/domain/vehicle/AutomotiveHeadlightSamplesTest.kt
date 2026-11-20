package org.openvoyah.controls.domain.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutomotiveHeadlightSamplesTest {
    @Test fun switchValuesUseAutomotiveSemanticsAndTheOriginalSampleTime() {
        val samples = AutomotiveHeadlightSamples(1_000_000_000L)
        val reportedByValue = mapOf(
            0 to HeadlightMode.OFF, 1 to HeadlightMode.ON,
            2 to HeadlightMode.DAYTIME_RUNNING, 256 to HeadlightMode.AUTOMATIC,
        )

        reportedByValue.entries.forEachIndexed { index, (value, mode) ->
            val event = 1_005_000_000L + index * 1_000_000_000L
            assertEquals(
                HeadlightFeedback.Confirmed(HeadlightObservation(mode, 10_000L), event),
                samples.accept(value, event, event + 35_000_000L, 10_035L),
            )
        }
    }

    @Test fun initialValueKeepsItsOriginalTimeAndDoesNotAffectFreshSamples() {
        val samples = AutomotiveHeadlightSamples(500_000_000L)
        assertEquals(HeadlightFeedback.EarlierSelectorReport(EarlierHeadlightReport(HeadlightMode.AUTOMATIC, 698L)),
            samples.accept(256, 499_000_000L, 501_000_000L, 700L))
        assertNull(samples.accept(1, 499_500_000L, 501_000_000L, 700L))
        assertNull(samples.accept(256, 502_000_000L, 501_000_000L, 700L))

        val accepted = samples.accept(0, 501_000_000L, 502_000_000L, 701L)
        assertEquals(HeadlightObservation(HeadlightMode.OFF, 700L),
            (accepted as HeadlightFeedback.Confirmed).observation)
        assertNull(samples.accept(256, 501_000_000L, 503_000_000L, 702L))
    }

    @Test fun startupReportsAreValidatedPerLamp() {
        val headlights = AutomotiveHeadlightSamples(500_000_000L, AutomotiveHeadlightSignal.HEADLIGHTS)
        val beam = AutomotiveHeadlightSamples(500_000_000L, AutomotiveHeadlightSignal.HIGH_BEAM)
        val unavailableBeam = AutomotiveHeadlightSamples(500_000_000L, AutomotiveHeadlightSignal.HIGH_BEAM)
        assertEquals(HeadlightFeedback.EarlierLampReport(ExteriorLamp.HEADLIGHTS, EarlierHeadlightReport(LampActivity.ON, 698L)),
            headlights.accept(1, 499_000_000L, 501_000_000L, 700L))
        assertNull(beam.accept(2, 499_000_000L, 501_000_000L, 700L))
        assertNull(beam.accept(1, 499_500_000L, 501_000_000L, 700L))
        assertNull(unavailableBeam.accept(1, 499_000_000L, 501_000_000L, 700L, available = false))
    }

    @Test fun unsupportedValuesAndAvailabilitySignalsHaveNoObservation() {
        val samples = AutomotiveHeadlightSamples(500_000_000L)
        assertEquals(HeadlightFeedback.SelectorUnavailable, samples.accept(3, 501_000_000L, 502_000_000L, 700L))
        assertEquals(HeadlightFeedback.SelectorUnavailable, samples.accept(null, 0L, 503_000_000L, 701L))
        assertNull(samples.accept(null, 502_000_000L, 503_000_000L, 702L))
    }

    @Test fun headlightsStateUsesVehicleLightStateAndOriginalEventTime() {
        val samples = AutomotiveHeadlightSamples(1_000_000_000L, AutomotiveHeadlightSignal.HEADLIGHTS)
        listOf(LampActivity.OFF, LampActivity.ON, LampActivity.DAYTIME_RUNNING).forEachIndexed { value, activity ->
            val event = 1_050_000_000L + value * 100_000_000L
            assertEquals(HeadlightFeedback.ActivityConfirmed(ExteriorLamp.HEADLIGHTS, LampObservation(activity, 8_000L), event),
                samples.accept(value, event, event + 30_000_000L, 8_030L))
        }
        assertEquals(HeadlightFeedback.ActivityUnavailable(ExteriorLamp.HEADLIGHTS),
            samples.accept(256, 1_400_000_000L, 1_405_000_000L, 8000L))
    }

    @Test fun highBeamConfirmsOffAndOnAndKeepsAnIndependentWatermark() {
        val selector = AutomotiveHeadlightSamples(500_000_000L)
        val beam = AutomotiveHeadlightSamples(500_000_000L, AutomotiveHeadlightSignal.HIGH_BEAM)
        val at = 501_000_000L
        assertEquals(HeadlightFeedback.Confirmed(HeadlightObservation(HeadlightMode.AUTOMATIC, 700), at),
            selector.accept(256, at, at, 700))
        assertEquals(HeadlightFeedback.ActivityConfirmed(ExteriorLamp.HIGH_BEAM, LampObservation(LampActivity.OFF, 700), at),
            beam.accept(0, at, at, 700))
        assertEquals(HeadlightFeedback.ActivityConfirmed(ExteriorLamp.HIGH_BEAM, LampObservation(LampActivity.ON, 701), at + 1),
            beam.accept(1, at + 1, at + 1, 701))
        assertEquals(HeadlightFeedback.ActivityUnavailable(ExteriorLamp.HIGH_BEAM),
            beam.accept(2, at + 2, at + 2, 702))
    }

    @Test fun activityRequiresAvailableIntegerAndNewTimestamp() {
        val samples = AutomotiveHeadlightSamples(500_000_000L, AutomotiveHeadlightSignal.HEADLIGHTS)
        val unavailable = HeadlightFeedback.ActivityUnavailable(ExteriorLamp.HEADLIGHTS)
        assertNull(samples.accept(0, 0L, 500_000_000L, 702L))
        assertNull(samples.accept(1, 499_000_000L, 500_000_000L, 702L))
        assertNull(samples.accept(1, 502_000_000L, 501_000_000L, 702L))
        assertEquals(unavailable, samples.accept(null, 0L, 501_000_000L, 702L, available = false))
        assertEquals(unavailable, samples.accept(1, 501_000_000L, 502_000_000L, 702L, available = false))
        assertNull(samples.accept(1, 501_000_000L, 503_000_000L, 703L))
        assertEquals(unavailable, samples.accept(1L, 502_000_000L, 503_000_000L, 703L))
        assertEquals(unavailable, samples.accept(1, 503_000_000L, 504_000_000L, 1L))
        assertEquals(HeadlightFeedback.ActivityConfirmed(ExteriorLamp.HEADLIGHTS, LampObservation(LampActivity.ON, 703L), 504_000_000L),
            samples.accept(1, 504_000_000L, 504_000_000L, 703L))
    }

    @Test fun undatedLossRejectsDelayedConfirmationsThroughTheTimeItWasReceived() {
        val samples = AutomotiveHeadlightSamples(100_000_000L, AutomotiveHeadlightSignal.HEADLIGHTS)
        val unavailable = HeadlightFeedback.ActivityUnavailable(ExteriorLamp.HEADLIGHTS)
        assertEquals(
            HeadlightFeedback.ActivityConfirmed(ExteriorLamp.HEADLIGHTS, LampObservation(LampActivity.OFF, 700L), 110_000_000L),
            samples.accept(0, 110_000_000L, 110_000_000L, 700L),
        )
        assertEquals(unavailable, samples.accept(null, 0L, 200_000_000L, 790L, available = false))
        assertNull(samples.accept(1, 150_000_000L, 210_000_000L, 800L))
        assertNull(samples.accept(1, 200_000_000L, 210_000_000L, 800L))
        assertEquals(
            HeadlightFeedback.ActivityConfirmed(ExteriorLamp.HEADLIGHTS, LampObservation(LampActivity.ON, 795L), 205_000_000L),
            samples.accept(1, 205_000_000L, 210_000_000L, 800L),
        )
    }

    @Test fun lossAtTheLatestSampleTimeRevokesItAndAnOlderLossIsIgnored() {
        val samples = AutomotiveHeadlightSamples(100_000_000L)
        val latest = 120_000_000L
        assertEquals(
            HeadlightFeedback.Confirmed(HeadlightObservation(HeadlightMode.AUTOMATIC, 700L), latest),
            samples.accept(256, latest, latest, 700L),
        )
        assertNull(samples.accept(null, latest - 1, latest + 1, 701L, available = false))
        assertEquals(HeadlightFeedback.SelectorUnavailable,
            samples.accept(null, latest, latest + 2, 702L, available = false))
        assertNull(samples.accept(256, latest, latest + 3, 703L))
        assertEquals(
            HeadlightFeedback.Confirmed(HeadlightObservation(HeadlightMode.OFF, 704L), latest + 4),
            samples.accept(0, latest + 4, latest + 4, 704L),
        )
    }

    @Test fun unsupportedHighBeamValueRevokesFreshnessAtEqualOrUnknownTime() {
        val samples = AutomotiveHeadlightSamples(100_000_000L, AutomotiveHeadlightSignal.HIGH_BEAM)
        val lamp = ExteriorLamp.HIGH_BEAM
        val unavailable = HeadlightFeedback.ActivityUnavailable(lamp)
        assertEquals(HeadlightFeedback.ActivityConfirmed(lamp, LampObservation(LampActivity.ON, 700L), 120_000_000L),
            samples.accept(1, 120_000_000L, 120_000_000L, 700L))
        assertEquals(unavailable, samples.accept(2, 120_000_000L, 121_000_000L, 701L))
        assertEquals(HeadlightFeedback.ActivityConfirmed(lamp, LampObservation(LampActivity.ON, 705L), 125_000_000L),
            samples.accept(1, 125_000_000L, 125_000_000L, 705L))
        assertEquals(unavailable, samples.accept(2, 0L, 140_000_000L, 720L))
        assertNull(samples.accept(0, 139_000_000L, 142_000_000L, 722L))
        assertEquals(HeadlightFeedback.ActivityConfirmed(lamp, LampObservation(LampActivity.OFF, 721L), 141_000_000L),
            samples.accept(0, 141_000_000L, 142_000_000L, 722L))
    }
}
