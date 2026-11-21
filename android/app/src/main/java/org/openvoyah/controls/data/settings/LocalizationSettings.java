package org.openvoyah.controls.data.settings;

import android.content.Context;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openvoyah.controls.R;
import org.openvoyah.controls.UiText;
import org.openvoyah.controls.diagnostics.ControlsLog;

/** Small, validated dictionaries are published through the existing root transport. */
public final class LocalizationSettings {
    public static final Set<String> PACKAGES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "com.qinggan.app.launcher", "com.qinggan.app.setting", "com.qinggan.app.vehicle", "com.qinggan.bluetoothphone")));
    private static volatile boolean unreadableReported;
    private final android.content.SharedPreferences prefs;
    private final Context context;
    public LocalizationSettings(Context context) { this.context = context; prefs = context.getSharedPreferences("localization", 0); }
    public String exportJson() { return prefs.getString("dictionary", "{\"schema\":1,\"enabled\":false,\"products\":[],\"packages\":{}}"); }
    public boolean enabled() {
        try {
            boolean enabled = new JSONObject(exportJson()).getBoolean("enabled");
            unreadableReported = false;
            return enabled;
        } catch (Exception ex) {
            // Screens ask on every refresh; record the damaged store once until it is readable again.
            if (!unreadableReported) {
                unreadableReported = true;
                ControlsLog.warn("store_decode", ex, "store", "localization", "outcome", "disabled_by_default");
            }
            return false;
        }
    }
    public void preset(String language) throws Exception {
        if (!"ru".equals(language) && !"en".equals(language)) throw new IllegalArgumentException("Invalid translation language");
        try (var stream = context.getAssets().open("localization/" + language + ".json")) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
            importJson(output.toString("UTF-8"));
        }
    }
    public void disable() throws Exception { importJson(new JSONObject(exportJson()).put("enabled", false).toString()); }
    public void restore() throws Exception {
        String previous = prefs.getString("previous", null);
        if (previous == null) throw new IOException("No previous dictionary");
        importJson(previous);
    }
    public static JSONObject validate(String raw) throws Exception {
        if (raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 48000) throw new IOException("Dictionary exceeds 48 KB");
        try { return validateDictionary(raw); }
        catch (JSONException ex) {
            // org.json syntax messages quote the whole input.
            ControlsLog.warn("settings_import", ex, "store", "localization", "outcome", "invalid_json");
            throw new IOException(UiText.get(R.string.ui_153), ex);
        }
    }
    private static JSONObject validateDictionary(String raw) throws Exception {
        JSONObject root = new JSONObject(raw);
        if (root.getInt("schema") != 1 || !(root.get("enabled") instanceof Boolean)) throw new IOException("Invalid dictionary schema");
        JSONArray products = root.getJSONArray("products");
        if (products.length() > 20) throw new IOException("Too many products");
        for (int i = 0; i < products.length(); i++) if (!products.getString(i).matches("[A-Za-z0-9_.-]{1,80}")) throw new IOException("Invalid product");
        JSONObject packages = root.getJSONObject("packages"); int count = 0;
        for (var names = packages.keys(); names.hasNext();) {
            String name = names.next();
            if (!PACKAGES.contains(name)) throw new IOException("Unsupported localization package: " + name);
            JSONObject table = packages.getJSONObject(name);
            for (var keys = table.keys(); keys.hasNext();) {
                String key = keys.next(); Object value = table.get(key);
                if (++count > 1000 || key.isEmpty() || key.length() > 512 || !(value instanceof String) || ((String)value).length() > 512)
                    throw new IOException("Invalid translation entry");
            }
        }
        return root;
    }
    public void importJson(String raw) throws Exception {
        String validated = validate(raw).toString();
        if (!prefs.edit().putString("previous", exportJson()).putString("dictionary", validated).commit())
            throw new IOException("Could not save localization dictionary");
    }
}
