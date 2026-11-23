package org.openvoyah.controls;

import android.test.AndroidTestCase;
import org.json.JSONObject;
import org.openvoyah.controls.core.Action;
import org.openvoyah.controls.core.Binding;
import org.openvoyah.controls.core.PanelSlots;
import org.openvoyah.controls.data.frida.FridaBridge;
import org.openvoyah.controls.data.settings.SettingsStore;
import org.openvoyah.controls.platform.boot.RecoveryJobs;

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
    private void assertOnlyInactiveAppMenuDefault(SettingsStore.State state) {
        assertFalse(state.panelEnabled);
        assertEquals(1, state.panelActions.size());
        assertEquals(Action.Kind.CONTROLS_APPS, state.panelActions.get("mainApps").kind);
        assertEquals("", state.panelActions.get("mainApps").component);
        for (String slot : PanelSlots.PANEL) {
            if (!slot.equals("mainApps")) assertFalse(slot, state.panelActions.containsKey(slot));
        }
        assertOnlyVoiceKeyDefault(state);
        assertTrue(state.bindings.isEmpty());
    }
    private void assertOnlyVoiceKeyDefault(SettingsStore.State state) {
        assertEquals(1, state.steeringActions.size());
        assertEquals(Action.Kind.START_VOICE, state.steeringActions.get("voiceShort").kind);
        assertEquals("", state.steeringActions.get("voiceShort").component);
    }
    public void testNewStateDefaultsOnlyTheInactiveAppMenu() {
        assertOnlyInactiveAppMenuDefault(new SettingsStore.State());
    }
    public void testOldSchemaWithoutAnAppMenuGetsOnlyTheInactiveDefault() throws Exception {
        String old = "{\"schema\":1,\"enabled\":true,\"holdMs\":650,\"debounceMs\":100,\"bindings\":[]}";
        assertOnlyInactiveAppMenuDefault(SettingsStore.decode(old));
        assertOnlyInactiveAppMenuDefault(SettingsStore.decode(new JSONObject(old)
            .put("panelActions", new JSONObject()).toString()));
    }
    public void testOldSchemaMissingAppMenuPreservesExistingNavigation() throws Exception {
        JSONObject old = new JSONObject(SettingsStore.encode(sample()));
        old.getJSONObject("panelActions").remove("mainApps");
        old.put("panelEnabled", false);
        SettingsStore.State actual = SettingsStore.decode(old.toString());
        assertFalse(actual.panelEnabled);
        assertEquals(2, actual.panelActions.size());
        assertEquals(Action.Kind.CONTROLS_APPS, actual.panelActions.get("mainApps").kind);
        assertEquals(Action.Kind.LAUNCH_APP, actual.panelActions.get("navbar01").kind);
        assertEquals("com.example.player/.Main", actual.panelActions.get("navbar01").component);
        for (String slot : PanelSlots.PANEL) {
            if (!slot.equals("mainApps") && !slot.equals("navbar01")) assertFalse(slot, actual.panelActions.containsKey(slot));
        }
    }
    public void testExplicitlyClearedAppMenuSurvivesEncodingAndPersistence() throws Exception {
        SettingsStore.State state = new SettingsStore.State();
        state.panelActions.put("mainApps", Action.NONE);
        String encoded = SettingsStore.encode(state);
        assertEquals("NONE", new JSONObject(encoded).getJSONObject("panelActions")
            .getJSONObject("mainApps").getString("kind"));
        SettingsStore.State decoded = SettingsStore.decode(encoded);
        assertFalse(decoded.panelEnabled);
        assertEquals(1, decoded.panelActions.size());
        assertEquals(Action.Kind.NONE, decoded.panelActions.get("mainApps").kind);
        assertFalse(decoded.panelActions.get("mainApps").isAssigned());
        store.save(decoded);
        assertEquals(Action.Kind.NONE, new SettingsStore(getContext()).load().panelActions.get("mainApps").kind);
    }
    public void testChosenAppMenuApplicationSurvivesEncodingAndPersistence() throws Exception {
        SettingsStore.State state = new SettingsStore.State();
        Action selected = new Action(Action.Kind.LAUNCH_APP, "com.example.apps/.Library", "Library");
        state.panelActions.put("mainApps", selected);
        SettingsStore.State decoded = SettingsStore.decode(SettingsStore.encode(state));
        assertFalse(decoded.panelEnabled);
        assertEquals(1, decoded.panelActions.size());
        assertEquals(Action.Kind.LAUNCH_APP, decoded.panelActions.get("mainApps").kind);
        assertEquals(selected.component, decoded.panelActions.get("mainApps").component);
        assertEquals(selected.label, decoded.panelActions.get("mainApps").label);
        store.save(decoded);
        Action actual = new SettingsStore(getContext()).load().panelActions.get("mainApps");
        assertEquals(Action.Kind.LAUNCH_APP, actual.kind);
        assertEquals(selected.component, actual.component);
        assertEquals(selected.label, actual.label);
    }
    public void testBootSchedulesOnlyAfterExplicitConnectionAndCancellationWorks() {
        android.content.SharedPreferences bridge = getContext().getSharedPreferences("bridge", 0);
        boolean previousConfigured = bridge.getBoolean("configured", false);
        android.app.job.JobScheduler scheduler = getContext().getSystemService(android.app.job.JobScheduler.class);
        try {
            RecoveryJobs.cancel(getContext());
            bridge.edit().putBoolean("configured", false).commit();
            new BootReceiver().onReceive(getContext(), new android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED));
            assertNull(scheduler.getPendingJob(RecoveryJobs.ID));
            bridge.edit().putBoolean("configured", true).commit();
            new BootReceiver().onReceive(getContext(), new android.content.Intent("unrelated"));
            assertNull(scheduler.getPendingJob(RecoveryJobs.ID));
            new BootReceiver().onReceive(getContext(), new android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED));
            android.app.job.JobInfo job = scheduler.getPendingJob(RecoveryJobs.ID);
            assertNotNull(job); assertTrue(job.isPersisted()); assertEquals(30000L, job.getInitialBackoffMillis());
            RecoveryJobs.cancel(getContext());
            assertNull(scheduler.getPendingJob(RecoveryJobs.ID));
        } finally {
            RecoveryJobs.cancel(getContext());
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
    public void testOldSteeringWithoutTheVoiceKeyGetsTheDefaultBesideItsOwnKeys() throws Exception {
        JSONObject old = new JSONObject(SettingsStore.encode(sample()));
        old.put("steeringActions", new JSONObject().put("cameraShort", new JSONObject()
            .put("kind", "SYSTEM_SETTINGS").put("component", "").put("label", "Settings")));
        SettingsStore.State actual = SettingsStore.decode(old.toString());
        assertEquals(2, actual.steeringActions.size());
        assertEquals(Action.Kind.START_VOICE, actual.steeringActions.get("voiceShort").kind);
        assertEquals(Action.Kind.SYSTEM_SETTINGS, actual.steeringActions.get("cameraShort").kind);
        assertOnlyVoiceKeyDefault(SettingsStore.decode(new JSONObject(old.toString())
            .put("steeringActions", new JSONObject()).toString()));
    }
    public void testFactoryVoiceKeySurvivesPersistenceAndLeavesTheHookDocument() throws Exception {
        SettingsStore.State state = new SettingsStore.State();
        state.steeringActions.put("voiceShort", Action.NONE);
        store.save(SettingsStore.decode(SettingsStore.encode(state)));
        SettingsStore.State actual = new SettingsStore(getContext()).load();
        assertEquals(1, actual.steeringActions.size());
        assertEquals(Action.Kind.NONE, actual.steeringActions.get("voiceShort").kind);
        assertEquals(0, new JSONObject(FridaBridge.config(actual)).getJSONObject("steering").length());
        assertEquals("START_VOICE", new JSONObject(FridaBridge.config(new SettingsStore.State()))
            .getJSONObject("steering").getJSONObject("voiceShort").getString("kind"));
    }
    public void testExplicitVoiceActionsKeepTheirKindAcrossDecodeAndPersistence() throws Exception {
        SettingsStore.State expected = sample();
        Action voice = new Action(Action.Kind.START_VOICE, "", "Speak");
        expected.panelActions.put("navbar02", voice);
        expected.steeringActions.put("voiceShort", voice);
        expected.bindings.add(new Binding("voice", "Voice", "panel", 87, voice, Action.NONE, true));
        String encoded = SettingsStore.encode(expected);
        SettingsStore.State decoded = SettingsStore.decode(encoded);
        store.save(decoded);
        SettingsStore.State actual = new SettingsStore(getContext()).load();
        for (SettingsStore.State state : new SettingsStore.State[]{decoded, actual}) {
            assertEquals(Action.Kind.START_VOICE, state.panelActions.get("navbar02").kind);
            assertEquals("", state.panelActions.get("navbar02").component);
            assertEquals("Speak", state.panelActions.get("navbar02").label);
            assertEquals(Action.Kind.START_VOICE, state.steeringActions.get("voiceShort").kind);
            assertEquals(Action.Kind.START_VOICE, state.bindings.get(1).shortPress.kind);
            assertEquals(Action.Kind.LAUNCH_APP, state.panelActions.get("navbar01").kind);
            assertEquals("com.example.player/.Main", state.panelActions.get("navbar01").component);
            assertEquals(900, state.holdMs);
            assertTrue(state.panelEnabled);
        }
        assertEquals(encoded, store.exportJson());
    }
    public void testApplicationLaunchActionsKeepTheirExactStoredComponent() throws Exception {
        SettingsStore.State expected = sample();
        Action selected = new Action(Action.Kind.LAUNCH_APP, "com.example.voice/.Capture", "Voice application");
        expected.panelActions.put("navbar02", selected);
        store.save(SettingsStore.decode(SettingsStore.encode(expected)));
        Action actual = new SettingsStore(getContext()).load().panelActions.get("navbar02");
        assertEquals(Action.Kind.LAUNCH_APP, actual.kind);
        assertEquals(selected.component, actual.component);
        assertEquals(selected.label, actual.label);
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
        SettingsStore.State state = sample();
        state.enabled = true;
        state.steeringActions.put("voiceShort", new Action(Action.Kind.START_VOICE, "", "Speak"));
        JSONObject config = new JSONObject(FridaBridge.config(state));
        assertEquals(1, config.getInt("schema")); assertTrue(config.getBoolean("enabled"));
        assertEquals("LAUNCH_APP", config.getJSONObject("actions").getJSONObject("navbar01").getString("kind"));
        assertTrue(config.getBoolean("steeringEnabled"));
        assertEquals("START_VOICE", config.getJSONObject("steering").getJSONObject("voiceShort").getString("kind"));
        assertEquals("CONTROLS_APPS", config.getJSONObject("actions").getJSONObject("mainApps").getString("kind"));
        assertEquals(2, config.getJSONObject("actions").length());
        assertEquals(1, config.getJSONObject("steering").length());
    }
    public void testNavigatorHomeCardIsOffUntilTurnedOnAndIndependentOfPanelAssignments() throws Exception {
        assertFalse(new SettingsStore.State().navigatorHomeCard);
        String old = "{\"schema\":1,\"enabled\":true,\"holdMs\":650,\"debounceMs\":100,\"bindings\":[]}";
        assertFalse(SettingsStore.decode(old).navigatorHomeCard);
        assertFalse(new JSONObject(FridaBridge.config(new SettingsStore.State())).has("homeCard"));
        SettingsStore.State state = sample(); state.navigatorHomeCard = true; state.panelEnabled = false;
        store.save(state);
        SettingsStore.State loaded = new SettingsStore(getContext()).load();
        assertTrue(loaded.navigatorHomeCard);
        JSONObject config = new JSONObject(FridaBridge.config(loaded));
        assertFalse(config.getBoolean("enabled"));
        assertEquals("ru.yandex.yandexnavi", config.getJSONObject("homeCard").getString("package"));
    }
    public void testNavigatorWidgetScaleStartsLargeAndKeepsAChosenLegacyDensity() throws Exception {
        String base = "{\"schema\":1,\"enabled\":true,\"holdMs\":650,\"debounceMs\":100,\"bindings\":[]";
        assertEquals(150, new SettingsStore.State().navigatorWidgetScale);
        assertEquals(150, SettingsStore.decode(base + "}").navigatorWidgetScale);
        // The first version always saved its default density, 160: not a choice, so today's default applies.
        assertEquals(150, SettingsStore.decode(base + ",\"navigatorWidgetDpi\":160}").navigatorWidgetScale);
        assertEquals(130, SettingsStore.decode(base + ",\"navigatorWidgetDpi\":200}").navigatorWidgetScale);
        // 120 DPI is 80 %, below what Navigator can draw for: the smallest scale.
        assertEquals(90, SettingsStore.decode(base + ",\"navigatorWidgetDpi\":120}").navigatorWidgetScale);
        assertEquals(90, SettingsStore.decode(base + ",\"navigatorWidgetScale\":70}").navigatorWidgetScale);
        assertEquals(90, SettingsStore.decode(base + ",\"navigatorWidgetScale\":90}").navigatorWidgetScale);
        assertEquals(150, SettingsStore.decode(base + ",\"navigatorWidgetScale\":40}").navigatorWidgetScale);
        assertEquals(100, SettingsStore.decode(base + ",\"navigatorWidgetScale\":100}").navigatorWidgetScale);
        assertEquals(200, SettingsStore.decode(base + ",\"navigatorWidgetScale\":200}").navigatorWidgetScale);
        assertEquals(150, SettingsStore.decode(base + ",\"navigatorWidgetScale\":210}").navigatorWidgetScale);
        SettingsStore.State state = sample(); state.navigatorHomeCard = true; state.navigatorWidgetScale = 180;
        assertEquals(180, new JSONObject(FridaBridge.config(state)).getJSONObject("homeCard").getInt("scale"));
    }
    public void testHomeWeatherCardIsOffUntilTurnedOnAndCarriesTheNoDataText() throws Exception {
        assertFalse(new SettingsStore.State().weatherCard);
        assertFalse(new JSONObject(FridaBridge.config(new SettingsStore.State())).has("weatherCard"));
        SettingsStore.State state = sample(); state.weatherCard = true;
        store.save(state);
        SettingsStore.State loaded = new SettingsStore(getContext()).load();
        assertTrue(loaded.weatherCard);
        String noData = new JSONObject(FridaBridge.config(loaded)).getJSONObject("weatherCard").getString("noData");
        assertFalse(noData.isEmpty());
        assertTrue(noData.length() <= 40);
    }
    public void testDisabledConfigAndResetRemoveCustomActions() throws Exception {
        store.save(sample()); store.save(new SettingsStore.State());
        JSONObject config = new JSONObject(FridaBridge.config(store.load()));
        assertFalse(config.getBoolean("enabled")); assertEquals(1, config.getJSONObject("actions").length());
        assertEquals("CONTROLS_APPS", config.getJSONObject("actions").getJSONObject("mainApps").getString("kind"));
    }
    public void testLocalSettingsDoNotRequirePrivilegedPermission() {
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
            getContext().checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS"));
        store.save(sample());
        assertEquals("com.example.player/.Main", new SettingsStore(getContext()).load().panelActions.get("navbar01").component);
    }
}
