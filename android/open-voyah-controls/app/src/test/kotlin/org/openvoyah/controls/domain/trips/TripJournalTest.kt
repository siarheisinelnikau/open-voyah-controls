package org.openvoyah.controls.domain.trips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.domain.vehicle.automation.VehicleGear

class TripJournalTest {
    @Test
    fun automaticTripUsesGearBoundariesAndObservedEndpointLevels() {
        val tracker = TripTracker(null) { "trip" }
        assertNull(tracker.observe(reading(VehicleGear.PARK, 100.0), 100_000, 10_000))
        val start = checkNotNull(tracker.observe(reading(VehicleGear.DRIVE, 100.0), 100_100, 10_100)).trip
        assertEquals("trip", start.id)
        assertEquals(100.0, start.start.odometerKm)
        val neutral = checkNotNull(tracker.observe(reading(VehicleGear.NEUTRAL, 100.2), 105_100, 15_100)).trip
        assertNull(neutral.finishedAtMillis)
        val end = checkNotNull(tracker.observe(reading(VehicleGear.PARK, 100.4), 110_100, 20_100)).trip
        assertEquals(10_000L, end.durationMillis)
        assertEquals(0.4, checkNotNull(end.distanceKm), 0.00001)
        assertEquals(42.0, end.latest.fuelLitres)
        assertEquals(110_100L, end.finishedAtMillis)
        assertFalse(end.interrupted)
        assertNull(tracker.active)
    }

    @Test
    fun restartRetainsCheckpointAndDoesNotInventTimeOrLevelAcrossGap() {
        val tracker = TripTracker(null) { "trip" }
        tracker.observe(reading(VehicleGear.DRIVE, 200.0), 100_000, 11_000)
        val saved = checkNotNull(tracker.observe(reading(VehicleGear.DRIVE, 200.2), 105_000, 16_000)).trip
        val resumed = TripTracker(saved)
        val end = checkNotNull(resumed.observe(TripReadings("new", VehicleGear.PARK), 500_000, 1_000))
        assertEquals(5_000L, end.trip.durationMillis)
        assertEquals(0.2, checkNotNull(end.trip.distanceKm), 0.00001)
        assertTrue(end.trip.interrupted)
        assertNull(end.trip.latest.chargePercent)
        assertEquals(TripUsagePoint(5_000, null, null, null), end.usage)
    }

    @Test
    fun decreasingOdometerInvalidatesDistanceEvenAcrossUnavailableReading() {
        val tracker = TripTracker(null)
        tracker.observe(reading(VehicleGear.DRIVE, 200.0), 100_000, 10_000)
        tracker.observe(reading(VehicleGear.DRIVE, 204.0), 105_000, 15_000)
        tracker.observe(TripReadings("session", VehicleGear.DRIVE), 110_000, 20_000)
        val reset = checkNotNull(tracker.observe(reading(VehicleGear.DRIVE, 201.0), 115_000, 25_000)).trip
        assertTrue(reset.invalidOdometer)
        assertNull(reset.distanceKm)
        val end = checkNotNull(tracker.observe(reading(VehicleGear.PARK, 204.0), 120_000, 30_000)).trip
        assertNull(end.distanceKm)
    }

    @Test
    fun rollingUsageNeedsACompleteContinuousWindowAndIndependentFuelAndBatteryReadings() {
        val usage = RollingTripUsage()
        repeat(10) { index ->
            val distance = index * 0.25
            assertEquals(TripUsage(), usage.observe(TripUsagePoint(index * 2_000L, distance, 20.0 - distance, null)))
        }
        val full = usage.observe(TripUsagePoint(20_000, 2.5, 17.5, null))
        assertEquals(2.5, full.electricKwh)
        assertNull(full.fuelLitres)
        val interpolated = usage.observe(TripUsagePoint(22_000, 2.6, 17.4, null))
        assertEquals(2.5, checkNotNull(interpolated.electricKwh), 0.00001)
        assertEquals(TripUsage(), usage.observe(TripUsagePoint(40_000, 2.7, 17.3, null)))
    }

    @Test
    fun aRefillBreaksOnlyFuelUsage() {
        val usage = RollingTripUsage()
        var reading = TripUsage()
        repeat(11) { index ->
            val km = index * 0.25
            reading = usage.observe(TripUsagePoint(index * 2_000L, km, 20.0 - km, (if (index < 5) 10.0 else 15.0) - km))
        }
        assertEquals(2.5, reading.electricKwh)
        assertNull(reading.fuelLitres)
    }

    @Test
    fun routeRejectsInvalidCoordinatesAndSpeed() {
        assertTrue(validRoutePoint(53.9, 27.6, null))
        assertFalse(validRoutePoint(Double.NaN, 27.6, 1.0))
        assertFalse(validRoutePoint(53.9, 185.0, 1.0))
        assertFalse(validRoutePoint(53.9, 27.6, -1.0))
    }

    private fun reading(gear: VehicleGear, odometerKm: Double) =
        TripReadings("session", gear, TripLevels(odometerKm, 75.0, 30.0, 42.0))
}
