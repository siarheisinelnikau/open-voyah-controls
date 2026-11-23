package org.openvoyah.controls.data.frida

import android.os.Build
import android.test.AndroidTestCase
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.core.root.LoopbackShell

/** Run explicitly on a disposable emulator provisioned with the local channel fixture. */
class RootChannelIntegrationTest : AndroidTestCase() {
    override fun setUp() {
        super.setUp()
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
    }
    fun testAppUidAuthenticatesToTheRealRootShell() {
        assertTrue("The APK must remain an ordinary app", android.os.Process.myUid() >= 10_000)
        assertEquals("0", AuthorizedRootChannel.existing(context).execute("id -u"))
    }
    fun testUtf8AndCaseSurviveTheTerminal() {
        assertEquals("Привет Mixed_CASE 0123", AuthorizedRootChannel.existing(context)
            .execute("UPPER='Привет Mixed_CASE 0123'; printf '%s' \"\$UPPER\""))
    }
    fun testLongHeredocSurvivesTheRealTelnetPty() {
        val payload = "x".repeat(32_768)
        repeat(12) {
            assertEquals("32769", AuthorizedRootChannel.existing(context).execute("wc -c <<'OVC_TEST_DATA'\n$payload\nOVC_TEST_DATA").trim())
        }
    }
    fun testWrongKeyCannotDispatchACommand() {
        try {
            LoopbackShell(2323, "0".repeat(64)).execute("printf SHOULD_NOT_EXECUTE")
            fail("A client without the installation key authenticated")
        } catch (_: java.io.IOException) { }
        assertEquals("healthy", AuthorizedRootChannel.existing(context).execute("printf healthy"))
    }
}
