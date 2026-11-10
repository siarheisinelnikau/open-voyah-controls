package org.openvoyah.controls;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.util.Collections;
import org.openvoyah.controls.data.settings.SettingsStore;
import org.openvoyah.controls.platform.input.ButtonInputAdapter;
import org.openvoyah.controls.platform.ui.ActionExecutor;

/** Retains the service ID used by Android's accessibility grant. */
public final class ButtonAccessibilityService extends AccessibilityService {
    private static ButtonAccessibilityService connected;
    private ButtonInputAdapter input;
    private SettingsStore store;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> {
        if ("state".equals(key)) reload();
    };

    static ButtonAccessibilityService connected() { return connected; }

    @Override protected void onServiceConnected() {
        if (input != null) return;
        store = new SettingsStore(this);
        input = new ButtonInputAdapter(new ActionExecutor(this)::execute);
        store.preferences().registerOnSharedPreferenceChangeListener(listener);
        reload();
        connected = this;
    }

    private void reload() {
        try {
            SettingsStore.State state = store.load();
            input.configure(state.bindings, state.enabled, state.holdMs, state.debounceMs);
        } catch (IllegalStateException ex) { input.configure(Collections.emptyList(), false, 650, 100); }
    }

    void beginCapture(ButtonInputAdapter.Capture capture) { input.beginCapture(capture); }
    void cancelCapture() { if (input != null) input.cancelCapture(); }

    @Override protected boolean onKeyEvent(KeyEvent event) { return input != null && input.handle(event); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { if (input != null) input.interrupt(); }

    @Override public void onDestroy() {
        if (connected == this) connected = null;
        if (store != null) store.preferences().unregisterOnSharedPreferenceChangeListener(listener);
        if (input != null) { input.close(); input = null; }
        super.onDestroy();
    }
}
