package org.openvoyah.controls.data.frida;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import java.io.IOException;
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
        return new org.openvoyah.controls.core.root.LoopbackShell(port, null).execute(script);
    }
}
