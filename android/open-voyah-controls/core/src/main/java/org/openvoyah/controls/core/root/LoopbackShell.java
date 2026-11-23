package org.openvoyah.controls.core.root;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;

/** Telnet framing over loopback only. A command is dispatched once; timeout is an unknown outcome. */
public final class LoopbackShell {
    public static final int PRODUCTION_PORT = 2323;
    public static final class Failed extends IOException {
        public final int status;
        Failed(int status) { super("Root command failed with status " + status); this.status = status; }
    }
    private final int port;
    private final String key;
    public LoopbackShell(int port, String key) {
        if (port < 1 || port > 65535 || (key != null && !key.matches("[a-f0-9]{64}"))) throw new IllegalArgumentException();
        this.port = port; this.key = key;
    }
    public String execute(String script) throws IOException {
        if (script.indexOf('\0') >= 0 || script.getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024)
            throw new IOException("Root request exceeds the local limit");
        if (Thread.currentThread().isInterrupted()) throw new IOException("Root request canceled");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            Session session = new Session(socket, deadline);
            if (key != null) authenticate(session);
            String marker = "OVC_" + UUID.randomUUID().toString().replace("-", "");
            String prefix = marker.substring(0, 12), suffix = marker.substring(12);
            session.write("stty -echo 2>/dev/null; printf '\\n%s%s_START\\n' '" + prefix + "' '" + suffix + "'\n");
            session.until(marker + "_START");
            // Keep the shell alive until the client receives completion and closes the socket.
            // Immediate exit can make Telnet discard buffered PTY output during session teardown.
            session.write("(\n" + script + "\n); ovc_rc=$?; printf '\\n%s%s_END:%s\\n' '" + prefix + "' '" + suffix + "' \"$ovc_rc\"\n");
            StringBuilder output = new StringBuilder();
            while (true) {
                String line = session.line();
                if (line.startsWith(marker + "_END:")) {
                    String value = line.substring(marker.length() + 5);
                    if (!value.matches("[0-9]{1,3}")) throw new IOException("Invalid root completion frame");
                    int status = Integer.parseInt(value);
                    if (status != 0) throw new Failed(status);
                    return output.toString().trim();
                }
                output.append(line).append('\n');
            }
        }
    }
    private void authenticate(Session session) throws IOException {
        session.until("OVC_ACCESS_1");
        String nonce = UUID.randomUUID().toString().replace("-", "");
        session.write(nonce + "\n");
        String[] reply = session.line().split(" ");
        if (reply.length != 3 || !reply[0].equals("OVC_SERVER") || !reply[1].matches("[a-f0-9]{32}") ||
                !RootChannelAuth.matches(RootChannelAuth.server(key, nonce), reply[2]))
            throw new IOException("The root service did not authenticate");
        session.write(RootChannelAuth.client(key, nonce, reply[1]) + "\n");
        if (!session.line().equals("OVC_AUTHENTICATED")) throw new IOException("Root channel authentication failed");
    }
    private static final class Session {
        final Socket socket; final InputStream input; final OutputStream output; final long deadline;
        int received, negotiations;
        Session(Socket socket, long deadline) throws IOException {
            this.socket=socket; this.deadline=deadline; input=socket.getInputStream(); output=socket.getOutputStream();
        }
        long remaining() throws IOException {
            long value=deadline-System.nanoTime();
            if (value <= 0 || Thread.currentThread().isInterrupted()) throw new IOException("Root request timed out or was canceled; outcome is unknown");
            return value;
        }
        void write(String value) throws IOException {
            write(value.getBytes(StandardCharsets.UTF_8));
        }
        void write(byte[] value) throws IOException {
            FutureTask<Void> writer = new FutureTask<>(() -> { output.write(value); output.flush(); return null; });
            Thread thread = new Thread(writer,"ovc-channel-write"); thread.setDaemon(true); thread.start();
            try { writer.get(remaining(),TimeUnit.NANOSECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("Root request canceled",error); }
            catch (ExecutionException | TimeoutException error) { throw new IOException("Root request did not complete; outcome is unknown",error); }
            finally { writer.cancel(true); }
        }
        int raw() throws IOException {
            socket.setSoTimeout((int)Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining())));
            int value=input.read();
            if (value < 0) throw new EOFException("Root channel closed before completion");
            if (++received > 131072) throw new IOException("Root response exceeds the local limit");
            return value;
        }
        String line() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (true) {
                int value=raw();
                if (value==255) {
                    if (++negotiations > 32) throw new IOException("Too many Telnet negotiations");
                    int verb=raw();
                    if (verb==255) bytes.write(255);
                    else if (verb>=251 && verb<=254) {
                        int option=raw();
                        if (verb==251 || verb==253) write(new byte[]{(byte)255,(byte)(verb==251?254:252),(byte)option});
                    } else if (verb==250) {
                        int previous=0, count=0;
                        while (true) { int next=raw(); if (previous==255 && next==240) break; previous=next; if (++count>512) throw new IOException("Oversized Telnet negotiation"); }
                    }
                    continue;
                }
                if (value=='\n') return bytes.toString(StandardCharsets.UTF_8.name()).replace("\r", "");
                bytes.write(value);
            }
        }
        void until(String expected) throws IOException { while (!line().equals(expected)) { /* bounded by deadline and response size */ } }
    }
}
