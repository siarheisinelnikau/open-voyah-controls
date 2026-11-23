package org.openvoyah.controls.data.frida;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.openvoyah.controls.ButtonAccessibilityService;
import org.openvoyah.controls.R;
import org.openvoyah.controls.UiText;
import org.openvoyah.controls.core.PanelControls.Operation;
import org.openvoyah.controls.core.PanelControls.Result;
import org.openvoyah.controls.core.PanelControls;
import org.openvoyah.controls.data.settings.SettingsStore;

public final class FridaPanelControls implements PanelControls {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final long BUTTON_SERVICE_WAIT_MS = 5000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private final FridaBridge bridge;
    private final SettingsStore settings;

    public FridaPanelControls(Context context, SettingsStore settings) {
        this.context = context.getApplicationContext();
        this.bridge = new FridaBridge(this.context);
        this.settings = settings;
    }

    @Override public String health() { return FridaBridge.lastHealth(context); }
    @Override public boolean configured() { return FridaBridge.wasConfigured(context); }
    @Override public boolean activationPaused() { return FridaBridge.activationPaused(context); }

    @Override public void checkActivation(Consumer<Boolean> done) {
        WORKER.execute(() -> {
            boolean paused;
            try { paused = bridge.checkActivationPaused(); }
            catch (Exception ex) { paused = FridaBridge.activationPaused(context); }
            boolean answer = paused;
            main.post(() -> done.accept(answer));
        });
    }

    @Override public void run(Operation operation, Consumer<Result> done) {
        WORKER.execute(() -> {
            Result result;
            try { result = new Result(true, perform(operation)); }
            catch (Exception ex) { result = new Result(false, FridaFailureText.message(ex)); }
            Result completed = result;
            main.post(() -> done.accept(completed));
        });
    }

    private String perform(Operation operation) throws Exception {
        return switch (operation) {
            case CONNECT -> {
                bridge.connect(settings.load());
                yield UiText.get(R.string.ui_74) + bridge.status();
            }
            case STATUS -> bridge.status();
            case DISCONNECT -> {
                bridge.disconnect();
                yield UiText.get(R.string.ui_77);
            }
            case BUTTON_SERVICE -> {
                bridge.enableButtonService();
                yield UiText.get(awaitButtonService() ? R.string.button_service_connected : R.string.button_service_pending);
            }
            case NAVIGATOR_WIDGET -> {
                bridge.openNavigatorWidgetSettings();
                yield UiText.get(R.string.home_navigator_widget_opened);
            }
        };
    }

    /** Android binds the service in this process shortly after the setting changes. */
    private static boolean awaitButtonService() throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + BUTTON_SERVICE_WAIT_MS;
        while (!ButtonAccessibilityService.isConnected()) {
            if (SystemClock.elapsedRealtime() >= deadline) return false;
            Thread.sleep(100);
        }
        return true;
    }

    @Override public void publish(Consumer<String> error) {
        bridge.publishAsync(message -> main.post(() -> error.accept(message)));
    }
}
