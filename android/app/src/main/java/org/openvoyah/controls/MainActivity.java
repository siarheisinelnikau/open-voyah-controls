package org.openvoyah.controls;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.*;
import androidx.activity.ComponentActivity;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.openvoyah.controls.core.Action;
import org.openvoyah.controls.core.Binding;
import org.openvoyah.controls.core.PanelControls;
import org.openvoyah.controls.core.PanelSlots;
import org.openvoyah.controls.data.frida.FridaPanelControls;
import org.openvoyah.controls.data.settings.LocalSettingsArchive;
import org.openvoyah.controls.data.settings.SettingsStore;
import org.openvoyah.controls.platform.input.ButtonInputAdapter;
import org.openvoyah.controls.platform.ui.ActionExecutor;
import org.openvoyah.controls.presentation.home.ControlsHome;
import org.openvoyah.controls.presentation.settings.BindingDialogs;
import org.openvoyah.controls.presentation.settings.PanelViewModel;
import org.openvoyah.controls.presentation.theme.CockpitDialogs;

public final class MainActivity extends ComponentActivity {
    private static final String OPEN_SETTINGS = "org.openvoyah.controls.action.OPEN_SETTINGS";
    private static final int[] PANEL_TITLES = {
        R.string.ui_3, R.string.ui_4, R.string.ui_5, R.string.ui_6, R.string.ui_7, R.string.ui_8, R.string.ui_9
    };
    private static final int EXPORT = 10, IMPORT = 11;
    private int INK, MUTED, ACCENT;
    private ControlsHome home;
    private String loadError;
    private SettingsStore store;
    private LocalSettingsArchive archive;
    private PanelViewModel panelModel;
    private PanelControls.Result shownResult;
    private Dialog progress;
    private boolean visible;
    private SettingsStore.State state;
    private LinearLayout content;
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
        archive = new LocalSettingsArchive(this);
        dialogs = new CockpitDialogs(this);
        bindingDialogs = new BindingDialogs(dialogs);
        panelModel = new PanelViewModel(new FridaPanelControls(this, store));
        INK = getColor(R.color.cockpit_text);
        MUTED = getColor(R.color.cockpit_muted);
        ACCENT = getColor(R.color.cockpit_accent);
        String version;
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException ex) { throw new IllegalStateException(ex); }
        home = new ControlsHome(this, version, destination -> {
            openSection(destination);
            return kotlin.Unit.INSTANCE;
        }, this::createButtonsPage, this::createSettingsPage, OPEN_SETTINGS.equals(getIntent().getAction()));

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
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(6), 0, dp(6)); return view;
    }
    private Button button(String value, Runnable action) {
        Button button = new Button(this); button.setText(value); button.setAllCaps(false); button.setMinHeight(dp(52));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = dp(6);
        button.setLayoutParams(size);
        button.setOnClickListener(v -> action.run()); return button;
    }
    private LinearLayout card() {
        LinearLayout card = column(); card.setPadding(dp(20), dp(14), dp(20), dp(14));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(getColor(R.color.cockpit_surface)); bg.setStroke(dp(1), getColor(R.color.cockpit_stroke));
        bg.setCornerRadius(dp(18)); card.setBackground(bg);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(14); content.addView(card, params); return card;
    }
    private void render() {
        loadError = null;
        try { state = store.load(); }
        catch (IllegalStateException ex) { state = null; loadError = ex.getMessage(); }
        home.refresh();
    }

    private void openSection(ControlsHome.Destination destination) {
        switch (destination) {
            case APPS -> open(new Intent(this, AppsActivity.class));
            case VOICE -> open(new Intent(this, VoiceActivity.class));
            case DISPLAY -> open(new Intent(this, DisplayActivity.class));
            case VEHICLE -> open(new Intent(this, VehicleSettingsActivity.class));
            case FIRMWARE -> open(new Intent(this, FirmwareActivity.class));
            case SYSTEM -> open(new Intent(Settings.ACTION_SETTINGS));
            case LANGUAGE -> dialogs.choices(UiText.get(R.string.cockpit_language), new String[]{"Русский", "English"}, index -> {
                    UiText.language(index == 0 ? "ru" : "en");
                    recreate();
                });
        }
    }

    private View createButtonsPage() {
        content = column();
        if (state == null) {
            content.addView(text(loadError, 18, INK));
            return content;
        }
        renderPanel();
        content.addView(text(UiText.get(R.string.ui_37), 22, INK));
        LinearLayout status = card();
        boolean connected = ButtonAccessibilityService.connected() != null;
        status.addView(text(connected ? UiText.get(R.string.ui_38) : UiText.get(R.string.ui_39), 20, connected ? ACCENT : INK));
        status.addView(text(connected ? UiText.get(R.string.ui_40) : UiText.get(R.string.ui_41), 15, MUTED));
        status.addView(button(UiText.get(R.string.ui_42), () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        Switch enabled = new Switch(this); enabled.setText(UiText.get(R.string.ui_43)); enabled.setTextColor(INK); enabled.setMinHeight(dp(52)); enabled.setChecked(state.enabled);
        enabled.setOnCheckedChangeListener((v, checked) -> { state.enabled = checked; save(); }); status.addView(enabled);

        content.addView(text(UiText.get(R.string.ui_44), 22, INK));
        if (state.bindings.isEmpty()) {
            LinearLayout empty = card(); empty.addView(text(UiText.get(R.string.ui_45), 19, INK));
            empty.addView(text(UiText.get(R.string.ui_46), 16, MUTED));
        }
        for (Binding binding : new ArrayList<>(state.bindings)) {
            LinearLayout row = card(); row.addView(text(binding.label + (binding.enabled ? "" : UiText.get(R.string.ui_47)), 20, INK));
            row.addView(text(KeyEvent.keyCodeToString(binding.keyCode) + " · " + (binding.device.isEmpty() ? UiText.get(R.string.ui_48) : UiText.get(R.string.ui_49)), 13, MUTED));
            row.addView(text(UiText.get(R.string.ui_50) + label(binding.shortPress) + UiText.get(R.string.ui_51) + label(binding.longPress), 16, INK));
            row.addView(button(UiText.get(R.string.ui_52), () -> edit(binding.keyCode, binding.device, binding.label, binding)));
        }
        content.addView(button(UiText.get(R.string.ui_53), this::capture));
        content.addView(button(UiText.get(R.string.ui_54), this::manual));
        if (android.os.Build.VERSION.SDK_INT == 30) {
            content.addView(button(UiText.get(R.string.oem_steering), this::steering));
            content.addView(text(UiText.get(R.string.oem_steering_scope), 14, MUTED));
        }

        return content;
    }

    private View createSettingsPage() {
        content = column();
        LinearLayout settings = card();
        settings.addView(text(UiText.get(R.string.ui_55), 21, INK));
        if (state != null) {
            settings.addView(text(UiText.get(R.string.ui_56) + state.holdMs + UiText.get(R.string.ui_57)
                + state.debounceMs + UiText.get(R.string.ui_58), 16, MUTED));
            settings.addView(button(UiText.get(R.string.ui_59), this::timings));
        } else {
            settings.addView(text(loadError, 18, INK));
        }
        settings.addView(button(UiText.get(R.string.ui_62), this::confirmReset));

        LinearLayout backup = card();
        backup.addView(text(UiText.get(R.string.settings_archive_title), 21, INK));
        backup.addView(text(UiText.get(R.string.settings_archive_description), 16, MUTED));
        LinearLayout files = new LinearLayout(this);
        files.addView(button(UiText.get(R.string.settings_archive_export), this::exportSettings),
            new LinearLayout.LayoutParams(0, -2, 1));
        files.addView(button(UiText.get(R.string.settings_archive_import), this::importSettings),
            new LinearLayout.LayoutParams(0, -2, 1));
        backup.addView(files);

        LinearLayout info = card(); info.addView(text(UiText.get(R.string.ui_63), 20, INK));
        info.addView(text(UiText.get(R.string.ui_64), 15, MUTED));
        info.addView(text(UiText.get(R.string.ui_65), 15, MUTED));
        return content;
    }
    private String label(Action action) {
        if (action.kind == Action.Kind.START_VOICE) return UiText.get(R.string.quick_voice_title);
        return action.isAssigned() ? action.label : UiText.get(R.string.ui_66);
    }
    private void steering() {
        int[] titles = {R.string.oem_voice, R.string.oem_custom};
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
    private void renderPanel() {
        LinearLayout panel = card(); panel.addView(text(UiText.get(R.string.ui_67), 22, INK));
        panel.addView(text(panelModel.health(), 15, MUTED));
        panel.addView(text(UiText.get(R.string.ui_68), 16, MUTED));
        Switch enabled = new Switch(this); enabled.setText(UiText.get(R.string.ui_69)); enabled.setTextColor(INK); enabled.setMinHeight(dp(52)); enabled.setChecked(state.panelEnabled);
        enabled.setOnCheckedChangeListener((v, checked) -> {
            state.panelEnabled = checked;
            if (save() && !panelModel.configured()) error(UiText.get(R.string.ui_70));
        }); panel.addView(enabled);
        for (int i = 0; i < PanelSlots.PANEL.size(); i++) {
            String slot = PanelSlots.PANEL.get(i), title = UiText.get(PANEL_TITLES[i]);
            Action action = state.panelActions.getOrDefault(slot, Action.NONE);
            panel.addView(button(title + "\n" + (action.isAssigned() ? label(action) : UiText.get(R.string.ui_71)), () -> bindingDialogs.choose(chosen -> {
                if (chosen.isAssigned()) state.panelActions.put(slot, chosen); else state.panelActions.remove(slot);
                if (save()) {
                    render();
                    if (state.panelEnabled && !panelModel.configured()) error(UiText.get(R.string.ui_72));
                }
            })));
        }
        panel.addView(button(UiText.get(R.string.ui_73), () -> {
            state.panelEnabled = true;
            if (!save()) return;
            panelModel.run(PanelControls.Operation.CONNECT);
        }));
        panel.addView(button(UiText.get(R.string.ui_75), () -> panelModel.run(PanelControls.Operation.STATUS)));
        panel.addView(button(UiText.get(R.string.ui_76), () -> {
            state.panelEnabled = false; if (!save()) return;
            panelModel.run(PanelControls.Operation.DISCONNECT);
        }));
        panel.addView(text(UiText.get(R.string.ui_78), 14, MUTED));
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
        try { store.save(state); publishPanel(); return true; }
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
        try {
            if (request == EXPORT) {
                try (OutputStream output = getContentResolver().openOutputStream(data.getData(), "wt")) {
                    if (output == null) throw new java.io.IOException(UiText.get(R.string.ui_123));
                    String backup;
                    try { backup = archive.exportJson(); }
                    catch (org.json.JSONException ex) { backup = store.exportJson(); }
                    output.write(backup.getBytes(StandardCharsets.UTF_8));
                } Toast.makeText(this, UiText.get(R.string.ui_124), Toast.LENGTH_SHORT).show();
            } else if (request == IMPORT) {
                String raw;
                try (InputStream input = getContentResolver().openInputStream(data.getData())) {
                    if (input == null) throw new java.io.IOException(UiText.get(R.string.ui_123));
                    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) { if (output.size() + count > LocalSettingsArchive.MAX_BYTES) throw new java.io.IOException(UiText.get(R.string.ui_125)); output.write(buffer, 0, count); }
                    raw = output.toString(StandardCharsets.UTF_8.name());
                }
                LocalSettingsArchive.Restore imported = archive.prepare(raw);
                String detail = UiText.get(R.string.ui_127) + imported.bindingCount + UiText.get(R.string.ui_128);
                if (imported.complete) detail += "\n\n" + UiText.get(R.string.settings_archive_scope);
                dialogs.message(UiText.get(R.string.ui_126), detail, UiText.get(R.string.ui_129), () -> {
                    try { archive.apply(imported); publishPanel(); render(); }
                    catch (Exception ex) { error(ex.getMessage()); }
                }, UiText.get(R.string.ui_89));
            }
        } catch (Exception ex) { error(UiText.get(R.string.ui_130) + ex.getMessage()); }
    }
}
