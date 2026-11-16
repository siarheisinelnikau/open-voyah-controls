package org.openvoyah.controls;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import androidx.activity.ComponentActivity;
import java.util.ArrayList;
import java.util.List;
import org.openvoyah.controls.core.Action;
import org.openvoyah.controls.core.Binding;
import org.openvoyah.controls.core.PanelControls;
import org.openvoyah.controls.core.PanelSlots;
import org.openvoyah.controls.data.frida.FridaPanelControls;
import org.openvoyah.controls.data.settings.SettingsStore;
import org.openvoyah.controls.platform.input.ButtonInputAdapter;
import org.openvoyah.controls.platform.ui.ActionExecutor;
import org.openvoyah.controls.presentation.home.ControlsHome;
import org.openvoyah.controls.presentation.home.HomeActions;
import org.openvoyah.controls.presentation.home.HomeCommand;
import org.openvoyah.controls.presentation.settings.BindingDialogs;
import org.openvoyah.controls.presentation.settings.PanelViewModel;
import org.openvoyah.controls.presentation.settings.SettingsArchiveDialogs;
import org.openvoyah.controls.presentation.theme.CockpitDialogs;

public final class MainActivity extends ComponentActivity implements HomeActions {
    private static final String OPEN_SETTINGS = "org.openvoyah.controls.action.OPEN_SETTINGS";
    private static final int EXPORT = 10, IMPORT = 11;
    private ControlsHome home;
    private String loadError;
    private SettingsStore store;
    private SettingsArchiveDialogs archiveDialogs;
    private PanelViewModel panelModel;
    private PanelControls.Result shownResult;
    private Dialog progress;
    private boolean visible;
    private SettingsStore.State state;
    private Dialog learning;
    private CockpitDialogs dialogs;
    private BindingDialogs bindingDialogs;

    public static Intent settingsIntent(Context context) {
        return new Intent(context, MainActivity.class).setAction(OPEN_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new SettingsStore(this);
        dialogs = new CockpitDialogs(this);
        bindingDialogs = new BindingDialogs(dialogs);
        panelModel = new PanelViewModel(new FridaPanelControls(this, store));
        String version;
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException ex) { throw new IllegalStateException(ex); }
        home = new ControlsHome(this, version, destination -> {
            openSection(destination);
            return kotlin.Unit.INSTANCE;
        }, this, OPEN_SETTINGS.equals(getIntent().getAction()));
        archiveDialogs = new SettingsArchiveDialogs(this, dialogs, this::render);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (OPEN_SETTINGS.equals(intent.getAction())) home.showSettings();
    }

    @Override public void onResume() {
        super.onResume();
        visible = true;
        render();
        panelModel.observe(this::renderBridgeState);
    }

    @Override public void onPause() {
        visible = false;
        panelModel.observe(null);
        dismissProgress();
        if (learning != null) learning.dismiss();
        super.onPause();
    }
    @Override public void onDestroy() { dialogs.dismiss(); super.onDestroy(); }
    private void render() {
        loadError = null;
        try { state = store.load(); }
        catch (IllegalStateException ex) { state = null; loadError = ex.getMessage(); }
        home.refresh(state, loadError, panelModel.health(), ButtonAccessibilityService.connected() != null);
    }

    private void openSection(ControlsHome.Destination destination) {
        switch (destination) {
            case APPS -> open(new Intent(this, AppsActivity.class));
            case LISTEN -> open(QuickVoiceActivity.intent(this));
            case VOICE -> open(new Intent(this, VoiceActivity.class));
            case DISPLAY -> open(new Intent(this, DisplayActivity.class));
            case VEHICLE -> open(new Intent(this, VehicleSettingsActivity.class));
            case FIRMWARE -> open(new Intent(this, FirmwareActivity.class));
            case SYSTEM -> open(new Intent(Settings.ACTION_SETTINGS));
            case UPDATES -> open(new Intent(this, UpdatesActivity.class));
            case LANGUAGE -> dialogs.choices(UiText.get(R.string.cockpit_language), new String[]{"Русский", "English"}, index -> {
                    UiText.language(index == 0 ? "ru" : "en");
                    recreate();
                });
        }
    }

    private String label(Action action) {
        if (action.kind == Action.Kind.START_VOICE) return UiText.get(R.string.quick_voice_title);
        return action.isAssigned() ? action.label : UiText.get(R.string.ui_66);
    }
    private void steering() {
        int[] titles = {R.string.oem_voice, R.string.oem_custom, R.string.oem_camera};
        String[] choices = new String[titles.length * 2];
        for (int i = 0; i < choices.length; i++) {
            Action action = state.steeringActions.getOrDefault(PanelSlots.STEERING.get(i), Action.NONE);
            choices[i] = UiText.get(titles[i / 2]) + " · " + UiText.get(i % 2 == 0 ? R.string.oem_short : R.string.oem_hold) + "\n" + label(action);
        }
        dialogs.choices(UiText.get(R.string.oem_steering), choices, i -> bindingDialogs.choose(action -> {
                String slot = PanelSlots.STEERING.get(i);
                if (action.isAssigned()) state.steeringActions.put(slot, action); else state.steeringActions.remove(slot);
                if (save() && !panelModel.configured()) error(UiText.get(R.string.ui_72));
            }), UiText.get(R.string.oem_restore_keys), () ->
                dialogs.message(UiText.get(R.string.oem_restore_keys), UiText.get(R.string.oem_restore_keys_info),
                    UiText.get(R.string.ui_187), () -> {
                        state.steeringActions.clear();
                        save();
                    }, UiText.get(R.string.ui_89)));
    }
    @Override public void command(HomeCommand command) {
        switch (command) {
            case ACCESSIBILITY -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            case CAPTURE -> capture();
            case MANUAL -> manual();
            case STEERING -> steering();
            case TIMINGS -> timings();
            case RESET -> confirmReset();
            case EXPORT -> exportSettings();
            case IMPORT -> importSettings();
            case CONNECT -> {
                state.panelEnabled = true;
                if (save()) panelModel.run(PanelControls.Operation.CONNECT);
            }
            case STATUS -> panelModel.run(PanelControls.Operation.STATUS);
            case DISCONNECT -> {
                state.panelEnabled = false;
                if (save()) panelModel.run(PanelControls.Operation.DISCONNECT);
            }
        }
    }
    @Override public void buttonsEnabled(boolean enabled) {
        if (state == null) return;
        boolean previous = state.enabled;
        state.enabled = enabled;
        if (!save()) { state.enabled = previous; render(); }
    }
    @Override public void panelEnabled(boolean enabled) {
        if (state == null) return;
        boolean previous = state.panelEnabled;
        state.panelEnabled = enabled;
        if (!save()) { state.panelEnabled = previous; render(); }
        else if (!panelModel.configured()) error(UiText.get(R.string.ui_70));
    }
    @Override public void choosePanel(String slot) {
        if (state == null || !PanelSlots.forProduct(android.os.Build.PRODUCT).contains(slot)) return;
        bindingDialogs.choose(chosen -> {
            if (chosen.isAssigned()) state.panelActions.put(slot, chosen); else state.panelActions.remove(slot);
            if (save() && state.panelEnabled && !panelModel.configured()) error(UiText.get(R.string.ui_72));
        });
    }
    @Override public void editBinding(String id) {
        if (state == null) return;
        for (Binding binding : state.bindings) {
            if (binding.id.equals(id)) { edit(binding.keyCode, binding.device, binding.label, binding); return; }
        }
        error(UiText.get(R.string.binding_changed));
    }
    private void renderBridgeState(PanelViewModel.State state) {
        if (state.busy()) {
            if (progress == null) {
                progress = dialogs.message(UiText.get(R.string.ui_79), UiText.get(R.string.ui_80), null, null, null, false);
            }
            return;
        }
        dismissProgress();
        if (state.result() == null || state.result() == shownResult) return;
        shownResult = state.result();
        render();
        dialogs.message(UiText.get(shownResult.successful() ? R.string.ui_81 : R.string.ui_82), shownResult.message(), UiText.get(R.string.ui_83));
    }

    private void dismissProgress() {
        if (progress != null) { progress.dismiss(); progress = null; }
    }
    private boolean save() {
        try { store.save(state); publishPanel(); render(); return true; }
        catch (RuntimeException ex) { error(ex.getMessage()); return false; }
    }
    private void publishPanel() {
        panelModel.publish(message -> {
            if (visible && !isFinishing()) error(message);
        });
    }
    private void error(String message) { dialogs.message(UiText.get(R.string.ui_84), message, UiText.get(R.string.ui_83)); }
    private void open(Intent intent) { try { startActivity(intent); } catch (RuntimeException ex) { error(UiText.get(R.string.ui_85)); } }
    private void capture() {
        ButtonAccessibilityService service = ButtonAccessibilityService.connected();
        if (service == null) { error(UiText.get(R.string.ui_86)); return; }
        learning = dialogs.message(UiText.get(R.string.ui_87), UiText.get(R.string.ui_88), null, null,
            UiText.get(R.string.ui_89), true, () -> { service.cancelCapture(); learning = null; });
        service.beginCapture(new ButtonInputAdapter.Capture() {
            public void captured(int code, String descriptor, String deviceName) {
                if (isFinishing() || isDestroyed()) return;
                if (learning != null) learning.dismiss();
                edit(code, descriptor, KeyEvent.keyCodeToString(code) + " · " + deviceName, null);
            }
            public void expired() { if (learning != null) learning.dismiss(); if (!isFinishing()) error(UiText.get(R.string.ui_90)); }
        });
    }
    private void manual() {
        bindingDialogs.manual(key -> edit(key, "", KeyEvent.keyCodeToString(key), null));
    }
    private void edit(int code, String descriptor, String initialLabel, Binding existing) {
        bindingDialogs.edit(code, descriptor, initialLabel, existing, binding -> saveBinding(binding, existing),
            () -> removeBinding(existing), action -> new ActionExecutor(this).execute(action));
    }
    private int bindingIndex(Binding binding) {
        if (state == null || binding == null) return -1;
        for (int index = 0; index < state.bindings.size(); index++) {
            if (state.bindings.get(index).id.equals(binding.id)) return index;
        }
        return -1;
    }
    private String saveBinding(Binding binding, Binding existing) {
        if (binding.longPress.isAssigned() && !binding.shortPress.isAssigned()) return UiText.get(R.string.button_tap_required);
        int index = bindingIndex(existing);
        if (state == null || (existing != null && index < 0)) return UiText.get(R.string.binding_changed);
        for (Binding other : state.bindings) {
            if (!other.id.equals(binding.id) && other.keyCode == binding.keyCode && other.device.equals(binding.device)) {
                return UiText.get(R.string.ui_107);
            }
        }
        List<Binding> previous = new ArrayList<>(state.bindings);
        if (index < 0) state.bindings.add(binding); else state.bindings.set(index, binding);
        try {
            store.save(state);
        } catch (RuntimeException ex) {
            state.bindings.clear();
            state.bindings.addAll(previous);
            return ex.getMessage() == null ? UiText.get(R.string.ui_84) : ex.getMessage();
        }
        publishPanel();
        render();
        return null;
    }
    private void removeBinding(Binding existing) {
        int index = bindingIndex(existing);
        if (index < 0) { error(UiText.get(R.string.binding_changed)); return; }
        Binding previous = state.bindings.remove(index);
        if (save()) render(); else state.bindings.add(index, previous);
    }
    private void timings() {
        bindingDialogs.timings(state.holdMs, state.debounceMs, (hold, debounce) -> {
            int oldHold = state.holdMs, oldDebounce = state.debounceMs;
            state.holdMs = hold;
            state.debounceMs = debounce;
            if (save()) { render(); return true; }
            state.holdMs = oldHold;
            state.debounceMs = oldDebounce;
            return false;
        });
    }
    private void confirmReset() {
        dialogs.message(UiText.get(R.string.ui_119), UiText.get(R.string.ui_120), UiText.get(R.string.ui_121), () -> {
            SettingsStore.State previous = state;
            state = new SettingsStore.State();
            if (save()) render(); else state = previous;
        }, UiText.get(R.string.ui_89));
    }
    private void exportSettings() { document(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "open-voyah-controls.json"), EXPORT); }
    private void importSettings() { document(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE), IMPORT); }
    private void document(Intent intent, int request) { try { startActivityForResult(intent, request); } catch (RuntimeException ex) { error(UiText.get(R.string.ui_122)); } }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == EXPORT) archiveDialogs.export(data.getData());
        else if (request == IMPORT) archiveDialogs.prepare(data.getData());
    }
}
