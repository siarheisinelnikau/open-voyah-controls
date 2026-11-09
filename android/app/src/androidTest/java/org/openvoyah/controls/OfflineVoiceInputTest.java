package org.openvoyah.controls;
import android.test.AndroidTestCase;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Requires RECORD_AUDIO granted to the debug APK on the isolated emulator. */
public final class OfflineVoiceInputTest extends AndroidTestCase {
    public void testRussianModelStarts() throws Exception { startModel("ru"); }
    public void testEnglishModelStarts() throws Exception { startModel("en"); }
    private void startModel(String language) throws Exception {
        VoiceSettings settings=new VoiceSettings(getContext());String previous=settings.exportJson();settings.language(language);
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,getContext().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO));
        CountDownLatch ready=new CountDownLatch(1); AtomicReference<String> error=new AtomicReference<>();
        OfflineVoiceInput input=new OfflineVoiceInput(getContext());
        try {
            input.start(new OfflineVoiceInput.Listener() {
                public void status(String text) { if(text.equals(UiText.get(R.string.ui_143)))ready.countDown(); }
                public void result(String text) { }
                public void error(String text) { error.set(text);ready.countDown(); }
            });
            assertTrue("Vosk startup timed out",ready.await(30,TimeUnit.SECONDS));
            assertNull(error.get());input.cancel();
        } finally { input.close(); settings.importJson(previous); }
    }
}
