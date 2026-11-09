package org.openvoyah.controls;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import org.openvoyah.controls.core.ButtonEngine;

public final class ButtonAccessibilityService extends AccessibilityService implements SharedPreferences.OnSharedPreferenceChangeListener {
    interface Capture { void captured(int keyCode, String descriptor, String deviceName); void expired(); }
    private static ButtonAccessibilityService connected;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ButtonEngine engine;
    private SettingsStore store;
    private Capture capture;
    private KeyEvent captureDown;
    private Runnable captureTimeout;
    private String captureDescriptor;
    private String captureDeviceName;

    static ButtonAccessibilityService connected() { return connected; }
    @Override protected void onServiceConnected() {
        connected = this;
        store = new SettingsStore(this);
        ActionExecutor executor = new ActionExecutor(this);
        engine = new ButtonEngine(new ButtonEngine.Scheduler() {
            public Object after(long delay, Runnable callback) { handler.postDelayed(callback, delay); return callback; }
            public void cancel(Object token) { handler.removeCallbacks((Runnable) token); }
        }, executor::execute);
        store.preferences().registerOnSharedPreferenceChangeListener(this);
        reload();
    }
    private void reload() {
        try {
            SettingsStore.State state = store.load();
            engine.configure(state.bindings, state.enabled, state.holdMs, state.debounceMs);
        } catch (IllegalStateException ex) { engine.configure(java.util.Collections.emptyList(), false, 650, 100); }
    }
    @Override public void onSharedPreferenceChanged(SharedPreferences preferences, String key) { if ("state".equals(key)) reload(); }

    void beginCapture(Capture callback) {
        cancelCapture();
        engine.cancelPending();
        capture = callback;
        captureTimeout = () -> { Capture current = capture; cancelCapture(); if (current != null) current.expired(); };
        handler.postDelayed(captureTimeout, 30_000);
    }
    void cancelCapture() {
        capture = null;
        // Retain a swallowed DOWN until its matching UP arrives.
        if (captureTimeout != null) handler.removeCallbacks(captureTimeout);
    }
    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (engine == null) return false;
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_UNKNOWN) return false;
        InputDevice device = event.getDevice();
        String descriptor = device == null ? "id:" + event.getDeviceId() : device.getDescriptor();
        if (captureDown != null && captureDown.getDeviceId() == event.getDeviceId() && captureDown.getKeyCode() == code) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                Capture callback = capture;
                String capturedDevice = captureDescriptor, deviceName = captureDeviceName;
                captureDown = null;
                cancelCapture();
                if (callback != null && !event.isCanceled()) callback.captured(code, capturedDevice, deviceName);
            }
            return true;
        }
        if (capture != null && captureDown == null && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            captureDown = new KeyEvent(event);
            captureDescriptor = descriptor;
            captureDeviceName = device == null ? UiText.get(R.string.ui_2) + event.getDeviceId() : device.getName();
            return true;
        }
        long now = SystemClock.uptimeMillis();
        if (event.getAction() == KeyEvent.ACTION_UP) return engine.up(descriptor, code, event.isCanceled(), now);
        if (capture != null) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) return engine.down(descriptor, code, event.getRepeatCount(), now);
        return false;
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { if (engine != null) engine.cancelPending(); cancelCapture(); }
    @Override public void onDestroy() {
        if (connected == this) connected = null;
        cancelCapture(); captureDown = null;
        if (store != null) store.preferences().unregisterOnSharedPreferenceChangeListener(this);
        if (engine != null) engine.close();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
