package org.openvoyah.controls;

import android.test.AndroidTestCase;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Actual loopback sockets: terminal echo must be disabled before transmitting a bundle. */
public class FridaTransportTest extends AndroidTestCase {
    public void testLargeHeredocWaitsForShellHandshake() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            String script = "cat <<'DATA'\n" + ("A".repeat(768) + "\n").repeat(420) + "DATA\n";
            Thread peer = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String first = input.readLine();
                    assertTrue(first.startsWith("stty -echo"));
                    Matcher match = Pattern.compile("'(OVC_[0-9a-f]+)' '([0-9a-f]+)'").matcher(first);
                    assertTrue(match.find()); String marker = match.group(1) + match.group(2);
                    socket.setSoTimeout(100);
                    try { input.read(); fail("Payload arrived before echo was disabled"); }
                    catch (java.net.SocketTimeoutException expected) { }
                    socket.setSoTimeout(2000);
                    socket.getOutputStream().write((first + "\r\n" + marker + "_START\r\n").getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                    StringBuilder actual = new StringBuilder(); String line;
                    while ((line = input.readLine()) != null) {
                        if (line.contains("_END:%s")) break;
                        actual.append(line).append('\n');
                    }
                    assertEquals("( " + script, actual.toString());
                    socket.getOutputStream().write(("uploaded\r\n" + marker + "_END:0\r\n").getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                } catch (Throwable ex) { failure.set(ex); }
            });
            peer.start();
            java.lang.reflect.Method command = FridaBridge.class.getDeclaredMethod("command", String.class);
            command.setAccessible(true);
            assertEquals("uploaded", command.invoke(new FridaBridge(getContext(), server.getLocalPort()), script));
            peer.join(3000); assertFalse(peer.isAlive());
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }
}
