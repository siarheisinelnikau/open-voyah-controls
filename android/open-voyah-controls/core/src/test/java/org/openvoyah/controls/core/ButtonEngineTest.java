package org.openvoyah.controls.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ButtonEngineTest {
    private final Action shortAction = new Action(Action.Kind.SYSTEM_SETTINGS, "", "Settings");
    private final Action longAction = new Action(Action.Kind.CONTROLS_SETTINGS, "", "Controls");
    private final List<Action> executed = new ArrayList<>();
    private final List<Runnable> scheduled = new ArrayList<>();
    private final ButtonEngine engine = new ButtonEngine(new ButtonEngine.Scheduler() {
        public Object after(long ms, Runnable callback) { scheduled.add(callback); return callback; }
        public void cancel(Object token) { scheduled.remove(token); }
    }, executed::add);
    private Binding binding(String id, String device, Action shortPress, Action longPress) {
        return new Binding(id, id, device, 85, shortPress, longPress, true);
    }
    private void setup() { engine.configure(List.of(binding("music", "", shortAction, longAction)), true, 650, 100); }
    private void hold() { for (Runnable job : new ArrayList<>(scheduled)) { scheduled.remove(job); job.run(); } }

    @Test public void shortPressRunsOnReleaseOnce() {
        setup(); assertTrue(engine.down("panel", 85, 0, 0)); assertTrue(executed.isEmpty());
        assertTrue(engine.down("panel", 85, 1, 50)); assertTrue(engine.up("panel", 85, false, 100));
        hold(); assertEquals(List.of(shortAction), executed); assertFalse(engine.up("panel", 85, false, 200));
    }
    @Test public void holdNeverAlsoRunsShortAction() {
        setup(); engine.down("panel", 85, 0, 0); hold(); engine.down("panel", 85, 2, 700); engine.up("panel", 85, false, 900);
        assertEquals(List.of(longAction), executed);
    }
    @Test public void delayedHoldTimerUsesReleaseTimeAndCannotDispatchTwice() {
        setup(); assertTrue(engine.down("panel", 85, 0, 100));
        Runnable delayed = scheduled.get(0);
        assertTrue(engine.up("panel", 85, false, 1000));
        assertTrue(scheduled.isEmpty());
        assertEquals(List.of(longAction), executed);
        delayed.run();
        assertFalse(engine.up("panel", 85, false, 1001));
        assertEquals(List.of(longAction), executed);
    }
    @Test public void delayedTimerRespectsTheExactHoldThreshold() {
        for (long duration : new long[]{649, 650}) {
            setup(); executed.clear();
            assertTrue(engine.down("panel", 85, 0, 100));
            Runnable delayed = scheduled.get(0);
            assertTrue(engine.up("panel", 85, false, 100 + duration));
            delayed.run();
            assertEquals(List.of(duration < 650 ? shortAction : longAction), executed);
        }
    }
    @Test public void aLongPressWithNoHoldAssignmentRetainsItsTapAction() {
        engine.configure(List.of(binding("tap", "", shortAction, Action.NONE)), true, 650, 100);
        assertTrue(engine.down("panel", 85, 0, 100));
        assertTrue(engine.up("panel", 85, false, 1000));
        assertEquals(List.of(shortAction), executed);
        assertTrue(scheduled.isEmpty());
    }
    @Test public void aBackwardsClockConsumesTheOwnedReleaseWithoutDispatch() {
        setup(); assertTrue(engine.down("panel", 85, 0, 1000));
        Runnable delayed = scheduled.get(0);
        assertTrue(engine.up("panel", 85, false, 900));
        delayed.run();
        assertTrue(executed.isEmpty());
        assertTrue(scheduled.isEmpty());
    }
    @Test public void unknownAndUnassignedPassThrough() {
        setup(); assertFalse(engine.down("panel", 86, 0, 0));
        engine.configure(List.of(binding("empty", "", Action.NONE, Action.NONE)), true, 650, 100);
        assertFalse(engine.down("panel", 85, 0, 0));
    }
    @Test public void disabledAndOrphanRepeatPassThrough() {
        setup(); assertFalse(engine.down("panel", 85, 1, 0));
        engine.configure(List.of(binding("music", "", shortAction, longAction)), false, 650, 100);
        assertFalse(engine.down("panel", 85, 0, 0));
    }
    @Test public void configurationChangeCancelsActionButConsumesMatchingUp() {
        setup(); engine.down("panel", 85, 0, 0); engine.configure(List.of(), false, 650, 100);
        hold(); assertTrue(engine.up("panel", 85, false, 900)); assertTrue(executed.isEmpty());
    }
    @Test public void canceledPressDoesNothing() {
        setup(); engine.down("panel", 85, 0, 0); engine.up("panel", 85, true, 100); hold(); assertTrue(executed.isEmpty());
    }
    @Test public void debounceSwallowsWholeSecondPress() {
        setup(); engine.down("panel", 85, 0, 0); engine.up("panel", 85, false, 100);
        assertTrue(engine.down("panel", 85, 0, 130)); hold(); assertTrue(engine.up("panel", 85, false, 150));
        assertEquals(List.of(shortAction), executed);
    }
    @Test public void deviceSpecificMappingWinsAndDevicesAreIndependent() {
        engine.configure(List.of(binding("wildcard", "", shortAction, Action.NONE), binding("specific", "wheel", longAction, Action.NONE)), true, 650, 100);
        engine.down("panel", 85, 0, 0); engine.down("wheel", 85, 0, 0);
        engine.up("panel", 85, false, 100); engine.up("wheel", 85, false, 100);
        assertEquals(List.of(shortAction, longAction), executed);
    }
    @Test public void longOnlyBindingPreservesTheWholeStockGesture() {
        engine.configure(List.of(binding("hold", "", Action.NONE, longAction)), true, 650, 100);
        assertFalse(engine.down("panel", 85, 0, 0)); hold();
        assertFalse(engine.up("panel", 85, false, 100)); assertTrue(executed.isEmpty());
    }
    @Test public void unavailableTargetPreservesDownAndUpWithoutScheduling() {
        ButtonEngine guarded = new ButtonEngine(new ButtonEngine.Scheduler() {
            public Object after(long ms, Runnable r) { fail("Unavailable action must not schedule"); return null; }
            public void cancel(Object token) { }
        }, new ButtonEngine.Sink() {
            public void execute(Action action) { fail("Unavailable action must not execute"); }
            public boolean canExecute(Action action) { return action != longAction; }
        });
        guarded.configure(List.of(binding("gone", "", shortAction, longAction)), true, 650, 100);
        assertFalse(guarded.down("panel", 85, 0, 0));
        assertFalse(guarded.up("panel", 85, false, 900));
    }
    @Test public void closeCancelsTimers() { setup(); engine.down("panel", 85, 0, 0); engine.close(); hold(); assertTrue(executed.isEmpty()); }
}
