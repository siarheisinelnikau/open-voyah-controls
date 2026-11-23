package org.openvoyah.controls.domain.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.domain.routines.NamedRoutine
import org.openvoyah.controls.domain.routines.RoutineLibrary
import org.openvoyah.controls.domain.routines.RoutinePlan
import org.openvoyah.controls.domain.routines.RoutineTarget
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoor
import org.openvoyah.controls.domain.vehicle.automation.VehicleDoorState
import org.openvoyah.controls.domain.vehicle.automation.VehicleGear
import org.openvoyah.controls.domain.vehicle.automation.VehicleIdentity
import org.openvoyah.controls.domain.vehicle.automation.VehicleSignal
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetryWindow

class ConditionalRuleEngineTest {
    private val routine = NamedRoutine("00000000-0000-0000-0000-000000000001", "Morning", RoutinePlan(RoutineTarget.APP_GRID))
    private val routines = RoutineLibrary(listOf(routine))
    private val rule = ConditionalRule("00000000-0000-0000-0000-000000000002", "Park routine", routine,
        gear = VehicleGear.PARK, enabled = true)
    private val library = ConditionalRuleLibrary(listOf(rule))
    private val identity = VehicleIdentity("fixture", "fixture/verified", 30, "x86_64")

    @Test fun initialMatchingStateOnlySeedsAFalseToTrueTransition() {
        val engine = ConditionalRuleEngine()
        assertTrue(engine.evaluate(library, routines, at(1, VehicleGear.PARK), second(1)).isEmpty())
        assertTrue(engine.evaluate(library, routines, at(2, VehicleGear.PARK), second(2)).isEmpty())
        assertTrue(engine.evaluate(library, routines, at(3, VehicleGear.DRIVE), second(3)).isEmpty())
        val decision = engine.evaluate(library, routines, at(4, VehicleGear.PARK), second(4)).single()
        assertEquals(routine, decision.routine)
        assertTrue(decision.isCurrent(library, routines, at(4, VehicleGear.PARK), second(4)))
        assertTrue(engine.evaluate(library, routines, at(4, VehicleGear.PARK), second(4)).isEmpty())
    }

    @Test fun lostUnknownAndFutureSamplesNeverBecomeAnEdge() {
        val engine = ConditionalRuleEngine()
        engine.evaluate(library, routines, at(1, VehicleGear.DRIVE), second(1))
        assertTrue(engine.evaluate(library, routines, at(2, VehicleGear.DRIVE).copy(gear = null), second(2)).isEmpty())
        assertTrue(engine.evaluate(library, routines, at(3, VehicleGear.PARK), second(3)).isEmpty())
        engine.evaluate(library, routines, at(4, VehicleGear.DRIVE), second(4))
        assertTrue(engine.evaluate(library, routines, at(6, VehicleGear.PARK), second(5)).isEmpty())
        assertTrue(engine.evaluate(library, routines, at(6, VehicleGear.PARK), second(6)).isEmpty())
    }

    @Test fun aSilentFalseBaselineOfTheSameSessionStillArmsTheNextTransition() {
        // The gearbox reports only changes: Drive seen a minute ago is still the known previous state.
        val engine = ConditionalRuleEngine()
        engine.evaluate(library, routines, at(1, VehicleGear.DRIVE), second(1))
        assertEquals(1, engine.evaluate(library, routines, at(61, VehicleGear.PARK), second(61)).size)
    }

    @Test fun reconnectSleepAndOwnerStopRequireANewKnownTransition() {
        val engine = ConditionalRuleEngine()
        engine.evaluate(library, routines, at(1, VehicleGear.DRIVE), second(1))
        assertTrue(engine.evaluate(library, routines, at(2, VehicleGear.PARK, "new-session"), second(2)).isEmpty())
        engine.evaluate(library, routines, at(3, VehicleGear.DRIVE, "new-session"), second(3))
        engine.invalidate()
        assertTrue(engine.evaluate(library, routines, at(4, VehicleGear.PARK, "new-session"), second(4)).isEmpty())
        val oldSessionSignal = at(5, VehicleGear.PARK).gear
        assertTrue(engine.evaluate(library, routines, at(5, VehicleGear.DRIVE, "new-session").copy(gear = oldSessionSignal), second(5)).isEmpty())
    }

    @Test fun bothConditionsRequireFreshEvidenceAndAllClosedRequiresEveryDoor() {
        val both = rule.copy(doors = DoorRequirement.ALL_CLOSED)
        val configured = ConditionalRuleLibrary(listOf(both))
        val engine = ConditionalRuleEngine()
        engine.evaluate(configured, routines, at(1, VehicleGear.DRIVE), second(1))
        val missingDoor = at(2, VehicleGear.PARK).let { it.copy(doors = it.doors - VehicleDoor.REAR_RIGHT) }
        assertTrue(engine.evaluate(configured, routines, missingDoor, second(2)).isEmpty())
        assertTrue(engine.evaluate(configured, routines, at(3, VehicleGear.PARK), second(3)).isEmpty())
        val open = at(4, VehicleGear.PARK).let { it.copy(doors = it.doors + (VehicleDoor.DRIVER to sample(VehicleDoorState.OPEN, 4))) }
        engine.evaluate(configured, routines, open, second(4))
        assertEquals(1, engine.evaluate(configured, routines, at(5, VehicleGear.PARK), second(5)).size)
    }

    @Test fun anyOpenUsesAFreshPositiveProofButUnknownCannotMeanAllClosed() {
        val doorRule = rule.copy(gear = null, doors = DoorRequirement.ANY_OPEN)
        val configured = ConditionalRuleLibrary(listOf(doorRule))
        val engine = ConditionalRuleEngine()
        engine.evaluate(configured, routines, at(1, VehicleGear.PARK), second(1))
        val open = at(2, VehicleGear.PARK).copy(doors = mapOf(VehicleDoor.DRIVER to sample(VehicleDoorState.OPEN, 2)))
        assertEquals(1, engine.evaluate(configured, routines, open, second(2)).size)
        val unknown = at(40, VehicleGear.PARK).copy(doors = mapOf(VehicleDoor.DRIVER to sample(VehicleDoorState.CLOSED, 40)))
        assertTrue(engine.evaluate(configured, routines, unknown, second(40)).isEmpty())
        assertTrue(engine.evaluate(configured, routines, open.copy(doors = mapOf(VehicleDoor.DRIVER to sample(VehicleDoorState.OPEN, 41))), second(41)).isEmpty())
    }

    @Test fun attemptsAreReservedBeforeDispatchAndAmbiguousOutcomesNeverRetry() {
        val engine = ConditionalRuleEngine()
        engine.evaluate(library, routines, at(1, VehicleGear.DRIVE), second(1))
        val decision = engine.evaluate(library, routines, at(2, VehicleGear.PARK), second(2)).single()
        engine.completeAttempt(decision, RuleAttemptOutcome.UNCONFIRMED)
        assertEquals(RuleAttemptOutcome.UNCONFIRMED, engine.lastAttempt(rule.id)?.outcome)
        assertTrue(engine.evaluate(library, routines, at(3, VehicleGear.PARK), second(3)).isEmpty())
        engine.evaluate(library, routines, at(4, VehicleGear.DRIVE), second(4))
        assertTrue(engine.evaluate(library, routines, at(5, VehicleGear.PARK), second(5)).isEmpty())
        engine.evaluate(library, routines, at(32, VehicleGear.DRIVE), second(32))
        assertEquals(1, engine.evaluate(library, routines, at(33, VehicleGear.PARK), second(33)).size)
    }

    @Test fun routineMutationRemovalAndRuleEditsInvalidatePendingDecisions() {
        val engine = ConditionalRuleEngine()
        engine.evaluate(library, routines, at(1, VehicleGear.DRIVE), second(1))
        val decision = engine.evaluate(library, routines, at(2, VehicleGear.PARK), second(2)).single()
        val changed = routine.copy(plan = RoutinePlan(RoutineTarget.SETTINGS))
        val changedRoutines = routines.copy(entries = listOf(changed))
        assertFalse(decision.isCurrent(library, changedRoutines, at(2, VehicleGear.PARK), second(2)))
        assertFalse(decision.isCurrent(library, RoutineLibrary(), at(2, VehicleGear.PARK), second(2)))
        assertFalse(decision.isCurrent(library.copy(entries = listOf(rule.copy(enabled = false))), routines, at(2, VehicleGear.PARK), second(2)))
        assertTrue(engine.evaluate(library, changedRoutines, at(3, VehicleGear.DRIVE), second(3)).isEmpty())
        assertTrue(engine.evaluate(library, changedRoutines, at(4, VehicleGear.PARK), second(4)).isEmpty())
        val accepted = library.copy(entries = listOf(rule.copy(routine = changed)))
        assertTrue(engine.evaluate(accepted, changedRoutines, at(5, VehicleGear.PARK), second(5)).isEmpty())
        engine.evaluate(accepted, changedRoutines, at(6, VehicleGear.DRIVE), second(6))
        assertEquals(1, engine.evaluate(accepted, changedRoutines, at(7, VehicleGear.PARK), second(7)).size)
    }

    @Test fun pendingDecisionExpiresAndCannotCrossAConnectionOrLoss() {
        val engine = ConditionalRuleEngine()
        engine.evaluate(library, routines, at(1, VehicleGear.DRIVE), second(1))
        val decision = engine.evaluate(library, routines, at(2, VehicleGear.PARK), second(2)).single()
        assertFalse(decision.isCurrent(library, routines, at(8, VehicleGear.PARK), second(8)))
        assertFalse(decision.isCurrent(library, routines, at(3, VehicleGear.PARK, "other"), second(3)))
        assertFalse(decision.isCurrent(library, routines, at(3, VehicleGear.PARK).copy(gear = null), second(3)))
        val window = VehicleTelemetryWindow()
        val revoked = at(3, VehicleGear.PARK).copy(confirmationWindow = window)
        window.close()
        assertFalse(decision.isCurrent(library, routines, revoked, second(3)))
    }

    @Test fun oldOutOfOrderFalseSnapshotCannotManufactureANewTransition() {
        val engine = ConditionalRuleEngine()
        engine.evaluate(library, routines, at(1, VehicleGear.PARK), second(1))
        assertTrue(engine.evaluate(library, routines, at(2, VehicleGear.PARK), second(2)).isEmpty())
        assertTrue(engine.evaluate(library, routines, at(1, VehicleGear.DRIVE), second(3)).isEmpty())
        assertTrue(engine.evaluate(library, routines, at(4, VehicleGear.PARK), second(4)).isEmpty())
        assertNull(engine.lastAttempt(rule.id))
    }

    @Test fun aChangedSourceOnlySeedsAndCannotValidateAnEarlierDecision() {
        val engine = ConditionalRuleEngine()
        engine.evaluate(library, routines, at(1, VehicleGear.DRIVE), second(1))
        val decision = engine.evaluate(library, routines, at(2, VehicleGear.PARK), second(2)).single()
        fun replacement(time: Long, gear: VehicleGear) = at(time, gear).let {
            it.copy(gear = it.gear!!.copy(sourceId = "replacement-controller"))
        }
        assertFalse(decision.isCurrent(library, routines, replacement(3, VehicleGear.PARK), second(3)))
        assertTrue(engine.evaluate(library, routines, replacement(3, VehicleGear.DRIVE), second(3)).isEmpty())
        // The changed source is usable after its own fresh baseline and the existing cooldown.
        engine.evaluate(library, routines, replacement(32, VehicleGear.DRIVE), second(32))
        assertEquals(1, engine.evaluate(library, routines, replacement(33, VehicleGear.PARK), second(33)).size)
    }

    private fun second(value: Long) = value * 1_000_000_000L

    private fun <T> sample(value: T, at: Long, session: String = "fixture-session") = VehicleSignal(
        value, second(at), 1_000_000L + at * 1_000, "fixture-controller", session,
    )

    private fun at(time: Long, gear: VehicleGear, session: String = "fixture-session") = VehicleTelemetry(
        identity, session, second(1), gear = sample(gear, time, session),
        doors = VehicleDoor.entries.associateWith { sample(VehicleDoorState.CLOSED, time, session) }, unavailable = emptySet(),
    )
}
