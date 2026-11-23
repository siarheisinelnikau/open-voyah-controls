package org.openvoyah.controls.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Single-threaded press state machine. Timings use monotonic milliseconds. */
public final class ButtonEngine {
    public interface Scheduler { Object after(long delayMs, Runnable callback); void cancel(Object token); }
    public interface Clock { long now(); }
    public interface Sink {
        void execute(Action action);
        default boolean canExecute(Action action) { return true; }
    }
    private final Scheduler scheduler;
    private final Sink sink;
    private final Clock clock;
    // Match the CAN agents' action lifetime without releasing ownership of swallowed edges.
    private static final long MAX_PRESS_MS = 15_000;
    private final Map<String, Press> presses = new HashMap<>();
    private final Map<String, Long> lastUps = new HashMap<>();
    private List<Binding> bindings = new ArrayList<>();
    private boolean enabled = true;
    private long holdMs = 650;
    private long debounceMs = 100;

    private static final class Press {
        Binding binding;
        Object timer;
        long started;
        long clockStarted;
        long holdMs;
        boolean longFired;
        boolean canceled;
    }

    public ButtonEngine(Scheduler scheduler, Sink sink) {
        this(scheduler, sink, () -> System.nanoTime() / 1_000_000L);
    }

    public ButtonEngine(Scheduler scheduler, Sink sink, Clock clock) {
        this.scheduler = scheduler;
        this.sink = sink;
        this.clock = clock;
    }

    public void configure(List<Binding> bindings, boolean enabled, long holdMs, long debounceMs) {
        if (holdMs < 250 || holdMs > 2000 || debounceMs < 0 || debounceMs > 500) {
            throw new IllegalArgumentException("Invalid press timing");
        }
        // Keep consumed presses until UP so a settings change cannot produce an orphan UP.
        cancelPending();
        this.bindings = new ArrayList<>(bindings);
        this.enabled = enabled;
        this.holdMs = holdMs;
        this.debounceMs = debounceMs;
        lastUps.clear();
    }

    public boolean down(String device, int code, int repeat, long now) {
        String key = device + "\u0000" + code;
        if (presses.containsKey(key)) return true;
        if (!enabled || repeat > 0) return false;
        Binding binding = find(device, code);
        if (binding == null || !binding.isAssigned()) return false;
        Press press = new Press();
        press.binding = binding;
        press.started = now;
        try { press.clockStarted = clock.now(); }
        catch (RuntimeException ex) { return false; }
        // Accessibility cannot replay an OEM gesture after consuming DOWN. Preserve the
        // complete stock gesture when its tap has no replacement or either target is absent.
        if (!binding.shortPress.isAssigned() || !sink.canExecute(binding.shortPress)
                || (binding.longPress.isAssigned() && !sink.canExecute(binding.longPress))
                || !withinLifetime(press)) return false;
        press.holdMs = holdMs;
        Long last = lastUps.get(key);
        press.canceled = last != null && now >= last && now - last < debounceMs;
        presses.put(key, press);
        if (!press.canceled && binding.longPress.isAssigned()) {
            press.timer = scheduler.after(holdMs, () -> {
                press.timer = null;
                if (presses.get(key) == press && !press.canceled) {
                    if (!withinLifetime(press)) { press.canceled = true; return; }
                    press.longFired = true;
                    sink.execute(binding.longPress);
                }
            });
        }
        return true;
    }

    public boolean up(String device, int code, boolean canceled, long now) {
        String key = device + "\u0000" + code;
        Press press = presses.remove(key);
        if (press == null) return false;
        if (press.timer != null) scheduler.cancel(press.timer);
        lastUps.put(key, now);
        if (lastUps.size() > 128) lastUps.clear();
        if (!canceled && !press.canceled && !press.longFired
                && withinLifetime(press.started, now) && withinLifetime(press)) {
            // Input delivery can reach UP before a delayed main-thread hold callback.
            Action action = press.binding.longPress.isAssigned() && now - press.started >= press.holdMs
                ? press.binding.longPress : press.binding.shortPress;
            if (action.isAssigned()) sink.execute(action);
        }
        return true;
    }

    public void cancelPending() {
        for (Press press : presses.values()) {
            press.canceled = true;
            if (press.timer != null) scheduler.cancel(press.timer);
        }
    }

    public void close() { cancelPending(); presses.clear(); lastUps.clear(); }

    private boolean withinLifetime(Press press) {
        try { return withinLifetime(press.clockStarted, clock.now()); }
        catch (RuntimeException ex) { return false; }
    }

    private static boolean withinLifetime(long started, long now) {
        long elapsed = now - started;
        return now >= started && elapsed >= 0 && elapsed <= MAX_PRESS_MS;
    }

    private Binding find(String device, int code) {
        Binding fallback = null;
        for (Binding binding : bindings) {
            if (!binding.matches(device, code)) continue;
            if (!binding.device.isEmpty()) return binding;
            fallback = binding;
        }
        return fallback;
    }
}
