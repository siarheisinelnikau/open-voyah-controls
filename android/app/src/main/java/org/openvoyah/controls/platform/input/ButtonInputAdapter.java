package org.openvoyah.controls.platform.input;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import java.util.List;
import org.openvoyah.controls.R;
import org.openvoyah.controls.UiText;
import org.openvoyah.controls.core.Binding;
import org.openvoyah.controls.core.ButtonEngine;

/** Maps Android key events and key learning to the portable timing engine. */
public final class ButtonInputAdapter implements AutoCloseable {
    public interface Capture { void captured(int keyCode, String descriptor, String deviceName); void expired(); }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ButtonEngine engine;
    private Capture capture;
    private KeyEvent captureDown;
    private Runnable captureTimeout;
    private String captureDescriptor;
    private String captureDeviceName;

    public ButtonInputAdapter(ButtonEngine.Sink sink) {
        engine = new ButtonEngine(new ButtonEngine.Scheduler() {
            @Override public Object after(long delay, Runnable callback) { handler.postDelayed(callback, delay); return callback; }
            @Override public void cancel(Object token) { handler.removeCallbacks((Runnable) token); }
        }, sink);
    }

    public void configure(List<Binding> bindings, boolean enabled, int hold, int debounce) {
        engine.configure(bindings, enabled, hold, debounce);
    }

    public void beginCapture(Capture callback) {
        cancelCapture();
        engine.cancelPending();
        capture = callback;
        captureTimeout = () -> {
            Capture current = capture;
            cancelCapture();
            if (current != null) current.expired();
        };
        handler.postDelayed(captureTimeout, 30_000);
    }

    public void cancelCapture() {
        capture = null;
        // Retain a swallowed DOWN until its matching UP arrives.
        if (captureTimeout != null) handler.removeCallbacks(captureTimeout);
        captureTimeout = null;
    }

    public boolean handle(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_UNKNOWN) return false;
        InputDevice device = event.getDevice();
        String descriptor = device == null ? "id:" + event.getDeviceId() : device.getDescriptor();
        if (captureDown != null && captureDown.getDeviceId() == event.getDeviceId() && captureDown.getKeyCode() == code) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                Capture callback = capture;
                String capturedDevice = captureDescriptor, deviceName = captureDeviceName;
                captureDown = null;
                cancelCapture();
                if (callback != null && !event.isCanceled()) callback.captured(code, capturedDevice, deviceName);
            }
            return true;
        }
        if (capture != null && captureDown == null && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            captureDown = new KeyEvent(event);
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
        captureDown = null;
        engine.close();
        handler.removeCallbacksAndMessages(null);
    }
}
