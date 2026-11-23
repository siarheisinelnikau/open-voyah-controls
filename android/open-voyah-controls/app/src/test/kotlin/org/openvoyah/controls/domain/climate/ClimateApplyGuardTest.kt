package org.openvoyah.controls.domain.climate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClimateApplyGuardTest {
    private val plan = SeasonalClimatePlan(ClimateSeason.SUMMER, SeasonalClimatePreset(outsideThresholdCelsius = 24))

    @Test fun oneSharedReservationRejectsAnIdenticalGroupInsideTheCooldown() {
        val guard = ClimateApplyGuard(cooldownNanos = 600_000_000_000L)
        assertTrue(guard.tryReserve(plan, 1_000_000_000L))
        assertFalse(guard.tryReserve(plan, 60_000_000_000L))
        assertTrue(guard.tryReserve(plan, 601_000_000_000L))
    }

    @Test fun aChangedPresetIsANewReservation() {
        val guard = ClimateApplyGuard()
        assertTrue(guard.tryReserve(plan, 1_000_000_000L))
        assertTrue(guard.tryReserve(plan.copy(preset = plan.preset.copy(fanLevel = 4)), 2_000_000_000L))
        assertFalse(guard.tryReserve(plan, 3_000_000_000L))
    }

    @Test fun aRegressedClockCannotReleaseOrOverwriteAReservation() {
        val guard = ClimateApplyGuard()
        assertTrue(guard.tryReserve(plan, 20_000_000_000L))
        assertFalse(guard.tryReserve(plan.copy(preset = plan.preset.copy(fanLevel = 4)), 10_000_000_000L))
        assertFalse(guard.tryReserve(plan, 21_000_000_000L))
    }

    @Test fun manyPresetEditsCannotEvictRecentReservations() {
        val guard = ClimateApplyGuard()
        for (index in 0 until 32) {
            val preset = plan.preset.copy(driverCelsius = 16 + index % 15, passengerCelsius = 16 + index / 15)
            assertTrue(guard.tryReserve(plan.copy(preset = preset), 1_000L + index))
        }
        assertFalse(guard.tryReserve(plan.copy(preset = plan.preset.copy(fanLevel = 8)), 2_000L))
        assertTrue(guard.tryReserve(plan, 600_000_002_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aNonPositiveClockCannotReserve() {
        ClimateApplyGuard().tryReserve(plan, 0)
    }
}
