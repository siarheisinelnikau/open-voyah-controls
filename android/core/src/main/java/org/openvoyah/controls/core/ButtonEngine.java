package org.openvoyah.controls.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Single-threaded press state machine. Timings use monotonic milliseconds. */
public final class ButtonEngine {
    public interface Scheduler { Object after(long delayMs, Runnable callback); void cancel(Object token); }
    public interface Sink { void execute(Action action); }
    private final Scheduler scheduler;
    private final Sink sink;
    private final Map<String, Press> presses = new HashMap<>();
    private final Map<String, Long> lastUps = new HashMap<>();
    private List<Binding> bindings = new ArrayList<>();
    private boolean enabled = true;
    private long holdMs = 650;
    private long debounceMs = 100;

    private static final class Press {
        Binding binding;
        Object timer;
        boolean longFired;
        boolean canceled;
    }

    public ButtonEngine(Scheduler scheduler, Sink sink) { this.scheduler = scheduler; this.sink = sink; }

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
        Long last = lastUps.get(key);
        press.canceled = last != null && now >= last && now - last < debounceMs;
        presses.put(key, press);
        if (!press.canceled && binding.longPress.isAssigned()) {
            press.timer = scheduler.after(holdMs, () -> {
                if (presses.get(key) == press && !press.canceled) {
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
        if (!canceled && !press.canceled && !press.longFired && press.binding.shortPress.isAssigned()) {
            sink.execute(press.binding.shortPress);
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
