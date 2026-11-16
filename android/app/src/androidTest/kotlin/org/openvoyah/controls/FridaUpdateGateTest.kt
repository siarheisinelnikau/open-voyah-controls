package org.openvoyah.controls

import android.content.Context
import android.system.Os
import android.test.AndroidTestCase
import org.openvoyah.controls.core.root.LoopbackShell
import org.openvoyah.controls.core.root.RootChannelAuth
import org.openvoyah.controls.data.frida.FridaBridge
import org.openvoyah.controls.data.frida.ProductionRootAccess
import org.openvoyah.controls.data.settings.SettingsStore
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Authenticated loopback receipts exercise orchestration; this fixture executes no root commands. */
class FridaUpdateGateTest : AndroidTestCase() {
    fun testUnconfiguredPreferencesStillRequirePositiveRuntimeShutdown() = fixture { peer, token ->
        FridaBridge.pauseRuntimeForUpdate(context, token)
        assertTrue(peer.stopped)
        assertTrue(peer.cleared)
        assertTrue(peer.markerWritten)
        assertEquals(token, FridaBridge.pendingUpdate(context))
        assertFalse(FridaBridge.wasConfigured(context))
        try {
            FridaBridge(context).connect(SettingsStore(context).load())
            fail("Connect must remain blocked until the update is resolved")
        } catch (expected: ProductionRootAccess.Refused) {
            assertEquals(UiText.get(R.string.runtime_update_paused), expected.message)
        }
        FridaBridge.finishRuntimeUpdate(context, token)
        assertNull(FridaBridge.pendingUpdate(context))
        assertFalse(peer.markerWritten)
        assertFalse(FridaBridge.wasConfigured(context))
        val commands = peer.commands
        // A lost final acknowledgment can be recovered without a second shutdown or activation.
        FridaBridge.finishRuntimeUpdate(context, token)
        assertEquals(commands, peer.commands)
    }

    fun testUnconfirmedVisualRestoreKeepsTheUpdateBlocked() = fixture { peer, token ->
        peer.restoreIcons = false
        try {
            FridaBridge.pauseRuntimeForUpdate(context, token)
            fail("An unconfirmed restoration must not authorize APK replacement")
        } catch (_: ProductionRootAccess.Refused) { }
        assertTrue(peer.stopped)
        assertTrue(peer.visualRecovery)
        assertFalse(peer.markerWritten)
        assertEquals(token, FridaBridge.pendingUpdate(context))
        assertNull(context.getSharedPreferences("bridge", 0).getString("finishedUpdateToken", null))
    }

    fun testUnknownWorkerShutdownCannotFinishThePause() = fixture { peer, token ->
        peer.stopStatus = 78
        try {
            FridaBridge.pauseRuntimeForUpdate(context, token)
            fail("An ambiguous worker must block installation")
        } catch (_: ProductionRootAccess.Refused) { }
        assertFalse(peer.markerWritten)
        assertEquals(token, FridaBridge.pendingUpdate(context))
        assertTrue(context.getSharedPreferences("bridge", 0).getBoolean("stopPending", false))
        peer.stopStatus = 0
        FridaBridge.finishRuntimeUpdate(context, token)
        assertNull(FridaBridge.pendingUpdate(context))
        assertFalse(FridaBridge.wasConfigured(context))
    }

    fun testAnotherTransactionCannotReleaseTheActivePause() = fixture { peer, token ->
        FridaBridge.pauseRuntimeForUpdate(context, token)
        val commands = peer.commands
        try {
            FridaBridge.finishRuntimeUpdate(context, UUID.randomUUID().toString())
            fail("A different receipt must not release the pause")
        } catch (_: java.io.IOException) { }
        assertEquals(token, FridaBridge.pendingUpdate(context))
        assertEquals(commands, peer.commands)
    }

    fun testJournalBeforePauseStillVerifiesShutdownBeforeResolving() = fixture { peer, token ->
        FridaBridge.finishRuntimeUpdate(context, token)
        assertTrue(peer.stopped)
        assertTrue(peer.cleared)
        assertNull(FridaBridge.pendingUpdate(context))
    }

    private fun fixture(body: (Peer, String) -> Unit) {
        check(org.openvoyah.controls.data.frida.FridaTransport.canUseFixture(context))
        val preferences = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
        val keys = listOf("health", "configured", "stopPending", "productionAuthorized", "updatePauseToken", "finishedUpdateToken")
        val previous = keys.associateWith { preferences.all[it] }
        val file = File(context.filesDir, "root-channel.key")
        val previousKey = if (file.exists()) file.readBytes() else null
        val key = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
        // Bind before changing local credentials; an existing service is never replaced by the fixture.
        Peer(key).use { peer ->
            try {
                file.writeText("$key\n")
                Os.chmod(file.path, 0x180)
                preferences.edit().apply { keys.forEach { remove(it) } }.commit()
                body(peer, UUID.randomUUID().toString())
            } finally {
                if (previousKey == null) file.delete() else file.writeBytes(previousKey)
                if (file.exists()) Os.chmod(file.path, 0x180)
                preferences.edit().apply {
                    previous.forEach { (key, value) -> when (value) {
                        null -> remove(key)
                        is Boolean -> putBoolean(key, value)
                        is String -> putString(key, value)
                        else -> error("Unexpected bridge preference type")
                    } }
                }.commit()
            }
        }
    }

    private class Peer(private val key: String) : AutoCloseable {
        private val server = ServerSocket(LoopbackShell.PRODUCTION_PORT, 1, InetAddress.getByName("127.0.0.1"))
        private val failure = AtomicReference<Throwable>()
        @Volatile private var closed = false
        @Volatile var restoreIcons = true
        @Volatile var stopStatus = 0
        @Volatile var stopped = false
        @Volatile var cleared = false
        @Volatile var visualRecovery = false
        @Volatile var markerWritten = false
        @Volatile var commands = 0
        private val worker = Thread({
            try {
                while (!closed) server.accept().use { socket ->
                    socket.soTimeout = 4000
                    val input = socket.getInputStream().bufferedReader()
                    val output = socket.getOutputStream().bufferedWriter()
                    fun send(text: String) { output.write(text + "\n"); output.flush() }
                    send("OVC_ACCESS_1")
                    val nonce = input.readLine()
                    val serverNonce = UUID.randomUUID().toString().replace("-", "")
                    send("OVC_SERVER $serverNonce ${RootChannelAuth.server(key, nonce)}")
                    check(RootChannelAuth.matches(RootChannelAuth.client(key, nonce, serverNonce), input.readLine()))
                    send("OVC_AUTHENTICATED")
                    val parts = Regex("'(OVC_[0-9a-f]+)' '([0-9a-f]+)'").find(input.readLine()) ?: error("Handshake")
                    val marker = parts.groupValues[1] + parts.groupValues[2]
                    send(marker + "_START")
                    val script = buildString {
                        while (true) {
                            val line = input.readLine() ?: error("Missing command completion")
                            if (line.contains("_END:%s")) break
                            appendLine(line)
                        }
                    }
                    commands++
                    var status = 0
                    val answer = when {
                        script == "(\nid -u\n" -> "0"
                        script.contains("--visual-session-state") -> "absent"
                        script.contains("--visual-session-clear-") -> "cleared"
                        script.contains("OVC_PANEL_ICONS_READY") || script.contains("OVC_WINDOW_READY") -> "ready"
                        script.contains("OVC_PANEL_ICONS_RESTORED") -> if (restoreIcons) "restored" else "unconfirmed"
                        script.contains("OVC_WINDOW_RESULT") -> Regex("OVC_WINDOW_RESULT [a-f0-9]{32} ")
                            .find(script)!!.value + "{\"status\":\"applied\"}"
                        script.contains("for ovc_marker in recovery.visual-pending") -> { visualRecovery = true; "" }
                        script.contains("ovc_busy") -> { status = stopStatus; stopped = status == 0; "" }
                        script.contains("settings delete global org.openvoyah.controls.panel") -> { cleared = true; "" }
                        script.contains("settings put global org.openvoyah.controls.windowRequest") ||
                            script.contains("settings delete global org.openvoyah.controls.windowRequest") -> ""
                        script.contains("/.update.XXXXXXXX") -> {
                            check(stopped && cleared && !visualRecovery) { "Marker was written before verified restoration" }
                            markerWritten = true; ""
                        }
                        script.contains("rm -f '/data/adb/open-voyah-controls/update.pending'") -> { markerWritten = false; "" }
                        script.contains("recovery.visual-pending") -> { if (visualRecovery) status = 78; "" }
                        else -> error("Unexpected shutdown command")
                    }
                    send(answer + "\n" + marker + "_END:" + status)
                }
            } catch (error: SocketException) { if (!closed) failure.set(error) }
            catch (error: Throwable) { failure.set(error) }
        }, "authenticated-update-gate-fixture").apply { start() }

        override fun close() {
            closed = true
            server.close()
            worker.join(5000)
            check(!worker.isAlive) { "Fixture did not stop" }
            failure.get()?.let { throw AssertionError(it) }
        }
    }
}
