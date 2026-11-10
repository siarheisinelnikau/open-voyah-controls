package org.openvoyah.controls.data.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;
import org.openvoyah.controls.R;
import org.openvoyah.controls.UiText;
import org.openvoyah.controls.data.display.AppWindowPreferences;
import org.openvoyah.controls.data.display.DisplayPreferences;

/** File format and recoverable restore for the independent local settings stores. */
public final class LocalSettingsArchive {
    public static final int MAX_BYTES = 2_097_152;
    private static final String FORMAT = "org.openvoyah.controls.settings";
    private static final String[] STORES = {"controls", "voice", "localization", "ui", "display", "app_windows"};
    private final Context context;
    private final SettingsStore buttons;
    private final VoiceSettings voice;
    private final LocalizationSettings localization;
    private final DisplayPreferences display;
    private final AppWindowPreferences windows;
    private final AtomicFile rollback;

    public LocalSettingsArchive(Context context) {
        this.context = context.getApplicationContext();
        buttons = new SettingsStore(context);
        voice = new VoiceSettings(context);
        localization = new LocalizationSettings(context);
        display = new DisplayPreferences(context);
        windows = new AppWindowPreferences(context);
        rollback = new AtomicFile(new File(context.getFilesDir(), "settings-restore-rollback.json"));
    }

    public String exportJson() throws Exception {
        return new JSONObject().put("format", FORMAT).put("schema", 1)
            .put("buttons", new JSONObject(buttons.exportJson()))
            .put("voice", new JSONObject(voice.exportJson()))
            .put("localization", new JSONObject(localization.exportJson()))
            .put("language", UiText.language())
            .put("display", new JSONObject().put("density", display.density()).put("clusterMedia", display.clusterMedia()))
            .put("appWindows", windows.exportProfiles())
            .toString(2);
    }

    public static final class Restore {
        public final int bindingCount;
        public final boolean complete;
        private final String buttons, voice, localization, language;
        private final int density;
        private final boolean clusterMedia;
        private final JSONArray windowProfiles;

        private Restore(String buttons, String voice, String localization, String language,
                int density, boolean clusterMedia, JSONArray windowProfiles, int bindingCount, boolean complete) {
            this.buttons = buttons;
            this.voice = voice;
            this.localization = localization;
            this.language = language;
            this.density = density;
            this.clusterMedia = clusterMedia;
            this.windowProfiles = windowProfiles;
            this.bindingCount = bindingCount;
            this.complete = complete;
        }
    }

    public Restore prepare(String raw) throws Exception {
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IOException(UiText.get(R.string.ui_125));
        JSONObject root = new JSONObject(raw);
        if (!root.has("format")) {
            SettingsStore.State state = SettingsStore.decode(raw);
            return new Restore(SettingsStore.encode(state), null, null, null, 0, false, null, state.bindings.size(), false);
        }
        if (!FORMAT.equals(root.getString("format")) || root.getInt("schema") != 1)
            throw new IOException(UiText.get(R.string.settings_archive_invalid));
        SettingsStore.State state = SettingsStore.decode(root.getJSONObject("buttons").toString());
        String voiceJson = root.getJSONObject("voice").toString();
        VoiceSettings.validate(voiceJson);
        String localizationJson = LocalizationSettings.validate(root.getJSONObject("localization").toString()).toString();
        String language = root.getString("language");
        JSONObject displayData = root.getJSONObject("display");
        Object density = displayData.get("density");
        Object clusterMedia = displayData.get("clusterMedia");
        if (!("en".equals(language) || "ru".equals(language)) || !(density instanceof Integer)
                || !DisplayPreferences.validDensity((int) density) || !(clusterMedia instanceof Boolean))
            throw new IOException(UiText.get(R.string.settings_archive_invalid));
        JSONArray windowProfiles = root.has("appWindows")
            ? AppWindowPreferences.validateProfiles(root.getJSONArray("appWindows")) : null;
        return new Restore(SettingsStore.encode(state), voiceJson, localizationJson, language,
            (int) density, (boolean) clusterMedia, windowProfiles, state.bindings.size(), true);
    }

    public void apply(Restore restore) throws Exception {
        recoverInterruptedRestore();
        if (!restore.complete) {
            buttons.importJson(restore.buttons);
            return;
        }
        JSONObject previous = new JSONObject();
        for (String name : STORES) previous.put(name, new JSONObject(preferences(name).getAll()));
        writeRollback(previous);
        try {
            buttons.importJson(restore.buttons);
            voice.importJson(restore.voice);
            localization.importJson(restore.localization);
            UiText.language(restore.language);
            display.restore(restore.density, restore.clusterMedia);
            if (restore.windowProfiles != null) windows.restoreProfiles(restore.windowProfiles);
            rollback.delete();
        } catch (Exception ex) {
            try { recoverInterruptedRestore(); }
            catch (Exception recovery) { ex.addSuppressed(recovery); }
            throw ex;
        }
    }

    public void recoverInterruptedRestore() throws Exception {
        byte[] saved;
        try { saved = rollback.readFully(); }
        catch (FileNotFoundException ex) { return; }
        JSONObject previous = new JSONObject(new String(saved, StandardCharsets.UTF_8));
        for (String name : STORES) {
            if (name.equals("app_windows") && !previous.has(name)) continue;
            JSONObject values = previous.getJSONObject(name);
            SharedPreferences.Editor editor = preferences(name).edit().clear();
            for (var keys = values.keys(); keys.hasNext();) {
                String key = keys.next();
                Object value = values.get(key);
                if (value instanceof String) editor.putString(key, (String) value);
                else if (value instanceof Integer) editor.putInt(key, (Integer) value);
                else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                else throw new IOException(UiText.get(R.string.settings_archive_invalid));
            }
            if (!editor.commit()) throw new IOException(UiText.get(R.string.settings_archive_save_failed));
        }
        rollback.delete();
    }

    private SharedPreferences preferences(String name) {
        return context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    private void writeRollback(JSONObject previous) throws IOException {
        byte[] expected = previous.toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream output = rollback.startWrite();
        try {
            output.write(expected);
            output.getFD().sync();
            rollback.finishWrite(output);
        } catch (IOException | RuntimeException ex) {
            rollback.failWrite(output);
            throw ex;
        }
        if (!Arrays.equals(expected, rollback.readFully()))
            throw new IOException(UiText.get(R.string.settings_archive_save_failed));
    }
}
