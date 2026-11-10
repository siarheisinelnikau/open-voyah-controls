package org.openvoyah.controls.data.frida;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.openvoyah.controls.R;
import org.openvoyah.controls.UiText;
import org.openvoyah.controls.core.display.NavigationFeed;

public final class FridaNavigationFeed implements NavigationFeed {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private final FridaBridge bridge;

    public FridaNavigationFeed(Context context) {
        this.context = context.getApplicationContext();
        bridge = new FridaBridge(this.context);
    }

    @Override public boolean enabled() { return FridaBridge.navigationHookEnabled(context); }

    @Override public void setEnabled(boolean enabled, Consumer<String> done) {
        WORKER.execute(() -> {
            String result;
            try {
                bridge.setNavigationHook(enabled);
                result = UiText.get(enabled ? R.string.navigation_feed_on : R.string.navigation_feed_off);
            } catch (Exception ex) { result = ex.getMessage(); }
            String completed = result;
            main.post(() -> done.accept(completed));
        });
    }
}
