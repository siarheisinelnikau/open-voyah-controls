package org.openvoyah.controls.data.frida;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openvoyah.controls.R;
import org.openvoyah.controls.UiText;

/** Keeps local transport details out of consumer status without replacing verification or storage reasons. */
public final class FridaFailureText {
    // Only the Android 9 loopback-connect format recorded by older versions. Preserve its operation context.
    private static final Pattern RECORDED_CONNECT = Pattern.compile(
        "failed to connect to /127\\.0\\.0\\.1 \\(port [0-9]{1,5}\\) from /127\\.0\\.0\\.1 \\(port [0-9]{1,5}\\) " +
        "after [0-9]+ms: isConnected failed: (ECONNREFUSED|ETIMEDOUT|EHOSTUNREACH|ENETUNREACH) \\([^\\r\\n)]*\\)");

    private FridaFailureText() { }

    public static String message(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && chain.size() < 16 && seen.add(current); current = current.getCause()) {
            chain.add(current);
        }
        // An explicitly localized refusal remains authoritative even when its underlying cause was transport IO.
        for (Throwable current : chain) {
            if (current instanceof ProductionRootAccess.Refused) return originalOrFallback(current.getMessage());
        }
        for (Throwable current : chain) {
            if (current instanceof SocketTimeoutException || current instanceof TimeoutException ||
                current instanceof IOException && "Root request timed out or was canceled; outcome is unknown".equals(current.getMessage())) {
                return UiText.get(R.string.frida_local_timeout);
            }
        }
        for (Throwable current : chain) {
            if (current instanceof InterruptedException ||
                current instanceof IOException && ("Root request canceled".equals(current.getMessage()) ||
                    "Root request did not complete; outcome is unknown".equals(current.getMessage())) ||
                current instanceof EOFException && "Root channel closed before completion".equals(current.getMessage())) {
                return UiText.get(R.string.frida_local_interrupted);
            }
        }
        for (Throwable current : chain) {
            if (current instanceof ConnectException || current instanceof NoRouteToHostException || current instanceof UnknownHostException) {
                return UiText.get(R.string.frida_local_unavailable);
            }
        }
        for (Throwable current : chain) {
            if (current instanceof SocketException) return UiText.get(R.string.frida_local_interrupted);
        }
        return recordedStatus(originalOrFallback(failure == null ? null : failure.getMessage()));
    }

    /** Old health text has no Throwable; replace only its exact known socket segment, retaining stop/retry context. */
    public static String recordedStatus(String status) {
        if (status == null) return UiText.get(R.string.frida_operation_failed);
        Matcher match = RECORDED_CONNECT.matcher(status);
        StringBuffer result = new StringBuffer();
        while (match.find()) {
            String replacement = UiText.get("ETIMEDOUT".equals(match.group(1))
                ? R.string.frida_local_timeout : R.string.frida_local_unavailable);
            match.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        match.appendTail(result);
        return result.toString();
    }

    private static String originalOrFallback(String message) {
        return message == null || message.trim().isEmpty() ? UiText.get(R.string.frida_operation_failed) : message;
    }
}
