package org.openvoyah.controls;

import android.content.SharedPreferences;
import android.test.AndroidTestCase;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.openvoyah.controls.data.frida.FridaFailureText;
import org.openvoyah.controls.data.frida.ProductionRootAccess;

/** In-memory failures only; no socket, package, root or network operation is performed. */
public final class FridaFailureTextTest extends AndroidTestCase {
    private static final String RAW = "failed to connect to /127.0.0.1 (port 1) from /127.0.0.1 (port 34702) " +
        "after 2000ms: isConnected failed: ECONNREFUSED (Connection refused)";
    private String language;

    @Override protected void setUp() throws Exception {
        super.setUp();
        language = getContext().getSharedPreferences("ui", 0).getString("language", null);
    }

    @Override protected void tearDown() throws Exception {
        try {
            SharedPreferences.Editor edit = getContext().getSharedPreferences("ui", 0).edit();
            if (language == null) edit.remove("language"); else edit.putString("language", language);
            assertTrue(edit.commit());
        } finally { super.tearDown(); }
    }

    public void testConnectFailuresAndWrappedCausesHaveActionableLocalAccessText() {
        for (Throwable failure : new Throwable[]{new ConnectException(RAW), new NoRouteToHostException(RAW),
            new UnknownHostException(RAW), new IOException("Outer request", new ConnectException(RAW))}) {
            assertEquals(UiText.get(R.string.frida_local_unavailable), FridaFailureText.message(failure));
            assertFalse(FridaFailureText.message(failure).contains("127.0.0.1"));
        }
    }

    public void testSocketAndWriterTimeoutsDoNotExposeEndpointsOrClaimAnOutcome() {
        for (Throwable failure : new Throwable[]{new SocketTimeoutException(RAW),
            new IOException("Request failed", new ExecutionException(new TimeoutException(RAW))),
            new IOException("Root request timed out or was canceled; outcome is unknown")}) {
            assertEquals(UiText.get(R.string.frida_local_timeout), FridaFailureText.message(failure));
        }
    }

    public void testInterruptedChannelsKeepTheNeedToCheckConnectionStatus() {
        for (Throwable failure : new Throwable[]{new SocketException("Connection reset at /127.0.0.1:2323"),
            new EOFException("Root channel closed before completion"),
            new IOException("Root request did not complete; outcome is unknown"),
            new IOException("Root request canceled", new InterruptedException())}) {
            assertEquals(UiText.get(R.string.frida_local_interrupted), FridaFailureText.message(failure));
        }
    }

    public void testLocalizedProductionRefusalWinsOverItsTransportCause() {
        String reason = UiText.get(R.string.production_evidence_changed);
        ProductionRootAccess.Refused refused = new ProductionRootAccess.Refused(reason, new SocketTimeoutException(RAW));
        assertEquals(reason, FridaFailureText.message(refused));
        assertEquals(reason, FridaFailureText.message(new IOException("Outer failure", refused)));
    }

    public void testStorageCompatibilityAndOtherMeaningfulFailuresRemainIntact() {
        for (String reason : new String[]{UiText.get(R.string.production_broker_unavailable),
            "Not enough space for the protected runtime", "Installed runtime version is incompatible"}) {
            assertEquals(reason, FridaFailureText.message(new IOException(reason)));
            assertEquals(reason, FridaFailureText.recordedStatus(reason));
        }
        String truncated = "The local model archive is truncated";
        assertEquals(truncated, FridaFailureText.message(new EOFException(truncated)));
        assertEquals(UiText.get(R.string.frida_operation_failed), FridaFailureText.message(new IOException()));
    }

    public void testExactLegacySocketSegmentKeepsStopAndRetryContext() {
        String prefix = "Stop was not confirmed. ";
        String suffix = " Retry remains scheduled.";
        assertEquals(prefix + UiText.get(R.string.frida_local_unavailable) + suffix,
            FridaFailureText.recordedStatus(prefix + RAW + suffix));
        String timeout = RAW.replace("ECONNREFUSED (Connection refused)", "ETIMEDOUT (Connection timed out)");
        assertEquals(prefix + UiText.get(R.string.frida_local_timeout) + suffix,
            FridaFailureText.recordedStatus(prefix + timeout + suffix));
        String unrelated = "The backup label mentions 127.0.0.1 and port 1";
        assertEquals(unrelated, FridaFailureText.recordedStatus(unrelated));
    }

    public void testEnglishAndRussianMessagesFollowTheCurrentUiLanguage() {
        UiText.language("en");
        String english = FridaFailureText.message(new ConnectException(RAW));
        assertEquals(UiText.get(R.string.frida_local_unavailable), english);
        UiText.language("ru");
        String russian = FridaFailureText.message(new ConnectException(RAW));
        assertEquals(UiText.get(R.string.frida_local_unavailable), russian);
        assertFalse(english.equals(russian));
        assertFalse(russian.contains("127.0.0.1"));
        String prefix = "Остановка не подтверждена. ", suffix = " Повторная проверка запланирована.";
        assertEquals(prefix + russian + suffix, FridaFailureText.recordedStatus(prefix + RAW + suffix));
    }
}
