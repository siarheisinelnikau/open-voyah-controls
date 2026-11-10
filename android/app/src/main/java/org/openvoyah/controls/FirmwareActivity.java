package org.openvoyah.controls;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.openvoyah.controls.data.frida.FridaBridge;
import org.openvoyah.controls.data.settings.LocalizationSettings;
import org.openvoyah.controls.data.settings.SettingsStore;

import org.openvoyah.controls.presentation.theme.CockpitViews;

/** Firmware-facing operations run independently from activity lifecycle and UI rendering. */
public final class FirmwareActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LocalizationSettings settings;
    private TextView status;
    private boolean busy;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); settings = new LocalizationSettings(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = CockpitViews.page(this, R.string.firmware_settings);
        scroll.addView(page);
        setContentView(scroll);
        LinearLayout root = CockpitViews.card(page);
        add(root, R.string.cluster_title, () -> startActivity(new Intent(this,ClusterActivity.class)));
        if (FridaBridge.windowHookSupported()) {
            add(root, R.string.window_hook_title, () -> {
                boolean enabled = FridaBridge.windowHookEnabled(this);
                new AlertDialog.Builder(this).setTitle(UiText.get(R.string.window_hook_title))
                    .setMessage(UiText.get(enabled ? R.string.window_hook_disable : R.string.window_hook_enable))
                    .setPositiveButton(UiText.get(R.string.ui_187), (d, w) -> run(() -> {
                        new FridaBridge(this).setWindowHook(!enabled);
                        return UiText.get(enabled ? R.string.window_hook_off : R.string.window_hook_on);
                    })).setNegativeButton(UiText.get(R.string.ui_89), null).show();
            });
        }
        TextView info = new TextView(this); info.setText(UiText.get(R.string.firmware_info)); info.setTextSize(17); root.addView(info);
        status = new TextView(this); status.setText(android.os.Build.PRODUCT + " / " + org.openvoyah.controls.core.Firmware.identify(android.os.Build.PRODUCT)
            + "\n" + UiText.get(settings.enabled()?R.string.translation_on:R.string.translation_off)); status.setTextSize(18); root.addView(status);
        add(root, R.string.translate_ru, () -> run(() -> { settings.preset("ru"); return apply(); }));
        add(root, R.string.translate_en, () -> run(() -> { settings.preset("en"); return apply(); }));
        add(root, R.string.translation_disable, () -> run(() -> { settings.disable(); return apply(); }));
        add(root, R.string.translation_import, () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 40));
        add(root, R.string.translation_export, () -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"controls-localization.json"),41));
        add(root, R.string.translation_restore, () -> run(() -> { settings.restore(); return apply(); }));
        add(root, R.string.profile_restore, () -> new AlertDialog.Builder(this).setTitle(UiText.get(R.string.profile_restore))
            .setMessage(UiText.get(R.string.profile_restore_info)).setNegativeButton(UiText.get(R.string.ui_89),null)
            .setPositiveButton(UiText.get(R.string.ui_187),(d,w)->run(()->new FridaBridge(this).restoreProfile())).show());
    }
    private void add(LinearLayout root, int label, Runnable action) {
        Button button = new Button(this); button.setText(UiText.get(label)); button.setAllCaps(false);
        button.setOnClickListener(v -> { if (!busy) { try { action.run(); } catch(Exception ex) { status.setText(ex.getMessage()); } } }); CockpitViews.addButton(root, button);
    }
    private String apply() throws Exception {
        if (!FridaBridge.wasConfigured(this)) return UiText.get(R.string.translation_saved);
        new FridaBridge(this).connect(new SettingsStore(this).load());
        return UiText.get(R.string.translation_applied);
    }
    interface Operation { String run() throws Exception; }
    private void run(Operation operation) {
        if (busy) return; busy = true; status.setText(UiText.get(R.string.ui_80));
        worker.execute(() -> {
            String result; try { result = operation.run(); } catch(Exception ex) { result = UiText.get(R.string.ui_205) + ex.getMessage(); }
            String message = result;
            runOnUiThread(() -> { busy = false; if (!isDestroyed() && !isFinishing()) status.setText(message); });
        });
    }
    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data);
        if (result!=RESULT_OK || data==null || data.getData()==null) return;
        run(() -> {
            if (request==41) {
                try (var out=getContentResolver().openOutputStream(data.getData(),"wt")) {
                    if(out==null) throw new java.io.IOException("File unavailable");
                    out.write(settings.exportJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                return UiText.get(R.string.ui_124);
            }
            try (var input=getContentResolver().openInputStream(data.getData()); var output=new java.io.ByteArrayOutputStream()) {
                if(input==null) throw new java.io.IOException("File unavailable");
                byte[] buffer=new byte[4096]; int n;
                while((n=input.read(buffer))!=-1) { if(output.size()+n>48000) throw new java.io.IOException("Dictionary exceeds 48 KB"); output.write(buffer,0,n); }
                settings.importJson(output.toString("UTF-8"));
            }
            return apply();
        });
    }
    @Override public void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
