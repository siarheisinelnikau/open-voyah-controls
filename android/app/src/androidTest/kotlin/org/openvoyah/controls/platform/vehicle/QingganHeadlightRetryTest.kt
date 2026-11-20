package org.openvoyah.controls.platform.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.test.InstrumentationTestCase
import java.io.IOException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.domain.vehicle.HeadlightFeedback

class QingganHeadlightRetryTest : InstrumentationTestCase() {
    override fun setUp() {
        super.setUp()
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
    }

    /** Delivers Android lifecycle callbacks directly. No system binding or fixture process is needed. */
    private class ScriptedBindings(base: Context, val reply: (ServiceConnection, ComponentName) -> Unit) : ContextWrapper(base) {
        var checks = 0
        var binds = 0
        var unbinds = 0
        val target = ComponentName(base.packageName, "fixture.ReadOnlyHeadlightFeed")

        fun observe(allow: (Int) -> Boolean = { true }) = QingganHeadlightReader.subscribe(this, Intent().setComponent(target)) {
            if (allow(++checks)) packageManager.getPackageInfo(target.packageName, 0) else null
        }

        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            check(intent.component == target)
            binds++
            reply(connection, target)
            return true
        }

        override fun unbindService(connection: ServiceConnection) { unbinds++ }
    }

    fun testOnlyTwoRebindsAfterRepeatedBindingDeath() = runBlocking {
        val links = ScriptedBindings(instrumentation.targetContext) { conn, target -> conn.onBindingDied(target) }
        val failure = withTimeoutOrNull(6_000) { runCatching { links.observe().toList() }.exceptionOrNull() }
        assertTrue("Repeated terminal loss must complete with an unavailable transport", failure is IOException)
        assertEquals(3, links.checks)
        assertEquals(3, links.binds)
        assertEquals(links.binds, links.unbinds)
    }

    fun testAdmissionIsCheckedAgainBeforeRebindingAndNullBindingStops() = runBlocking {
        val revoked = ScriptedBindings(instrumentation.targetContext) { conn, name -> conn.onBindingDied(name) }
        assertTrue(revoked.observe { it == 1 }.toList().contains(HeadlightFeedback.Unavailable))
        assertEquals(2, revoked.checks)
        assertEquals(1, revoked.binds)
        assertEquals(1, revoked.unbinds)

        val rejected = ScriptedBindings(instrumentation.targetContext) { conn, name -> conn.onNullBinding(name) }
        assertEquals(listOf(HeadlightFeedback.Unavailable), rejected.observe().toList())
        assertEquals(1, rejected.checks)
        assertEquals(1, rejected.binds)
        assertEquals(1, rejected.unbinds)
    }

    fun testCancellationDuringReconnectWaitReleasesTheBinding() = runBlocking {
        val links = ScriptedBindings(instrumentation.targetContext) { conn, name -> conn.onServiceDisconnected(name) }
        val feedback = Channel<HeadlightFeedback>(Channel.UNLIMITED)
        val observer = launch { links.observe().collect(feedback::send) }
        try {
            assertEquals(HeadlightFeedback.Unavailable, withTimeoutOrNull(2_000) { feedback.receive() })
        } finally {
            observer.cancelAndJoin()
        }
        assertEquals(1, links.checks)
        assertEquals(1, links.binds)
        assertEquals(1, links.unbinds)
    }

    fun testUnrestoredDisconnectGetsOneNewAdmissionAfterDeadline() = runBlocking {
        val links = ScriptedBindings(instrumentation.targetContext) { conn, name -> conn.onServiceDisconnected(name) }
        val reports = withTimeoutOrNull(8_000) { links.observe { it == 1 }.toList() }
        assertTrue("A lost binding must be released within the recovery window", reports?.contains(HeadlightFeedback.Unavailable) == true)
        assertEquals(2, links.checks)
        assertEquals(1, links.binds)
        assertEquals(1, links.unbinds)
    }
}
