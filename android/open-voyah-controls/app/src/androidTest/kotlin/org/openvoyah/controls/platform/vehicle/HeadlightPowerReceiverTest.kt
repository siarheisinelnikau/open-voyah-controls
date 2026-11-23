package org.openvoyah.controls.platform.vehicle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.test.InstrumentationTestCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.openvoyah.controls.BuildConfig

class HeadlightPowerReceiverTest : InstrumentationTestCase() {
    private class ReceiverContext(base: Context) : ContextWrapper(base) {
        val registered = CompletableDeferred<Pair<BroadcastReceiver, IntentFilter>>()
        val removed = CompletableDeferred<BroadcastReceiver>()

        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, permission: String?, handler: Handler?): Intent? =
            super.registerReceiver(receiver, filter, permission, handler).also {
                if (receiver != null) registered.complete(receiver to filter)
            }

        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, permission: String?, handler: Handler?, flags: Int): Intent? =
            super.registerReceiver(receiver, filter, permission, handler, flags).also {
                if (receiver != null) registered.complete(receiver to filter)
            }

        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            super.unregisterReceiver(receiver)
            removed.complete(receiver)
        }
    }

    fun testReceiverStartsFromAndroidPowerStateAndUnregistersAtCancellation() = runBlocking {
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val app = ReceiverContext(instrumentation.targetContext)
        val power = checkNotNull(app.getSystemService(PowerManager::class.java))
        val updates = Channel<Pair<Boolean, Long>>(Channel.UNLIMITED)
        val watcher = launch { watchDeviceInteractivity(app) { on, time -> updates.trySend(on to time) } }
        try {
            withTimeout(5_000) {
                val (receiver, filter) = app.registered.await()
                assertEquals(2, filter.countActions())
                assertTrue(filter.hasAction(Intent.ACTION_SCREEN_OFF))
                assertTrue(filter.hasAction(Intent.ACTION_SCREEN_ON))
                val initial = updates.receive()
                assertEquals(power.isInteractive, initial.first)
                assertTrue(initial.second > 0L)

                // Exercise the registered receiver in-process without changing device power or sending a protected broadcast.
                instrumentation.runOnMainSync { receiver.onReceive(app, Intent(Intent.ACTION_SCREEN_OFF)) }
                val off = updates.receive()
                assertFalse(off.first)
                instrumentation.runOnMainSync { receiver.onReceive(app, Intent(Intent.ACTION_SCREEN_ON)) }
                val wake = updates.receive()
                assertEquals(power.isInteractive, wake.first)
                assertTrue(wake.second >= off.second && off.second >= initial.second)

                watcher.cancelAndJoin()
                assertSame(receiver, app.removed.await())
            }
        } finally {
            watcher.cancelAndJoin()
        }
    }
}
