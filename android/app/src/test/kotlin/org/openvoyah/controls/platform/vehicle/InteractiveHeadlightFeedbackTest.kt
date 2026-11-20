package org.openvoyah.controls.platform.vehicle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.domain.vehicle.AutomotiveHeadlightSignal
import org.openvoyah.controls.domain.vehicle.EarlierHeadlightReport
import org.openvoyah.controls.domain.vehicle.ExteriorLamp
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

class InteractiveHeadlightFeedbackTest {
    private class Observer(val publish: (HeadlightFeedback) -> Boolean) {
        val releaseStarted = CompletableDeferred<Unit>()
        var holdRelease: CompletableDeferred<Unit>? = null
    }

    private class Fixture : HeadlightHistory {
        val power = CompletableDeferred<(Boolean, Long) -> Unit>()
        val watcherRemoved = CompletableDeferred<Unit>()
        val observers = Channel<Observer>(Channel.UNLIMITED)
        val saved = mutableListOf<HeadlightObservation>()
        private val lamps = mutableMapOf<ExteriorLamp, LampObservation>()
        override fun read() = saved.lastOrNull()
        override fun save(observation: HeadlightObservation) = saved.add(observation)
        override fun readLamps() = lamps.toMap()
        override fun saveLamp(lamp: ExteriorLamp, observation: LampObservation): Boolean {
            lamps[lamp] = observation
            return true
        }

        val feedback = interactiveHeadlightFeedback({ changed ->
            power.complete(changed)
            try { awaitCancellation() } finally { watcherRemoved.complete(Unit) }
        }) { publish ->
            val observer = Observer(publish)
            observers.send(observer)
            try { awaitCancellation() } finally {
                observer.releaseStarted.complete(Unit)
                withContext(NonCancellable) { observer.holdRelease?.await() }
            }
        }
    }

    private class SourceFixture {
        val factory = Channel<HeadlightFeedback>(Channel.UNLIMITED)
        val automotive = Channel<HeadlightFeedback>(Channel.UNLIMITED)
        val failures = Channel<Pair<HeadlightSource, Throwable>>(Channel.UNLIMITED)
        val states = Channel<HeadlightStatus>(Channel.UNLIMITED)
        val history = Fixture()
        val monitor = HeadlightMonitor(channelFlow {
            readHeadlightSources(
                factory.receiveAsFlow(), automotive.receiveAsFlow(),
                onSourceFailure = { source, failure -> failures.trySend(source to failure) },
            ) { _, report -> trySend(report) }
        }, history, Dispatchers.Unconfined)
    }

    private fun confirmed(mode: HeadlightMode, elapsed: Long) =
        HeadlightFeedback.Confirmed(HeadlightObservation(mode, 10_000L + elapsed), elapsed)

    private fun lamp(activity: LampActivity, elapsed: Long, lamp: ExteriorLamp = ExteriorLamp.HEADLIGHTS) =
        HeadlightFeedback.ActivityConfirmed(lamp, LampObservation(activity, 10_000L + elapsed), elapsed)

    @Test fun automotiveSelectorReportsWhileFactoryWaitsAndLampStatusStaysIndependent() = runBlocking {
        withTimeout(5_000) {
            val factory = Channel<HeadlightFeedback>(Channel.UNLIMITED)
            val started = Channel<AutomotiveHeadlightSignal>(Channel.UNLIMITED)
            val standard = AutomotiveHeadlightSignal.entries.associateWith {
                Channel<HeadlightFeedback>(Channel.UNLIMITED)
            }
            val feedback = channelFlow {
                val automotive = automotiveHeadlightChannels(true, true) { signal ->
                    started.trySend(signal)
                    standard.getValue(signal).receiveAsFlow()
                }
                readHeadlightSources(factory.receiveAsFlow(), automotive) { _, report -> trySend(report) }
            }
            val history = Fixture()
            val states = Channel<HeadlightStatus>(Channel.UNLIMITED)
            val collection = launch { HeadlightMonitor(feedback, history, Dispatchers.Unconfined).observe().collect(states::send) }
            try {
                assertEquals(HeadlightFeedback.AwaitingController, states.receive().feedback)
                val signals = AutomotiveHeadlightSignal.entries.toSet()
                assertEquals(signals, List(signals.size) { started.receive() }.toSet())
                assertTrue(started.tryReceive().isFailure)

                factory.send(HeadlightFeedback.AwaitingController)
                val beforeSubscription = EarlierHeadlightReport(HeadlightMode.OFF, 10_090L)
                standard.getValue(AutomotiveHeadlightSignal.SELECTOR).send(HeadlightFeedback.EarlierSelectorReport(beforeSubscription))
                val initial = states.receive()
                assertEquals(beforeSubscription, initial.earlierReport)
                assertNull(initial.currentObservation)
                assertTrue(history.saved.isEmpty())
                val earlyAutomotive = confirmed(HeadlightMode.AUTOMATIC, 110L)
                standard.getValue(AutomotiveHeadlightSignal.SELECTOR).send(earlyAutomotive)
                assertEquals(earlyAutomotive.observation, states.receive().currentObservation)

                val position = confirmed(HeadlightMode.POSITION, 115L)
                factory.send(position)
                assertEquals(position.observation, states.receive().currentObservation)
                val headlights = lamp(LampActivity.ON, 120L)
                standard.getValue(AutomotiveHeadlightSignal.HEADLIGHTS).send(headlights)
                assertEquals(headlights.observation, states.receive().lamps[ExteriorLamp.HEADLIGHTS]?.current)
                val highBeam = HeadlightFeedback.ActivityConfirmed(
                    ExteriorLamp.HIGH_BEAM, LampObservation(LampActivity.OFF, 10_125L), 125L,
                )
                standard.getValue(AutomotiveHeadlightSignal.HIGH_BEAM).send(highBeam)
                val bothLive = states.receive()
                assertEquals(highBeam.observation, bothLive.lamps[ExteriorLamp.HIGH_BEAM]?.current)
                assertEquals(position.observation, bothLive.currentObservation)

                factory.send(HeadlightFeedback.Unavailable)
                val disconnected = states.receive()
                assertNull(disconnected.currentObservation)
                assertEquals(HeadlightFeedback.AwaitingController, disconnected.feedback)
                assertEquals(position.observation, disconnected.lastConfirmed)
                assertEquals(bothLive.lamps, disconnected.lamps)
                factory.send(HeadlightFeedback.AwaitingController)
                assertTrue(started.tryReceive().isFailure)

                standard.getValue(AutomotiveHeadlightSignal.HEADLIGHTS).send(
                    HeadlightFeedback.ActivityUnavailable(ExteriorLamp.HEADLIGHTS),
                )
                val oneLampUnavailable = states.receive()
                assertEquals(HeadlightFeedback.AwaitingController, oneLampUnavailable.feedback)
                assertNull(oneLampUnavailable.lamps[ExteriorLamp.HEADLIGHTS]?.current)
                assertEquals(headlights.observation, oneLampUnavailable.lamps[ExteriorLamp.HEADLIGHTS]?.lastConfirmed)
                assertEquals(highBeam.observation, oneLampUnavailable.lamps[ExteriorLamp.HIGH_BEAM]?.current)

                val standardSelector = confirmed(HeadlightMode.ON, 150L)
                standard.getValue(AutomotiveHeadlightSignal.SELECTOR).send(standardSelector)
                val switched = states.receive()
                assertEquals(standardSelector.observation, switched.currentObservation)
                assertEquals(oneLampUnavailable.lamps, switched.lamps)
                assertEquals(listOf(earlyAutomotive, position, standardSelector).map { it.observation }, history.saved)

                factory.close()
                val newHeadlights = lamp(LampActivity.OFF, 160L)
                standard.getValue(AutomotiveHeadlightSignal.HEADLIGHTS).send(newHeadlights)
                val afterClose = states.receive()
                assertEquals(newHeadlights.observation, afterClose.lamps[ExteriorLamp.HEADLIGHTS]?.current)
                assertEquals(standardSelector.observation, afterClose.currentObservation)
            } finally {
                collection.cancelAndJoin()
            }
        }
    }

    @Test fun selectorFailoverUsesOnlyNewerFeedbackFromAnAvailableSource() = runBlocking {
        withTimeout(5_000) {
            val factory = Channel<HeadlightFeedback>(Channel.UNLIMITED)
            val standard = Channel<HeadlightFeedback>(Channel.UNLIMITED)
            val reports = Channel<Pair<HeadlightSource, HeadlightFeedback>>(Channel.UNLIMITED)
            val lateFactory = confirmed(HeadlightMode.POSITION, 299L)
            val sameTimeFactory = confirmed(HeadlightMode.LOW_BEAM, 300L)
            val duplicatesQueued = CompletableDeferred<Unit>()
            val factoryFlow = flow {
                for (feedback in factory) {
                    emit(feedback)
                    if (feedback == sameTimeFactory) duplicatesQueued.complete(Unit)
                }
            }
            val collection = launch {
                readHeadlightSources(factoryFlow, standard.receiveAsFlow()) { source, report -> reports.trySend(source to report) }
            }
            try {
                standard.send(HeadlightFeedback.SelectorUnavailable)
                assertEquals(HeadlightSource.FACTORY to HeadlightFeedback.SelectorAwaitingController, reports.receive())

                val position = confirmed(HeadlightMode.POSITION, 200L)
                factory.send(position)
                assertEquals(HeadlightSource.FACTORY to position, reports.receive())

                val alternative = confirmed(HeadlightMode.AUTOMATIC, 300L)
                standard.send(alternative)
                standard.send(confirmed(HeadlightMode.OFF, 250L))
                val lampsOn = lamp(LampActivity.ON, 310L)
                standard.send(lampsOn)
                assertEquals(HeadlightSource.ANDROID_AUTOMOTIVE to lampsOn, reports.receive())

                val lowBeam = confirmed(HeadlightMode.LOW_BEAM, 275L)
                factory.send(lowBeam)
                assertEquals(HeadlightSource.FACTORY to lowBeam, reports.receive())
                factory.send(HeadlightFeedback.Unavailable)
                assertEquals(HeadlightSource.ANDROID_AUTOMOTIVE to alternative, reports.receive())

                factory.send(lateFactory)
                factory.send(sameTimeFactory)
                duplicatesQueued.await()
                val automotiveOff = confirmed(HeadlightMode.OFF, 400L)
                standard.send(automotiveOff)
                assertEquals(HeadlightSource.ANDROID_AUTOMOTIVE to automotiveOff, reports.receive())

                val factoryAuto = confirmed(HeadlightMode.AUTOMATIC, 500L)
                factory.send(factoryAuto)
                assertEquals(HeadlightSource.FACTORY to factoryAuto, reports.receive())
                standard.send(confirmed(HeadlightMode.ON, 600L))
                standard.send(HeadlightFeedback.SelectorUnavailable)
                val lampsOff = lamp(LampActivity.OFF, 610L)
                standard.send(lampsOff)
                assertEquals(HeadlightSource.ANDROID_AUTOMOTIVE to lampsOff, reports.receive())
                factory.send(HeadlightFeedback.Unavailable)
                assertEquals(HeadlightSource.FACTORY to HeadlightFeedback.SelectorUnavailable, reports.receive())

                val resumed = confirmed(HeadlightMode.ON, 700L)
                standard.send(resumed)
                assertEquals(HeadlightSource.ANDROID_AUTOMOTIVE to resumed, reports.receive())
            } finally {
                collection.cancelAndJoin()
            }
        }
    }

    @Test fun factoryReaderFailurePreservesBothLiveLampsAndTheirSubsequentConfirmations() = runBlocking {
        withTimeout(5_000) {
            val fixture = SourceFixture()
            val collection = launch { fixture.monitor.observe().collect(fixture.states::send) }
            try {
                assertEquals(HeadlightFeedback.AwaitingController, fixture.states.receive().feedback)
                val position = confirmed(HeadlightMode.POSITION, 110L)
                fixture.factory.send(position)
                assertEquals(position.observation, fixture.states.receive().currentObservation)

                val headlights = lamp(LampActivity.ON, 120L)
                fixture.automotive.send(headlights)
                assertEquals(headlights.observation, fixture.states.receive().lamps[headlights.lamp]?.current)
                val highBeam = lamp(LampActivity.OFF, 125L, ExteriorLamp.HIGH_BEAM)
                fixture.automotive.send(highBeam)
                val bothLive = fixture.states.receive()
                assertEquals(highBeam.observation, bothLive.lamps[highBeam.lamp]?.current)

                val failure = IllegalArgumentException("Factory observer reply failed")
                fixture.factory.close(failure)
                val afterFailure = fixture.states.receive()
                assertEquals(HeadlightFeedback.AwaitingController, afterFailure.feedback)
                assertNull(afterFailure.currentObservation)
                assertEquals(position.observation, afterFailure.lastConfirmed)
                assertEquals(bothLive.lamps, afterFailure.lamps)
                val (source, reportedFailure) = fixture.failures.receive()
                assertEquals(HeadlightSource.FACTORY, source)
                assertEquals(failure.javaClass, reportedFailure.javaClass)

                val headlightsOff = lamp(LampActivity.OFF, 140L)
                fixture.automotive.send(headlightsOff)
                val updated = fixture.states.receive()
                assertEquals(headlightsOff.observation, updated.lamps[headlightsOff.lamp]?.current)
                assertEquals(highBeam.observation, updated.lamps[highBeam.lamp]?.current)
                assertEquals(mapOf(headlightsOff.lamp to headlightsOff.observation, highBeam.lamp to highBeam.observation),
                    fixture.history.readLamps())
                assertEquals(position.observation, fixture.history.read())
            } finally {
                collection.cancelAndJoin()
            }
            assertTrue(fixture.failures.tryReceive().isFailure)
        }
    }

    @Test fun automotiveReaderFailurePreservesTheLiveFactorySelectorAndItsSubsequentConfirmation() = runBlocking {
        withTimeout(5_000) {
            val fixture = SourceFixture()
            val collection = launch { fixture.monitor.observe().collect(fixture.states::send) }
            try {
                assertEquals(HeadlightFeedback.AwaitingController, fixture.states.receive().feedback)
                val position = confirmed(HeadlightMode.POSITION, 110L)
                fixture.factory.send(position)
                assertEquals(position.observation, fixture.states.receive().currentObservation)

                val headlights = lamp(LampActivity.ON, 120L)
                fixture.automotive.send(headlights)
                assertEquals(headlights.observation, fixture.states.receive().lamps[headlights.lamp]?.current)
                val highBeam = lamp(LampActivity.OFF, 125L, ExteriorLamp.HIGH_BEAM)
                fixture.automotive.send(highBeam)
                assertEquals(highBeam.observation, fixture.states.receive().lamps[highBeam.lamp]?.current)

                val failure = NoClassDefFoundError("Automotive reader failed")
                fixture.automotive.close(failure)
                val firstLoss = fixture.states.receive()
                assertEquals(position.observation, firstLoss.currentObservation)
                val bothUnavailable = fixture.states.receive()
                assertEquals(position.observation, bothUnavailable.currentObservation)
                assertNull(bothUnavailable.lamps[headlights.lamp]?.current)
                assertNull(bothUnavailable.lamps[highBeam.lamp]?.current)
                assertEquals(headlights.observation, bothUnavailable.lamps[headlights.lamp]?.lastConfirmed)
                assertEquals(highBeam.observation, bothUnavailable.lamps[highBeam.lamp]?.lastConfirmed)
                val (source, reportedFailure) = fixture.failures.receive()
                assertEquals(HeadlightSource.ANDROID_AUTOMOTIVE, source)
                assertEquals(failure.javaClass, reportedFailure.javaClass)

                val lowBeam = confirmed(HeadlightMode.LOW_BEAM, 140L)
                fixture.factory.send(lowBeam)
                val updated = fixture.states.receive()
                assertEquals(lowBeam.observation, updated.currentObservation)
                assertEquals(bothUnavailable.lamps, updated.lamps)
                assertEquals(listOf(position.observation, lowBeam.observation), fixture.history.saved)
            } finally {
                collection.cancelAndJoin()
            }
            assertTrue(fixture.failures.tryReceive().isFailure)
        }
    }

    @Test fun screenOffDemotesImmediatelyAndOldCallbacksCannotCrossTheWakeBoundary() = runBlocking {
        withTimeout(5_000) {
            val fixture = Fixture()
            val states = Channel<HeadlightStatus>(Channel.UNLIMITED)
            val collection = launch { HeadlightMonitor(fixture.feedback, fixture, Dispatchers.Unconfined).observe().collect(states::send) }
            val release = CompletableDeferred<Unit>()
            try {
                assertEquals(HeadlightFeedback.AwaitingController, states.receive().feedback)
                val change = fixture.power.await()
                change(false, 50L)
                assertEquals(HeadlightFeedback.Unavailable, states.receive().feedback)
                assertTrue(fixture.observers.tryReceive().isFailure)

                change(true, 100L)
                assertEquals(HeadlightFeedback.AwaitingController, states.receive().feedback)
                val old = fixture.observers.receive()
                old.holdRelease = release
                val subscribed = HeadlightFeedback.ReaderState(HeadlightInput.ANDROID_HEADLAMPS, HeadlightInputState.SUBSCRIBED)
                assertTrue(old.publish(subscribed))
                assertEquals(mapOf(subscribed.input to subscribed.state), states.receive().inputStates)
                val rawPosition = confirmed(HeadlightMode.POSITION, 110L)
                val position = rawPosition.withConfirmationSource(HeadlightSource.FACTORY) as HeadlightFeedback.Confirmed
                assertEquals(rawPosition.eventElapsedNanos, position.eventElapsedNanos)
                assertEquals(rawPosition.observation.copy(source = HeadlightSource.FACTORY), position.observation)
                assertTrue(old.publish(position))
                assertEquals(position.observation, states.receive().currentObservation)
                val rawLights = lamp(LampActivity.ON, 120L)
                val lightsOn = rawLights.withConfirmationSource(HeadlightSource.ANDROID_AUTOMOTIVE) as HeadlightFeedback.ActivityConfirmed
                assertEquals(rawLights.eventElapsedNanos, lightsOn.eventElapsedNanos)
                assertEquals(rawLights.observation.copy(source = HeadlightSource.ANDROID_AUTOMOTIVE), lightsOn.observation)
                assertTrue(old.publish(lightsOn))
                assertEquals(lightsOn.observation, states.receive().lamps[lightsOn.lamp]?.current)

                change(false, 200L)
                val off = states.receive()
                assertEquals(HeadlightFeedback.Unavailable, off.feedback)
                assertTrue(off.inputStates.isEmpty())
                assertNull(off.currentObservation)
                assertEquals(position.observation, off.lastConfirmed)
                assertNull(off.lamps[lightsOn.lamp]?.current)
                assertEquals(lightsOn.observation, off.lamps[lightsOn.lamp]?.lastConfirmed)
                old.releaseStarted.await()
                assertFalse(old.publish(subscribed))
                assertFalse(old.publish(confirmed(HeadlightMode.OFF, 220L)))
                assertFalse(old.publish(lamp(LampActivity.OFF, 225L)))

                change(true, 300L)
                val awake = states.receive()
                assertEquals(HeadlightFeedback.AwaitingController, awake.feedback)
                assertTrue(awake.inputStates.isEmpty())
                assertEquals(position.observation, awake.lastConfirmed)
                assertTrue(fixture.observers.tryReceive().isFailure)
                release.complete(Unit)
                val current = fixture.observers.receive()
                assertFalse(old.publish(subscribed))
                assertFalse(old.publish(confirmed(HeadlightMode.OFF, 330L)))
                assertFalse(current.publish(confirmed(HeadlightMode.OFF, 290L)))
                assertFalse(current.publish(lamp(LampActivity.OFF, 295L)))
                assertEquals(lightsOn.observation, fixture.readLamps()[lightsOn.lamp])
                assertEquals(listOf(position.observation), fixture.saved)

                val low = confirmed(HeadlightMode.LOW_BEAM, 350L)
                assertTrue(current.publish(low))
                assertEquals(low.observation, states.receive().currentObservation)
                assertEquals(listOf(position.observation, low.observation), fixture.saved)
            } finally {
                release.complete(Unit)
                collection.cancelAndJoin()
            }
            assertTrue(fixture.watcherRemoved.isCompleted)
        }
    }

    @Test fun aWakeNoticeStartsFreshEvenWhenTheOffNoticeWasMissed() = runBlocking {
        withTimeout(5_000) {
            val fixture = Fixture()
            val reports = Channel<HeadlightFeedback>(Channel.UNLIMITED)
            val collection = launch { fixture.feedback.collect(reports::send) }
            try {
                val change = fixture.power.await()
                change(true, 100L)
                assertEquals(HeadlightFeedback.AwaitingController, reports.receive())
                val old = fixture.observers.receive()
                val automatic = confirmed(HeadlightMode.AUTOMATIC, 110L)
                assertTrue(old.publish(automatic))
                val firstReport = reports.receive() as HeadlightFeedback.Confirmed
                assertEquals(automatic, firstReport.copy(confirmationWindow = null))
                assertTrue(firstReport.isCurrent)

                change(true, 200L)
                assertEquals(HeadlightFeedback.AwaitingController, reports.receive())
                assertFalse(firstReport.isCurrent)
                val current = fixture.observers.receive()
                assertTrue(old.releaseStarted.isCompleted)
                assertFalse(old.publish(confirmed(HeadlightMode.OFF, 210L)))
                assertFalse(current.publish(automatic))
                val earlier = HeadlightFeedback.EarlierSelectorReport(EarlierHeadlightReport(HeadlightMode.OFF, 10_090L))
                assertFalse(old.publish(earlier))
                assertTrue(current.publish(earlier))
                assertEquals(earlier, reports.receive())
                val second = confirmed(HeadlightMode.POSITION, 220L)
                assertTrue(current.publish(second))
                val secondReport = reports.receive() as HeadlightFeedback.Confirmed
                assertEquals(second, secondReport.copy(confirmationWindow = null))
                assertTrue(secondReport.isCurrent)

                collection.cancelAndJoin()
                assertFalse(secondReport.isCurrent)
                assertTrue(current.releaseStarted.isCompleted)
                assertFalse(current.publish(second))
                assertTrue(fixture.watcherRemoved.isCompleted)
            } finally {
                collection.cancelAndJoin()
            }
        }
    }
}
