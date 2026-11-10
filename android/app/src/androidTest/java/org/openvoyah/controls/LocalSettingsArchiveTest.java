package org.openvoyah.controls;

import android.content.Context;
import android.test.AndroidTestCase;
import android.util.AtomicFile;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.openvoyah.controls.data.display.DisplayPreferences;
import org.openvoyah.controls.data.settings.LocalSettingsArchive;
import org.openvoyah.controls.data.settings.LocalizationSettings;
import org.openvoyah.controls.data.settings.SettingsStore;
import org.openvoyah.controls.data.settings.VoiceSettings;

public final class LocalSettingsArchiveTest extends AndroidTestCase {
    public void testCombinedBackupAndLegacyButtonImport() throws Exception {
        Context context = getContext();
        var archive = new LocalSettingsArchive(context);
        var before = archive.prepare(archive.exportJson());
        var buttons = new SettingsStore(context);
        var voice = new VoiceSettings(context);
        var display = new DisplayPreferences(context);
        var locale = new LocalizationSettings(context);
        try {
            var state = buttons.load();
            state.holdMs = 900;
            buttons.save(state);
            voice.language("en");
            voice.audio(true, true);
            display.restore(240, true);
            UiText.language("ru");
            locale.preset("en");
            var combined = archive.prepare(archive.exportJson());

            voice.language("ru");
            voice.audio(false, false);
            display.restore(0, false);
            UiText.language("en");
            locale.disable();
            archive.apply(combined);

            assertEquals(900, buttons.load().holdMs);
            assertEquals("en", voice.language());
            assertTrue(voice.option("duckMedia"));
            assertTrue(voice.option("spokenFeedback"));
            assertEquals("ru", UiText.language());
            assertEquals(240, display.density());
            assertTrue(display.clusterMedia());
            assertTrue(locale.enabled());

            var oldFile = archive.prepare(SettingsStore.encode(new SettingsStore.State()));
            assertFalse(oldFile.complete);
            archive.apply(oldFile);
            assertEquals(650, buttons.load().holdMs);
            assertEquals("en", voice.language());
            assertEquals(240, display.density());
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
}
