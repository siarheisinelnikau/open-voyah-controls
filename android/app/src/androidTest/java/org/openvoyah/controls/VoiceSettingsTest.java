package org.openvoyah.controls;

import android.test.AndroidTestCase;
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
