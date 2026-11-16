package org.openvoyah.controls;

import android.content.SharedPreferences;
import android.test.AndroidTestCase;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openvoyah.controls.data.frida.FridaBridge;
import org.openvoyah.controls.data.frida.ProductionRootAccess;

/** A real loopback transport with explicit agent receipts; no root command is executed. */
public final class FridaDisconnectTest extends AndroidTestCase {
    public void testConfirmedRestorationCompletesDisconnect() throws Exception { check(new Scenario(true, true)); }
    public void testUnconfirmedIconsRefuseSuccessfulDisconnect() throws Exception { check(new Scenario(false, true)); }
    public void testUnconfirmedWindowsRefuseSuccessfulDisconnect() throws Exception { check(new Scenario(true, false)); }

    public void testCurrentSessionWithoutOriginalAgentCannotClaimRestoration() throws Exception {
        for (int scope = 0; scope < 2; scope++) {
            Scenario s = new Scenario(true, true); s.sessions[scope] = "current"; s.ready[scope] = false; s.restorable[scope] = false; check(s);
        }
    }
    public void testUncertainSessionRejectsEvenAnAvailableReplacementAgent() throws Exception {
        for (int scope = 0; scope < 2; scope++) {
            Scenario s = new Scenario(true, true); s.sessions[scope] = "uncertain"; s.restorable[scope] = false; check(s);
        }
    }
    public void testProvenTargetChangeCanRetireTheOldVisualSession() throws Exception {
        for (int scope = 0; scope < 2; scope++) {
            Scenario s = new Scenario(true, true); s.sessions[scope] = "changed"; s.ready[scope] = false; check(s);
        }
    }
    public void testOriginalAcknowledgmentClearsOnlyItsObservedSession() throws Exception {
        Scenario s = new Scenario(true, true); s.sessions[0] = "current"; s.sessions[1] = "current"; check(s);
    }
    public void testReplacedJournalAfterAcknowledgmentIsNotCleared() throws Exception {
        for (int scope = 0; scope < 2; scope++) {
            Scenario s = new Scenario(true, true); s.sessions[scope] = "current"; s.replaceBeforeClear[scope] = true;
            s.restorable[scope] = false; check(s);
        }
    }
    public void testJournalAppearingDuringShutdownRefusesSuccessAfterQuiescence() throws Exception {
        for (int scope = 0; scope < 2; scope++) {
            Scenario s = new Scenario(true, true); s.afterStop[scope] = "current"; s.restorable[scope] = false; check(s);
        }
    }
    public void testDisablingWindowChecksALateJournalAfterFullWorkerStop() throws Exception {
        SharedPreferences prefs = getContext().getSharedPreferences("bridge", 0);
        Object previous = prefs.getAll().get("windowHook");
        try {
            for (boolean lateSession : new boolean[]{false, true}) {
                Scenario s = new Scenario(true, true);
                s.ready[1] = false;
                if (lateSession) s.afterStop[1] = "current";
                try (Peer peer = new Peer(s)) {
                    prefs.edit().putBoolean("windowHook", true).commit();
                    FridaBridge bridge = new FridaBridge(getContext(), peer.server.getLocalPort());
                    try {
                        bridge.setWindowHook(false);
                        assertFalse("A late window session must refuse a successful disable", lateSession);
                    } catch (java.io.IOException expected) {
                        assertTrue("A clean inactive window worker should disable normally", lateSession);
                        assertEquals(UiText.get(R.string.window_restore_unconfirmed), expected.getMessage());
                    }
                    assertTrue(peer.stopped);
                    assertTrue(peer.windowSettingsCleared);
                    assertFalse(peer.sessionCleared[1]);
                    assertFalse(prefs.getBoolean("windowHook", true));
                }
            }
        } finally {
            SharedPreferences.Editor edit = prefs.edit();
            if (previous == null) edit.remove("windowHook");
            else edit.putBoolean("windowHook", (Boolean) previous);
            edit.commit();
        }
    }
    public void testRepeatedHealthyWindowEnableKeepsTheExistingAgentAndLogs() throws Exception {
        checkWindowEnable(true, false);
    }
    public void testFreshWindowEnableStopsBeforeStagingAndRejectsALateSession() throws Exception {
        checkWindowEnable(false, false);
        checkWindowEnable(false, true);
    }

    private void checkWindowEnable(boolean healthy, boolean lateSession) throws Exception {
        SharedPreferences prefs = getContext().getSharedPreferences("bridge", 0);
        Map<String, Object> saved = new HashMap<>();
        for (String key : new String[]{"configured", "windowHook", "updatePauseToken"}) saved.put(key, prefs.getAll().get(key));
        Scenario s = new Scenario(true, true);
        s.sessions[1] = healthy ? "current" : "absent";
        s.ready[1] = healthy;
        s.allowWindowRestage = !healthy;
        if (lateSession) s.afterStop[1] = "current";
        try (Peer peer = new Peer(s)) {
            prefs.edit().putBoolean("configured", true).putBoolean("windowHook", healthy).remove("updatePauseToken").commit();
            FridaBridge bridge = new FridaBridge(getContext(), peer.server.getLocalPort());
            try {
                bridge.setWindowHook(true);
                assertFalse("A late session must refuse fresh staging", lateSession);
                if (healthy) bridge.setWindowHook(true);
            } catch (java.io.IOException expected) {
                assertTrue("A healthy or clean window worker must enable normally", lateSession);
                assertEquals(UiText.get(R.string.window_restore_unconfirmed), expected.getMessage());
            }
            assertEquals(!healthy, peer.stopped);
            assertEquals(!healthy && !lateSession, peer.windowStaged);
            assertEquals(!healthy && !lateSession, peer.windowStarted);
            assertEquals(!healthy && !lateSession, peer.windowSettingsCleared);
            assertEquals(healthy ? 2 : lateSession ? 0 : 1, peer.windowProfilesPublished);
            assertEquals(!lateSession, prefs.getBoolean("windowHook", false));
        } finally {
            SharedPreferences.Editor edit = prefs.edit();
            for (Map.Entry<String, Object> entry : saved.entrySet()) {
                if (entry.getValue() == null) edit.remove(entry.getKey());
                else if (entry.getValue() instanceof Boolean value) edit.putBoolean(entry.getKey(), value);
                else edit.putString(entry.getKey(), (String) entry.getValue());
            }
            edit.commit();
        }
    }

    private static final class Scenario {
        final boolean[] receipt, restorable, ready = {true, true}, replaceBeforeClear = {false, false};
        final String[] sessions = {"absent", "absent"}, afterStop = {null, null};
        boolean allowWindowRestage;
        Scenario(boolean icons, boolean windows) { receipt = new boolean[]{icons, windows}; restorable = new boolean[]{icons, windows}; }
    }

    private void check(Scenario s) throws Exception {
        SharedPreferences prefs = getContext().getSharedPreferences("bridge", 0);
        Map<String, Object> saved = new HashMap<>();
        for (String key : new String[]{"health", "configured", "stopPending"}) saved.put(key, prefs.getAll().get(key));
        try (Peer peer = new Peer(s)) {
            prefs.edit().putBoolean("configured", true).commit();
            FridaBridge bridge = new FridaBridge(getContext(), peer.server.getLocalPort());
            try {
                bridge.disconnect();
                assertTrue("Unconfirmed restoration must not be reported as success", s.restorable[0] && s.restorable[1]);
            } catch (ProductionRootAccess.Refused refused) {
                assertFalse("Confirmed restoration was refused", s.restorable[0] && s.restorable[1]);
                String expected = UiText.get(s.restorable[1] ? R.string.panel_restore_unconfirmed : R.string.window_restore_unconfirmed);
                assertEquals(expected, refused.getMessage());
                assertEquals(expected, FridaBridge.lastHealth(getContext()));
            }
            assertTrue("Owned workers must still be stopped", peer.stopped);
            assertTrue("Published configuration must still be cleared", peer.cleared);
            assertFalse(prefs.getBoolean("configured", true));
            assertFalse(prefs.getBoolean("stopPending", true));
            for (int scope = 0; scope < 2; scope++) {
                boolean expected = !s.sessions[scope].equals("absent") && !s.sessions[scope].equals("uncertain")
                    && (s.ready[scope] ? s.receipt[scope] : s.sessions[scope].equals("changed")) && !s.replaceBeforeClear[scope];
                assertEquals("Only the acknowledged or proven changed session may clear", expected, peer.sessionCleared[scope]);
            }
        } finally {
            SharedPreferences.Editor edit = prefs.edit();
            for (Map.Entry<String, Object> entry : saved.entrySet()) {
                if (entry.getValue() == null) edit.remove(entry.getKey());
                else if (entry.getValue() instanceof Boolean value) edit.putBoolean(entry.getKey(), value);
                else edit.putString(entry.getKey(), (String) entry.getValue());
            }
            edit.commit();
        }
    }

    private static final class Peer implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final boolean[] acknowledged = {false, false}, sessionCleared = {false, false};
        volatile boolean closed, stopped, cleared, windowSettingsCleared, windowStaged, windowStarted;
        volatile int windowProfilesPublished;
        final Thread worker;

        Peer(Scenario scenario) throws Exception {
            String[] sessions = scenario.sessions.clone(), proofs = {"a".repeat(64), "b".repeat(64)};
            worker = new Thread(() -> {
                try {
                    while (!closed) try (Socket socket = server.accept()) {
                        socket.setSoTimeout(3000);
                        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        Matcher parts = Pattern.compile("'(OVC_[0-9a-f]+)' '([0-9a-f]+)'").matcher(input.readLine());
                        if (!parts.find()) throw new AssertionError("Missing transport handshake");
                        String marker = parts.group(1) + parts.group(2);
                        socket.getOutputStream().write((marker + "_START\n").getBytes(StandardCharsets.UTF_8));
                        socket.getOutputStream().flush();
                        StringBuilder body = new StringBuilder();
                        String line;
                        while ((line = input.readLine()) != null && !line.contains("_END:%s")) body.append(line).append('\n');
                        String script = body.toString(), answer;
                        if (script.equals("(\nid -u\n")) answer = "0";
                        else if (script.contains("--visual-session-state")) {
                            int scope = script.contains("--visual-session-state window") ? 1 : 0;
                            answer = sessions[scope];
                            if (answer.equals("current") || answer.equals("changed")) answer += ":" + proofs[scope];
                        } else if (script.contains("--visual-session-clear-")) {
                            int scope = script.contains(" window ") ? 1 : 0;
                            boolean restored = script.contains("--visual-session-clear-restored");
                            if (restored && !acknowledged[scope]) throw new AssertionError("Journal cleared before original acknowledgment");
                            if (scenario.replaceBeforeClear[scope]) proofs[scope] = "c".repeat(64);
                            if (!script.contains("'" + proofs[scope] + "'") || !restored && !sessions[scope].equals("changed")) answer = "unconfirmed";
                            else { sessions[scope] = "absent"; sessionCleared[scope] = true; answer = "cleared"; }
                        } else if (script.contains("OVC_PANEL_ICONS_READY")) answer = scenario.ready[0] ? "ready" : "";
                        else if (script.contains("OVC_WINDOW_READY")) answer = scenario.ready[1] ? "ready" : "";
                        else if (script.contains("OVC_PANEL_ICONS_RESTORED")) { acknowledged[0] = scenario.receipt[0]; answer = acknowledged[0] ? "restored" : "unconfirmed"; }
                        else if (script.contains("OVC_WINDOW_RESULT")) {
                            Matcher receipt = Pattern.compile("OVC_WINDOW_RESULT [a-f0-9]{32} ").matcher(script);
                            if (!receipt.find()) throw new AssertionError("Missing window receipt token");
                            acknowledged[1] = scenario.receipt[1];
                            answer = receipt.group() + "{\"status\":\"" + (acknowledged[1] ? "applied" : "failed") + "\"}";
                        } else if (script.contains("ovc_busy")) {
                            stopped = true; answer = "";
                            for (int scope = 0; scope < 2; scope++) if (scenario.afterStop[scope] != null) {
                                sessions[scope] = scenario.afterStop[scope]; proofs[scope] = "d".repeat(64);
                            }
                        } else if (script.contains("settings delete global org.openvoyah.controls.panel")) { cleared = true; answer = ""; }
                        else if (script.contains("settings delete global org.openvoyah.controls.windowRequest")) { windowSettingsCleared = true; answer = ""; }
                        else if (script.contains("settings put global org.openvoyah.controls.windowRequest")) answer = "";
                        else if (script.contains("settings put global org.openvoyah.controls.windowProfiles")) { windowProfilesPublished++; answer = ""; }
                        else if (script.contains("nohup /system/bin/sh") && script.contains("/supervise.sh window")) {
                            if (!scenario.allowWindowRestage || !stopped) throw new AssertionError("Fresh worker started before positive shutdown");
                            windowStarted = true; answer = "";
                        } else if (script.contains("mkdir -p ") || script.contains("OVC_ASSET") || script.contains(".new")
                                || script.contains("/window/ready") || script.contains("/window/enabled")) {
                            if (!scenario.allowWindowRestage || !stopped) throw new AssertionError("Healthy worker artifacts were restaged or staging preceded shutdown");
                            windowStaged = true; answer = "";
                        }
                        else throw new AssertionError("Unexpected fixture command");
                        socket.getOutputStream().write((answer + "\n" + marker + "_END:0\n").getBytes(StandardCharsets.UTF_8));
                        socket.getOutputStream().flush();
                    }
                } catch (SocketException error) { if (!closed) failure.set(error); }
                catch (Throwable error) { failure.set(error); }
            }, "disconnect-receipt-fixture");
            worker.start();
        }
        @Override public void close() throws Exception {
            closed = true; server.close(); worker.join(3000);
            if (worker.isAlive()) throw new AssertionError("Fixture did not stop");
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }
}
