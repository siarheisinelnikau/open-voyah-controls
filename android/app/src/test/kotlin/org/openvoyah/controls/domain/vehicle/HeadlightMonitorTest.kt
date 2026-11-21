package org.openvoyah.controls.domain.vehicle

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadlightMonitorTest {
    private val previous = HeadlightObservation(HeadlightMode.POSITION, 1_000L)
    private val latest = HeadlightObservation(HeadlightMode.LOW_BEAM, 2_000L)

    private class History(var stored: HeadlightObservation? = null) : HeadlightHistory {
        var fail = false
        val writes = mutableListOf<HeadlightObservation>()
        val lamps = mutableMapOf<ExteriorLamp, LampObservation>()
        override fun read() = stored
        override fun save(observation: HeadlightObservation): Boolean {
            writes += observation
            if (fail) return false
            stored = observation
            return true
        }
        override fun readLamps() = lamps.toMap()
        override fun saveLamp(lamp: ExteriorLamp, observation: LampObservation): Boolean {
            if (fail) return false
            lamps[lamp] = observation
            return true
        }
    }

    @Test fun unavailableSourceKeepsHistoryWithoutFabricatingACurrentMode() = runBlocking {
        val history = History(previous)
        val monitor = HeadlightMonitor(flowOf(HeadlightFeedback.Unavailable), history, Dispatchers.Unconfined)
        val state = monitor.observe().toList().last()
        assertEquals(HeadlightStatus(HeadlightFeedback.Unavailable, previous), state)
        assertTrue(history.writes.isEmpty())
    }

    @Test fun readerCompletionRetainsDiagnosticOutcomesUntilTheInteractivePeriodEnds() = runBlocking {
        val history = History(previous)
        val report = HeadlightFeedback.ReaderState(HeadlightInput.FACTORY_SELECTOR, HeadlightInputState.MODEL_UNREVIEWED)
        val monitor = HeadlightMonitor(flowOf(report, HeadlightFeedback.ReadersFinished, HeadlightFeedback.Unavailable),
            history, Dispatchers.Unconfined)
        val states = monitor.observe().toList()
        val completed = states.single { it.feedback == HeadlightFeedback.Unavailable && it.inputStates.isNotEmpty() }
        assertEquals(mapOf(report.input to report.state), completed.inputStates)
        assertEquals(previous, completed.lastConfirmed)
        assertNull(completed.currentObservation)
        assertTrue(states.last().inputStates.isEmpty())
        assertTrue(history.writes.isEmpty())
    }

    @Test fun confirmedFeedbackIsSavedAndRemainsHistoricalAfterDisconnect() = runBlocking {
        val history = History()
        val live = HeadlightFeedback.Confirmed(latest, 2_000L)
        val signals = flow<HeadlightFeedback> {
            emit(live)
            throw IOException("fixture disconnected")
        }
        val states = HeadlightMonitor(signals, history, Dispatchers.Unconfined).observe().toList()
        assertEquals(HeadlightStatus(live, latest), states[1])
        assertEquals(listOf(latest), history.writes)
        assertEquals(HeadlightStatus(HeadlightFeedback.Unavailable, latest), states.last())
        val reloaded = HeadlightMonitor(flowOf(HeadlightFeedback.Unavailable), History(history.stored),
            Dispatchers.Unconfined).observe().toList().last()
        assertEquals(states.last(), reloaded)
    }

    @Test fun outOfOrderAndDuplicateEventsDoNotOverwriteNewerFeedback() = runBlocking {
        val history = History()
        val live = HeadlightFeedback.Confirmed(latest, 20L)
        val signals = flowOf(live, HeadlightFeedback.Confirmed(previous, 10L), live)
        val states = HeadlightMonitor(signals, history, Dispatchers.Unconfined).observe().toList()
        assertEquals(listOf(latest), history.writes)
        assertEquals(latest, states.last().lastConfirmed)
    }

    @Test fun reconnectionKeepsTheLastConfirmationHistoricalUntilFreshFeedback() = runBlocking {
        val history = History()
        val first = HeadlightFeedback.Confirmed(previous, 10L)
        val second = HeadlightFeedback.Confirmed(latest, 20L)
        val feedback = flowOf(first, HeadlightFeedback.Unavailable, HeadlightFeedback.AwaitingController, first, second)
        val states = HeadlightMonitor(feedback, history, Dispatchers.Unconfined).observe().toList()
        assertEquals(listOf(null, previous, null, null, latest, null), states.map { it.currentObservation })
        assertEquals(listOf(null, previous, previous, previous, latest, latest), states.map { it.lastConfirmed })
        assertEquals(listOf(previous, latest), history.writes)
    }

    @Test fun twoLampChannelsKeepIndependentHistoryWhenTheSwitchAndOneLampBecomeUnavailable() = runBlocking {
        val main = ExteriorLamp.HEADLIGHTS
        val high = ExteriorLamp.HIGH_BEAM
        val historicalHigh = LampObservation(LampActivity.OFF, 5L)
        val headlights = LampObservation(LampActivity.DAYTIME_RUNNING, 20L)
        val highBeam = LampObservation(LampActivity.ON, 19L)
        val history = History(previous).apply { lamps[high] = historicalHigh }
        val signals = flowOf(
            HeadlightFeedback.Confirmed(latest, 100L),
            HeadlightFeedback.ActivityConfirmed(main, headlights, 120L),
            HeadlightFeedback.ActivityConfirmed(high, highBeam, 119L),
            HeadlightFeedback.SelectorUnavailable,
            HeadlightFeedback.ActivityUnavailable(main),
            HeadlightFeedback.ActivityConfirmed(main, LampObservation(LampActivity.OFF, 10L), 110L),
            HeadlightFeedback.AwaitingController,
        )
        val states = HeadlightMonitor(signals, history, Dispatchers.Unconfined).observe().toList()
        assertEquals(LampStatus(lastConfirmed = historicalHigh), states.first().lamps[high])
        assertEquals(latest, states[3].currentObservation)
        assertEquals(highBeam, states[3].lamps[high]?.current)
        assertNull(states[4].currentObservation)
        assertEquals(headlights, states[4].lamps[main]?.current)
        assertNull(states[5].lamps[main]?.current)
        assertEquals(highBeam, states[5].lamps[high]?.current)
        assertTrue(states.last().lamps.values.all { it.current == null })
        assertEquals(mapOf(main to headlights, high to highBeam), history.readLamps())
        val reopened = HeadlightMonitor(flowOf(HeadlightFeedback.Unavailable), history, Dispatchers.Unconfined)
            .observe().toList().last()
        assertEquals(states.last(), reopened)
    }

    @Test fun initialReportsOnlyFillMissingReadingsAndClearWhenObservationStops() = runBlocking {
        val main = ExteriorLamp.HEADLIGHTS
        val high = ExteriorLamp.HIGH_BEAM
        val earlierLamp = EarlierHeadlightReport(LampActivity.ON, 2_000L)
        val storedHigh = LampObservation(LampActivity.OFF, 1_000L)
        val history = History(previous).apply { lamps[high] = storedHigh }
        val signals = flowOf(
            HeadlightFeedback.EarlierSelectorReport(EarlierHeadlightReport(HeadlightMode.OFF, 2_000L)),
            HeadlightFeedback.EarlierLampReport(high, earlierLamp),
            HeadlightFeedback.EarlierLampReport(main, earlierLamp),
            HeadlightFeedback.Unavailable,
        )
        val states = HeadlightMonitor(signals, history, Dispatchers.Unconfined).observe().toList()
        assertEquals(3, states.size)
        assertEquals(previous, states[1].lastConfirmed)
        assertNull(states[1].earlierReport)
        assertEquals(LampStatus(lastConfirmed = storedHigh), states[1].lamps[high])
        assertEquals(LampStatus(earlierReport = earlierLamp), states[1].lamps[main])
        assertEquals(LampStatus(), states.last().lamps[main])
        assertTrue(history.writes.isEmpty())
        assertEquals(mapOf(high to storedHigh), history.readLamps())
    }

    @Test fun freshObservationRetiresTheInitialReportEvenIfHistoryCannotBeWritten() = runBlocking {
        val history = History().apply { fail = true }
        val earlier = HeadlightFeedback.EarlierSelectorReport(EarlierHeadlightReport(HeadlightMode.OFF, 500L))
        val live = HeadlightFeedback.Confirmed(latest, 20L)
        val signals = flowOf(earlier, live, HeadlightFeedback.Unavailable, earlier)
        val states = HeadlightMonitor(signals, history, Dispatchers.Unconfined).observe().toList()
        assertEquals(4, states.size)
        assertEquals(earlier.reading, states[1].earlierReport)
        assertNull(states[1].lastConfirmed)
        assertEquals(latest, states[2].currentObservation)
        assertTrue(states[2].saveFailed)
        assertNull(states[2].earlierReport)
        assertNull(states.last().earlierReport)
        assertEquals(listOf(latest), history.writes)
    }

    @Test fun failedLampWriteLeavesOlderObservationWhileLiveFeedbackRemainsVisible() = runBlocking {
        val main = ExteriorLamp.HEADLIGHTS
        val durable = LampObservation(LampActivity.OFF, 100L)
        val fresh = LampObservation(LampActivity.ON, 200L)
        val history = History(previous).apply {
            lamps[main] = durable
            fail = true
        }
        val report = HeadlightFeedback.ActivityConfirmed(main, fresh, 20L)
        val states = HeadlightMonitor(flowOf(report), history, Dispatchers.Unconfined).observe().toList()
        assertEquals(LampStatus(report, durable, saveFailed = true), states[1].lamps[main])
        assertEquals(LampStatus(lastConfirmed = durable, saveFailed = true), states.last().lamps[main])
        assertEquals(durable, history.readLamps()[main])
        assertEquals(previous, history.stored)
    }

    @Test fun aReaderExceptionIsReportedAndReadsAsUnavailable() = runBlocking {
        val reported = mutableListOf<Exception>()
        val signals = flow<HeadlightFeedback> { throw IOException("fixture disconnected") }
        val states = HeadlightMonitor(signals, History(previous), Dispatchers.Unconfined) { reported += it }.observe().toList()
        assertEquals(HeadlightStatus(HeadlightFeedback.Unavailable, previous), states.last())
        assertEquals(listOf<Class<*>>(IOException::class.java), reported.map { it.javaClass })
    }

    @Test fun anErrorIsRethrownInsteadOfReadingAsAnUnavailableLamp() = runBlocking {
        val reported = mutableListOf<Exception>()
        val signals = flow<HeadlightFeedback> { throw AssertionError("fixture defect") }
        val failure = runCatching {
            HeadlightMonitor(signals, History(previous), Dispatchers.Unconfined) { reported += it }.observe().toList()
        }.exceptionOrNull()
        assertTrue(failure is AssertionError)
        assertTrue(reported.isEmpty())
    }

    @Test fun writeFailureKeepsTheOlderDurableRecord() = runBlocking {
        val history = History(previous).apply { fail = true }
        val live = HeadlightFeedback.Confirmed(latest, 2_000L)
        val states = HeadlightMonitor(flowOf(live), history, Dispatchers.Unconfined).observe().toList()
        assertEquals(HeadlightStatus(live, previous, saveFailed = true), states[1])
        assertEquals(previous, history.stored)
        assertTrue(states.last().saveFailed)
        assertNull((states.last().feedback as? HeadlightFeedback.Confirmed)?.observation)
    }
}
