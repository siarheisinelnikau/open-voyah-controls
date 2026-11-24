package org.openvoyah.controls.data.settings;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openvoyah.controls.R;
import org.openvoyah.controls.UiText;
import org.openvoyah.controls.core.Action;
import org.openvoyah.controls.core.Binding;
import org.openvoyah.controls.core.PanelSlots;

public final class SettingsStore {
    private final SharedPreferences preferences;
    public static final int SCHEMA = 1;
    /** The steering-wheel voice key opens Controls voice input until another or the factory action is chosen. */
    public static final String DEFAULT_STEERING_SLOT = "voiceShort";
    /** Absence selects an app default for these slots, so an explicit factory choice must be saved. */
    public static boolean hasDefault(String slot) { return "mainApps".equals(slot) || DEFAULT_STEERING_SLOT.equals(slot); }
    /** True only for the untouched steering default, which does not depend on a qualified steering hook. */
    public static boolean isDefaultSteering(String slot, Action action) {
        return DEFAULT_STEERING_SLOT.equals(slot) && action.kind == Action.Kind.START_VOICE;
    }
    /** Yandex Navigator. Its launcher icon is a yellow arrow on a white plate. */
    public static final String YANDEX_NAVIGATOR = "ru.yandex.yandexnavi";
    /**
     * At 100 % the widget's map labels are a few pixels tall on the 160 dpi head unit display (seen on the car), so the
     * card starts larger.
     */
    public static final int NAVIGATOR_WIDGET_SCALE = 150;
    /**
     * The smallest scale. Below it the widget is laid out so large that Android refuses Navigator's picture (a widget
     * update may carry bitmaps of at most 6 x the display's pixels in bytes; at 80 % the traffic widget needed 8.9 of
     * 8.3 MB on the car) and the card keeps an old picture.
     */
    public static final int NAVIGATOR_WIDGET_SCALE_MIN = 90;
    /** Percent, 90 to 200; a smaller one saved earlier (from 50) becomes 90, anything else the default. */
    /**
     * Voyah Music and Voyah Radio, offered in the factory home screen's media card when installed: package and
     * media browser service, in tab order.
     */
    public static final String[][] MEDIA_CARD_APPS = {
        {"app.voyahmusic.player", "app.voyahmusic.player.playback.MusicBrowserService"},
        {"app.voyahradio", "app.voyahradio.playback.RadioBrowserService"}
    };
    public static int navigatorWidgetScale(int value) {
        if (value >= 50 && value < NAVIGATOR_WIDGET_SCALE_MIN) return NAVIGATOR_WIDGET_SCALE_MIN;
        return value >= NAVIGATOR_WIDGET_SCALE_MIN && value <= 200 ? value : NAVIGATOR_WIDGET_SCALE;
    }
    public static final class State {
        public boolean enabled = true;
        public int holdMs = 650;
        public int debounceMs = 100;
        public final List<Binding> bindings = new ArrayList<>();
        public boolean panelEnabled = false;
        /** Show Yandex Navigator instead of the Baidu map in the factory home screen's navigation card. Off unless turned on. */
        public boolean navigatorHomeCard = false;
        /** Scale of the Navigator widget in that card in percent; more is larger content. */
        public int navigatorWidgetScale = NAVIGATOR_WIDGET_SCALE;
        /** Feed the factory home screen's weather card with Open-Meteo weather. Off unless turned on. */
        public boolean weatherCard = false;
        /** Offer Voyah Music and Voyah Radio (MEDIA_CARD_APPS) in the factory home screen's media card. On unless turned off. */
        public boolean mediaCardApps = true;
        public final java.util.Map<String, Action> panelActions = new java.util.LinkedHashMap<>();
        public final java.util.Map<String, Action> steeringActions = new java.util.LinkedHashMap<>();
        public State() {
            panelActions.put("mainApps", new Action(Action.Kind.CONTROLS_APPS, "", "Controls"));
            steeringActions.put(DEFAULT_STEERING_SLOT, new Action(Action.Kind.START_VOICE, "", "Voice"));
        }
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
    public static State decode(String raw) throws JSONException {
        if (raw.length() > 1_048_576) throw new IllegalArgumentException(UiText.get(R.string.ui_154));
        JSONObject root = new JSONObject(raw);
        if (root.getInt("schema") != SCHEMA) throw new IllegalArgumentException(UiText.get(R.string.ui_155));
        State state = new State();
        state.enabled = root.getBoolean("enabled");
        state.holdMs = root.getInt("holdMs");
        state.debounceMs = root.getInt("debounceMs");
        state.panelEnabled = root.optBoolean("panelEnabled", false);
        state.navigatorHomeCard = root.optBoolean("navigatorHomeCard", false);
        // A density saved by the first version becomes the nearest 10 % (160 = 100 %). That version always saved its
        // default, 160, so 160 means "not chosen" and takes today's default.
        int legacyDpi = root.optInt("navigatorWidgetDpi", 0);
        int legacyScale = legacyDpi > 0 && legacyDpi != 160 ? Math.round(legacyDpi * 10f / 160f) * 10 : NAVIGATOR_WIDGET_SCALE;
        state.navigatorWidgetScale = navigatorWidgetScale(root.optInt("navigatorWidgetScale", legacyScale));
        state.weatherCard = root.optBoolean("weatherCard", false);
        state.mediaCardApps = root.optBoolean("mediaCardApps", true);
        JSONObject panel = root.optJSONObject("panelActions");
        if (panel != null) {
            java.util.Iterator<String> slots = panel.keys();
            while (slots.hasNext()) {
                String slot = slots.next();
                if (!PanelSlots.PANEL.contains(slot)) throw new IllegalArgumentException(UiText.get(R.string.ui_156));
                state.panelActions.put(slot, action(panel.getJSONObject(slot)));
            }
        }
        JSONObject steering = root.optJSONObject("steeringActions");
        if (steering != null) {
            java.util.Iterator<String> slots = steering.keys();
            while (slots.hasNext()) {
                String slot = slots.next();
                if (!PanelSlots.STEERING.contains(slot)) throw new IllegalArgumentException(UiText.get(R.string.ui_156));
                state.steeringActions.put(slot, action(steering.getJSONObject(slot)));
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
    public static String encode(State state) throws JSONException {
        JSONObject panel = new JSONObject();
        for (java.util.Map.Entry<String, Action> entry : state.panelActions.entrySet()) panel.put(entry.getKey(), action(entry.getValue()));
        JSONObject steering = new JSONObject();
        for (java.util.Map.Entry<String, Action> entry : state.steeringActions.entrySet()) steering.put(entry.getKey(), action(entry.getValue()));
        JSONArray rows = new JSONArray();
        for (Binding b : state.bindings) rows.put(new JSONObject().put("id", b.id).put("label", b.label).put("device", b.device)
            .put("keyCode", b.keyCode).put("enabled", b.enabled).put("short", action(b.shortPress)).put("long", action(b.longPress)));
        return new JSONObject().put("schema", SCHEMA).put("enabled", state.enabled).put("holdMs", state.holdMs)
            .put("debounceMs", state.debounceMs).put("bindings", rows).put("panelEnabled", state.panelEnabled)
            .put("navigatorHomeCard", state.navigatorHomeCard)
            .put("navigatorWidgetScale", state.navigatorWidgetScale)
            .put("weatherCard", state.weatherCard)
            .put("mediaCardApps", state.mediaCardApps)
            .put("panelActions", panel).put("steeringActions", steering).toString(2);
    }
}
