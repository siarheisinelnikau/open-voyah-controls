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
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openvoyah.controls.R;
import org.openvoyah.controls.UiText;
import org.openvoyah.controls.data.apps.AppGridPreferences;
import org.openvoyah.controls.data.apps.AppIconPreferences;
import org.openvoyah.controls.data.apps.DesktopInfoPreferences;
import org.openvoyah.controls.data.apps.DesktopLayoutRevision;
import org.openvoyah.controls.data.apps.DesktopLayoutStores;
import org.openvoyah.controls.data.apps.DesktopLayoutsPreferences;
import org.openvoyah.controls.data.apps.DesktopWallpaperPreferences;
import org.openvoyah.controls.data.climate.LocalSeasonalClimatePreferences;
import org.openvoyah.controls.data.display.AppPairPreferences;
import org.openvoyah.controls.data.display.AppWindowPreferences;
import org.openvoyah.controls.data.display.DisplayPreferences;
import org.openvoyah.controls.data.display.DisplayPreviewPreferences;
import org.openvoyah.controls.data.dock.QuickActionPreferences;
import org.openvoyah.controls.data.routines.RoutinePreferences;
import org.openvoyah.controls.data.rules.ConditionalRulesPreferences;
import org.openvoyah.controls.data.updates.UpdatePreferences;
import org.openvoyah.controls.data.vehicle.HeadlightMonitoringPreferences;
import org.openvoyah.controls.data.vehicle.ServiceReminderPreferences;
import org.openvoyah.controls.data.vehicle.automation.LocalVehicleAutomationPreferences;
import org.openvoyah.controls.data.vehicle.comfort.AdaptiveBrightnessPreferences;
import org.openvoyah.controls.data.vehicle.comfort.PlaybackComfortPreferences;
import org.openvoyah.controls.diagnostics.ControlsLog;
import org.openvoyah.controls.domain.display.AppDisplayDefaults;

/** File format and recoverable restore for the independent local settings stores. */
public final class LocalSettingsArchive {
    public static final int MAX_BYTES = 3 * 1024 * 1024;
    private static final String FORMAT = "org.openvoyah.controls.settings";
    private static final String ROLLBACK_FILE = "settings-restore-rollback.json";
    private static final Object ARCHIVE_LOCK = new Object();
    private static final String ROLLBACK_SCOPE = "scope";
    private static final String DESKTOP_LAYOUT_SCOPE = "desktopLayout";
    private static final String[] DESKTOP_LAYOUT_STORES = {
        AppGridPreferences.STORE, DesktopWallpaperPreferences.STORE, DesktopInfoPreferences.STORE
    };
    private static final String[] REQUIRED_STORES = {"controls", "voice", "localization", "ui", "display"};
    private static final String[] OPTIONAL_STORES = {
        "app_windows", "updates", AppGridPreferences.STORE, AppearancePreferences.STORE,
        DisplayPreviewPreferences.STORE, DesktopWallpaperPreferences.STORE, DesktopInfoPreferences.STORE, ServiceReminderPreferences.STORE,
        QuickActionPreferences.STORE, RoutinePreferences.STORE, SeatMemoryPreferences.STORE, DesktopLayoutsPreferences.STORE,
        HeadlightMonitoringPreferences.STORE, LocalVehicleAutomationPreferences.STORE,
        LocalSeasonalClimatePreferences.STORE, ConditionalRulesPreferences.STORE, AppIconPreferences.STORE,
        ActionPermissions.STORE, AppPairPreferences.STORE, PlaybackComfortPreferences.STORE, AdaptiveBrightnessPreferences.STORE
    };
    private final Context context;
    private final SettingsStore buttons;
    private final VoiceSettings voice;
    private final LocalizationSettings localization;
    private final DisplayPreferences display;
    private final AppWindowPreferences windows;
    private final AppPairPreferences pairs;
    private final UpdatePreferences updates;
    private final AppGridPreferences grid;
    private final DesktopWallpaperPreferences wallpapers;
    private final DesktopInfoPreferences desktopInfoCards;
    private final DesktopLayoutsPreferences desktopLayouts;
    private final AppearancePreferences appearance;
    private final DisplayPreviewPreferences preview;
    private final ServiceReminderPreferences serviceReminder;
    private final QuickActionPreferences quickActions;
    private final RoutinePreferences routines;
    private final SeatMemoryPreferences seats;
    private final HeadlightMonitoringPreferences headlights;
    private final LocalVehicleAutomationPreferences vehicleAutomation;
    private final PlaybackComfortPreferences playbackComfort;
    private final AdaptiveBrightnessPreferences adaptiveBrightness;
    private final LocalSeasonalClimatePreferences seasonalClimate;
    private final ConditionalRulesPreferences conditionalRules;
    private final AppIconPreferences appIcons;
    private final ActionPermissions actionPermissions;
    private final AtomicFile rollback;

    public LocalSettingsArchive(Context context) {
        this.context = context.getApplicationContext();
        buttons = new SettingsStore(context);
        voice = new VoiceSettings(context);
        localization = new LocalizationSettings(context);
        display = new DisplayPreferences(context);
        windows = new AppWindowPreferences(context);
        pairs = new AppPairPreferences(context);
        updates = new UpdatePreferences(context);
        grid = new AppGridPreferences(context);
        wallpapers = new DesktopWallpaperPreferences(context);
        desktopInfoCards = new DesktopInfoPreferences(context);
        desktopLayouts = new DesktopLayoutsPreferences(context);
        appearance = new AppearancePreferences(context);
        preview = new DisplayPreviewPreferences(context);
        serviceReminder = new ServiceReminderPreferences(context);
        quickActions = new QuickActionPreferences(context);
        routines = new RoutinePreferences(context);
        seats = new SeatMemoryPreferences(context);
        headlights = new HeadlightMonitoringPreferences(context);
        vehicleAutomation = new LocalVehicleAutomationPreferences(context);
        playbackComfort = new PlaybackComfortPreferences(context);
        adaptiveBrightness = new AdaptiveBrightnessPreferences(context);
        seasonalClimate = new LocalSeasonalClimatePreferences(context);
        conditionalRules = new ConditionalRulesPreferences(context);
        appIcons = new AppIconPreferences(context);
        actionPermissions = new ActionPermissions(context);
        rollback = new AtomicFile(new File(context.getFilesDir(), ROLLBACK_FILE));
    }

    /**
     * Whether an interrupted restore may have left a rollback record. AtomicFile keeps it under the base name, its
     * ".bak" backup or, on newer releases, a ".new" file; checking all three costs no preference store loads.
     */
    public static boolean hasPendingRollback(Context context) {
        File base = new File(context.getFilesDir(), ROLLBACK_FILE);
        return base.exists() || new File(base.getPath() + ".bak").exists() || new File(base.getPath() + ".new").exists();
    }

    public String exportJson() throws Exception {
        synchronized (ARCHIVE_LOCK) {
            JSONObject desktop = desktopLayoutSnapshot();
            return new JSONObject().put("format", FORMAT).put("schema", 1)
                .put("buttons", new JSONObject(buttons.exportJson()))
                .put("voice", new JSONObject(voice.exportJson()))
                .put("localization", new JSONObject(localization.exportJson()))
                .put("language", UiText.language())
                // clusterMedia belongs to a retired switch; versions before its removal refuse a display section without it.
                .put("display", new JSONObject().put("density", display.density()).put("clusterMedia", false))
                .put("appWindows", windows.exportProfiles())
                .put("appPairs", pairs.exportSettings())
                .put("appDisplayDpi", windows.exportDefaults())
                .put("updates", new JSONObject().put("checkOnStartup", updates.checkOnStartup()))
                .put("appGrid", desktop.getJSONObject("appGrid"))
                .put("desktopWallpapers", desktop.getJSONObject("desktopWallpapers"))
                .put("desktopInfoCards", desktop.getJSONObject("desktopInfoCards"))
                .put("desktopLayouts", desktopLayouts.exportSettings())
                .put("appearance", appearance.exportSettings())
                .put("displayPreview", preview.exportSettings())
                .put("serviceReminder", serviceReminder.exportSettings())
                .put("quickActions", quickActions.exportSettings())
                .put("routines", routines.exportSettings())
                .put("seatMemory", seats.exportSettings())
                .put("headlightMonitoring", headlights.exportSettings())
                .put("vehicleAutomation", vehicleAutomation.exportSettings())
                .put("playbackComfort", playbackComfort.exportSettings())
                .put("adaptiveControlsBrightness", adaptiveBrightness.exportSettings())
                .put("seasonalClimate", seasonalClimate.exportSettings())
                .put("conditionalRules", conditionalRules.exportSettings())
                .put("appIcons", appIcons.exportSettings())
                .put("actionPermissions", actionPermissions.exportSettings())
                .toString(2);
        }
    }

    private record OptionalPreferences(JSONArray windowProfiles, AppDisplayDefaults appDisplayDefaults, JSONArray appPairs,
            Boolean checkOnStartup, JSONObject appGrid, JSONObject desktopWallpapers, JSONObject desktopInfoCards,
            JSONObject desktopLayouts, JSONObject appearance, JSONObject displayPreview, JSONObject serviceReminder,
            JSONObject quickActions, JSONObject routines, JSONObject seatMemory, JSONObject headlightMonitoring,
            JSONObject vehicleAutomation, JSONObject seasonalClimate, JSONObject conditionalRules, JSONObject appIcons,
            JSONObject actionPermissions, JSONObject playbackComfort, JSONObject adaptiveControlsBrightness) { }

    public JSONObject captureDesktopLayout() throws Exception {
        synchronized (ARCHIVE_LOCK) {
            synchronized (DesktopLayoutStores.WRITE_LOCK) {
                restoreRollback();
                return desktopLayoutSnapshot();
            }
        }
    }

    private JSONObject desktopLayoutSnapshot() throws Exception {
        synchronized (DesktopLayoutStores.WRITE_LOCK) {
            return new JSONObject().put("appGrid", grid.exportSettings())
                .put("desktopWallpapers", wallpapers.exportSettings())
                .put("desktopInfoCards", desktopInfoCards.exportSettings());
        }
    }

    public void applyDesktopLayout(JSONObject appGrid, JSONObject desktopWallpapers, JSONObject desktopCards) throws Exception {
        JSONObject validGrid = AppGridPreferences.validateLayout(appGrid);
        JSONObject validWallpapers = DesktopWallpaperPreferences.validateLayout(desktopWallpapers);
        JSONObject validCards = DesktopInfoPreferences.validateLayout(desktopCards);
        synchronized (ARCHIVE_LOCK) {
            synchronized (DesktopLayoutStores.WRITE_LOCK) {
                restoreRollback();
                writeDesktopLayout(validGrid, validWallpapers, validCards, () -> true);
            }
        }
    }

    @FunctionalInterface
    public interface LayoutDispatch {
        /** Run the write once on the calling thread when eligible. Return false to skip it. */
        boolean tryApply(RestoreAction write) throws Exception;
    }

    public void applySavedDesktopLayout(String id) throws Exception {
        applySavedDesktopLayout(id, null, () -> true, write -> {
            write.run();
            return true;
        });
    }

    /** Calls the final commit guard once after all three stores have been written and the rollback is still available. */
    public boolean applySavedDesktopLayout(DesktopLayoutRevision expected, BooleanSupplier commitAllowed,
            LayoutDispatch dispatch) throws Exception {
        return applySavedDesktopLayout(expected.getId(), expected, commitAllowed, dispatch);
    }

    private boolean applySavedDesktopLayout(String id, DesktopLayoutRevision expected, BooleanSupplier commitAllowed,
            LayoutDispatch dispatch) throws Exception {
        synchronized (ARCHIVE_LOCK) {
            synchronized (DesktopLayoutStores.WRITE_LOCK) {
                restoreRollback();
                return desktopLayouts.withLayout(id, expected, (validGrid, images, validCards) -> {
                    JSONObject validWallpapers = DesktopWallpaperPreferences.validateLayout(images);
                    return dispatch.tryApply(() -> writeDesktopLayout(validGrid, validWallpapers, validCards, commitAllowed));
                });
            }
        }
    }

    private void writeDesktopLayout(JSONObject validGrid, JSONObject validWallpapers, JSONObject validCards,
            BooleanSupplier commitAllowed) throws Exception {
        JSONObject previous = new JSONObject().put(ROLLBACK_SCOPE, DESKTOP_LAYOUT_SCOPE);
        for (String name : DESKTOP_LAYOUT_STORES) snapshot(previous, name);
        applyWithRollback(previous, () -> {
            grid.restoreSettings(validGrid);
            wallpapers.restoreSettings(validWallpapers);
            desktopInfoCards.restoreSettings(validCards);
            if (!commitAllowed.getAsBoolean()) throw new CancellationException("Desktop layout selection cancelled");
        });
    }

    public static final class Restore {
        public final int bindingCount;
        public final boolean complete;
        private final String buttons, voice, localization, language;
        private final int density;
        private final OptionalPreferences optional;

        private Restore(String buttons, String voice, String localization, String language,
                int density, OptionalPreferences optional, int bindingCount, boolean complete) {
            this.buttons = buttons;
            this.voice = voice;
            this.localization = localization;
            this.language = language;
            this.density = density;
            this.optional = optional;
            this.bindingCount = bindingCount;
            this.complete = complete;
        }
    }

    public Restore prepare(String raw) throws Exception {
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IOException(UiText.get(R.string.settings_archive_too_large));
        try {
            return parse(raw);
        } catch (JSONException ex) {
            // org.json messages quote the input ("at character N of ..."), which can hold contacts and phone numbers.
            ControlsLog.warn("settings_import", ex, "store", "archive", "outcome", "invalid_json");
            throw new IOException(UiText.get(R.string.settings_archive_invalid), ex);
        }
    }

    private Restore parse(String raw) throws Exception {
        JSONObject root = new JSONObject(raw);
        if (!root.has("format")) {
            SettingsStore.State state = SettingsStore.decode(raw);
            return new Restore(SettingsStore.encode(state), null, null, null, 0, null,
                state.bindings.size(), false);
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
        // Archives from before the retired cluster track switch carry its value; it is accepted and ignored.
        Object clusterMedia = displayData.opt("clusterMedia");
        if (!UiText.supportsLanguage(language) || !(density instanceof Integer)
                || !DisplayPreferences.validDensity((int) density) || (clusterMedia != null && !(clusterMedia instanceof Boolean)))
            throw new IOException(UiText.get(R.string.settings_archive_invalid));
        JSONArray windowProfiles = root.has("appWindows")
            ? AppWindowPreferences.validateProfiles(root.getJSONArray("appWindows")) : null;
        AppDisplayDefaults appDisplayDefaults = root.has("appDisplayDpi")
            ? AppWindowPreferences.validateDefaults(root.getJSONObject("appDisplayDpi")) : null;
        Boolean checkOnStartup = null;
        if (root.has("updates")) {
            Object option = root.getJSONObject("updates").get("checkOnStartup");
            if (!(option instanceof Boolean enabled)) throw new IOException(UiText.get(R.string.settings_archive_invalid));
            checkOnStartup = enabled;
        }
        JSONObject appGrid = root.has("appGrid")
            ? AppGridPreferences.validateSettings(root.getJSONObject("appGrid")) : null;
        JSONObject desktopWallpapers = root.has("desktopWallpapers")
            ? DesktopWallpaperPreferences.validateSettings(root.getJSONObject("desktopWallpapers")) : null;
        JSONObject desktopInfoCards = root.has("desktopInfoCards")
            ? DesktopInfoPreferences.validateSettings(root.getJSONObject("desktopInfoCards")) : null;
        JSONObject desktopLayouts = root.has("desktopLayouts")
            ? DesktopLayoutsPreferences.validateSettings(root.getJSONObject("desktopLayouts")) : null;
        JSONObject appearance = root.has("appearance")
            ? AppearancePreferences.validateSettings(root.getJSONObject("appearance")) : null;
        JSONObject displayPreview = root.has("displayPreview")
            ? DisplayPreviewPreferences.validateSettings(root.getJSONObject("displayPreview")) : null;
        JSONObject serviceReminder = root.has("serviceReminder")
            ? ServiceReminderPreferences.validateSettings(root.getJSONObject("serviceReminder")) : null;
        JSONObject quickActions = root.has("quickActions")
            ? QuickActionPreferences.validateSettings(root.getJSONObject("quickActions")) : null;
        JSONObject routines = root.has("routines")
            ? RoutinePreferences.validateSettings(root.getJSONObject("routines")) : null;
        JSONObject seatMemory = root.has("seatMemory")
            ? SeatMemoryPreferences.validateSettings(root.getJSONObject("seatMemory")) : null;
        JSONObject headlightMonitoring = root.has("headlightMonitoring")
            ? HeadlightMonitoringPreferences.validateSettings(root.getJSONObject("headlightMonitoring")) : null;
        JSONObject vehicleAutomation = root.has("vehicleAutomation")
            ? LocalVehicleAutomationPreferences.validateSettings(root.getJSONObject("vehicleAutomation")) : null;
        JSONObject seasonalClimate = root.has("seasonalClimate")
            ? LocalSeasonalClimatePreferences.validateSettings(root.getJSONObject("seasonalClimate")) : null;
        JSONObject conditionalRules = root.has("conditionalRules")
            ? ConditionalRulesPreferences.validateSettings(root.getJSONObject("conditionalRules")) : null;
        JSONObject appIcons = root.has("appIcons")
            ? AppIconPreferences.validateSettings(root.getJSONObject("appIcons")) : null;
        JSONObject actionPermissions = root.has("actionPermissions")
            ? ActionPermissions.validateSettings(root.getJSONObject("actionPermissions")) : null;
        JSONArray appPairs = root.has("appPairs") ? AppPairPreferences.validateSettings(root.getJSONArray("appPairs")) : null;
        JSONObject playbackComfort = root.has("playbackComfort")
            ? PlaybackComfortPreferences.validateSettings(root.getJSONObject("playbackComfort")) : null;
        JSONObject adaptiveControlsBrightness = root.has("adaptiveControlsBrightness")
            ? AdaptiveBrightnessPreferences.validateSettings(root.getJSONObject("adaptiveControlsBrightness")) : null;
        var optional = new OptionalPreferences(windowProfiles, appDisplayDefaults, appPairs, checkOnStartup,
            appGrid, desktopWallpapers, desktopInfoCards, desktopLayouts, appearance, displayPreview, serviceReminder,
            quickActions, routines, seatMemory, headlightMonitoring, vehicleAutomation, seasonalClimate, conditionalRules,
            appIcons, actionPermissions, playbackComfort, adaptiveControlsBrightness);
        return new Restore(SettingsStore.encode(state), voiceJson, localizationJson, language,
            (int) density, optional, state.bindings.size(), true);
    }

    public void apply(Restore restore) throws Exception {
        synchronized (ARCHIVE_LOCK) {
            synchronized (DesktopLayoutStores.WRITE_LOCK) { applyArchive(restore); }
        }
    }

    private void applyArchive(Restore restore) throws Exception {
        restoreRollback();
        if (!restore.complete) {
            buttons.importJson(restore.buttons);
            return;
        }
        var optional = restore.optional;
        JSONObject previous = new JSONObject();
        for (String name : REQUIRED_STORES) snapshot(previous, name);
        if (optional.windowProfiles() != null || optional.appDisplayDefaults() != null)
            snapshot(previous, "app_windows");
        if (optional.appPairs() != null) snapshot(previous, AppPairPreferences.STORE);
        if (optional.checkOnStartup() != null) snapshot(previous, "updates");
        if (optional.appGrid() != null) snapshot(previous, AppGridPreferences.STORE);
        if (optional.desktopWallpapers() != null) snapshot(previous, DesktopWallpaperPreferences.STORE);
        if (optional.desktopInfoCards() != null) snapshot(previous, DesktopInfoPreferences.STORE);
        if (optional.desktopLayouts() != null) snapshot(previous, DesktopLayoutsPreferences.STORE);
        if (optional.appearance() != null) snapshot(previous, AppearancePreferences.STORE);
        if (optional.displayPreview() != null) snapshot(previous, DisplayPreviewPreferences.STORE);
        if (optional.serviceReminder() != null) snapshot(previous, ServiceReminderPreferences.STORE);
        if (optional.quickActions() != null) snapshot(previous, QuickActionPreferences.STORE);
        if (optional.routines() != null) snapshot(previous, RoutinePreferences.STORE);
        if (optional.seatMemory() != null) snapshot(previous, SeatMemoryPreferences.STORE);
        if (optional.headlightMonitoring() != null) snapshot(previous, HeadlightMonitoringPreferences.STORE);
        if (optional.vehicleAutomation() != null) snapshot(previous, LocalVehicleAutomationPreferences.STORE);
        if (optional.playbackComfort() != null) snapshot(previous, PlaybackComfortPreferences.STORE);
        if (optional.adaptiveControlsBrightness() != null) snapshot(previous, AdaptiveBrightnessPreferences.STORE);
        if (optional.seasonalClimate() != null) snapshot(previous, LocalSeasonalClimatePreferences.STORE);
        if (optional.conditionalRules() != null) snapshot(previous, ConditionalRulesPreferences.STORE);
        if (optional.appIcons() != null) snapshot(previous, AppIconPreferences.STORE);
        if (optional.actionPermissions() != null) snapshot(previous, ActionPermissions.STORE);
        applyWithRollback(previous, () -> {
            buttons.importJson(restore.buttons);
            voice.importJson(restore.voice);
            localization.importJson(restore.localization);
            UiText.language(restore.language);
            display.restore(restore.density);
            if (optional.windowProfiles() != null) windows.restoreProfiles(optional.windowProfiles());
            if (optional.appDisplayDefaults() != null) windows.saveDefaults(optional.appDisplayDefaults());
            if (optional.appPairs() != null) pairs.restoreSettings(optional.appPairs());
            if (optional.checkOnStartup() != null) updates.checkOnStartup(optional.checkOnStartup());
            if (optional.appGrid() != null) grid.restoreSettings(optional.appGrid());
            if (optional.desktopWallpapers() != null) wallpapers.restoreSettings(optional.desktopWallpapers());
            if (optional.desktopInfoCards() != null) desktopInfoCards.restoreSettings(optional.desktopInfoCards());
            if (optional.desktopLayouts() != null) desktopLayouts.restoreSettings(optional.desktopLayouts());
            if (optional.appearance() != null) appearance.restoreSettings(optional.appearance());
            if (optional.displayPreview() != null) preview.restoreSettings(optional.displayPreview());
            if (optional.serviceReminder() != null) serviceReminder.restoreSettings(optional.serviceReminder());
            if (optional.quickActions() != null) quickActions.restoreSettings(optional.quickActions());
            if (optional.routines() != null) routines.restoreSettings(optional.routines());
            if (optional.headlightMonitoring() != null) headlights.restoreSettings(optional.headlightMonitoring());
            if (optional.seatMemory() != null) seats.restoreSettings(optional.seatMemory());
            if (optional.vehicleAutomation() != null) vehicleAutomation.restoreSettings(optional.vehicleAutomation());
            if (optional.playbackComfort() != null) playbackComfort.restoreSettings(optional.playbackComfort());
            if (optional.adaptiveControlsBrightness() != null) adaptiveBrightness.restoreSettings(optional.adaptiveControlsBrightness());
            if (optional.seasonalClimate() != null) seasonalClimate.restoreSettings(optional.seasonalClimate());
            if (optional.conditionalRules() != null) conditionalRules.restoreSettings(optional.conditionalRules());
            if (optional.appIcons() != null) appIcons.restoreSettings(optional.appIcons());
            if (optional.actionPermissions() != null) actionPermissions.restoreSettings(optional.actionPermissions());
        });
    }

    @FunctionalInterface
    public interface RestoreAction { void run() throws Exception; }

    private void applyWithRollback(JSONObject previous, RestoreAction write) throws Exception {
        writeRollback(previous);
        try {
            write.run();
            rollback.delete();
        } catch (Exception ex) {
            try { restoreRollback(); }
            catch (Exception recovery) { ex.addSuppressed(recovery); }
            throw ex;
        }
    }

    public void recoverInterruptedRestore() throws Exception {
        synchronized (ARCHIVE_LOCK) {
            synchronized (DesktopLayoutStores.WRITE_LOCK) { restoreRollback(); }
        }
    }

    private void restoreRollback() throws Exception {
        byte[] saved;
        try { saved = rollback.readFully(); }
        catch (FileNotFoundException ex) { return; }
        JSONObject previous = new JSONObject(new String(saved, StandardCharsets.UTF_8));
        if (previous.has(ROLLBACK_SCOPE)) {
            if (!DESKTOP_LAYOUT_SCOPE.equals(previous.get(ROLLBACK_SCOPE)) || previous.length() != DESKTOP_LAYOUT_STORES.length + 1)
                throw new IOException(UiText.get(R.string.settings_archive_invalid));
            JSONObject[] stores = new JSONObject[DESKTOP_LAYOUT_STORES.length];
            for (int i = 0; i < stores.length; i++) stores[i] = previous.getJSONObject(DESKTOP_LAYOUT_STORES[i]);
            for (int i = 0; i < stores.length; i++) recoverStore(DESKTOP_LAYOUT_STORES[i], stores[i]);
        } else {
            for (String name : REQUIRED_STORES) recoverStore(name, previous.getJSONObject(name));
            for (String name : OPTIONAL_STORES) if (previous.has(name)) recoverStore(name, previous.getJSONObject(name));
        }
        rollback.delete();
    }

    private void recoverStore(String name, JSONObject values) throws Exception {
        SharedPreferences preferences = preferences(name);
        SharedPreferences.Editor editor = preferences.edit();
        // Removing absent keys individually also notifies active preference listeners on Android 9.
        for (String key : preferences.getAll().keySet()) if (!values.has(key)) editor.remove(key);
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

    private SharedPreferences preferences(String name) {
        return context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    private void snapshot(JSONObject target, String name) throws Exception {
        target.put(name, new JSONObject(preferences(name).getAll()));
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
