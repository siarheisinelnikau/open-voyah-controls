package org.openvoyah.controls.core.root;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Fixed-length, direction-separated challenge responses. The installation key never crosses the socket. */
public final class RootChannelAuth {
    private RootChannelAuth() { }
    public static String server(String key, String clientNonce) { return digest("OVC_ROOT_SERVER_V1", key, clientNonce, null); }
    public static String client(String key, String clientNonce, String serverNonce) { return digest("OVC_ROOT_CLIENT_V1", key, clientNonce, serverNonce); }
    public static boolean matches(String expected, String actual) {
        return actual != null && actual.matches("[a-f0-9]{64}") && MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }
    private static String digest(String role, String key, String client, String server) {
        if (!key.matches("[a-f0-9]{64}") || !client.matches("[a-f0-9]{32}") ||
                (server != null && !server.matches("[a-f0-9]{32}"))) throw new IllegalArgumentException("Invalid root channel identity");
        try {
            String message = role + ":" + key + ":" + client + (server == null ? "" : ":" + server);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(message.getBytes(StandardCharsets.US_ASCII));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
