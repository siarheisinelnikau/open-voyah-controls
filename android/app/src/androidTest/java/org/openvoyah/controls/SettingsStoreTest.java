package org.openvoyah.controls;

import android.test.AndroidTestCase;
import org.json.JSONObject;
import org.openvoyah.controls.core.Action;
import org.openvoyah.controls.core.Binding;

/** Executes against Android's actual JSON and preferences implementation on a test emulator. */
public class SettingsStoreTest extends AndroidTestCase {
    private SettingsStore store;
    private String previous;
    @Override protected void setUp() throws Exception {
        super.setUp(); store = new SettingsStore(getContext()); previous = store.exportJson(); store.save(new SettingsStore.State());
    }
    @Override protected void tearDown() throws Exception {
        try { store.importJson(previous); } finally { super.tearDown(); }
    }
    private SettingsStore.State sample() {
        SettingsStore.State state = new SettingsStore.State(); state.panelEnabled = true; state.holdMs = 900;
        Action action = new Action(Action.Kind.LAUNCH_APP, "com.example.player/.Main", "Плеер");
        state.panelActions.put("navbar01", action);
        state.bindings.add(new Binding("test", "Кнопка", "panel", 85, action, Action.NONE, true)); return state;
    }
    public void testBootSchedulesOnlyAfterExplicitConnectionAndCancellationWorks() {
        android.content.SharedPreferences bridge = getContext().getSharedPreferences("bridge", 0);
        boolean previousConfigured = bridge.getBoolean("configured", false);
        android.app.job.JobScheduler scheduler = getContext().getSystemService(android.app.job.JobScheduler.class);
        try {
            BootHookService.cancel(getContext());
            bridge.edit().putBoolean("configured", false).commit();
            new BootReceiver().onReceive(getContext(), new android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED));
            assertNull(scheduler.getPendingJob(BootHookService.JOB_ID));
            bridge.edit().putBoolean("configured", true).commit();
            new BootReceiver().onReceive(getContext(), new android.content.Intent("unrelated"));
            assertNull(scheduler.getPendingJob(BootHookService.JOB_ID));
            new BootReceiver().onReceive(getContext(), new android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED));
            android.app.job.JobInfo job = scheduler.getPendingJob(BootHookService.JOB_ID);
            assertNotNull(job); assertTrue(job.isPersisted()); assertEquals(30000L, job.getInitialBackoffMillis());
            BootHookService.cancel(getContext());
            assertNull(scheduler.getPendingJob(BootHookService.JOB_ID));
        } finally {
            BootHookService.cancel(getContext());
            bridge.edit().putBoolean("configured", previousConfigured).commit();
        }
    }
    public void testRoundTripKeepsPanelAndPhysicalSettings() throws Exception {
        SettingsStore.State expected = sample(); store.save(expected);
        SettingsStore.State actual = new SettingsStore(getContext()).load();
        assertTrue(actual.panelEnabled); assertEquals(900, actual.holdMs);
        assertEquals("com.example.player/.Main", actual.panelActions.get("navbar01").component);
        assertEquals("panel", actual.bindings.get(0).device);
        assertEquals(SettingsStore.encode(expected), store.exportJson());
    }
    public void testImportReplacesAtomically() throws Exception {
        store.importJson(SettingsStore.encode(sample())); String before = store.exportJson();
        try { store.importJson("{broken}"); fail(); } catch (org.json.JSONException expected) { }
        assertEquals(before, store.exportJson());
    }
    public void testUnsupportedSchemaRejected() throws Exception {
        String invalid = new JSONObject(SettingsStore.encode(sample())).put("schema", 99).toString();
        try { store.importJson(invalid); fail(); } catch (IllegalArgumentException expected) { }
    }
    public void testDuplicateBindingRejected() {
        SettingsStore.State state = sample(); state.bindings.add(new Binding("other", "Дубль", "panel", 85, Action.NONE, Action.NONE, true));
        try { store.save(state); fail(); } catch (IllegalArgumentException expected) { }
        assertTrue(store.load().bindings.isEmpty());
    }
    public void testReservedPowerKeyRejected() {
        SettingsStore.State state = new SettingsStore.State(); state.bindings.add(new Binding("power", "Power", "", 26, Action.NONE, Action.NONE, true));
        try { store.save(state); fail(); } catch (IllegalArgumentException expected) { }
    }
    public void testUnknownPanelSlotRejected() {
        SettingsStore.State state = sample(); state.panelActions.put("unknown", Action.NONE);
        try { store.save(state); fail(); } catch (IllegalArgumentException expected) { }
    }
    public void testFridaConfigUsesIndependentNamespaceAndActions() throws Exception {
        JSONObject config = new JSONObject(FridaBridge.config(sample()));
        assertEquals(1, config.getInt("schema")); assertTrue(config.getBoolean("enabled"));
        assertEquals("LAUNCH_APP", config.getJSONObject("actions").getJSONObject("navbar01").getString("kind"));
        assertEquals(3, config.length());
    }
    public void testDisabledConfigAndResetRemoveActions() throws Exception {
        store.save(sample()); store.save(new SettingsStore.State());
        JSONObject config = new JSONObject(FridaBridge.config(store.load()));
        assertFalse(config.getBoolean("enabled")); assertEquals(0, config.getJSONObject("actions").length());
    }
    public void testLocalSettingsDoNotRequirePrivilegedPermission() {
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
            getContext().checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS"));
        store.save(sample());
        assertEquals("com.example.player/.Main", new SettingsStore(getContext()).load().panelActions.get("navbar01").component);
    }
}
