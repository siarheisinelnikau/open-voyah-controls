package org.openvoyah.controls.domain.vehicle.automation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleTelemetryFreshnessTest {
    private val identity = VehicleIdentity("fixture", "fixture/build", 30, "x86_64")
    private val start = 1_000_000_000L
    private fun <T> sample(value: T, at: Long = start) = VehicleSignal(value, at, 1_700_000_000_000, "fixture", "session")
    private fun complete() = VehicleTelemetry(identity, "session", start,
        gear = sample(VehicleGear.PARK), speedKph = sample(0f), outsideCelsius = sample(12f),
        drivingMode = sample(DrivingMode.COMFORT),
        doors = VehicleDoor.entries.associateWith { sample(VehicleDoorState.CLOSED) }, unavailable = emptySet())

    @Test fun everyKindExpiresWithoutDiscardingRawEvidenceOrClearingSourceLossFlags() {
        val original = complete()
        assertSame(original, original.expireVehicleSignals(start + 5_000_000_000))
        val expired = original.expireVehicleSignals(start + 5_000_000_001)
        assertEquals(VehicleSignalKind.entries.toSet(), expired.unavailable)
        assertSame(original.gear, expired.gear)
        assertSame(original.speedKph, expired.speedKph)
        assertSame(original.outsideCelsius, expired.outsideCelsius)
        assertSame(original.drivingMode, expired.drivingMode)
        assertSame(original.doors, expired.doors)
        assertTrue(VehicleSignalKind.GEAR in original.copy(unavailable = setOf(VehicleSignalKind.GEAR))
            .expireVehicleSignals(start).unavailable)
    }

    @Test fun individualDoorExpiryInvalidatesTheGroupButNotNewerIndependentSignals() {
        val original = complete().copy(
            gear = sample(VehicleGear.PARK, start + 1_000_000_000),
            speedKph = sample(0f, start + 1_000_000_000),
            outsideCelsius = sample(12f, start + 1_000_000_000),
            drivingMode = sample(DrivingMode.COMFORT, start + 1_000_000_000),
            doors = VehicleDoor.entries.associateWith {
                sample(VehicleDoorState.CLOSED, if (it == VehicleDoor.DRIVER) start else start + 1_000_000_000)
            })
        val expired = original.expireVehicleSignals(start + 5_000_000_001)
        assertEquals(setOf(VehicleSignalKind.DOORS), expired.unavailable)
        val partial = original.copy(doors = mapOf(VehicleDoor.DRIVER to sample(VehicleDoorState.OPEN)))
        assertFalse(VehicleSignalKind.DOORS in partial.expireVehicleSignals(start + 1_000_000_000).unavailable)
    }

    @Test fun closedWindowWrongSessionAndFutureSamplesAreUnavailableImmediately() {
        val window = VehicleTelemetryWindow()
        val original = complete().copy(confirmationWindow = window)
        window.close()
        assertEquals(VehicleSignalKind.entries.toSet(), original.expireVehicleSignals(start).unavailable)
        assertTrue(VehicleSignalKind.GEAR in complete().copy(gear = sample(VehicleGear.PARK).copy(sessionId = "old"))
            .expireVehicleSignals(start).unavailable)
        assertTrue(VehicleSignalKind.GEAR in complete().copy(gear = sample(VehicleGear.PARK, start + 1))
            .expireVehicleSignals(start).unavailable)
    }

    @Test fun aFiniteDeadlineExpiresTheUnchangedSnapshotAndUnknownSourcesScheduleNothing() = runBlocking {
        var now = start
        val deadlines = mutableListOf<Long>()
        val frames = flowOf(complete()).withVehicleSignalExpiry({ now }, awaitDeadline = { deadline ->
            deadlines += deadline
            now = deadline
        }).toList()
        assertEquals(listOf(start + 5_000_000_001), deadlines)
        assertEquals(2, frames.size)
        assertEquals(VehicleSignalKind.entries.toSet(), frames.last().unavailable)
        val unknown = VehicleTelemetry(identity, "unknown", start)
        val quiet = flowOf(unknown).withVehicleSignalExpiry({ now }, awaitDeadline = { error("No deadline expected") }).toList()
        assertEquals(listOf(unknown), quiet)
    }

    @Test fun aNewCallbackCancelsTheOldDeadlineAndOnlyTheNewSampleExpires() = runBlocking {
        withTimeout(2_000) {
            var now = start
            val source = MutableSharedFlow<VehicleTelemetry>(replay = 1)
            val frames = Channel<VehicleTelemetry>(Channel.UNLIMITED)
            val waits = Channel<Pair<Long, CompletableDeferred<Unit>>>(Channel.UNLIMITED)
            var cancelledWaits = 0
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                source.withVehicleSignalExpiry({ now }, awaitDeadline = { deadline ->
                    val gate = CompletableDeferred<Unit>()
                    waits.send(deadline to gate)
                    try { gate.await() } finally { if (!gate.isCompleted) cancelledWaits++ }
                }).collect { frames.send(it) }
            }
            try {
                val first = complete()
                source.emit(first)
                assertEquals(first, frames.receive())
                val oldWait = waits.receive()
                now = start + 1_000_000_000
                val refreshed = VehicleTelemetry(identity, "session", start,
                    gear = sample(VehicleGear.DRIVE, now), unavailable = VehicleSignalKind.entries.toSet() - VehicleSignalKind.GEAR)
                source.emit(refreshed)
                assertEquals(refreshed, frames.receive())
                val newWait = waits.receive()
                assertEquals(1, cancelledWaits)
                oldWait.second.complete(Unit)
                yield()
                assertTrue(frames.tryReceive().isFailure)
                now = newWait.first
                newWait.second.complete(Unit)
                val expired = frames.receive()
                assertEquals(VehicleSignalKind.entries.toSet(), expired.unavailable)
                assertSame(refreshed.gear, expired.gear)
            } finally {
                collector.cancelAndJoin()
                frames.close()
                waits.close()
            }
        }
    }

    @Test fun theDefaultMonotonicDeadlineWakesWithoutAnotherSourceEvent() = runBlocking {
        withTimeout(2_000) {
            val sampledAt = System.nanoTime()
            val original = VehicleTelemetry(identity, "session", sampledAt,
                gear = sample(VehicleGear.PARK, sampledAt),
                unavailable = VehicleSignalKind.entries.toSet() - VehicleSignalKind.GEAR)
            val frames = flowOf(original).withVehicleSignalExpiry(System::nanoTime, maxAgeMillis = 100).toList()
            assertEquals(2, frames.size)
            assertFalse(VehicleSignalKind.GEAR in frames.first().unavailable)
            assertTrue(VehicleSignalKind.GEAR in frames.last().unavailable)
            assertSame(original.gear, frames.last().gear)
        }
    }
}
