package org.openvoyah.controls;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.util.Collections;
import org.openvoyah.controls.data.settings.SettingsStore;
import org.openvoyah.controls.diagnostics.ControlsLog;
import org.openvoyah.controls.platform.input.ButtonInputAdapter;
import org.openvoyah.controls.platform.media.FocusedMediaKeys;
import org.openvoyah.controls.platform.ui.ActionExecutor;
import org.openvoyah.controls.platform.vehicle.automation.VehicleWorkflowRuntime;

/** Retains the service ID used by Android's accessibility grant. */
public final class ButtonAccessibilityService extends AccessibilityService {
    private static volatile ButtonAccessibilityService connected;
    private ButtonInputAdapter input;
    private FocusedMediaKeys mediaKeys;
    private boolean buttonsEnabled;
    private SettingsStore store;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> {
        if ("state".equals(key)) reload();
    };

    static ButtonAccessibilityService connected() { return connected; }
    public static boolean isConnected() { return connected != null; }

    @Override protected void onServiceConnected() {
        if (input != null) return;
        store = new SettingsStore(this);
        input = new ButtonInputAdapter(new ActionExecutor(this));
        mediaKeys = new FocusedMediaKeys(this);
        store.preferences().registerOnSharedPreferenceChangeListener(listener);
        reload();
        connected = this;
        VehicleWorkflowRuntime.serviceConnected(this, this);
    }

    private void reload() {
        try {
            SettingsStore.State state = store.load();
            buttonsEnabled = state.enabled;
            input.configure(state.bindings, state.enabled, state.holdMs, state.debounceMs);
        } catch (IllegalStateException ex) {
            // Every binding stops working here; the stored record stays untouched for export and recovery.
            ControlsLog.error("button_bindings", ex, "outcome", "invalid_stored_settings");
            buttonsEnabled = false;
            input.configure(Collections.emptyList(), false, 650, 100);
        }
    }

    void beginCapture(ButtonInputAdapter.Capture capture) { input.beginCapture(capture); }
    void cancelCapture() { if (input != null) input.cancelCapture(); }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (input == null) return false;
        return mediaKeys.finishOwnedEvent(event, buttonsEnabled) || input.handle(event)
                || (buttonsEnabled && mediaKeys.claim(event));
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() {
        if (input != null) input.interrupt();
        if (mediaKeys != null) mediaKeys.cancelPending();
    }

    @Override public void onDestroy() {
        VehicleWorkflowRuntime.serviceDisconnected(this, this);
        if (connected == this) connected = null;
        if (store != null) store.preferences().unregisterOnSharedPreferenceChangeListener(listener);
        if (input != null) { input.close(); input = null; }
        super.onDestroy();
    }
}
