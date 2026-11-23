package org.openvoyah.controls;

import android.test.AndroidTestCase;
import org.json.JSONObject;
import org.openvoyah.controls.core.voice.VoiceAudioOptions;
import org.openvoyah.controls.core.voice.VoiceAudioOptions.EndCue;
import org.openvoyah.controls.core.voice.VoiceAudioOptions.FeedbackStyle;
import org.openvoyah.controls.core.voice.VoiceAudioOptions.ListeningWindow;
import org.openvoyah.controls.core.voice.VoiceAudioOptions.ResultDuration;
import org.openvoyah.controls.data.frida.FridaBridge;
import org.openvoyah.controls.data.settings.SettingsStore;
import org.openvoyah.controls.data.settings.VoiceSettings;
import org.openvoyah.controls.platform.boot.RecoveryJobs;

public final class VoiceSettingsTest extends AndroidTestCase {
    private String before;
    private VoiceSettings store;
    @Override public void setUp() { store=new VoiceSettings(getContext());before=store.exportJson(); }
    @Override public void tearDown() throws Exception { store.importJson(before); }
    public void testCatalogIsPackagedAndAllPhrasesMatch() throws Exception {
        var catalog=VoiceSettings.catalog();assertEquals(138,catalog.all().size());
        for(var command:catalog.all()) for(String phrase:command.phrases()) assertEquals(command,catalog.match(phrase));
    }
    public void testInvalidImportLeavesStateUnchanged() throws Exception {
        try { store.importJson("{\"schema\":1,\"values\":{\"profile\":\"INVALID\"}}");fail(); } catch(IllegalArgumentException expected) { }
        assertEquals(before,store.exportJson());
    }
    public void testImportKeepsCurrentBindingsAndDropsObsoleteCommands() throws Exception {
        store.importJson("{\"schema\":1,\"values\":{\"app.Obsolete\":\"test.app/.Main\",\"app.Camera\":\"test.camera/.Main\"}}");
        assertEquals("",store.app("Obsolete"));
        assertEquals("test.camera/.Main",store.app("Camera"));
        store.language("en");
        assertEquals("en",store.language());
    }
    public void testContactIsLocalExactAndDialOnly() throws Exception {
        store.importJson("{\"schema\":1,\"values\":{}}");
        store.contact("Тестовый контакт","+123456789");
        var command=store.withContacts().match("позвони тестовый контакт");assertNotNull(command);
        assertEquals("+123456789",store.phone(command.id()));
        assertNull(store.withContacts().match("не позвони тестовый контакт"));
        try { store.contact("тестовый контакт","12345");fail(); } catch(IllegalArgumentException expected) { }
    }
    public void testVoiceRollbackRestoresPreviousSnapshot() throws Exception {
        store.importJson("{\"schema\":1,\"values\":{\"profile\":\"FREE\"}}");
        store.importJson("{\"schema\":1,\"values\":{\"profile\":\"DREAMER\"}}");
        store.restore();assertEquals("FREE",store.profile());
    }
    public void testOneWindowOptionsSaveRestoresBothPreviousValues() throws Exception {
        store.importJson("{\"schema\":1,\"values\":{\"doubleSmoke\":\"false\",\"doubleVentilation\":\"false\",\"language\":\"en\"}}");
        store.windowOptions(true, true);
        assertTrue(store.option("doubleSmoke"));
        assertTrue(store.option("doubleVentilation"));
        store.restore();
        assertFalse(store.option("doubleSmoke"));
        assertFalse(store.option("doubleVentilation"));
        assertEquals("en", store.language());
    }

    public void testVoiceAudioValuesRoundTripAndOlderArchivesRetainThem() throws Exception {
        var initial = new VoiceAudioOptions(100, 100, false, 35);
        var saved = new VoiceAudioOptions(75, 40, true, 20);
        store.audio(false, false, initial);
        store.audio(true, true, saved);
        assertEquals(saved, new VoiceSettings(getContext()).audioOptions());
        var exported = new JSONObject(store.exportJson());
        assertEquals(1, exported.getInt("schema"));
        var values = exported.getJSONObject("values");
        assertEquals("75", values.get("speechRatePercent"));
        assertEquals("40", values.get("speechVolumePercent"));
        assertEquals("true", values.get("readyCue"));
        assertEquals("20", values.get("readyCueVolumePercent"));
        store.restore();
        assertEquals(initial, store.audioOptions());
        store.importJson(exported.toString());
        store.importJson("{\"schema\":1,\"values\":{\"language\":\"en\",\"spokenFeedback\":\"false\"}}");
        assertEquals(saved, store.audioOptions());
        assertFalse(store.option("spokenFeedback"));
    }

    public void testVoiceAudioValuesRejectInvalidRangesAndTypes() throws Exception {
        for (String invalid : new String[]{"\"speechRatePercent\":\"49\"", "\"speechRatePercent\":\"151\"",
                "\"speechVolumePercent\":\"-1\"", "\"readyCueVolumePercent\":\"101\"", "\"readyCue\":true",
                "\"speechVolumePercent\":35"}) {
            try { store.importJson("{\"schema\":1,\"values\":{" + invalid + "}}"); fail(invalid); }
            catch (IllegalArgumentException expected) { }
            assertEquals(before, store.exportJson());
        }
    }

    public void testOfflineVoiceChoicesAreLanguageSpecificAndPortable() throws Exception {
        store.audio(false, true, VoiceAudioOptions.DEFAULTS, "en", "en-US-local");
        store.audio(false, true, VoiceAudioOptions.DEFAULTS, "ru", "ru-RU-local");
        var archive = new JSONObject(store.exportJson());
        assertEquals("en-US-local", archive.getJSONObject("values").getString("offlineVoice.en"));
        assertEquals("ru-RU-local", archive.getJSONObject("values").getString("offlineVoice.ru"));
        store.importJson("{\"schema\":1,\"values\":{\"language\":\"en\"}}");
        assertEquals("en-US-local", store.offlineVoice("en"));
        assertEquals("ru-RU-local", store.offlineVoice("ru"));
        store.audio(false, false, VoiceAudioOptions.DEFAULTS, "en", "");
        assertEquals("", store.offlineVoice("en"));
        assertEquals("ru-RU-local", store.offlineVoice("ru"));
        store.restore();
        assertEquals("en-US-local", store.offlineVoice("en"));
    }

    public void testReadyPhrasesArePortablePerLanguageAndRejectInvalidArchivesAtomically() throws Exception {
        var options = new VoiceAudioOptions(85, 70, true, 25, FeedbackStyle.OUTCOME, EndCue.SOFT);
        store.audio(false, true, options, "en", "en-US-local", "Go ahead.");
        store.audio(false, true, options, "ru", "ru-RU-local", "Говорите.");
        String portable = store.exportJson();
        var values = new JSONObject(portable).getJSONObject("values");
        assertEquals("Go ahead.", values.getString("readyPrompt.en"));
        assertEquals("Говорите.", values.getString("readyPrompt.ru"));

        store.audio(false, true, options, "en", "en-US-local", "");
        assertEquals("", store.readyPrompt("en"));
        assertEquals("Говорите.", store.readyPrompt("ru"));
        store.restore();
        assertEquals("Go ahead.", store.readyPrompt("en"));
        store.importJson(portable);
        store.importJson("{\"schema\":1,\"values\":{\"language\":\"ru\"}}");
        store.audio(false, false, options, "en", "new-en-voice");
        var reopened = new VoiceSettings(getContext());
        assertEquals("Go ahead.", reopened.readyPrompt("en"));
        assertEquals("Говорите.", reopened.readyPrompt("ru"));
        assertEquals("ru-RU-local", reopened.offlineVoice("ru"));
        assertEquals(options, reopened.audioOptions());

        String saved = store.exportJson();
        for (Object invalid : new Object[]{false, 3, JSONObject.NULL, "first\nsecond", "word\u2028split", "   ",
                "123456789012345678901234567890123"}) {
            var changes = new JSONObject().put("readyPrompt.en", "Replace me").put("readyPrompt.ru", invalid);
            try { store.importJson(new JSONObject().put("schema", 1).put("values", changes).toString()); fail(); }
            catch (IllegalArgumentException expected) { }
            assertEquals(saved, store.exportJson());
        }
    }

    public void testFeedbackStyleAndEndCueRetainTheirIndependentValues() throws Exception {
        store.audio(false, false, new VoiceAudioOptions(100, 100, false, 35));
        assertEquals(FeedbackStyle.READBACK, store.audioOptions().feedbackStyle());
        assertEquals(EndCue.OFF, store.audioOptions().endCue());
        var selected = new VoiceAudioOptions(85, 70, true, 25, FeedbackStyle.OUTCOME, EndCue.SOFT);
        store.audio(false, true, selected);
        var exported = new JSONObject(store.exportJson()).getJSONObject("values");
        assertEquals("outcome", exported.getString("speechFeedbackStyle"));
        assertEquals("soft", exported.getString("endListeningCue"));

        store.importJson("{\"schema\":1,\"values\":{\"spokenFeedback\":\"true\"}}");
        assertEquals(selected, store.audioOptions());
        store.importJson("{\"schema\":1,\"values\":{\"endListeningCue\":\"off\"}}");
        assertEquals(FeedbackStyle.OUTCOME, store.audioOptions().feedbackStyle());
        assertEquals(EndCue.OFF, store.audioOptions().endCue());
        assertTrue(store.audioOptions().readyCue());
        store.restore();
        assertEquals(selected, store.audioOptions());

        for (String invalid : new String[]{"\"speechFeedbackStyle\":true", "\"endListeningCue\":\"loud\""}) {
            try { store.importJson("{\"schema\":1,\"values\":{" + invalid + "}}"); fail(invalid); }
            catch (IllegalArgumentException expected) { }
            assertEquals(selected, store.audioOptions());
        }
    }

    public void testReadyStyleAndReplyChannelSurviveOlderImportsAndUndo() throws Exception {
        var defaults = VoiceAudioOptions.DEFAULTS;
        store.audio(false, false, defaults);
        store.importJson("{\"schema\":1,\"values\":{\"readyCueStyle\":\"spoken\",\"spokenReplyChannel\":\"media\"}}");
        var selected = new VoiceSettings(getContext()).audioOptions();
        assertEquals(VoiceAudioOptions.ReadyCueStyle.SPOKEN, selected.readyCueStyle());
        assertEquals(VoiceAudioOptions.SpokenReplyChannel.MEDIA, selected.spokenReplyChannel());
        assertFalse(selected.readyCue());
        assertFalse(store.option("spokenFeedback"));
        var portable = store.exportJson();
        var values = new JSONObject(portable).getJSONObject("values");
        assertEquals("spoken", values.get("readyCueStyle"));
        assertEquals("media", values.get("spokenReplyChannel"));
        store.restore();
        assertEquals(defaults, store.audioOptions());
        store.importJson(portable);
        store.importJson("{\"schema\":1,\"values\":{\"readyCue\":\"true\",\"endListeningCue\":\"soft\"}}");
        assertEquals(selected.readyCueStyle(), store.audioOptions().readyCueStyle());
        assertEquals(selected.spokenReplyChannel(), store.audioOptions().spokenReplyChannel());
        assertTrue(store.audioOptions().readyCue());
        assertEquals(EndCue.SOFT, store.audioOptions().endCue());
        store.restore();
        assertEquals(selected, store.audioOptions());
        for (String invalid : new String[]{"\"readyCueStyle\":true", "\"readyCueStyle\":\"other\"",
                "\"spokenReplyChannel\":1", "\"spokenReplyChannel\":\"music\""}) {
            try { store.importJson("{\"schema\":1,\"values\":{" + invalid + "}}"); fail(invalid); }
            catch (IllegalArgumentException expected) { }
            assertEquals(selected, store.audioOptions());
        }
    }
    public void testCaptureAndPopupSettingsAreIndependentAndPortable() throws Exception {
        var selected = new VoiceAudioOptions(85, 70, true, 25, FeedbackStyle.READBACK, EndCue.SOFT,
            ListeningWindow.SIX_SECONDS, ResultDuration.ONE_SECOND, false);
        store.audio(false, true, selected);
        var portable = new JSONObject(store.exportJson());
        var values = portable.getJSONObject("values");
        assertEquals("6", values.get("listeningWindowSeconds"));
        assertEquals("1", values.get("resultDisplaySeconds"));
        assertEquals("false", values.get("showRecognizedPhrase"));
        store.audio(false, false, VoiceAudioOptions.DEFAULTS);
        store.importJson(portable.toString());
        assertEquals(selected, new VoiceSettings(getContext()).audioOptions());

        store.importJson("{\"schema\":1,\"values\":{\"language\":\"en\"}}");
        assertEquals(selected, store.audioOptions());
        store.importJson("{\"schema\":1,\"values\":{\"resultDisplaySeconds\":\"4\"}}");
        var changed = store.audioOptions();
        assertEquals(ListeningWindow.SIX_SECONDS, changed.listeningWindow());
        assertEquals(ResultDuration.FOUR_SECONDS, changed.resultDuration());
        assertFalse(changed.showRecognizedPhrase());
        assertEquals(selected.feedbackStyle(), changed.feedbackStyle());
        store.restore();
        assertEquals(selected, store.audioOptions());

        var beforeInvalid = store.exportJson();
        for (String invalid : new String[]{"\"listeningWindowSeconds\":6", "\"listeningWindowSeconds\":\"06\"",
                "\"resultDisplaySeconds\":true", "\"resultDisplaySeconds\":\"6\"",
                "\"showRecognizedPhrase\":false", "\"showRecognizedPhrase\":\"no\""}) {
            try { store.importJson("{\"schema\":1,\"values\":{" + invalid + "}}"); fail(invalid); }
            catch (IllegalArgumentException expected) { }
            assertEquals(beforeInvalid, store.exportJson());
        }
    }

    public void testPreciseEndCueVolumeRestoresAndKeepsLegacyGains() throws Exception {
        store.audio(false, false, VoiceAudioOptions.DEFAULTS);
        store.importJson("{\"schema\":1,\"values\":{\"endListeningCue\":\"soft\"}}");
        assertNull(store.audioOptions().endCueVolumePercent());
        assertEquals(.25f, store.audioOptions().endCueGain(), 0f);

        var exact = new VoiceAudioOptions(85, 70, true, 20, FeedbackStyle.OUTCOME, EndCue.STANDARD,
            ListeningWindow.SIX_SECONDS, ResultDuration.ONE_SECOND, false, 37);
        store.audio(false, true, exact);
        var saved = store.exportJson();
        assertEquals("37", new JSONObject(saved).getJSONObject("values").get("endCueVolumePercent"));
        store.audio(false, false, VoiceAudioOptions.DEFAULTS);
        store.importJson(saved);
        assertEquals(exact, new VoiceSettings(getContext()).audioOptions());
        store.importJson("{\"schema\":1,\"values\":{\"endListeningCue\":\"off\"}}");
        assertEquals(Integer.valueOf(37), store.audioOptions().endCueVolumePercent());
        assertEquals(0f, store.audioOptions().endCueGain(), 0f);
        store.restore();
        assertEquals(exact, store.audioOptions());

        store.importJson("{\"schema\":1,\"values\":{\"endCueVolumePercent\":\"preset\"}}");
        assertNull(store.audioOptions().endCueVolumePercent());
        assertEquals(.65f, store.audioOptions().endCueGain(), 0f);
        String reset = store.exportJson();
        assertEquals("preset", new JSONObject(reset).getJSONObject("values").get("endCueVolumePercent"));
        for (Object invalid : new Object[]{37, true, "-1", "101", "037", "auto"}) {
            var values = new JSONObject().put("endCueVolumePercent", invalid);
            try { store.importJson(new JSONObject().put("schema", 1).put("values", values).toString()); fail(invalid.toString()); }
            catch (IllegalArgumentException expected) { }
            assertEquals(reset, store.exportJson());
        }
    }

    public void testFailedFirstConnectionRetainsDesiredStateAndSchedulesRecovery() throws Exception {
        var prefs=getContext().getSharedPreferences("bridge",0);boolean old=prefs.getBoolean("configured",false);
        try {
            prefs.edit().putBoolean("configured",false).commit();
            try { new FridaBridge(getContext(),1).connect(new SettingsStore.State());fail(); } catch(java.io.IOException expected) { }
            assertTrue(FridaBridge.wasConfigured(getContext()));
            assertNotNull(getContext().getSystemService(android.app.job.JobScheduler.class).getPendingJob(RecoveryJobs.ID));
        } finally { RecoveryJobs.cancel(getContext());prefs.edit().putBoolean("configured",old).commit(); }
    }
    public void testFailedDisconnectSchedulesStopInsteadOfReconnect() throws Exception {
        var prefs=getContext().getSharedPreferences("bridge",0);
        boolean configured=prefs.getBoolean("configured",false),pending=prefs.getBoolean("stopPending",false);
        try {
            prefs.edit().putBoolean("configured",true).putBoolean("stopPending",false).commit();
            try { new FridaBridge(getContext(),1).disconnect();fail(); } catch(java.io.IOException expected) { }
            assertFalse(FridaBridge.wasConfigured(getContext()));assertTrue(FridaBridge.needsRecovery(getContext()));
            assertTrue(prefs.getBoolean("stopPending",false));
            assertNotNull(getContext().getSystemService(android.app.job.JobScheduler.class).getPendingJob(RecoveryJobs.ID));
        } finally { RecoveryJobs.cancel(getContext());prefs.edit().putBoolean("configured",configured).putBoolean("stopPending",pending).commit(); }
    }
}
