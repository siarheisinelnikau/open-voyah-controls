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

/** A built-in preset name plus small, validated custom entries, published through the existing root transport. */
public final class LocalizationSettings {
    public static final Set<String> PACKAGES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "com.qinggan.app.launcher", "com.qinggan.app.setting", "com.qinggan.app.vehicle", "com.qinggan.bluetoothphone",
        "com.qinggan.app.hiboard")));
    /** Built-in dictionaries bundled with the localization agent (android/open-voyah-controls/hooks/localization). */
    public static final Set<String> PRESETS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("ru", "en")));
    private static volatile boolean unreadableReported;
    private final android.content.SharedPreferences prefs;
    public LocalizationSettings(Context context) { prefs = context.getSharedPreferences("localization", 0); }
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
    /** Names a built-in dictionary; the agent carries its entries, so the published setting stays small. */
    public void preset(String language) throws Exception {
        if (!PRESETS.contains(language)) throw new IllegalArgumentException("Invalid translation language");
        importJson(new JSONObject().put("schema", 1).put("enabled", true).put("preset", language)
            .put("products", new JSONArray()).put("packages", new JSONObject()).toString());
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
        if (root.has("preset") && !PRESETS.contains(root.opt("preset"))) throw new IOException("Unsupported translation preset");
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
