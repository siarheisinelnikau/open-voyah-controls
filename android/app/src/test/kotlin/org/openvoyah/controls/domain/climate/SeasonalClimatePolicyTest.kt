package org.openvoyah.controls.domain.climate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoor
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoorState
import org.openvoyah.controls.domain.vehicle.automation.VehicleGear
import org.openvoyah.controls.domain.vehicle.automation.VehicleIdentity
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignal
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignalKind
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry

class SeasonalClimatePolicyTest {
    private val now = 20_000_000_000L
    private val preset = SeasonalClimatePreset(enabled = true, outsideThresholdCelsius = 24)
    private val settings = SeasonalClimateSettings(summer = preset)

    @Test fun bothPresetsAreDisabledByDefaultAndThresholdsCannotOverlap() {
        assertEquals(false, SeasonalClimateSettings().summer.enabled)
        assertEquals(false, SeasonalClimateSettings().winter.enabled)
        assertThrows(IllegalArgumentException::class.java) {
            SeasonalClimateSettings(summer = preset.copy(outsideThresholdCelsius = 5))
        }
        assertThrows(IllegalArgumentException::class.java) { preset.copy(driverCelsius = 15) }
        assertThrows(IllegalArgumentException::class.java) { preset.copy(fanLevel = 9) }
    }

    @Test fun temperatureGearSpeedAndEveryRequiredDoorMustBeFresh() {
        val telemetry = telemetry(24f)
        assertEquals(ClimateConditionState.READY, assess(telemetry))
        assertEquals(ClimateConditionState.WAITING_TEMPERATURE, assess(telemetry.copy(outsideCelsius = null)))
        assertEquals(ClimateConditionState.WAITING_GEAR, assess(telemetry.copy(gear = null)))
        assertEquals(ClimateConditionState.WAITING_SPEED, assess(telemetry.copy(speedKph = null)))
        assertEquals(ClimateConditionState.WAITING_DOORS, assess(telemetry.copy(doors = telemetry.doors - VehicleDoor.REAR_RIGHT)))
        assertEquals(ClimateConditionState.DOORS_OPEN, assess(telemetry.copy(doors = telemetry.doors +
            (VehicleDoor.REAR_RIGHT to signal(VehicleDoorState.OPEN)))))
        assertEquals(ClimateConditionState.PARK_REQUIRED, assess(telemetry.copy(speedKph = signal(1f))))
        assertEquals(ClimateConditionState.WAITING_SPEED, assess(telemetry.copy(speedKph = signal(Float.NaN))))
        assertEquals(ClimateConditionState.WAITING_TEMPERATURE, assess(telemetry.copy(outsideCelsius = signal(Float.NaN))))
        assertEquals(ClimateConditionState.WAITING_GEAR, assess(telemetry, now + 5_000_000_001))
    }

    @Test fun oldSessionFutureSamplesAndExplicitUnavailableStatusCannotSatisfyConditions() {
        val telemetry = telemetry(25f)
        assertEquals(ClimateConditionState.WAITING_TEMPERATURE,
            assess(telemetry.copy(outsideCelsius = signal(25f).copy(sessionId = "previous"))))
        assertEquals(ClimateConditionState.WAITING_TEMPERATURE,
            assess(telemetry.copy(outsideCelsius = signal(25f).copy(observedAtElapsedNanos = now + 1))))
        assertEquals(ClimateConditionState.WAITING_TEMPERATURE,
            assess(telemetry.copy(unavailable = setOf(VehicleSignalKind.OUTSIDE_TEMPERATURE))))
    }

    @Test fun driveAndOptionalDoorsAreExplicitConditionsWithInclusiveSeasonalThresholds() {
        val drive = preset.copy(gearCondition = ClimateGearCondition.DRIVE, requireDoorsClosed = false)
        val moving = telemetry(24f).copy(gear = signal(VehicleGear.DRIVE), speedKph = null, doors = emptyMap())
        assertEquals(ClimateConditionState.READY, SeasonalClimatePolicy.assess(ClimateSeason.SUMMER, drive, moving, now))
        assertEquals(ClimateConditionState.DRIVE_REQUIRED, SeasonalClimatePolicy.assess(ClimateSeason.SUMMER, drive, telemetry(24f), now))
        assertEquals(ClimateConditionState.TEMPERATURE_NOT_MET, assess(telemetry(23.9f)))
        val winter = settings.winter.copy(enabled = true)
        assertEquals(ClimateConditionState.READY, SeasonalClimatePolicy.assess(ClimateSeason.WINTER, winter, telemetry(5f), now))
        assertEquals(ClimateConditionState.TEMPERATURE_NOT_MET, SeasonalClimatePolicy.assess(ClimateSeason.WINTER, winter, telemetry(5.1f), now))
    }

    @Test fun seatsReuseExplicitSeasonalCommandsAndZeroLeavesTheSeatUntouched() {
        val plain = SeasonalClimatePlan(ClimateSeason.SUMMER, preset)
        assertEquals(listOf("SetDriverTemperature", "SetPassengerTemperature", "SetFanSpeed"), plain.commands.map { it.id })
        val seats = preset.copy(driverSeatLevel = 3, passengerSeatLevel = 1)
        assertEquals(listOf("EnableConditionDriverLevelTree", "EnableConditionPassengerLevelOne"),
            SeasonalClimatePlan(ClimateSeason.SUMMER, seats).commands.drop(3).map { it.id })
        assertEquals(listOf("EnableHeatDriverLevelTree", "EnableHeatPassengerLevelOne"),
            SeasonalClimatePlan(ClimateSeason.WINTER, seats).commands.drop(3).map { it.id })
        assertThrows(IllegalArgumentException::class.java) { preset.copy(driverSeatLevel = 4) }
    }

    @Test fun aKnownFalseToTrueEdgeIsReservedOnceAndCooldownPreventsReplay() {
        val engine = SeasonalClimateEdgeEngine(cooldownMillis = 1_000)
        assertTrue(engine.observe(settings, telemetry(20f), now).isEmpty())
        assertEquals(listOf(SeasonalClimatePlan(ClimateSeason.SUMMER, preset)), engine.observe(settings, telemetry(25f, now + 1), now + 1))
        assertTrue(engine.observe(settings, telemetry(25f, now + 2), now + 2).isEmpty())
        assertTrue(engine.observe(settings, telemetry(20f, now + 3), now + 3).isEmpty())
        assertTrue(engine.observe(settings, telemetry(25f, now + 4), now + 4).isEmpty())
        assertTrue(engine.observe(settings, telemetry(20f, now + 1_000_000_000), now + 1_000_000_000).isEmpty())
        assertEquals(1, engine.observe(settings, telemetry(25f, now + 1_000_000_002), now + 1_000_000_002).size)
    }

    @Test fun initialMatchUnknownRecoveryReconnectionAndEditsNeverCreateAnAction() {
        val engine = SeasonalClimateEdgeEngine(cooldownMillis = 1_000)
        assertTrue(engine.observe(settings, telemetry(25f), now).isEmpty())
        assertTrue(engine.observe(settings, telemetry(25f, now + 1).copy(outsideCelsius = null), now + 1).isEmpty())
        assertTrue(engine.observe(settings, telemetry(25f, now + 2), now + 2).isEmpty())
        assertTrue(engine.observe(settings, telemetry(20f, now + 3), now + 3).isEmpty())
        val edited = settings.copy(summer = preset.copy(fanLevel = 4))
        assertTrue(engine.observe(edited, telemetry(25f, now + 4), now + 4).isEmpty())
        val reconnected = telemetry(25f, now + 5).let { old -> old.copy(sessionId = "next",
            gear = old.gear?.copy(sessionId = "next"), speedKph = old.speedKph?.copy(sessionId = "next"),
            outsideCelsius = old.outsideCelsius?.copy(sessionId = "next"),
            doors = old.doors.mapValues { it.value.copy(sessionId = "next") }) }
        assertTrue(engine.observe(edited, reconnected, now + 5).isEmpty())
    }

    @Test fun corruptSettingsRecoveryExpiredEvidenceAndChangedSessionStartOnlySeed() {
        val engine = SeasonalClimateEdgeEngine(cooldownMillis = 1_000)
        assertTrue(engine.observe(settings, telemetry(20f), now).isEmpty())
        engine.invalidate()
        assertTrue(engine.observe(settings, telemetry(25f, now + 1), now + 1).isEmpty())
        assertTrue(engine.observe(settings, telemetry(20f, now + 2), now + 2).isEmpty())
        val muchLater = now + 6_000_000_000
        assertTrue(engine.observe(settings, telemetry(25f, muchLater), muchLater).isEmpty())
        assertTrue(engine.observe(settings, telemetry(20f, muchLater + 1), muchLater + 1).isEmpty())
        assertTrue(engine.observe(settings, telemetry(25f, muchLater + 2).copy(sessionStartedAtElapsedNanos = muchLater), muchLater + 2).isEmpty())
    }

    @Test fun regressedOrContradictorySignalsCannotFabricateAConditionEdge() {
        val engine = SeasonalClimateEdgeEngine(cooldownMillis = 1_000)
        assertTrue(engine.observe(settings, telemetry(25f, now + 10), now + 10).isEmpty())
        assertTrue(engine.observe(settings, telemetry(20f, now + 9), now + 11).isEmpty())
        assertTrue(engine.observe(settings, telemetry(25f, now + 12), now + 12).isEmpty())
        assertTrue(engine.observe(settings, telemetry(20f, now + 12), now + 13).isEmpty())
        assertTrue(engine.observe(settings, telemetry(25f, now + 14), now + 14).isEmpty())
        assertTrue(engine.observe(settings, telemetry(20f, now + 15), now + 15).isEmpty())
        assertEquals(1, engine.observe(settings, telemetry(25f, now + 16), now + 16).size)
        engine.invalidate()
        assertTrue(engine.observe(settings, telemetry(20f, now + 17), now + 17).isEmpty())
        assertTrue(engine.observe(settings, telemetry(25f, now + 18), now + 18).isEmpty())
    }

    private fun assess(value: VehicleTelemetry, at: Long = now): ClimateConditionState =
        SeasonalClimatePolicy.assess(ClimateSeason.SUMMER, preset, value, at)

    private fun <T> signal(value: T) = VehicleSignal(value, now, 1_000L, "fixture", "current")
    private fun telemetry(temperature: Float, at: Long = now) = VehicleTelemetry(
        VehicleIdentity("fixture", "fixture/build", 30, "x86_64"), "current", now - 1,
        gear = signal(VehicleGear.PARK).copy(observedAtElapsedNanos = at), speedKph = signal(0f).copy(observedAtElapsedNanos = at),
        doors = VehicleDoor.entries.associateWith { signal(VehicleDoorState.CLOSED).copy(observedAtElapsedNanos = at) },
        outsideCelsius = signal(temperature).copy(observedAtElapsedNanos = at), unavailable = emptySet(),
    )
}
