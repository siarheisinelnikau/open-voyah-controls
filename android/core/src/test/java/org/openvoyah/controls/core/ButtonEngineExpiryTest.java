package org.openvoyah.controls.core;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ButtonEngineExpiryTest {
    private static final Action TAP = new Action(Action.Kind.SYSTEM_SETTINGS, "", "Tap");
    private static final Action HOLD = new Action(Action.Kind.CONTROLS_SETTINGS, "", "Hold");

    private static final class Clock implements ButtonEngine.Clock {
        long time = 900_000;
        boolean unavailable;
        @Override public long now() {
            if (unavailable) throw new IllegalStateException("Clock unavailable");
            return time;
        }
    }

    private static final class Fixture {
        final Clock clock = new Clock();
        final List<Action> executed = new ArrayList<>();
        final List<Runnable> scheduled = new ArrayList<>();
        final ButtonEngine engine = new ButtonEngine(new ButtonEngine.Scheduler() {
            @Override public Object after(long delay, Runnable callback) { scheduled.add(callback); return callback; }
            @Override public void cancel(Object token) { scheduled.remove(token); }
        }, executed::add, clock);
        long time = 100;

        Fixture(Action hold) { configure(hold); }
        void configure(Action hold) {
            engine.configure(List.of(new Binding("key", "Key", "", 85, TAP, hold, true)), true, 650, 100);
        }
        boolean down() { return engine.down("keyboard", 85, 0, time); }
        boolean up() { return engine.up("keyboard", 85, false, time); }
        void advance(long delay) { time += delay; clock.time += delay; }
        void fireTimers() {
            for (Runnable callback : new ArrayList<>(scheduled)) { scheduled.remove(callback); callback.run(); }
        }
    }

    @Test public void staleShortReleaseConsumesOwnershipWithoutExecutingAnObsoleteTap() {
        Fixture f = new Fixture(Action.NONE);
        assertTrue(f.down()); f.advance(60_000);
        assertTrue(f.engine.down("keyboard", 85, 1, f.time));
        assertTrue(f.up()); assertTrue(f.executed.isEmpty());
        assertFalse(f.up());
        f.advance(101); assertTrue(f.down()); f.advance(100); assertTrue(f.up());
        assertEquals(List.of(TAP), f.executed);
    }

    @Test public void staleHoldCallbackCancelsItsActionAndRetainsTheOwnedRelease() {
        Fixture f = new Fixture(HOLD);
        assertTrue(f.down()); f.advance(15_001); f.fireTimers();
        assertTrue(f.executed.isEmpty());
        assertTrue(f.engine.down("keyboard", 85, 1, f.time));
        assertTrue(f.up()); assertTrue(f.executed.isEmpty());
    }

    @Test public void staleReleaseCannotFallBackToEitherTapOrHoldBeforeItsDelayedTimer() {
        Fixture f = new Fixture(HOLD);
        assertTrue(f.down()); Runnable delayed = f.scheduled.get(0);
        f.advance(15_001); assertTrue(f.up());
        assertTrue(f.scheduled.isEmpty()); delayed.run();
        assertTrue(f.executed.isEmpty());
    }

    @Test public void exactCanLifetimeBoundaryStillAllowsTheOwnedRelease() {
        for (Action hold : new Action[]{Action.NONE, HOLD}) {
            Fixture f = new Fixture(hold);
            assertTrue(f.down()); f.advance(15_000); assertTrue(f.up()); f.fireTimers();
            assertEquals(List.of(hold.isAssigned() ? HOLD : TAP), f.executed);
        }
    }

    @Test public void exactCanLifetimeBoundaryStillAllowsTheHoldTimerOnce() {
        Fixture f = new Fixture(HOLD);
        assertTrue(f.down()); f.advance(15_000); f.fireTimers();
        assertEquals(List.of(HOLD), f.executed);
        f.advance(1000); assertTrue(f.up());
        assertEquals(List.of(HOLD), f.executed);
    }

    @Test public void clockExpiryRejectsAnUpEvenIfItsCallerTimestampStillLooksFresh() {
        Fixture f = new Fixture(Action.NONE);
        assertTrue(f.down()); f.clock.time += 15_001; f.time += 100;
        assertTrue(f.up()); assertTrue(f.executed.isEmpty());
    }

    @Test public void backwardsOrUnavailableTimerClockCannotReviveAnOwnedAction() {
        for (boolean unavailable : new boolean[]{false, true}) {
            Fixture f = new Fixture(HOLD);
            assertTrue(f.down()); long started = f.clock.time;
            f.advance(650);
            f.clock.time = started - 1; f.clock.unavailable = unavailable;
            f.fireTimers(); assertTrue(f.executed.isEmpty());
            f.clock.unavailable = false; f.clock.time = started + 1000;
            assertTrue(f.up()); assertTrue(f.executed.isEmpty());
        }
    }

    @Test public void unavailableClockBeforeDownPreservesTheEntireNativeGesture() {
        Fixture f = new Fixture(HOLD);
        f.clock.unavailable = true;
        assertFalse(f.down()); assertFalse(f.up());
        assertTrue(f.scheduled.isEmpty()); assertTrue(f.executed.isEmpty());
        f.clock.unavailable = false;
        assertTrue(f.down()); f.advance(100); assertTrue(f.up());
        assertEquals(List.of(TAP), f.executed);
    }

    @Test public void preflightCannotExtendTheGestureDeadlineBeforeOwnership() {
        Clock clock = new Clock();
        ButtonEngine engine = new ButtonEngine(new ButtonEngine.Scheduler() {
            @Override public Object after(long delay, Runnable callback) { throw new AssertionError("Expired preflight must not schedule"); }
            @Override public void cancel(Object token) { }
        }, new ButtonEngine.Sink() {
            @Override public boolean canExecute(Action action) { clock.time += 15_001; return true; }
            @Override public void execute(Action action) { throw new AssertionError("Expired preflight must not execute"); }
        }, clock);
        engine.configure(List.of(new Binding("key", "Key", "", 85, TAP, Action.NONE, true)), true, 650, 100);
        assertFalse(engine.down("keyboard", 85, 0, 100));
        assertFalse(engine.up("keyboard", 85, false, 15_101));
    }

    @Test public void canceledOldTimerCannotDispatchForANewerPressOnTheSameKey() {
        Fixture f = new Fixture(HOLD);
        assertTrue(f.down()); Runnable old = f.scheduled.get(0);
        f.configure(HOLD); f.advance(100); assertTrue(f.up());
        f.advance(101); assertTrue(f.down()); old.run(); assertTrue(f.executed.isEmpty());
        f.advance(650); f.fireTimers(); assertTrue(f.up());
        assertEquals(List.of(HOLD), f.executed);
    }

    @Test public void legacyConstructorKeepsCallerTimestampsIndependentOfItsClockEpoch() {
        List<Action> executed = new ArrayList<>();
        ButtonEngine engine = new ButtonEngine(new ButtonEngine.Scheduler() {
            @Override public Object after(long delay, Runnable callback) { throw new AssertionError("Tap must not schedule"); }
            @Override public void cancel(Object token) { }
        }, executed::add);
        engine.configure(List.of(new Binding("key", "Key", "", 85, TAP, Action.NONE, true)), true, 650, 100);
        assertTrue(engine.down("keyboard", 85, 0, 0));
        assertTrue(engine.up("keyboard", 85, false, 100));
        assertEquals(List.of(TAP), executed);
    }
}
