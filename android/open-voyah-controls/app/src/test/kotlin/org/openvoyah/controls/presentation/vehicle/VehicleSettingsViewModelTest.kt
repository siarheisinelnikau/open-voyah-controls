package org.openvoyah.controls.presentation.vehicle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openvoyah.controls.domain.vehicle.ExteriorLamp
import org.openvoyah.controls.domain.vehicle.HeadlightFeedback
import org.openvoyah.controls.domain.vehicle.HeadlightHistory
import org.openvoyah.controls.domain.vehicle.HeadlightMode
import org.openvoyah.controls.domain.vehicle.HeadlightMonitor
import org.openvoyah.controls.domain.vehicle.HeadlightMonitoringStore
import org.openvoyah.controls.domain.vehicle.HeadlightObservation
import org.openvoyah.controls.domain.vehicle.LampObservation

class VehicleSettingsViewModelTest {
    @Test fun optInSharesTheVisibleMonitorAndSavesAcrossRoutesUntilForegroundCollectionEnds() = runBlocking {
        val configured = MutableStateFlow(false)
        val store = object : HeadlightMonitoringStore {
            override fun observeWhileControlsOpen() = configured
            override suspend fun setWhileControlsOpen(enabled: Boolean) { configured.value = enabled }
        }
        val starts = Channel<Unit>(Channel.UNLIMITED)
        val stops = Channel<Unit>(Channel.UNLIMITED)
        val reports = Channel<HeadlightFeedback>(capacity = 1)
        val saved = CompletableDeferred<HeadlightObservation>()
        val source = flow {
            starts.send(Unit)
            try {
                for (report in reports) emit(report)
            } finally {
                stops.trySend(Unit)
            }
        }
        val history = object : HeadlightHistory {
            override fun read(): HeadlightObservation? = null
            override fun save(observation: HeadlightObservation) = saved.complete(observation)
            override fun readLamps() = emptyMap<ExteriorLamp, LampObservation>()
            override fun saveLamp(lamp: ExteriorLamp, observation: LampObservation) = false
        }
        val tasks = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val model = VehicleSettingsViewModel({ false }, HeadlightMonitor(source, history, Dispatchers.Unconfined), tasks, store)
        val foreground = launch { observeHeadlightsWhileOpen(configured) { model } }
        try {
            assertNull(withTimeoutOrNull(350) { starts.receive() })
            val vehicle = launch { model.headlightStatus.collect() }
            val preferenceUi = launch { model.headlightMonitoring.collect() }
            withTimeout(3_000) { starts.receive() }
            withTimeout(3_000) { model.headlightMonitoring.first { it.whileControlsOpen == false } }
            model.setHeadlightMonitoring(true)
            withTimeout(3_000) { model.headlightMonitoring.first { it.whileControlsOpen == true } }
            vehicle.cancelAndJoin()
            preferenceUi.cancelAndJoin()

            assertNull(withTimeoutOrNull(450) { stops.receive() })
            assertNull(starts.tryReceive().getOrNull())
            val observation = HeadlightObservation(HeadlightMode.POSITION, 3_000)
            reports.send(HeadlightFeedback.Confirmed(observation, eventElapsedNanos = 6_000))
            assertEquals(observation, withTimeout(3_000) { saved.await() })

            foreground.cancelAndJoin()
            withTimeout(3_000) { stops.receive() }
            assertEquals(true, configured.value)

            val resumed = launch { observeHeadlightsWhileOpen(configured) { model } }
            try {
                withTimeout(3_000) { starts.receive() }
                configured.value = false
                withTimeout(3_000) { stops.receive() }
            } finally {
                resumed.cancel()
            }
        } finally {
            foreground.cancel()
            tasks.cancel()
            reports.close()
        }
    }

    @Test fun savesHeadlightChangesDuringTheFactoryLightingVisitAndStopsWhenItEnds() = runBlocking {
        val subscribed = CompletableDeferred<Unit>()
        val unsubscribed = CompletableDeferred<Unit>()
        val durable = CompletableDeferred<HeadlightObservation>()
        val reports = Channel<HeadlightFeedback>(capacity = 1)
        val source = flow {
            subscribed.complete(Unit)
            try {
                for (report in reports) emit(report)
            } finally {
                unsubscribed.complete(Unit)
            }
        }
        val history = object : HeadlightHistory {
            override fun read(): HeadlightObservation? = null
            override fun save(observation: HeadlightObservation) = durable.complete(observation)
            override fun readLamps() = emptyMap<ExteriorLamp, LampObservation>()
            override fun saveLamp(lamp: ExteriorLamp, observation: LampObservation) = false
        }
        val tasks = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val model = VehicleSettingsViewModel({ false }, HeadlightMonitor(source, history, Dispatchers.Unconfined), tasks)
        val visible = launch { model.headlightStatus.collect() }
        try {
            withTimeout(3_000) { subscribed.await() }
            model.beginFactoryLightingVisit()
            visible.cancelAndJoin()

            // The normal sharing grace period has elapsed. Only the factory visit holds the source.
            assertNull(withTimeoutOrNull(450) { unsubscribed.await() })
            val observation = HeadlightObservation(HeadlightMode.LOW_BEAM, 2_000)
            reports.send(HeadlightFeedback.Confirmed(observation, eventElapsedNanos = 5_000))
            assertEquals(observation, withTimeout(3_000) { durable.await() })

            model.endFactoryLightingVisit()
            withTimeout(3_000) { unsubscribed.await() }
        } finally {
            visible.cancel()
            tasks.cancel()
            reports.close()
        }
    }
}
