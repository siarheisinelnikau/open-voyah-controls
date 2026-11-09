package org.openvoyah.controls;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openvoyah.controls.core.Action;
import org.openvoyah.controls.core.Binding;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SettingsStore {
    private final SharedPreferences preferences;
    public static final int SCHEMA = 1;
    public static final class State {
        public boolean enabled = true;
        public int holdMs = 650;
        public int debounceMs = 100;
        public final List<Binding> bindings = new ArrayList<>();
        public boolean panelEnabled = false;
        public final java.util.Map<String, Action> panelActions = new java.util.LinkedHashMap<>();
    }
    public SettingsStore(Context context) { preferences = context.getSharedPreferences("controls", Context.MODE_PRIVATE); }
    public SharedPreferences preferences() { return preferences; }
    public State load() {
        String raw = preferences.getString("state", null);
        if (raw == null) return new State();
        try { return decode(raw); }
        catch (JSONException | IllegalArgumentException ex) { throw new IllegalStateException(UiText.get(R.string.ui_151), ex); }
    }
    public String exportJson() { return preferences.getString("state", "{\"schema\":1,\"enabled\":true,\"holdMs\":650,\"debounceMs\":100,\"bindings\":[]}"); }
    public void save(State state) {
        try {
            String raw = encode(state);
            decode(raw); // Same validation for UI changes and imports.
            if (!preferences.edit().putString("state", raw).commit()) throw new IllegalStateException(UiText.get(R.string.ui_152));
        } catch (JSONException ex) { throw new IllegalArgumentException(UiText.get(R.string.ui_153), ex); }
    }
    public void importJson(String json) throws JSONException { save(decode(json)); }
    static State decode(String raw) throws JSONException {
        if (raw.length() > 1_048_576) throw new IllegalArgumentException(UiText.get(R.string.ui_154));
        JSONObject root = new JSONObject(raw);
        if (root.getInt("schema") != SCHEMA) throw new IllegalArgumentException(UiText.get(R.string.ui_155));
        State state = new State();
        state.enabled = root.getBoolean("enabled");
        state.holdMs = root.getInt("holdMs");
        state.debounceMs = root.getInt("debounceMs");
        state.panelEnabled = root.optBoolean("panelEnabled", false);
        JSONObject panel = root.optJSONObject("panelActions");
        if (panel != null) {
            java.util.Iterator<String> slots = panel.keys();
            while (slots.hasNext()) {
                String slot = slots.next();
                if (!java.util.Arrays.asList(FridaBridge.SLOTS).contains(slot)) throw new IllegalArgumentException(UiText.get(R.string.ui_156));
                state.panelActions.put(slot, action(panel.getJSONObject(slot)));
            }
        }
        if (state.holdMs < 250 || state.holdMs > 2000 || state.debounceMs < 0 || state.debounceMs > 500) throw new IllegalArgumentException(UiText.get(R.string.ui_157));
        JSONArray rows = root.getJSONArray("bindings");
        if (rows.length() > 128) throw new IllegalArgumentException(UiText.get(R.string.ui_158));
        Set<String> ids = new HashSet<>(), keys = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            Binding b = new Binding(row.getString("id"), row.getString("label"), row.getString("device"), row.getInt("keyCode"),
                action(row.getJSONObject("short")), action(row.getJSONObject("long")), row.getBoolean("enabled"));
            if (b.keyCode == 3 || b.keyCode == 26) throw new IllegalArgumentException(UiText.get(R.string.ui_159));
            if (b.label.length() > 120 || b.device.length() > 256 || b.device.indexOf('\0') >= 0) throw new IllegalArgumentException(UiText.get(R.string.ui_160));
            if (!ids.add(b.id) || !keys.add(b.device + "\u0000" + b.keyCode)) throw new IllegalArgumentException(UiText.get(R.string.ui_161));
            state.bindings.add(b);
        }
        return state;
    }
    private static Action action(JSONObject row) throws JSONException {
        Action.Kind kind = Action.Kind.valueOf(row.getString("kind"));
        String component = row.getString("component"), label = row.getString("label");
        if (component.length() > 512 || label.length() > 200) throw new IllegalArgumentException(UiText.get(R.string.ui_162));
        if (kind == Action.Kind.LAUNCH_APP && !component.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")) throw new IllegalArgumentException(UiText.get(R.string.ui_163));
        return new Action(kind, component, label);
    }
    private static JSONObject action(Action action) throws JSONException {
        return new JSONObject().put("kind", action.kind.name()).put("component", action.component).put("label", action.label);
    }
    static String encode(State state) throws JSONException {
        JSONObject panel = new JSONObject();
        for (java.util.Map.Entry<String, Action> entry : state.panelActions.entrySet()) panel.put(entry.getKey(), action(entry.getValue()));
        JSONArray rows = new JSONArray();
        for (Binding b : state.bindings) rows.put(new JSONObject().put("id", b.id).put("label", b.label).put("device", b.device)
            .put("keyCode", b.keyCode).put("enabled", b.enabled).put("short", action(b.shortPress)).put("long", action(b.longPress)));
        return new JSONObject().put("schema", SCHEMA).put("enabled", state.enabled).put("holdMs", state.holdMs)
            .put("debounceMs", state.debounceMs).put("bindings", rows).put("panelEnabled", state.panelEnabled).put("panelActions", panel).toString(2);
    }
}
