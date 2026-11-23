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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openvoyah.controls.data.frida.FridaBridge;
import org.openvoyah.controls.data.frida.FridaTransport;
import org.openvoyah.controls.data.settings.SettingsStore;
import org.openvoyah.controls.platform.boot.RecoveryJobs;

/** Bridge orchestration over real loopback framing. The peer executes no root commands. */
public final class FridaRetryBudgetTest extends AndroidTestCase {
    public void testExplicitConnectRenewsBudgetOnlyAfterRestoreAndFreshQuiescence() throws Exception {
        fixture((bridge, peer) -> {
            bridge.connect(new SettingsStore.State());
            assertEquals(0, peer.failures);
            assertTrue(peer.startRequested);
            assertEquals(Arrays.asList("icons", "windows", "stop", "quiet", "reset", "start"), peer.events);
        });
    }

    public void testAutomaticReconnectPreservesThePerBootBudget() throws Exception {
        fixture((bridge, peer) -> {
            bridge.reconnectAfterBoot();
            assertEquals(5, peer.failures);
            assertEquals(0, peer.resetRequests);
            assertTrue(peer.startRequested);
        });
    }

    public void testUnconfirmedPanelRestorationKeepsQuarantine() throws Exception {
        fixture((bridge, peer) -> { peer.icons = false; assertBlocked(bridge, peer); });
    }

    public void testUnconfirmedWindowRestorationKeepsQuarantine() throws Exception {
        fixture((bridge, peer) -> { peer.windows = false; assertBlocked(bridge, peer); });
    }

    public void testUnknownShutdownKeepsQuarantine() throws Exception {
        fixture((bridge, peer) -> { peer.stopStatus = 78; assertBlocked(bridge, peer); });
    }

    public void testFreshOrphanScanFailureKeepsQuarantine() throws Exception {
        fixture((bridge, peer) -> { peer.quiescenceStatus = 78; assertBlocked(bridge, peer); });
    }

    public void testRecoveryBlockBeforeResetKeepsQuarantine() throws Exception {
        fixture((bridge, peer) -> { peer.resetStatus = 78; assertBlocked(bridge, peer); });
    }

    private void assertBlocked(FridaBridge bridge, Peer peer) throws Exception {
        try {
            bridge.connect(new SettingsStore.State());
            fail("An unconfirmed shutdown or recovery block must refuse a fresh connection");
        } catch (java.io.IOException expected) { }
        assertEquals(5, peer.failures);
        assertFalse(peer.startRequested);
    }

    private interface Check { void run(FridaBridge bridge, Peer peer) throws Exception; }

    private void fixture(Check check) throws Exception {
        assertTrue(FridaTransport.canUseFixture(getContext()));
        SharedPreferences bridge = getContext().getSharedPreferences("bridge", 0);
        SharedPreferences localization = getContext().getSharedPreferences("localization", 0);
        Map<String, ?> previousBridge = new HashMap<>(bridge.getAll());
        Map<String, ?> previousLocalization = new HashMap<>(localization.getAll());
        try (Peer peer = new Peer()) {
            bridge.edit().clear().putBoolean("configured", true).putBoolean("windowHook", false).commit();
            localization.edit().putString("dictionary", "{\"schema\":1,\"enabled\":false,\"products\":[],\"packages\":{}}").commit();
            check.run(new FridaBridge(getContext(), peer.server.getLocalPort()), peer);
        } finally {
            RecoveryJobs.cancel(getContext());
            restore(bridge, previousBridge);
            restore(localization, previousLocalization);
        }
    }

    private static void restore(SharedPreferences preferences, Map<String, ?> values) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String text) editor.putString(entry.getKey(), text);
            else if (value instanceof Boolean flag) editor.putBoolean(entry.getKey(), flag);
            else if (value instanceof Integer number) editor.putInt(entry.getKey(), number);
            else throw new AssertionError("Unexpected fixture preference type");
        }
        if (!editor.commit()) throw new AssertionError("Could not restore fixture preferences");
    }

    private static final class Peer implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        volatile boolean closed, icons = true, windows = true, startRequested;
        volatile int stopStatus, quiescenceStatus, resetStatus, resetRequests, failures = 5;
        final Thread worker;

        Peer() throws Exception {
            worker = new Thread(() -> {
                try {
                    while (!closed) try (Socket socket = server.accept()) {
                        socket.setSoTimeout(8000);
                        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        Matcher parts = Pattern.compile("'(OVC_[0-9a-f]+)' '([0-9a-f]+)'").matcher(input.readLine());
                        if (!parts.find()) throw new AssertionError("Missing transport handshake");
                        String marker = parts.group(1) + parts.group(2);
                        send(socket, marker + "_START");
                        StringBuilder body = new StringBuilder();
                        String line;
                        while ((line = input.readLine()) != null && !line.contains("_END:%s")) body.append(line).append('\n');
                        String script = body.toString();
                        String answer = "";
                        int status = 0;
                        if (script.equals("(\nid -u\n")) answer = "0";
                        else if (script.contains(" --version")) answer = "17.26.2";
                        else if (script.contains("--visual-session-state")) answer = "absent";
                        else if (script.contains("--visual-session-clear-")) answer = "cleared";
                        else if (script.contains("OVC_PANEL_ICONS_READY") || script.contains("OVC_WINDOW_READY")) answer = "ready";
                        else if (script.contains("OVC_PANEL_ICONS_RESTORED")) {
                            answer = icons ? "restored" : "unconfirmed";
                            if (icons) events.add("icons");
                        } else if (script.contains("OVC_WINDOW_RESULT")) {
                            Matcher token = Pattern.compile("OVC_WINDOW_RESULT [a-f0-9]{32} ").matcher(script);
                            if (!token.find()) throw new AssertionError("Missing window receipt");
                            answer = token.group() + "{\"status\":\"" + (windows ? "applied" : "failed") + "\"}";
                            if (windows) events.add("windows");
                        } else if (script.contains("ovc_busy")) {
                            status = stopStatus;
                            if (status == 0) events.add("stop");
                        } else if (script.contains("if ovc_scoped_process_live; then exit 78; fi")) {
                            status = quiescenceStatus;
                            if (status == 0) events.add("quiet");
                        } else if (script.contains("/failure.boot") || script.contains("/failure.count")) {
                            resetRequests++;
                            if (!events.equals(Arrays.asList("icons", "windows", "stop", "quiet"))) {
                                throw new AssertionError("Retry evidence was touched before positive restoration and quiescence");
                            }
                            status = resetStatus;
                            if (status == 0) { failures = 0; events.add("reset"); }
                        } else if (script.contains("touch /data/local/tmp/open-voyah-controls/enabled")) {
                            startRequested = true;
                            events.add("start");
                        } else if (!(script.contains("mkdir -p ") || script.contains("settings put global ") ||
                                script.contains("settings delete global ") || script.contains("OVC_ASSET") ||
                                script.contains(".new") || script.contains("chmod 700 "))) {
                            throw new AssertionError("Unexpected fixture command");
                        }
                        send(socket, answer + "\n" + marker + "_END:" + status);
                    }
                } catch (SocketException error) { if (!closed) failure.set(error); }
                catch (Throwable error) { failure.set(error); }
            }, "retry-budget-bridge-fixture");
            worker.start();
        }

        private static void send(Socket socket, String text) throws Exception {
            socket.getOutputStream().write((text + "\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        }

        @Override public void close() throws Exception {
            closed = true;
            server.close();
            worker.join(5000);
            if (worker.isAlive()) throw new AssertionError("Fixture did not stop");
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }
}
