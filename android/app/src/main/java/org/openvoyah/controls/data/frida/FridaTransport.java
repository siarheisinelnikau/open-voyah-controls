package org.openvoyah.controls.data.frida;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.openvoyah.controls.BuildConfig;

/** Loopback framing for explicitly selected debuggable Android emulator fixtures. */
public final class FridaTransport {
    private final int port;
    private final Supplier<String> interrupted;
    private final Supplier<String> invalidResponse;

    private FridaTransport(int port, Supplier<String> interrupted, Supplier<String> invalidResponse) {
        this.port = port;
        this.interrupted = interrupted;
        this.invalidResponse = invalidResponse;
    }

    public static boolean canUseFixture(Context context) {
        return BuildConfig.DEBUG && (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
            && (Build.VERSION.SDK_INT == 28 || Build.VERSION.SDK_INT == 30)
            && ("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE))
            && Build.PRODUCT.startsWith("sdk_");
    }

    public static FridaTransport emulatorFixture(Context context, int port, Supplier<String> interrupted,
            Supplier<String> invalidResponse) {
        if (!canUseFixture(context) || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Loopback transport requires a supported debuggable emulator fixture");
        }
        return new FridaTransport(port, interrupted, invalidResponse);
    }

    public String execute(String script) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException(interrupted.get());
        String marker = "OVC_" + UUID.randomUUID().toString().replace("-", "");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000); socket.setSoTimeout(8000);
            InputStream input = socket.getInputStream();
            // Token literals are split so terminal echo cannot masquerade as command completion.
            String prefix = marker.substring(0, 12), suffix = marker.substring(12);
            String handshake = "stty -echo 2>/dev/null; printf '\\n%s%s_START\\n' '" + prefix + "' '" + suffix + "'\n";
            String payload = "( " + script + " ); ovc_rc=$?; printf '\\n%s%s_END:%s\\n' '" + prefix + "' '" + suffix + "' \"$ovc_rc\"\n";
            socket.getOutputStream().write(handshake.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush();
            boolean sent = false;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
            while (buffer.size() < 131072 && System.nanoTime() < deadline) {
                int value = input.read(); if (value < 0) break;
                // Refuse Telnet options; no terminal emulation or remote destination is needed.
                if (value == 255) {
                    int verb = input.read();
                    if (verb == 255) buffer.write(255);
                    else if (verb >= 251 && verb <= 254) {
                        int option = input.read(); if (option < 0) throw new IOException("Telnet negotiation ended");
                        if (verb == 251 || verb == 253) { socket.getOutputStream().write(new byte[]{(byte)255, (byte)(verb == 251 ? 254 : 252), (byte)option}); socket.getOutputStream().flush(); }
                    }
                    continue;
                }
                buffer.write(value);
                if (value != '\n') continue;
                String text = buffer.toString(StandardCharsets.UTF_8.name()).replace("\r", "");
                int start = text.indexOf(marker + "_START\n"), end = text.indexOf(marker + "_END:");
                if (start >= 0 && !sent) {
                    // Wait until echo is actually disabled before a large heredoc.
                    // Putting stty in the same compound command is too late: the shell
                    // reads the entire heredoc before executing that command.
                    socket.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush(); sent = true;
                }
                if (start >= 0 && end > start) {
                    int lineEnd = text.indexOf('\n', end);
                    if (lineEnd < 0) continue;
                    String output = text.substring(start + marker.length() + 7, end).trim();
                    String rc = text.substring(end + marker.length() + 5, lineEnd).trim();
                    if (!rc.equals("0")) throw new IOException("Shell: " + rc + ". " + output);
                    return output;
                }
            }
            throw new IOException(invalidResponse.get());
        }
    }
}
