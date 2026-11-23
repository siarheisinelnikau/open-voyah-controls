package org.openvoyah.controls.platform.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import android.test.InstrumentationTestCase
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.data.vehicle.LocalHeadlightHistory
import org.openvoyah.controls.domain.vehicle.HeadlightFeedback
import org.openvoyah.controls.domain.vehicle.HeadlightMode
import org.openvoyah.controls.domain.vehicle.HeadlightMonitor
import org.openvoyah.controls.domain.vehicle.HeadlightStatus
import org.openvoyah.controls.fixtures.HeadlightFixtureService

class QingganHeadlightBindingTest : InstrumentationTestCase() {
    private data class Report(val event: Int, val senderUid: Int, val pid: Int, val clientUid: Int)
    private data class Bound(val connection: ServiceConnection, val binder: IBinder)

    private class FixtureBindings(base: Context) : ContextWrapper(base) {
        val ready = Channel<Bound>(Channel.UNLIMITED)
        private val delegates = ConcurrentHashMap<ServiceConnection, ServiceConnection>()

        override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
            val forwarding = object : ServiceConnection by conn {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    conn.onServiceConnected(name, service)
                    ready.trySend(Bound(conn, service))
                }
            }
            delegates[conn] = forwarding
            return baseContext.bindService(intent, forwarding, flags)
        }

        override fun unbindService(conn: ServiceConnection) {
            baseContext.unbindService(checkNotNull(delegates.remove(conn)))
        }
    }

    private suspend fun <T : Any> awaitPhase(phase: String, block: suspend () -> T): T =
        withTimeoutOrNull(8_000) { block() } ?: throw AssertionError("Timed out waiting for $phase")

    private inner class RemoteFixture(val app: Context, fixturePackage: String) {
        val fixtureUid = checkNotNull(app.packageManager.getPackageInfo(fixturePackage, 0).applicationInfo).uid
        val component = ComponentName(fixturePackage, HeadlightFixtureService::class.java.name)
        lateinit var commands: Messenger
        val states = Channel<HeadlightStatus>(Channel.UNLIMITED)
        val reports = Channel<Report>(Channel.UNLIMITED)
        val reporter = Messenger(Handler(Looper.getMainLooper()) { message ->
            reports.trySend(Report(message.what, message.sendingUid, message.arg1, message.arg2))
            true
        })
        suspend fun expectReport(expected: Int, phase: String): Report {
            val received = withTimeoutOrNull(8_000) { reports.receive() }
                ?: throw AssertionError("Timed out waiting for $phase. Next monitor status ${states.tryReceive().getOrNull()}")
            assertEquals(phase, expected, received.event)
            assertEquals("Fixture sender UID during $phase", fixtureUid, received.senderUid)
            assertTrue("Fixture process during $phase", received.pid > 0 && received.pid != Process.myPid())
            assertEquals("Fixture's observed client UID during $phase", Process.myUid(), received.clientUid)
            return received
        }

        val bindings = FixtureBindings(app)
        val admissions = AtomicInteger()
        val sourceRequest = Intent(HeadlightFixtureService.SOURCE_ACTION).setComponent(component)
        val source = QingganHeadlightReader.subscribe(bindings, sourceRequest) {
            admissions.incrementAndGet()
            app.packageManager.getPackageInfo(fixturePackage, 0)
        }
        val historyFile = "headlight_fixture_" + UUID.randomUUID()
        val historyContext = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(name == LocalHeadlightHistory.STORE)
                return baseContext.getSharedPreferences(historyFile, mode)
            }
        }
        val history = LocalHeadlightHistory(historyContext)

        fun send(command: Int) = commands.send(Message.obtain(null, command))

        suspend fun nextState(phase: String) = awaitPhase(phase) { states.receive() }
    }

    private fun withRemoteFixture(scenario: suspend RemoteFixture.() -> Unit) = runBlocking {
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        with(RemoteFixture(instrumentation.targetContext, instrumentation.context.packageName)) {
            assertTrue("Both endpoints must use app UIDs", Process.myUid() >= Process.FIRST_APPLICATION_UID &&
                fixtureUid >= Process.FIRST_APPLICATION_UID)
            assertTrue("Fixture must have its own UID", fixtureUid != Process.myUid())
            val commandsReady = CompletableDeferred<Messenger>()
            val driverConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    if (name == component) commandsReady.complete(Messenger(service))
                    else commandsReady.completeExceptionally(AssertionError("Unexpected fixture component $name"))
                }

                override fun onNullBinding(name: ComponentName) {
                    commandsReady.completeExceptionally(AssertionError("Fixture returned a null command binding"))
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    commandsReady.completeExceptionally(AssertionError("Fixture command service disconnected"))
                }

                override fun onBindingDied(name: ComponentName) {
                    commandsReady.completeExceptionally(AssertionError("Fixture command binding died"))
                }
            }

            val driverRequest = Intent(HeadlightFixtureService.DRIVER_ACTION).setComponent(component)
            val bound = app.bindService(driverRequest, driverConnection, Context.BIND_AUTO_CREATE)
            assertTrue("Android must accept the fixture command binding", bound)
            try {
                commands = awaitPhase("Android to connect the fixture command service") { commandsReady.await() }
                commands.send(Message.obtain(null, HeadlightFixtureService.CONNECT_REPORTER).apply { replyTo = reporter })
                expectReport(HeadlightFixtureService.DRIVER_READY, "the fixture command handshake")
                scenario()
            } finally {
                app.unbindService(driverConnection)
                app.deleteSharedPreferences(historyFile)
            }
        }
    }

    fun testRemoteSelectorUpdatesHistoryAndUnregisters() = withRemoteFixture {
        coroutineScope {
            val collector = launch { HeadlightMonitor(source, history).observe().collect(states::send) }
            try {
                val initial = awaitPhase("the monitor's initial status") { states.receive() }
                assertEquals(HeadlightFeedback.AwaitingController, initial.feedback)
                assertNull(initial.lastConfirmed)
                expectReport(HeadlightFixtureService.REGISTERED, "registration of the remote observer")
                val original = awaitPhase("the original Android binding") { bindings.ready.receive() }
                assertNull(history.read())

                commands.send(Message.obtain(null, HeadlightFixtureService.SEND_IGNORED))
                expectReport(HeadlightFixtureService.IGNORED_SENT, "the fixture's acknowledgment and unrelated callbacks")
                assertNull("An acknowledgment or unrelated property must not change the monitor", withTimeoutOrNull(400) {
                    states.receive()
                })
                assertNull(history.read())

                commands.send(Message.obtain(null, HeadlightFixtureService.SEND_POSITION))
                val state = awaitPhase("the synthetic position-light observation") { states.receive() }
                val confirmed = state.feedback as? HeadlightFeedback.Confirmed
                    ?: throw AssertionError("Expected a position-light observation, received $state")
                assertEquals(HeadlightMode.POSITION, confirmed.observation.mode)
                assertEquals(confirmed.observation, state.lastConfirmed)
                assertFalse(state.saveFailed)
                assertEquals(confirmed.observation, LocalHeadlightHistory(historyContext).read())

                // Drive framework lifecycle notifications while the genuine cross-UID fixture is available for cleanup.
                original.connection.onServiceDisconnected(component)
                val disconnected = awaitPhase("temporary loss of the selector") { states.receive() }
                assertEquals(HeadlightFeedback.Unavailable, disconnected.feedback)
                assertNull(disconnected.currentObservation)
                assertEquals(confirmed.observation, disconnected.lastConfirmed)
                expectReport(HeadlightFixtureService.REMOVED, "release of the disconnected observer")

                original.connection.onServiceConnected(component, original.binder)
                val restored = awaitPhase("the system reconnection awaiting a fresh selector") { states.receive() }
                assertEquals(HeadlightFeedback.AwaitingController, restored.feedback)
                assertEquals(confirmed.observation, restored.lastConfirmed)
                expectReport(HeadlightFixtureService.REGISTERED, "registration on the original binding")
                assertEquals(2, admissions.get())
                assertTrue("Android reconnected on the existing binding", bindings.ready.tryReceive().isFailure)

                commands.send(Message.obtain(null, HeadlightFixtureService.SEND_RETIRED))
                expectReport(HeadlightFixtureService.RETIRED_SENT, "a packet to the retired observer")
                assertNull("A retired observer must not restore a live mode", withTimeoutOrNull(400) { states.receive() })
                assertEquals(confirmed.observation, history.read())
                commands.send(Message.obtain(null, HeadlightFixtureService.SEND_LOW_BEAM))
                val updated = awaitPhase("the new observer's low-beam reading") { states.receive() }
                assertEquals(HeadlightMode.LOW_BEAM, updated.currentObservation?.mode)
                assertEquals(updated.currentObservation, history.read())

                original.connection.onBindingDied(component)
                val dead = awaitPhase("immediate loss of the terminal binding") { states.receive() }
                assertEquals(HeadlightFeedback.Unavailable, dead.feedback)
                assertEquals(updated.currentObservation, dead.lastConfirmed)
                expectReport(HeadlightFixtureService.REMOVED, "release of the terminal binding's observer")
                val rebound = awaitPhase("a new explicit Android binding") { bindings.ready.receive() }
                assertNotSame(original.connection, rebound.connection)
                val waiting = awaitPhase("the rebound service awaiting fresh feedback") { states.receive() }
                assertEquals(HeadlightFeedback.AwaitingController, waiting.feedback)
                assertEquals(updated.currentObservation, waiting.lastConfirmed)
                expectReport(HeadlightFixtureService.REGISTERED, "registration after a fully rechecked bind")
                assertEquals(3, admissions.get())

                collector.cancelAndJoin()
                expectReport(HeadlightFixtureService.REMOVED, "removal of the registered observer")
                assertEquals(updated.currentObservation, LocalHeadlightHistory(historyContext).read())
            } finally {
                collector.cancelAndJoin()
            }
        }
    }

    fun testInteractiveWakeRequiresANewRemoteSelector() = withRemoteFixture {
        coroutineScope {
            val power = CompletableDeferred<(Boolean, Long) -> Unit>()
            val holdNext = AtomicBoolean()
            val delayed = CompletableDeferred<Pair<HeadlightFeedback.Confirmed, () -> Boolean>>()
            val interactive = interactiveHeadlightFeedback({ change ->
                change(true, SystemClock.elapsedRealtimeNanos())
                power.complete(change)
                awaitCancellation()
            }) { publish ->
                source.collect { feedback ->
                    if (feedback is HeadlightFeedback.Confirmed && holdNext.compareAndSet(true, false)) {
                        // Keep a real decoded callback until the test releases it across the Android power boundary.
                        delayed.complete(feedback to { publish(feedback) })
                    } else {
                        publish(feedback)
                    }
                }
            }
            val collector = launch { HeadlightMonitor(interactive, history).observe().collect(states::send) }
            try {
                val initial = nextState("the monitor to start with no current mode")
                assertNull(initial.currentObservation)
                assertNull(initial.lastConfirmed)
                val original = awaitPhase("the original remote Android binding") { bindings.ready.receive() }
                val registered = expectReport(HeadlightFixtureService.REGISTERED, "the first interactive observer")
                send(HeadlightFixtureService.SEND_POSITION)
                val before = nextState("a remote selector before screen off")
                assertEquals(HeadlightMode.POSITION, before.currentObservation?.mode)
                assertEquals(before.currentObservation, LocalHeadlightHistory(historyContext).read())

                holdNext.set(true)
                send(HeadlightFixtureService.SEND_LOW_BEAM)
                val (held, deliver) = awaitPhase("a real selector decoded by the old subscription") { delayed.await() }
                assertEquals(HeadlightMode.LOW_BEAM, held.observation.mode)
                val change = awaitPhase("the interactive power watcher") { power.await() }
                change(false, SystemClock.elapsedRealtimeNanos())
                val off = nextState("live feedback to become historical at screen off")
                assertEquals(HeadlightFeedback.Unavailable, off.feedback)
                assertNull(off.currentObservation)
                assertEquals(before.currentObservation, off.lastConfirmed)
                expectReport(HeadlightFixtureService.REMOVED, "release of the first remote observer")

                change(true, SystemClock.elapsedRealtimeNanos())
                val awake = nextState("the wake boundary awaiting a new controller report")
                assertEquals(HeadlightFeedback.AwaitingController, awake.feedback)
                assertNull(awake.currentObservation)
                assertEquals(off.lastConfirmed, awake.lastConfirmed)
                val rebound = awaitPhase("a new Android binding to the same running service") { bindings.ready.receive() }
                val registeredAgain = expectReport(HeadlightFixtureService.REGISTERED, "the new interactive observer")
                assertNotSame(original.connection, rebound.connection)
                assertEquals(original.binder, rebound.binder)
                assertEquals(registered.pid, registeredAgain.pid)
                assertEquals(2, admissions.get())

                assertFalse("A decoded callback from the previous subscription must be revoked", deliver())
                send(HeadlightFixtureService.SEND_RETIRED)
                expectReport(HeadlightFixtureService.RETIRED_SENT, "a late callback addressed to the retired Binder")
                assertEquals(off.lastConfirmed, history.read())
                send(HeadlightFixtureService.SEND_AUTOMATIC)
                val fresh = nextState("fresh Automatic feedback from the new observer")
                assertEquals(HeadlightMode.AUTOMATIC, fresh.currentObservation?.mode)
                assertEquals(fresh.currentObservation, fresh.lastConfirmed)
                assertEquals(fresh.currentObservation, LocalHeadlightHistory(historyContext).read())

                collector.cancelAndJoin()
                expectReport(HeadlightFixtureService.REMOVED, "release of the post-wake observer")
                assertEquals(fresh.currentObservation, LocalHeadlightHistory(historyContext).read())
            } finally {
                collector.cancelAndJoin()
            }
        }
    }
}
