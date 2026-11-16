package org.openvoyah.controls.platform.input;

import android.test.InstrumentationTestCase;
import android.view.InputDevice;
import android.view.KeyEvent;
import java.util.ArrayList;
import java.util.List;

public final class ButtonInputAdapterTest extends InstrumentationTestCase {
    private static final int FIRST = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
    private static final int SECOND = KeyEvent.KEYCODE_MEDIA_NEXT;

    private static final class Capture implements ButtonInputAdapter.Capture {
        final List<Integer> keys = new ArrayList<>();
        @Override public void captured(int code, String descriptor, String deviceName) { keys.add(code); }
        @Override public void expired() { throw new AssertionError("Unexpected capture timeout"); }
    }

    private static ButtonInputAdapter adapter() {
        return new ButtonInputAdapter(action -> { throw new AssertionError("Learning must not execute an action"); });
    }

    private static KeyEvent key(int action, int code) { return key(action, code, 0, 0); }
    private static KeyEvent key(int action, int code, int repeat, int flags) {
        return new KeyEvent(100, 200, action, code, repeat, 0, 41, 0, flags, InputDevice.SOURCE_KEYBOARD);
    }

    public void testOldReleaseDoesNotCompleteOrCancelReplacementCapture() throws Throwable {
        runTestOnUiThread(() -> {
            try (ButtonInputAdapter input = adapter()) {
                Capture first = new Capture(), second = new Capture();
                input.beginCapture(first);
                assertTrue(input.handle(key(KeyEvent.ACTION_DOWN, FIRST)));
                input.cancelCapture(); input.beginCapture(second);
                assertTrue(input.handle(key(KeyEvent.ACTION_UP, FIRST)));
                assertTrue(first.keys.isEmpty()); assertTrue(second.keys.isEmpty());
                assertTrue(input.handle(key(KeyEvent.ACTION_DOWN, SECOND)));
                assertTrue(input.handle(key(KeyEvent.ACTION_UP, SECOND)));
                assertEquals(List.of(SECOND), second.keys);
            }
        });
    }

    public void testReplacementOwnsItsKeyWhileAnOldReleaseIsStillPending() throws Throwable {
        runTestOnUiThread(() -> {
            try (ButtonInputAdapter input = adapter()) {
                Capture first = new Capture(), second = new Capture();
                input.beginCapture(first);
                assertTrue(input.handle(key(KeyEvent.ACTION_DOWN, FIRST)));
                input.beginCapture(second);
                assertTrue(input.handle(key(KeyEvent.ACTION_DOWN, SECOND)));
                assertTrue(input.handle(key(KeyEvent.ACTION_UP, FIRST)));
                assertTrue(first.keys.isEmpty()); assertTrue(second.keys.isEmpty());
                assertTrue(input.handle(key(KeyEvent.ACTION_UP, SECOND)));
                assertEquals(List.of(SECOND), second.keys);
            }
        });
    }

    public void testReusingTheCallbackStillCreatesANewCaptureOwner() throws Throwable {
        runTestOnUiThread(() -> {
            try (ButtonInputAdapter input = adapter()) {
                Capture callback = new Capture();
                input.beginCapture(callback);
                assertTrue(input.handle(key(KeyEvent.ACTION_DOWN, FIRST)));
                input.cancelCapture(); input.beginCapture(callback);
                assertTrue(input.handle(key(KeyEvent.ACTION_UP, FIRST)));
                assertTrue(callback.keys.isEmpty());
                assertTrue(input.handle(key(KeyEvent.ACTION_DOWN, SECOND)));
                assertTrue(input.handle(key(KeyEvent.ACTION_UP, SECOND)));
                assertEquals(List.of(SECOND), callback.keys);
            }
        });
    }

    public void testCanceledCaptureConsumesRepeatsAndReleaseWithoutLearning() throws Throwable {
        runTestOnUiThread(() -> {
            try (ButtonInputAdapter input = adapter()) {
                Capture callback = new Capture();
                input.beginCapture(callback);
                assertTrue(input.handle(key(KeyEvent.ACTION_DOWN, FIRST)));
                input.cancelCapture();
                assertTrue(input.handle(key(KeyEvent.ACTION_DOWN, FIRST, 1, 0)));
                assertTrue(input.handle(key(KeyEvent.ACTION_UP, FIRST)));
                assertTrue(callback.keys.isEmpty());
                assertFalse(input.handle(key(KeyEvent.ACTION_DOWN, SECOND)));
                assertFalse(input.handle(key(KeyEvent.ACTION_UP, SECOND)));
            }
        });
    }

    public void testInterruptDoesNotTransferItsPendingReleaseToANewCapture() throws Throwable {
        runTestOnUiThread(() -> {
            try (ButtonInputAdapter input = adapter()) {
                Capture first = new Capture(), second = new Capture();
                input.beginCapture(first);
                assertTrue(input.handle(key(KeyEvent.ACTION_DOWN, FIRST)));
                input.interrupt(); input.beginCapture(second);
                assertTrue(input.handle(key(KeyEvent.ACTION_UP, FIRST)));
                assertTrue(second.keys.isEmpty());
                assertTrue(input.handle(key(KeyEvent.ACTION_DOWN, SECOND)));
                assertTrue(input.handle(key(KeyEvent.ACTION_UP, SECOND)));
                assertEquals(List.of(SECOND), second.keys);
                assertTrue(first.keys.isEmpty());
            }
        });
    }

    public void testCanceledKeyReleaseEndsOnlyItsCurrentCaptureWithoutLearning() throws Throwable {
        runTestOnUiThread(() -> {
            try (ButtonInputAdapter input = adapter()) {
                Capture callback = new Capture();
                input.beginCapture(callback);
                assertTrue(input.handle(key(KeyEvent.ACTION_DOWN, FIRST)));
                assertTrue(input.handle(key(KeyEvent.ACTION_UP, FIRST, 0, KeyEvent.FLAG_CANCELED)));
                assertTrue(callback.keys.isEmpty());
                assertFalse(input.handle(key(KeyEvent.ACTION_DOWN, SECOND)));
                assertFalse(input.handle(key(KeyEvent.ACTION_UP, SECOND)));
            }
        });
    }
}
