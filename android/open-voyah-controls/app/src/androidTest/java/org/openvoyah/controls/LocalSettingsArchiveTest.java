package org.openvoyah.controls;

import android.content.Context;
import android.net.Uri;
import android.test.AndroidTestCase;
import android.util.AtomicFile;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.openvoyah.controls.data.apps.AppGridPreferences;
import org.openvoyah.controls.data.display.AppWindowPreferences;
import org.openvoyah.controls.data.display.DisplayPreferences;
import org.openvoyah.controls.data.display.DisplayPreviewPreferences;
import org.openvoyah.controls.data.settings.AppearancePreferences;
import org.openvoyah.controls.data.settings.LocalSettingsArchive;
import org.openvoyah.controls.data.settings.LocalSettingsArchiveDocuments;
import org.openvoyah.controls.data.settings.LocalizationSettings;
import org.openvoyah.controls.data.settings.SeatMemoryPreferences;
import org.openvoyah.controls.data.settings.SettingsStore;
import org.openvoyah.controls.data.settings.VoiceSettings;
import org.openvoyah.controls.data.updates.UpdatePreferences;
import org.openvoyah.controls.data.vehicle.HeadlightMonitoringPreferences;
import org.openvoyah.controls.domain.display.AppDisplayDefaults;

public final class LocalSettingsArchiveTest extends AndroidTestCase {
    public void testBrokenFullExportDoesNotTruncateDocumentOrDowngradeToButtons() throws Exception {
        Context context = getContext();
        var preferences = context.getSharedPreferences("localization", Context.MODE_PRIVATE);
        String dictionary = preferences.getString("dictionary", null);
        File destination = File.createTempFile("archive-export-", ".json", context.getCacheDir());
        byte[] previous = "existing complete backup".getBytes(StandardCharsets.UTF_8);
        try {
            java.nio.file.Files.write(destination.toPath(), previous);
            assertTrue(preferences.edit().putString("dictionary", "invalid dictionary").commit());
            try {
                new LocalSettingsArchiveDocuments(context).export(Uri.fromFile(destination).toString());
                fail("A broken archive section must report export failure");
            } catch (Exception expected) { assertTrue(expected instanceof org.json.JSONException); }
            assertTrue(java.util.Arrays.equals(previous, java.nio.file.Files.readAllBytes(destination.toPath())));
        } finally {
            var editor = preferences.edit();
            if (dictionary == null) editor.remove("dictionary"); else editor.putString("dictionary", dictionary);
            assertTrue(editor.commit());
            destination.delete();
        }
    }

    public void testCombinedBackupAndLegacyButtonImport() throws Exception {
        Context context = getContext();
        var archive = new LocalSettingsArchive(context);
        var before = archive.prepare(archive.exportJson());
        var buttons = new SettingsStore(context);
        var voice = new VoiceSettings(context);
        var display = new DisplayPreferences(context);
        var appWindows = new AppWindowPreferences(context);
        var locale = new LocalizationSettings(context);
        var updates = new UpdatePreferences(context);
        var grid = new AppGridPreferences(context);
        var appearance = new AppearancePreferences(context);
        var preview = new DisplayPreviewPreferences(context);
        var seats = new SeatMemoryPreferences(context);
        var headlights = new HeadlightMonitoringPreferences(context);
        var pins = gridSettings("com.example.maps/com.example.maps.Launcher", "compact", false)
            .put("desktops", desktopSettings(true, "favorites2"))
            .put("iconStyle", "monochrome").put("showAppLabels", false).put("sortOrder", "recentlyInstalled")
            .put("favoritesBackBehavior", "mainDesktop");
        var mediaOptions = displayPreviewSettings(false, true).put("showPlaybackProgress", true)
            .put("showAlbumArtwork", true).put("showTransportControls", true)
            .put("preferredMediaPackage", "com.example.player").put("roadNames", "latin").put("showCurrentRoad", false);
        var savedSeats = seatSettings(2, "Long trips").put("openFactorySeatsOnTap", true);
        try {
            var state = buttons.load();
            state.holdMs = 900;
            buttons.save(state);
            voice.language("en");
            voice.audio(true, true);
            display.restore(240, true);
            appWindows.saveDefaults(new AppDisplayDefaults(200, 220));
            UiText.language("kk");
            locale.preset("en");
            updates.checkOnStartup(false);
            grid.restoreSettings(pins);
            appearance.restoreSettings(appearanceSettings(false, true, "vehicle"));
            preview.restoreSettings(mediaOptions);
            seats.restoreSettings(savedSeats);
            headlights.restoreSettings(new JSONObject().put("whileControlsOpen", true));
            var combined = archive.prepare(archive.exportJson());

            voice.language("ru");
            voice.audio(false, false);
            display.restore(0, false);
            appWindows.saveDefaults(new AppDisplayDefaults(0, null));
            UiText.language("en");
            locale.disable();
            updates.checkOnStartup(true);
            grid.restoreSettings(gridSettings(null, "large", true).put("desktops", desktopSettings(false, "main"))
                .put("iconStyle", "native").put("showAppLabels", true).put("sortOrder", "name")
                .put("favoritesBackBehavior", "system"));
            appearance.restoreSettings(appearanceSettings(true, false, "settings"));
            preview.restoreSettings(displayPreviewSettings(true, false).put("showPlaybackProgress", false)
                .put("showAlbumArtwork", false).put("showTransportControls", false).put("preferredMediaPackage", "")
                .put("roadNames", "original").put("showCurrentRoad", true));
            seats.restoreSettings(seatSettings(1, "").put("openFactorySeatsOnTap", false));
            headlights.restoreSettings(new JSONObject().put("whileControlsOpen", false));
            archive.apply(combined);

            assertEquals(900, buttons.load().holdMs);
            assertEquals("en", voice.language());
            assertTrue(voice.option("duckMedia"));
            assertTrue(voice.option("spokenFeedback"));
            assertEquals("kk", UiText.language());
            assertEquals(240, display.density());
            assertTrue(display.clusterMedia());
            assertEquals(new AppDisplayDefaults(200, 220), appWindows.loadDefaults());
            assertTrue(locale.enabled());
            assertFalse(updates.checkOnStartup());
            assertGridSettings(pins, grid.exportSettings());
            assertAppearanceSettings(appearance, false, true, "vehicle");
            assertPreviewFields(mediaOptions, preview);
            assertEquals(savedSeats.toString(), seats.exportSettings().toString());
            assertTrue(headlights.exportSettings().getBoolean("whileControlsOpen"));

            var oldFile = archive.prepare(SettingsStore.encode(new SettingsStore.State()));
            assertFalse(oldFile.complete);
            archive.apply(oldFile);
            assertEquals(650, buttons.load().holdMs);
            assertEquals("en", voice.language());
            assertEquals(240, display.density());
            assertEquals(new AppDisplayDefaults(200, 220), appWindows.loadDefaults());
            assertFalse(updates.checkOnStartup());
            assertGridSettings(pins, grid.exportSettings());
            assertAppearanceSettings(appearance, false, true, "vehicle");
            assertPreviewFields(mediaOptions, preview);
            assertEquals(savedSeats.toString(), seats.exportSettings().toString());
            assertTrue(headlights.exportSettings().getBoolean("whileControlsOpen"));
        } finally { archive.apply(before); }
    }

    public void testOlderFullArchiveKeepsSettingsIntroducedLater() throws Exception {
        Context context = getContext();
        var archive = new LocalSettingsArchive(context);
        var before = archive.prepare(archive.exportJson());
        var older = new JSONObject(archive.exportJson());
        older.remove("updates");
        older.remove("appGrid");
        older.remove("appearance");
        older.remove("displayPreview");
        older.remove("seatMemory");
        older.remove("headlightMonitoring");
        var updates = new UpdatePreferences(context);
        var grid = new AppGridPreferences(context);
        var appearance = new AppearancePreferences(context);
        var preview = new DisplayPreviewPreferences(context);
        var seats = new SeatMemoryPreferences(context);
        var headlights = new HeadlightMonitoringPreferences(context);
        var pins = gridSettings("com.example.player/com.example.player.Main", "large", false);
        var savedSeats = seatSettings(3, "Weekend").put("openFactorySeatsOnTap", true);
        try {
            updates.checkOnStartup(false);
            grid.restoreSettings(pins);
            appearance.restoreSettings(appearanceSettings(false, true, "settings"));
            preview.restoreSettings(displayPreviewSettings(false, false));
            seats.restoreSettings(savedSeats);
            headlights.restoreSettings(new JSONObject().put("whileControlsOpen", true));
            var restore = archive.prepare(older.toString());
            assertTrue(restore.complete);
            archive.apply(restore);
            assertFalse(updates.checkOnStartup());
            assertGridSettings(pins, grid.exportSettings());
            assertAppearanceSettings(appearance, false, true, "settings");
            assertDisplayPreviewSettings(preview, false, false);
            assertEquals(savedSeats.toString(), seats.exportSettings().toString());
            assertTrue(headlights.exportSettings().getBoolean("whileControlsOpen"));
        } finally { archive.apply(before); }
    }

    public void testEarlierPreferenceSectionsRestoreSuppliedFieldsOnly() throws Exception {
        Context context = getContext();
        var archive = new LocalSettingsArchive(context);
        var original = archive.exportJson();
        var before = archive.prepare(original);
        var grid = new AppGridPreferences(context);
        var appearance = new AppearancePreferences(context);
        var preview = new DisplayPreviewPreferences(context);
        var seats = new SeatMemoryPreferences(context);
        String legacyPin = "com.example.maps/com.example.maps.Main";
        JSONObject desktops = desktopSettings(true, "favorites1");
        var earlier = new JSONObject(original)
            .put("appGrid", gridSettings(legacyPin))
            .put("appearance", new JSONObject().put("animateRoutes", true))
            .put("displayPreview", new JSONObject().put("showMedia", true))
            .put("seatMemory", seatSettings(2, "Legacy trip"));
        try {
            grid.restoreSettings(gridSettings("com.example.other/com.example.other.Main", "compact", false)
                .put("desktops", desktops));
            appearance.restoreSettings(appearanceSettings(false, true, "vehicle"));
            preview.restoreSettings(displayPreviewSettings(false, false).put("showPlaybackProgress", true));
            seats.setOpenFactorySeatsOnTap(true);
            archive.apply(archive.prepare(earlier.toString()));
            assertGridSettings(gridSettings(legacyPin, "compact", false).put("desktops", desktops), grid.exportSettings());
            assertAppearanceSettings(appearance, true, true, "vehicle");
            assertDisplayPreviewSettings(preview, true, false);
            assertTrue(preview.exportSettings().getBoolean("showPlaybackProgress"));
            assertEquals(seatSettings(2, "Legacy trip").put("openFactorySeatsOnTap", true).toString(),
                seats.exportSettings().toString());
        } finally { archive.apply(before); }
    }

    public void testInvalidSectionLeavesAllPreferencesUntouched() throws Exception {
        var archive = new LocalSettingsArchive(getContext());
        JSONObject original = new JSONObject(archive.exportJson());
        JSONObject bad = new JSONObject(original.toString());
        bad.getJSONObject("buttons").put("holdMs", 800);
        bad.getJSONObject("voice").getJSONObject("values").put("duckMedia", "invalid");
        try {
            archive.prepare(bad.toString());
            fail("An invalid section must reject the complete backup");
        } catch (IllegalArgumentException expected) { }
        assertEquals(original.toString(), new JSONObject(archive.exportJson()).toString());
    }

    public void testMalformedStartupUpdatePreferenceRejectsTheArchive() throws Exception {
        var archive = new LocalSettingsArchive(getContext());
        var original = archive.exportJson();
        var bad = new JSONObject(original);
        bad.getJSONObject("buttons").put("holdMs", 800);
        bad.getJSONObject("updates").put("checkOnStartup", "false");
        try {
            archive.prepare(bad.toString());
            fail("A string must not be accepted as a Boolean");
        } catch (IOException expected) { }
        assertEquals(original, archive.exportJson());
    }

    public void testInterruptedRestoreRollsBackAllStores() throws Exception {
        Context context = getContext();
        var archive = new LocalSettingsArchive(context);
        var file = new AtomicFile(new File(context.getFilesDir(), "settings-restore-rollback.json"));
        JSONObject original = new JSONObject();
        for (String name : new String[]{"controls", "voice", "localization", "ui", "display"}) {
            original.put(name, new JSONObject(context.getSharedPreferences(name, Context.MODE_PRIVATE).getAll()));
        }
        var stream = file.startWrite();
        stream.write(original.toString().getBytes(StandardCharsets.UTF_8));
        file.finishWrite(stream);
        try {
            var buttons = new SettingsStore(context);
            var changed = buttons.load();
            changed.holdMs = changed.holdMs == 800 ? 900 : 800;
            buttons.save(changed);
            new DisplayPreferences(context).restore(320, true);
            archive.recoverInterruptedRestore();
            for (String name : new String[]{"controls", "voice", "localization", "ui", "display"}) {
                assertEquals(original.getJSONObject(name).toString(),
                    new JSONObject(context.getSharedPreferences(name, Context.MODE_PRIVATE).getAll()).toString());
            }
            assertFalse(file.getBaseFile().exists());
        } finally { archive.recoverInterruptedRestore(); }
    }

    private static JSONObject gridSettings(String component) throws Exception {
        var pinnedApps = new JSONArray();
        if (component != null) pinnedApps.put(component);
        return new JSONObject().put("pinnedApps", pinnedApps);
    }

    private static JSONObject gridSettings(String component, String tileSize, boolean hideByDefault) throws Exception {
        return gridSettings(component).put("tileSize", tileSize).put("hideByDefault", hideByDefault);
    }

    private static JSONObject desktopSettings(boolean enabled, String startDesktop) throws Exception {
        return new JSONObject().put("enabled", enabled).put("defaultDesktop", startDesktop)
            .put("favorites1", new JSONArray().put("com.example.player/com.example.player.Main"))
            .put("favorites2", new JSONArray().put("com.example.navigation/com.example.navigation.Main"));
    }

    private static void assertGridSettings(JSONObject expected, JSONObject restored) throws Exception {
        assertEquals(expected.getJSONArray("pinnedApps").toString(), restored.getJSONArray("pinnedApps").toString());
        assertEquals(expected.getString("tileSize"), restored.getString("tileSize"));
        assertEquals(expected.getBoolean("hideByDefault"), restored.getBoolean("hideByDefault"));
        if (expected.has("desktops")) {
            assertEquals(expected.getJSONObject("desktops").toString(), restored.getJSONObject("desktops").toString());
        }
        for (String option : new String[]{"iconStyle", "showAppLabels", "sortOrder", "favoritesBackBehavior"}) {
            if (expected.has(option)) assertEquals(expected.get(option), restored.get(option));
        }
    }

    private static JSONObject appearanceSettings(boolean animateRoutes, boolean highContrast, String launcherEntry)
            throws Exception {
        return new JSONObject().put("animateRoutes", animateRoutes).put("highContrast", highContrast)
            .put("launcherEntry", launcherEntry);
    }

    private static void assertAppearanceSettings(AppearancePreferences appearance, boolean animateRoutes,
            boolean highContrast, String launcherEntry) throws Exception {
        var restored = appearance.exportSettings();
        assertEquals(animateRoutes, restored.getBoolean("animateRoutes"));
        assertEquals(highContrast, restored.getBoolean("highContrast"));
        assertEquals(launcherEntry, restored.getString("launcherEntry"));
    }

    private static JSONObject displayPreviewSettings(boolean showMedia, boolean showNavigation) throws Exception {
        return new JSONObject().put("showMedia", showMedia).put("showNavigation", showNavigation);
    }

    private static void assertDisplayPreviewSettings(DisplayPreviewPreferences preview, boolean media, boolean navigation)
            throws Exception {
        var restored = preview.exportSettings();
        assertEquals(media, restored.getBoolean("showMedia"));
        assertEquals(navigation, restored.getBoolean("showNavigation"));
    }

    private static void assertPreviewFields(JSONObject expected, DisplayPreviewPreferences preview) throws Exception {
        var restored = preview.exportSettings();
        for (var keys = expected.keys(); keys.hasNext();) {
            String key = keys.next();
            assertEquals(expected.get(key), restored.get(key));
        }
    }

    private static JSONObject seatSettings(int slot, String name) throws Exception {
        var names = new JSONArray().put("").put(name).put("");
        return new JSONObject().put("selectedSlot", slot).put("slotNames", names);
    }
}
