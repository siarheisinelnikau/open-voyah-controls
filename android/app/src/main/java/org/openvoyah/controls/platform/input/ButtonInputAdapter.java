package org.openvoyah.controls.platform.input;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.openvoyah.controls.R;
import org.openvoyah.controls.UiText;
import org.openvoyah.controls.core.Binding;
import org.openvoyah.controls.core.ButtonEngine;

/** Maps Android key events and key learning to the portable timing engine. */
public final class ButtonInputAdapter implements AutoCloseable {
    public interface Capture { void captured(int keyCode, String descriptor, String deviceName); void expired(); }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ButtonEngine engine;
    private final Map<Long, Long> captureOwners = new HashMap<>();
    private Capture capture;
    private long captureGeneration;
    private boolean hasCaptureDown;
    private Runnable captureTimeout;
    private String captureDescriptor;
    private String captureDeviceName;

    public ButtonInputAdapter(ButtonEngine.Sink sink) {
        engine = new ButtonEngine(new ButtonEngine.Scheduler() {
            @Override public Object after(long delay, Runnable callback) { handler.postDelayed(callback, delay); return callback; }
            @Override public void cancel(Object token) { handler.removeCallbacks((Runnable) token); }
        }, sink, SystemClock::uptimeMillis);
    }

    public void configure(List<Binding> bindings, boolean enabled, int hold, int debounce) {
        engine.configure(bindings, enabled, hold, debounce);
    }

    public void beginCapture(Capture callback) {
        cancelCapture();
        engine.cancelPending();
        capture = callback;
        long generation = captureGeneration;
        captureTimeout = () -> {
            if (captureGeneration != generation) return;
            Capture current = capture;
            cancelCapture();
            if (current != null) current.expired();
        };
        handler.postDelayed(captureTimeout, 30_000);
    }

    public void cancelCapture() {
        captureGeneration++;
        capture = null;
        hasCaptureDown = false;
        captureDescriptor = null;
        captureDeviceName = null;
        // Keep swallowed edges separate from the next learning request.
        if (captureTimeout != null) handler.removeCallbacks(captureTimeout);
        captureTimeout = null;
    }

    public boolean handle(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_UNKNOWN) return false;
        long key = ((long) event.getDeviceId() << 32) | (code & 0xffffffffL);
        Long owner = captureOwners.get(key);
        if (owner != null) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                captureOwners.remove(key);
                if (owner.longValue() == captureGeneration) {
                    Capture callback = capture;
                    String capturedDevice = captureDescriptor, deviceName = captureDeviceName;
                    cancelCapture();
                    if (callback != null && !event.isCanceled()) callback.captured(code, capturedDevice, deviceName);
                }
            }
            return true;
        }
        InputDevice device = event.getDevice();
        String descriptor = device == null ? "id:" + event.getDeviceId() : device.getDescriptor();
        if (capture != null && !hasCaptureDown && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            captureOwners.put(key, captureGeneration);
            hasCaptureDown = true;
            captureDescriptor = descriptor;
            captureDeviceName = device == null ? UiText.get(R.string.ui_2) + event.getDeviceId() : device.getName();
            return true;
        }
        long now = SystemClock.uptimeMillis();
        if (event.getAction() == KeyEvent.ACTION_UP) return engine.up(descriptor, code, event.isCanceled(), now);
        if (capture != null) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) return engine.down(descriptor, code, event.getRepeatCount(), now);
        return false;
    }

    public void interrupt() {
        engine.cancelPending();
        cancelCapture();
    }

    @Override public void close() {
        cancelCapture();
        captureOwners.clear();
        engine.close();
        handler.removeCallbacksAndMessages(null);
    }
}
