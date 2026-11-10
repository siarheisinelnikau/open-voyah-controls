package org.openvoyah.controls;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.widget.*;
import androidx.activity.ComponentActivity;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
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
import org.openvoyah.controls.presentation.settings.PanelViewModel;

public final class MainActivity extends ComponentActivity {
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
    private AlertDialog progress;
    private boolean visible;
    private SettingsStore.State state;
    private LinearLayout content;
    private AlertDialog learning;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new SettingsStore(this);
        archive = new LocalSettingsArchive(this);
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
        }, this::createButtonsPage, this::createSettingsPage);

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
            case FIRMWARE -> open(new Intent(this, FirmwareActivity.class));
            case SYSTEM -> open(new Intent(Settings.ACTION_SETTINGS));
            case LANGUAGE -> new AlertDialog.Builder(this).setTitle(UiText.get(R.string.cockpit_language))
                .setItems(new String[]{"Русский", "English"}, (dialog, index) -> {
                    UiText.language(index == 0 ? "ru" : "en");
                    recreate();
                }).show();
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
        int[] titles = {R.string.oem_voice, R.string.oem_custom, R.string.oem_camera};
        String[] choices = new String[PanelSlots.STEERING.size()];
        for (int i = 0; i < choices.length; i++) {
            Action action = state.steeringActions.getOrDefault(PanelSlots.STEERING.get(i), Action.NONE);
            choices[i] = UiText.get(titles[i / 2]) + " · " + UiText.get(i % 2 == 0 ? R.string.oem_short : R.string.oem_hold) + "\n" + label(action);
        }
        new AlertDialog.Builder(this).setTitle(UiText.get(R.string.oem_steering))
            .setItems(choices, (d, i) -> chooseAction(action -> {
                String slot = PanelSlots.STEERING.get(i);
                if (action.isAssigned()) state.steeringActions.put(slot, action); else state.steeringActions.remove(slot);
                if (save() && !panelModel.configured()) error(UiText.get(R.string.ui_72));
            })).setNeutralButton(UiText.get(R.string.oem_restore_keys), (d, w) ->
                new AlertDialog.Builder(this).setTitle(UiText.get(R.string.oem_restore_keys))
                    .setMessage(UiText.get(R.string.oem_restore_keys_info))
                    .setPositiveButton(UiText.get(R.string.ui_187), (dialog, item) -> {
                        state.steeringActions.clear();
                        save();
                    }).setNegativeButton(UiText.get(R.string.ui_89), null).show())
            .setNegativeButton(UiText.get(R.string.ui_172), null).show();
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
            panel.addView(button(title + "\n" + (action.isAssigned() ? label(action) : UiText.get(R.string.ui_71)), () -> chooseAction(chosen -> {
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
                progress = new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_79))
                    .setMessage(UiText.get(R.string.ui_80)).setCancelable(false).show();
            }
            return;
        }
        dismissProgress();
        if (state.result() == null || state.result() == shownResult) return;
        shownResult = state.result();
        render();
        new AlertDialog.Builder(this).setTitle(UiText.get(shownResult.successful() ? R.string.ui_81 : R.string.ui_82))
            .setMessage(shownResult.message()).setPositiveButton(UiText.get(R.string.ui_83), null).show();
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
    private void error(String message) { new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_84)).setMessage(message).setPositiveButton(UiText.get(R.string.ui_83), null).show(); }
    private void open(Intent intent) { try { startActivity(intent); } catch (RuntimeException ex) { error(UiText.get(R.string.ui_85)); } }
    private void capture() {
        ButtonAccessibilityService service = ButtonAccessibilityService.connected();
        if (service == null) { error(UiText.get(R.string.ui_86)); return; }
        learning = new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_87))
            .setMessage(UiText.get(R.string.ui_88))
            .setNegativeButton(UiText.get(R.string.ui_89), null).create();
        learning.setOnDismissListener(d -> { service.cancelCapture(); learning = null; }); learning.show();
        service.beginCapture(new ButtonInputAdapter.Capture() {
            public void captured(int code, String descriptor, String deviceName) {
                if (isFinishing() || isDestroyed()) return;
                if (learning != null) learning.dismiss();
                edit(code, descriptor, KeyEvent.keyCodeToString(code) + " · " + deviceName, null);
            }
            public void expired() { if (learning != null) learning.dismiss(); if (!isFinishing()) error(UiText.get(R.string.ui_90)); }
        });
    }
    private EditText field(String hint, String value, boolean numeric) {
        EditText edit = new EditText(this); edit.setHint(hint); edit.setText(value); edit.setSingleLine(true);
        if (numeric) edit.setInputType(InputType.TYPE_CLASS_NUMBER); return edit;
    }
    private void manual() {
        EditText code = field(UiText.get(R.string.ui_91), "", true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_92)).setView(code).setPositiveButton(UiText.get(R.string.ui_93), null).setNegativeButton(UiText.get(R.string.ui_89), null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            try { int key = Integer.parseInt(code.getText().toString());
                if (key <= 0 || key > 65535 || key == 3 || key == 26) throw new IllegalArgumentException();
                dialog.dismiss(); edit(key, "", KeyEvent.keyCodeToString(key), null);
            } catch (IllegalArgumentException ex) { code.setError(UiText.get(R.string.ui_94)); }
        })); dialog.show();
    }
    private void edit(int code, String descriptor, String initialLabel, Binding existing) {
        LinearLayout body = column(); body.setPadding(dp(20), dp(6), dp(20), dp(10));
        EditText name = field(UiText.get(R.string.ui_95), initialLabel, false); body.addView(name);
        body.addView(text(UiText.get(R.string.ui_96) + code, 14, MUTED));
        Action[] actions = { existing == null ? Action.NONE : existing.shortPress, existing == null ? Action.NONE : existing.longPress };
        Button shortButton = button(UiText.get(R.string.ui_50) + label(actions[0]), () -> {});
        Button longButton = button(UiText.get(R.string.ui_97) + label(actions[1]), () -> {});
        shortButton.setOnClickListener(v -> chooseAction(a -> { actions[0] = a; shortButton.setText(UiText.get(R.string.ui_50) + label(a)); }));
        longButton.setOnClickListener(v -> chooseAction(a -> { actions[1] = a; longButton.setText(UiText.get(R.string.ui_97) + label(a)); }));
        body.addView(shortButton); body.addView(longButton);
        CheckBox any = new CheckBox(this); any.setText(UiText.get(R.string.ui_98)); any.setChecked(descriptor.isEmpty()); any.setEnabled(!descriptor.isEmpty()); body.addView(any);
        CheckBox enabled = new CheckBox(this); enabled.setText(UiText.get(R.string.ui_99)); enabled.setChecked(existing == null || existing.enabled); body.addView(enabled);
        body.addView(text(UiText.get(R.string.ui_100), 14, MUTED));
        body.addView(button(UiText.get(R.string.ui_101), () -> new ActionExecutor(this).execute(actions[0])));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(existing == null ? UiText.get(R.string.ui_102) : UiText.get(R.string.ui_103)).setView(body)
            .setPositiveButton(UiText.get(R.string.ui_104), null).setNegativeButton(UiText.get(R.string.ui_89), null)
            .setNeutralButton(existing == null ? "" : UiText.get(R.string.ui_105), (d, w) -> { if (existing != null) { state.bindings.remove(existing); if (save()) render(); } }).create();
        dialog.setOnShowListener(d -> {
            if (existing == null) dialog.getButton(-3).setVisibility(View.GONE);
            dialog.getButton(-1).setOnClickListener(v -> {
                String title = name.getText().toString().trim();
                if (title.isEmpty() || title.length() > 120) { name.setError(UiText.get(R.string.ui_106)); return; }
                String device = any.isChecked() ? "" : descriptor;
                for (Binding b : state.bindings) if (b != existing && b.keyCode == code && b.device.equals(device)) { name.setError(UiText.get(R.string.ui_107)); return; }
                Binding binding = new Binding(existing == null ? UUID.randomUUID().toString() : existing.id, title, device, code, actions[0], actions[1], enabled.isChecked());
                List<Binding> previous = new ArrayList<>(state.bindings);
                if (existing != null) state.bindings.set(state.bindings.indexOf(existing), binding); else state.bindings.add(binding);
                if (save()) { dialog.dismiss(); render(); } else { state.bindings.clear(); state.bindings.addAll(previous); }
            });
        }); dialog.show();
    }
    private void chooseAction(Consumer<Action> selected) {
        new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_108)).setItems(new String[]{UiText.get(R.string.ui_109), UiText.get(R.string.ui_110), UiText.get(R.string.ui_61), UiText.get(R.string.ui_111), UiText.get(R.string.quick_voice_title)}, (d, index) -> {
            if (index == 0) selected.accept(Action.NONE);
            if (index == 1) chooseApp(selected);
            if (index == 2) selected.accept(new Action(Action.Kind.SYSTEM_SETTINGS, "", UiText.get(R.string.ui_61)));
            if (index == 3) selected.accept(new Action(Action.Kind.CONTROLS_SETTINGS, "", UiText.get(R.string.ui_111)));
            if (index == 4) selected.accept(new Action(Action.Kind.START_VOICE, "", UiText.get(R.string.quick_voice_title)));
        }).show();
    }
    private void chooseApp(Consumer<Action> selected) {
        new Thread(() -> {
            List<Action> choices = new ArrayList<>();
            for (InstalledApps.Entry app : InstalledApps.list(this)) choices.add(app.binding());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (choices.isEmpty()) { error(UiText.get(R.string.ui_112)); return; }
                LinearLayout panel = column(); EditText search = field(UiText.get(R.string.ui_113), "", false); panel.addView(search);
                ListView list = new ListView(this); panel.addView(list, new LinearLayout.LayoutParams(-1, dp(300)));
                List<Action> visible = new ArrayList<>(choices);
                ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
                Runnable refresh = () -> { adapter.clear(); for (Action a : visible) adapter.add(a.label + "\n" + a.component.split("/")[0]); adapter.notifyDataSetChanged(); };
                list.setAdapter(adapter); refresh.run();
                search.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        String q = s.toString().toLowerCase(java.util.Locale.ROOT); visible.clear();
                        for (Action a : choices) if ((a.label + " " + a.component).toLowerCase(java.util.Locale.ROOT).contains(q)) visible.add(a);
                        refresh.run();
                    }
                    public void afterTextChanged(android.text.Editable e) { }
                });
                AlertDialog picker = new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_114)).setView(panel).setNegativeButton(UiText.get(R.string.ui_89), null).create();
                list.setOnItemClickListener((p, v, position, id) -> { selected.accept(visible.get(position)); picker.dismiss(); }); picker.show();
            });
        }, "app-picker").start();
    }
    private void timings() {
        LinearLayout body = column(); body.setPadding(dp(20), dp(10), dp(20), dp(10));
        EditText hold = field(UiText.get(R.string.ui_115), String.valueOf(state.holdMs), true);
        EditText debounce = field(UiText.get(R.string.ui_116), String.valueOf(state.debounceMs), true); body.addView(hold); body.addView(debounce);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_117)).setView(body).setPositiveButton(UiText.get(R.string.ui_104), null).setNegativeButton(UiText.get(R.string.ui_89), null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            try { int h = Integer.parseInt(hold.getText().toString()), b = Integer.parseInt(debounce.getText().toString());
                if (h < 250 || h > 2000 || b < 0 || b > 500) throw new IllegalArgumentException();
                state.holdMs = h; state.debounceMs = b; if (save()) { dialog.dismiss(); render(); }
            } catch (IllegalArgumentException ex) { hold.setError(UiText.get(R.string.ui_118)); }
        })); dialog.show();
    }
    private void confirmReset() { new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_119)).setMessage(UiText.get(R.string.ui_120)).setNegativeButton(UiText.get(R.string.ui_89), null).setPositiveButton(UiText.get(R.string.ui_121), (d, w) -> { state = new SettingsStore.State(); if (save()) render(); }).show(); }
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
                new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_126)).setMessage(detail)
                    .setNegativeButton(UiText.get(R.string.ui_89), null)
                    .setPositiveButton(UiText.get(R.string.ui_129), (d, w) -> {
                        try { archive.apply(imported); publishPanel(); render(); }
                        catch (Exception ex) { error(ex.getMessage()); }
                    }).show();
            }
        } catch (Exception ex) { error(UiText.get(R.string.ui_130) + ex.getMessage()); }
    }
}
