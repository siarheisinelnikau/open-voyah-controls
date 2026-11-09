package org.openvoyah.controls;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.widget.*;
import org.openvoyah.controls.core.Action;
import org.openvoyah.controls.core.Binding;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final int EXPORT = 10, IMPORT = 11;
    private static final int INK = Color.rgb(232, 240, 247), MUTED = Color.rgb(160, 179, 195), ACCENT = Color.rgb(113, 223, 197);
    private SettingsStore store;
    private SettingsStore.State state;
    private LinearLayout content;
    private AlertDialog learning;

    @Override public void onCreate(Bundle saved) { super.onCreate(saved); store = new SettingsStore(this); }
    @Override public void onResume() { super.onResume(); render(); }
    @Override public void onPause() {
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
        button.setOnClickListener(v -> action.run()); return button;
    }
    private LinearLayout card() {
        LinearLayout card = column(); card.setPadding(dp(20), dp(14), dp(20), dp(14));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(26, 40, 53)); bg.setCornerRadius(dp(18)); card.setBackground(bg);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(14); content.addView(card, params); return card;
    }
    private void render() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = column(); content.setPadding(dp(24), dp(24), dp(24), dp(24)); scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll); scroll.requestApplyInsets();
        TextView title = text(UiText.get(R.string.ui_33), 28, INK); title.setTypeface(null, Typeface.BOLD); content.addView(title);
        String version;
        try { version=getPackageManager().getPackageInfo(getPackageName(),0).versionName; }
        catch(android.content.pm.PackageManager.NameNotFoundException ex) { throw new IllegalStateException(ex); }
        content.addView(text("OPEN VOYAH CONTROLS  /  " + version, 12, ACCENT));
        content.addView(button(UiText.get(R.string.ui_34), () -> open(new Intent(this, VoiceActivity.class))));
        content.addView(button("Language / Язык: " + (UiText.language().equals("ru") ? "Русский" : "English"), () ->
            new AlertDialog.Builder(this).setTitle("Language / Язык").setItems(new String[]{"Русский", "English"}, (d,n) -> {
                UiText.language(n == 0 ? "ru" : "en"); recreate();
            }).show()));
        content.addView(button(UiText.get(R.string.firmware_settings), () -> open(new Intent(this, FirmwareActivity.class))));
        try { state = store.load(); }
        catch (IllegalStateException ex) {
            content.addView(text(ex.getMessage(), 18, INK));
            content.addView(button(UiText.get(R.string.ui_35), this::exportSettings));
            content.addView(button(UiText.get(R.string.ui_36), this::confirmReset)); return;
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

        LinearLayout settings = card(); settings.addView(text(UiText.get(R.string.ui_55), 21, INK));
        settings.addView(text(UiText.get(R.string.ui_56) + state.holdMs + UiText.get(R.string.ui_57) + state.debounceMs + UiText.get(R.string.ui_58), 16, MUTED));
        settings.addView(button(UiText.get(R.string.ui_59), this::timings));
        settings.addView(button(UiText.get(R.string.ui_35), this::exportSettings));
        settings.addView(button(UiText.get(R.string.ui_60), this::importSettings));
        settings.addView(button(UiText.get(R.string.ui_61), () -> open(new Intent(Settings.ACTION_SETTINGS))));
        settings.addView(button(UiText.get(R.string.ui_62), this::confirmReset));

        LinearLayout info = card(); info.addView(text(UiText.get(R.string.ui_63), 20, INK));
        info.addView(text(UiText.get(R.string.ui_64), 15, MUTED));
        info.addView(text(UiText.get(R.string.ui_65), 15, MUTED));
    }
    private String label(Action action) { return action.isAssigned() ? action.label : UiText.get(R.string.ui_66); }
    private void renderPanel() {
        LinearLayout panel = card(); panel.addView(text(UiText.get(R.string.ui_67), 22, INK));
        panel.addView(text(FridaBridge.lastHealth(this), 15, MUTED));
        panel.addView(text(UiText.get(R.string.ui_68), 16, MUTED));
        Switch enabled = new Switch(this); enabled.setText(UiText.get(R.string.ui_69)); enabled.setTextColor(INK); enabled.setMinHeight(dp(52)); enabled.setChecked(state.panelEnabled);
        enabled.setOnCheckedChangeListener((v, checked) -> {
            state.panelEnabled = checked;
            if (save() && !FridaBridge.wasConfigured(this)) error(UiText.get(R.string.ui_70));
        }); panel.addView(enabled);
        for (int i = 0; i < FridaBridge.SLOTS.length; i++) {
            String slot = FridaBridge.SLOTS[i], title = FridaBridge.labels()[i];
            Action action = state.panelActions.getOrDefault(slot, Action.NONE);
            panel.addView(button(title + "\n" + (action.isAssigned() ? action.label : UiText.get(R.string.ui_71)), () -> chooseAction(chosen -> {
                if (chosen.isAssigned()) state.panelActions.put(slot, chosen); else state.panelActions.remove(slot);
                if (save()) {
                    render();
                    if (state.panelEnabled && !FridaBridge.wasConfigured(this)) error(UiText.get(R.string.ui_72));
                }
            })));
        }
        panel.addView(button(UiText.get(R.string.ui_73), () -> {
            state.panelEnabled = true;
            if (!save()) return;
            SettingsStore.State snapshot = store.load();
            bridgeWork(() -> { FridaBridge bridge = new FridaBridge(this); bridge.connect(snapshot); return UiText.get(R.string.ui_74) + bridge.status(); });
        }));
        panel.addView(button(UiText.get(R.string.ui_75), () -> bridgeWork(() -> new FridaBridge(this).status())));
        panel.addView(button(UiText.get(R.string.ui_76), () -> {
            state.panelEnabled = false; if (!save()) return;
            bridgeWork(() -> { new FridaBridge(this).disconnect(); return UiText.get(R.string.ui_77); });
        }));
        panel.addView(text(UiText.get(R.string.ui_78), 14, MUTED));
    }
    private interface BridgeWork { String run() throws Exception; }
    private void bridgeWork(BridgeWork work) {
        AlertDialog progress = new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_79)).setMessage(UiText.get(R.string.ui_80)).setCancelable(false).create(); progress.show();
        new Thread(() -> {
            String result; boolean success;
            try { result = work.run(); success = true; } catch (Exception ex) { result = ex.getMessage(); success = false; }
            String message = result; boolean ok = success;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                progress.dismiss(); render();
                new AlertDialog.Builder(this).setTitle(ok ? UiText.get(R.string.ui_81) : UiText.get(R.string.ui_82)).setMessage(message).setPositiveButton(UiText.get(R.string.ui_83), null).show();
            });
        }, "frida-bridge").start();
    }
    private boolean save() {
        try { store.save(state); publishPanel(); return true; }
        catch (RuntimeException ex) { error(ex.getMessage()); return false; }
    }
    private void publishPanel() {
        new FridaBridge(this).publishAsync(message -> runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) error(message);
        }));
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
        service.beginCapture(new ButtonAccessibilityService.Capture() {
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
        new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_108)).setItems(new String[]{UiText.get(R.string.ui_109), UiText.get(R.string.ui_110), UiText.get(R.string.ui_61), UiText.get(R.string.ui_111), UiText.get(R.string.ui_34)}, (d, index) -> {
            if (index == 0) selected.accept(Action.NONE);
            if (index == 1) chooseApp(selected);
            if (index == 2) selected.accept(new Action(Action.Kind.SYSTEM_SETTINGS, "", UiText.get(R.string.ui_61)));
            if (index == 3) selected.accept(new Action(Action.Kind.CONTROLS_SETTINGS, "", UiText.get(R.string.ui_111)));
            if (index == 4) selected.accept(new Action(Action.Kind.LAUNCH_APP, "org.openvoyah.controls/.VoiceActivity", UiText.get(R.string.ui_34)));
        }).show();
    }
    private void chooseApp(Consumer<Action> selected) {
        new Thread(() -> {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> apps = new ArrayList<>(getPackageManager().queryIntentActivities(query, 0));
            apps.removeIf(r -> r.activityInfo == null || !r.activityInfo.exported || !r.activityInfo.enabled);
            apps.sort(Comparator.comparing(r -> r.loadLabel(getPackageManager()).toString(), String.CASE_INSENSITIVE_ORDER));
            List<Action> choices = new ArrayList<>();
            for (ResolveInfo r : apps) choices.add(new Action(Action.Kind.LAUNCH_APP, new ComponentName(r.activityInfo.packageName, r.activityInfo.name).flattenToString(), r.loadLabel(getPackageManager()).toString()));
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
                    output.write(store.exportJson().getBytes(StandardCharsets.UTF_8));
                } Toast.makeText(this, UiText.get(R.string.ui_124), Toast.LENGTH_SHORT).show();
            } else if (request == IMPORT) {
                String raw;
                try (InputStream input = getContentResolver().openInputStream(data.getData())) {
                    if (input == null) throw new java.io.IOException(UiText.get(R.string.ui_123));
                    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) { if (output.size() + count > 1_048_576) throw new java.io.IOException(UiText.get(R.string.ui_125)); output.write(buffer, 0, count); }
                    raw = output.toString(StandardCharsets.UTF_8.name());
                }
                SettingsStore.State imported = SettingsStore.decode(raw);
                new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_126)).setMessage(UiText.get(R.string.ui_127) + imported.bindings.size() + UiText.get(R.string.ui_128))
                    .setNegativeButton(UiText.get(R.string.ui_89), null).setPositiveButton(UiText.get(R.string.ui_129), (d, w) -> { try { store.save(imported); publishPanel(); render(); } catch (RuntimeException ex) { error(ex.getMessage()); } }).show();
            }
        } catch (Exception ex) { error(UiText.get(R.string.ui_130) + ex.getMessage()); }
    }
}
