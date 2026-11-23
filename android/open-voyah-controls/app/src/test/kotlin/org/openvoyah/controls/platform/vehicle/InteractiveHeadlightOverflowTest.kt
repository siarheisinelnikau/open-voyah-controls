package org.openvoyah.controls.platform.vehicle

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.domain.vehicle.AutomotiveHeadlightSignal
import org.openvoyah.controls.domain.vehicle.ExteriorLamp
import org.openvoyah.controls.domain.vehicle.HeadlightConfirmationWindow
import org.openvoyah.controls.domain.vehicle.HeadlightFeedback
import org.openvoyah.controls.domain.vehicle.HeadlightHistory
import org.openvoyah.controls.domain.vehicle.HeadlightInput
import org.openvoyah.controls.domain.vehicle.HeadlightInputState
import org.openvoyah.controls.domain.vehicle.HeadlightMode
import org.openvoyah.controls.domain.vehicle.HeadlightMonitor
import org.openvoyah.controls.domain.vehicle.HeadlightObservation
import org.openvoyah.controls.domain.vehicle.HeadlightSource
import org.openvoyah.controls.domain.vehicle.HeadlightStatus
import org.openvoyah.controls.domain.vehicle.LampActivity
import org.openvoyah.controls.domain.vehicle.LampObservation

class InteractiveHeadlightOverflowTest {
    @Test fun powerLossDuringASelectorSaveKeepsBothReadingsAsHistory() = BlockingSaveFixture().observe { change, publish ->
        val highBeam = HeadlightFeedback.ActivityConfirmed(
            ExteriorLamp.HIGH_BEAM, LampObservation(LampActivity.ON, 1_010L), 110L,
        )
        assertTrue(publish(highBeam))
        val before = states.receiveAsFlow().first { it.lamps[highBeam.lamp]?.current == highBeam.observation }

        val selector = HeadlightFeedback.Confirmed(HeadlightObservation(HeadlightMode.AUTOMATIC, 1_020L), 120L)
        assertTrue(publish(selector))
        saveEntered.await()
        change(false, 200L)
        releaseSave()

        val saved = states.receiveAsFlow().first { state ->
            assertNull("Selector was current after power loss", state.currentObservation)
            assertNull("High beam was current after power loss", state.lamps[highBeam.lamp]?.current)
            state.lastConfirmed == selector.observation
        }
        assertEquals(highBeam.observation, saved.lamps[highBeam.lamp]?.lastConfirmed)
        assertEquals(highBeam.observation, before.lamps[highBeam.lamp]?.current)
        assertEquals(selector.observation, history.read())
    }

    @Test fun headlampLossDuringItsSaveKeepsHighBeamCurrentAndAllowsRecovery() =
        BlockingSaveFixture(ExteriorLamp.HEADLIGHTS).observe { _, publish ->
            val highBeam = HeadlightFeedback.ActivityConfirmed(
                ExteriorLamp.HIGH_BEAM, LampObservation(LampActivity.ON, 1_010L), 110L,
            )
            assertTrue(publish(highBeam))
            states.receiveAsFlow().first { it.lamps[highBeam.lamp]?.current == highBeam.observation }

            val headlamp = HeadlightFeedback.ActivityConfirmed(
                ExteriorLamp.HEADLIGHTS, LampObservation(LampActivity.ON, 1_020L), 120L,
            )
            assertTrue(publish(headlamp))
            saveEntered.await()
            assertTrue(publish(HeadlightFeedback.ActivityUnavailable(headlamp.lamp)))
            releaseSave()

            states.receiveAsFlow().first { state ->
                assertNull("Headlamps were current after losing the signal", state.lamps[headlamp.lamp]?.current)
                assertEquals(highBeam.observation, state.lamps[highBeam.lamp]?.current)
                state.lamps[headlamp.lamp]?.lastConfirmed == headlamp.observation
            }
            assertEquals(headlamp.observation, history.readLamps()[headlamp.lamp])

            val recovered = HeadlightFeedback.ActivityConfirmed(
                headlamp.lamp, LampObservation(LampActivity.OFF, 1_030L), 130L,
            )
            assertTrue(publish(recovered))
            val live = states.receiveAsFlow().first { it.lamps[headlamp.lamp]?.current == recovered.observation }
            assertEquals(highBeam.observation, live.lamps[highBeam.lamp]?.current)
            assertEquals(recovered.observation, history.readLamps()[headlamp.lamp])
        }

    @Test fun automotiveLossRevokesAnInFlightSaveEvenWhenTheCallbackQueueDropsTheLoss() =
        BlockingSaveFixture(ExteriorLamp.HEADLIGHTS).observe { _, publish ->
            coroutineScope {
                val signal = AutomotiveHeadlightSignal.HEADLIGHTS
                val lamp = ExteriorLamp.HEADLIGHTS
                val source = HeadlightSource.ANDROID_AUTOMOTIVE
                val subscription = AutomotiveHeadlightSubscription(100L, signal)
                val highBeam = AutomotiveHeadlightSubscription(100L, AutomotiveHeadlightSignal.HIGH_BEAM)
                val resumeHandoff = CompletableDeferred<Unit>()
                val forwarded = Channel<HeadlightFeedback>(Channel.UNLIMITED)
                val handoff = launch {
                    subscription.feedback.collect { report ->
                        assertTrue(publish(report.withConfirmationSource(source)))
                        forwarded.send(report)
                        if ((report as? HeadlightFeedback.Timed)?.eventElapsedNanos == 120L) resumeHandoff.await()
                    }
                }
                try {
                    highBeam.accept(1, 110L, 110L, 1_010L)
                    val beamReading = highBeam.feedback.first() as HeadlightFeedback.ActivityConfirmed
                    val currentBeam = beamReading.observation.copy(source = source)
                    assertTrue(publish(beamReading.withConfirmationSource(source)))
                    states.receiveAsFlow().first { it.lamps[ExteriorLamp.HIGH_BEAM]?.current == currentBeam }

                    subscription.accept(1, 120L, 120L, 1_020L)
                    assertEquals(120L, (forwarded.receive() as HeadlightFeedback.Timed).eventElapsedNanos)
                    saveEntered.await()
                    subscription.accept(null, 130L, 130L, 1_030L, available = false)
                    subscription.accept(1, 140L, 140L, 1_040L)
                    subscription.accept(0, 150L, 150L, 1_050L)

                    releaseSave()
                    val prior = LampObservation(LampActivity.ON, 1_020L, source)
                    val historical = states.receiveAsFlow().first { it.lamps[lamp]?.lastConfirmed == prior }
                    assertNull("The old report became current after the callback reported signal loss", historical.lamps[lamp]?.current)
                    assertEquals(currentBeam, historical.lamps[ExteriorLamp.HIGH_BEAM]?.current)
                    assertEquals(prior, history.readLamps()[lamp])

                    resumeHandoff.complete(Unit)
                    // The real two-slot queue now contains only the two recovery reports.
                    val recoveryReports = List(2) { forwarded.receive() as HeadlightFeedback.Timed }
                    assertEquals(listOf(140L, 150L), recoveryReports.map { it.eventElapsedNanos })
                    val latest = LampObservation(LampActivity.OFF, 1_050L, source)
                    val recovered = states.receiveAsFlow().first { it.lamps[lamp]?.current == latest }
                    assertEquals(latest, history.readLamps()[lamp])
                    assertEquals(currentBeam, recovered.lamps[ExteriorLamp.HIGH_BEAM]?.current)

                    subscription.close()
                    assertFalse(recoveryReports.last().isCurrent)
                    assertTrue(beamReading.isCurrent)
                    assertEquals(latest, recovered.lamps[lamp]?.current)
                } finally {
                    subscription.close()
                    highBeam.close()
                    handoff.cancelAndJoin()
                }
            }
        }

    @Test fun sourceReplacementDuringASelectorSaveShowsTheNewSourceAsCurrent() =
        BlockingSaveFixture().observe { _, publish ->
            val factory = HeadlightFeedback.Confirmed(
                HeadlightObservation(HeadlightMode.LOW_BEAM, 1_010L, HeadlightSource.FACTORY), 110L,
            )
            val androidWindow = HeadlightConfirmationWindow()
            val android = HeadlightFeedback.Confirmed(
                HeadlightObservation(HeadlightMode.ON, 1_020L, HeadlightSource.ANDROID_AUTOMOTIVE), 120L,
                confirmationWindow = androidWindow,
            )
            assertTrue(publish(factory))
            saveEntered.await()
            assertTrue(publish(android))
            releaseSave()

            val live = states.receiveAsFlow().first { state ->
                assertFalse("Factory selector was current after source replacement", state.currentObservation?.source == HeadlightSource.FACTORY)
                state.currentObservation == android.observation
            }
            assertEquals(android.observation, live.lastConfirmed)
            assertEquals(android.observation, history.read())

            val newerFactory = factory.copy(observation = factory.observation.copy(confirmedAtMillis = 1_030L), eventElapsedNanos = 130L)
            assertTrue(publish(newerFactory))
            states.receiveAsFlow().first { it.currentObservation == newerFactory.observation }
            assertTrue(androidWindow.isOpen)
        }

    @Test fun rapidAutomotiveReconnectRevokesAnInFlightSaveBeforeTheReadyEventIsCollected() =
        BlockingSaveFixture(ExteriorLamp.HEADLIGHTS).observe { _, publish ->
            coroutineScope {
                val car = Any()
                val lifecycle = CompletableDeferred<(Any, Boolean) -> Unit>()
                val disconnect: () -> Unit = {}
                val connectionChanges = automotiveHeadlightConnections<Any> { changed ->
                    lifecycle.complete(changed)
                    disconnect
                }
                val resumeConnectionReader = CompletableDeferred<Unit>()
                val forwarded = Channel<AutomotiveHeadlightConnection<Any>?>(Channel.UNLIMITED)
                val connectionReader = launch {
                    connectionChanges.collect { connection ->
                        forwarded.send(connection)
                        if (connection != null) resumeConnectionReader.await()
                    }
                }
                val changed = lifecycle.await()
                assertNull(forwarded.receive())
                changed(car, true)
                val first = checkNotNull(forwarded.receive())
                val lamp = ExteriorLamp.HEADLIGHTS
                val beam = ExteriorLamp.HIGH_BEAM
                val source = HeadlightSource.ANDROID_AUTOMOTIVE
                val headlight = AutomotiveHeadlightSubscription(100L, AutomotiveHeadlightSignal.HEADLIGHTS, first.window)
                val highBeam = AutomotiveHeadlightSubscription(100L, AutomotiveHeadlightSignal.HIGH_BEAM, first.window)
                try {
                    val factory = HeadlightFeedback.Confirmed(
                        HeadlightObservation(HeadlightMode.AUTOMATIC, 1_010L, HeadlightSource.FACTORY), 110L,
                    )
                    assertTrue(publish(factory))
                    highBeam.accept(1, 120L, 120L, 1_020L)
                    val priorBeam = LampObservation(LampActivity.ON, 1_020L, source)
                    assertTrue(publish(highBeam.feedback.first().withConfirmationSource(source)))
                    val beforeLoss = states.receiveAsFlow().first { it.lamps[beam]?.current == priorBeam }

                    headlight.accept(1, 130L, 130L, 1_030L)
                    assertTrue(publish(headlight.feedback.first().withConfirmationSource(source)))
                    saveEntered.await()
                    changed(car, false)
                    changed(car, true)
                    headlight.accept(0, 140L, 140L, 1_040L)
                    releaseSave()

                    val prior = LampObservation(LampActivity.ON, 1_030L, source)
                    val saved = states.receiveAsFlow().first { it.lamps[lamp]?.lastConfirmed == prior }
                    assertNull("The disconnected CarService report was published as current", saved.lamps[lamp]?.current)
                    assertNull(saved.lamps[beam]?.current)
                    assertEquals(priorBeam, saved.lamps[beam]?.lastConfirmed)
                    assertEquals(factory.observation, saved.currentObservation)
                    assertEquals(priorBeam, beforeLoss.lamps[beam]?.current)
                    assertEquals(prior, history.readLamps()[lamp])

                    resumeConnectionReader.complete(Unit)
                    val recovered = checkNotNull(forwarded.receive())
                    assertSame(car, recovered.car)
                    assertNotSame(first.window, recovered.window)
                    headlight.close()
                    assertTrue(headlight.feedback.toList().isEmpty())
                    val fresh = AutomotiveHeadlightSubscription(150L, AutomotiveHeadlightSignal.HEADLIGHTS, recovered.window)
                    try {
                        fresh.accept(0, 160L, 160L, 1_060L)
                        assertTrue(publish(fresh.feedback.first().withConfirmationSource(source)))
                        val latest = LampObservation(LampActivity.OFF, 1_060L, source)
                        val current = states.receiveAsFlow().first { it.lamps[lamp]?.current == latest }
                        assertEquals(factory.observation, current.currentObservation)
                        assertEquals(latest, history.readLamps()[lamp])
                    } finally {
                        fresh.close()
                    }
                } finally {
                    headlight.close()
                    highBeam.close()
                    connectionReader.cancelAndJoin()
                }
            }
        }

    @Test fun aBlockedMonitorRetainsIndependentRevocationsAndSavesQueuedConfirmationsAsHistory() = runBlocking {
        withTimeout(5_000) {
            val power = CompletableDeferred<(Boolean, Long) -> Unit>()
            val publisher = CompletableDeferred<(HeadlightFeedback) -> Boolean>()
            val releaseConsumer = CompletableDeferred<Unit>()
            val selector = HeadlightFeedback.Confirmed(HeadlightObservation(HeadlightMode.AUTOMATIC, 1_010L), 110L)
            val initial = HeadlightFeedback.ActivityConfirmed(ExteriorLamp.HEADLIGHTS, LampObservation(LampActivity.ON, 1_020L), 120L)
            val pendingSelector = HeadlightFeedback.Confirmed(HeadlightObservation(HeadlightMode.OFF, 1_030L), 130L)
            val pendingLamp = HeadlightFeedback.ActivityConfirmed(ExteriorLamp.HEADLIGHTS, LampObservation(LampActivity.OFF, 1_040L), 140L)
            val burst = List(24) { index ->
                HeadlightFeedback.ActivityConfirmed(
                    ExteriorLamp.HIGH_BEAM, LampObservation(LampActivity.ON, 2_000L + index), 200L + index,
                )
            }
            val feedback = interactiveHeadlightFeedback(
                watchPower = { changed -> power.complete(changed); awaitCancellation() },
                read = { publish -> publisher.complete(publish); awaitCancellation() },
            ).transform { update ->
                emit(update)
                if (update is HeadlightFeedback.ActivityConfirmed && update.copy(confirmationWindow = null) == initial) {
                    releaseConsumer.await()
                }
            }
            val history = MemoryHistory()
            val states = Channel<HeadlightStatus>(Channel.UNLIMITED)
            val collector = launch {
                HeadlightMonitor(feedback, history, Dispatchers.Unconfined).observe().collect(states::send)
            }
            try {
                power.await()(true, 100L)
                val publish = publisher.await()
                assertTrue(publish(selector))
                states.receiveAsFlow().first { it.currentObservation == selector.observation }
                assertTrue(publish(initial))
                states.receiveAsFlow().first { it.lamps[ExteriorLamp.HEADLIGHTS]?.current == initial.observation }

                assertTrue(publish(pendingSelector))
                assertTrue(publish(pendingLamp))
                assertTrue(publish(HeadlightFeedback.SelectorUnavailable))
                assertTrue(publish(HeadlightFeedback.ActivityUnavailable(ExteriorLamp.HEADLIGHTS)))
                burst.forEach { assertTrue(publish(it)) }
                releaseConsumer.complete(Unit)

                val latest = states.receiveAsFlow().first {
                    assertFalse(it.currentObservation == pendingSelector.observation)
                    assertFalse(it.lamps[ExteriorLamp.HEADLIGHTS]?.current == pendingLamp.observation)
                    it.lamps[ExteriorLamp.HIGH_BEAM]?.current == burst.last().observation
                }
                assertNull(latest.currentObservation)
                assertNull(latest.lamps[ExteriorLamp.HEADLIGHTS]?.current)
                assertEquals(pendingSelector.observation, latest.lastConfirmed)
                assertEquals(pendingLamp.observation, latest.lamps[ExteriorLamp.HEADLIGHTS]?.lastConfirmed)
                assertEquals(pendingSelector.observation, history.read())
                assertEquals(pendingLamp.observation, history.readLamps()[ExteriorLamp.HEADLIGHTS])
            } finally {
                releaseConsumer.complete(Unit)
                collector.cancelAndJoin()
            }
        }
    }

    @Test fun aFinishedReaderCannotPublishAnotherLiveReading() = runBlocking {
        withTimeout(5_000) {
            val power = CompletableDeferred<(Boolean, Long) -> Unit>()
            val publisher = CompletableDeferred<(HeadlightFeedback) -> Boolean>()
            val finish = CompletableDeferred<Unit>()
            val updates = Channel<HeadlightFeedback>(Channel.UNLIMITED)
            val observation = interactiveHeadlightFeedback(
                watchPower = { changed -> power.complete(changed); awaitCancellation() },
                read = { publish -> publisher.complete(publish); finish.await() },
            )
            val collector = launch { observation.collect(updates::send) }
            try {
                power.await()(true, 100L)
                assertEquals(HeadlightFeedback.AwaitingController, updates.receive())
                val publish = publisher.await()
                finish.complete(Unit)
                assertEquals(HeadlightFeedback.ReadersFinished, updates.receive())
                val late = HeadlightFeedback.Confirmed(HeadlightObservation(HeadlightMode.OFF, 1_010L), 110L)
                assertFalse(publish(late))
            } finally {
                collector.cancelAndJoin()
            }
        }
    }

    @Test fun latestReadinessAndWakeBoundarySurviveABurstWhileTheConsumerIsBlocked() = runBlocking {
        withTimeout(5_000) {
            val power = CompletableDeferred<(Boolean, Long) -> Unit>()
            val publishers = Channel<(HeadlightFeedback) -> Boolean>(Channel.UNLIMITED)
            val updates = Channel<HeadlightFeedback>(Channel.UNLIMITED)
            val releaseConsumer = CompletableDeferred<Unit>()
            val oldLamp = HeadlightFeedback.ActivityConfirmed(ExteriorLamp.HIGH_BEAM, LampObservation(LampActivity.ON, 1_000L), 110L)
            val observation = interactiveHeadlightFeedback(
                watchPower = { changed -> power.complete(changed); awaitCancellation() },
                read = { publish -> publishers.send(publish); awaitCancellation() },
            )
            val collector = launch {
                observation.collect { update ->
                    updates.send(update)
                    if (update is HeadlightFeedback.ActivityConfirmed && update.copy(confirmationWindow = null) == oldLamp) {
                        releaseConsumer.await()
                    }
                }
            }
            try {
                val change = power.await()
                change(true, 100L)
                assertEquals(HeadlightFeedback.AwaitingController, updates.receive())
                val oldPublisher = publishers.receive()
                val oldInput = HeadlightFeedback.ReaderState(HeadlightInput.ANDROID_HIGH_BEAM, HeadlightInputState.SUBSCRIBED)
                assertTrue(oldPublisher(oldInput))
                assertEquals(oldInput, updates.receive())
                assertTrue(oldPublisher(oldLamp))
                val liveLamp = updates.receive() as HeadlightFeedback.ActivityConfirmed
                assertTrue(liveLamp.isCurrent)
                assertEquals(oldLamp, liveLamp.copy(confirmationWindow = null))

                val beforeSleep = HeadlightFeedback.Confirmed(HeadlightObservation(HeadlightMode.POSITION, 1_010L), 115L)
                assertTrue(oldPublisher(beforeSleep))
                change(false, 200L)
                change(true, 300L)
                val newPublisher = publishers.receive()
                assertFalse(oldPublisher(oldInput))
                val expected = listOf(
                    HeadlightFeedback.ReaderState(HeadlightInput.FACTORY_SELECTOR, HeadlightInputState.MODEL_UNREVIEWED),
                    HeadlightFeedback.ReaderState(HeadlightInput.ANDROID_SELECTOR, HeadlightInputState.PERMISSION_DENIED),
                    HeadlightFeedback.ReaderState(HeadlightInput.ANDROID_HEADLAMPS, HeadlightInputState.SUBSCRIBED),
                )
                expected.forEach { assertTrue(newPublisher(it)) }
                val burst = List(24) { index ->
                    HeadlightFeedback.ActivityConfirmed(
                        ExteriorLamp.HEADLIGHTS, LampObservation(LampActivity.OFF, 2_000L + index), 310L + index,
                    )
                }
                burst.forEach { assertTrue(newPublisher(it)) }
                releaseConsumer.complete(Unit)

                assertEquals(HeadlightFeedback.AwaitingController, updates.receive())
                assertEquals(expected, List(expected.size) { updates.receive() })
                val historyOnly = updates.receive() as HeadlightFeedback.Confirmed
                assertFalse(historyOnly.isCurrent)
                assertEquals(beforeSleep, historyOnly.copy(confirmationWindow = null))
                var delivered: HeadlightFeedback.ActivityConfirmed
                do {
                    val report = updates.receive() as HeadlightFeedback.ActivityConfirmed
                    assertTrue(report.isCurrent)
                    delivered = report.copy(confirmationWindow = null)
                    assertTrue(delivered in burst)
                } while (delivered != burst.last())
                assertTrue(updates.tryReceive().isFailure)
            } finally {
                releaseConsumer.complete(Unit)
                collector.cancelAndJoin()
            }
        }
    }

    private class BlockingSaveFixture(blockedLamp: ExteriorLamp? = null) {
        private val released = CountDownLatch(1)
        val saveEntered = CompletableDeferred<Unit>()
        val states = Channel<HeadlightStatus>(Channel.UNLIMITED)
        val history = MemoryHistory { lamp ->
            if (lamp == blockedLamp) {
                saveEntered.complete(Unit)
                check(released.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release the history save" }
            }
        }

        fun observe(verify: suspend BlockingSaveFixture.((Boolean, Long) -> Unit, (HeadlightFeedback) -> Boolean) -> Unit) = runBlocking {
            withTimeout(5_000) {
                val power = CompletableDeferred<(Boolean, Long) -> Unit>()
                val publisher = CompletableDeferred<(HeadlightFeedback) -> Boolean>()
                val feedback = interactiveHeadlightFeedback(
                    watchPower = { changed -> power.complete(changed); awaitCancellation() },
                    read = { publish -> publisher.complete(publish); awaitCancellation() },
                )
                val collector = launch {
                    HeadlightMonitor(feedback, history, Dispatchers.IO).observe().collect(states::send)
                }
                try {
                    val change = power.await()
                    change(true, 100L)
                    verify(this@BlockingSaveFixture, change, publisher.await())
                } finally {
                    releaseSave()
                    collector.cancelAndJoin()
                }
            }
        }

        fun releaseSave() = released.countDown()
    }

    private class MemoryHistory(private val beforeSave: (ExteriorLamp?) -> Unit = {}) : HeadlightHistory {
        private var selector: HeadlightObservation? = null
        private val lamps = mutableMapOf<ExteriorLamp, LampObservation>()

        override fun read() = selector
        override fun readLamps() = lamps.toMap()
        override fun save(observation: HeadlightObservation): Boolean {
            beforeSave(null)
            selector = observation
            return true
        }
        override fun saveLamp(lamp: ExteriorLamp, observation: LampObservation): Boolean {
            beforeSave(lamp)
            lamps[lamp] = observation
            return true
        }
    }
}
