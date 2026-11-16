package org.openvoyah.controls.platform.updates

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.openvoyah.controls.core.updates.ApkUpdatePolicy
import org.openvoyah.controls.core.updates.ReleasePolicy
import org.openvoyah.controls.data.updates.UpdateException
import org.openvoyah.controls.data.updates.UpdateProblem
import java.io.File
import kotlin.coroutines.CoroutineContext

class PackageUpdateInstallerTest {
    @Test fun preparationIsDurableBeforePauseAndLiveRecoveryDoesNotLoseItsCapability() = runBlocking {
        val h = Harness()
        val prepared = h.prepare()
        assertEquals(listOf("write:PREPARING", "pause", "write:PREPARED"), h.events)
        assertNotNull(h.gate.pending)
        h.installer.recover()
        assertEquals(UpdateReceiptStep.PREPARED, h.journal.receipt?.step)
        h.installer.commit(prepared)
        assertEquals(1, h.sessions.commits)
        assertTrue(h.events.indexOf("write:ATTEMPTED") < h.events.indexOf("commit"))
        h.installer.commit(prepared)
        assertEquals(1, h.sessions.commits)
    }

    @Test fun deniedInstallPermissionDoesNotPauseOrCreateAReceipt() = runBlocking {
        val h = Harness()
        h.sessions.allowed = false
        val result = h.installer.prepare(h.update) as UpdatePreparation.Blocked
        assertEquals(UpdateProblem.INSTALL_PERMISSION, result.problem)
        assertNull(h.journal.receipt)
        assertTrue(h.events.isEmpty())
    }

    @Test fun failedRuntimePauseRetainsItsReceiptUntilExplicitDiscard() = runBlocking {
        val h = Harness()
        h.gate.failPause = true
        assertTrue(h.installer.prepare(h.update) is UpdatePreparation.Blocked)
        assertEquals(UpdateInstallPhase.INTERRUPTED, h.installer.state.value.phase)
        assertNotNull(h.journal.receipt)
        assertFalse(h.events.contains("finish"))
        h.gate.failPause = false
        h.installer.discardInterrupted()
        assertEquals(UpdateInstallPhase.CANCELLED, h.installer.state.value.phase)
        assertNull(h.journal.receipt)
    }

    @Test fun anUnwrittenInitialReceiptCanBeDiscardedWithoutLeavingAnUnrecoverableBusyState() = runBlocking {
        val h = Harness()
        h.journal.failWrite = UpdateReceiptStep.PREPARING
        assertTrue(h.installer.prepare(h.update) is UpdatePreparation.Blocked)
        assertNull(h.journal.receipt)
        assertNull(h.gate.pending)
        h.journal.failWrite = null
        h.installer.discardInterrupted()
        assertEquals(UpdateInstallPhase.CANCELLED, h.installer.state.value.phase)
        assertNull(h.journal.receipt)
        assertEquals(0, h.sessions.commits)
    }

    @Test fun attemptedReceiptMustBeDurableBeforeAndroidCanReceiveCommit() = runBlocking {
        val h = Harness()
        val prepared = h.prepare()
        h.journal.failWrite = UpdateReceiptStep.ATTEMPTED
        h.installer.commit(prepared)
        assertEquals(0, h.sessions.commits)
        assertEquals(UpdateInstallPhase.INTERRUPTED, h.installer.state.value.phase)
        assertFalse(checkNotNull(h.journal.receipt).attempted)
        h.journal.failWrite = null
        h.reopen().recover()
        assertNull(h.journal.receipt)
        assertEquals(0, h.sessions.commits)
    }

    @Test fun sourceOrStagedByteChangesFailBeforeCommitAndOnlyReleaseAfterConfirmedAbandonment() = runBlocking {
        for (changedIdentity in listOf(false, true)) {
            val h = Harness()
            val prepared = h.prepare()
            if (changedIdentity) h.sessions.fileIdentity = "changed"
            else h.sessions.stageProblem = UpdateProblem.SIZE_OR_DIGEST
            h.installer.commit(prepared)
            assertEquals(0, h.sessions.commits)
            assertEquals(UpdateInstallPhase.FAILED, h.installer.state.value.phase)
            assertEquals(UpdateProblem.SIZE_OR_DIGEST, h.installer.state.value.problem)
            assertNull(h.journal.receipt)
            assertTrue(h.events.indexOf("abandon") < h.events.indexOf("finish"))
        }
    }

    @Test fun failedCommitAndProcessDeathNeverReplayOrAutomaticallyAbandonAnAmbiguousSession() = runBlocking {
        val h = Harness()
        h.sessions.failCommit = true
        h.installer.commit(h.prepare())
        assertEquals(UpdateInstallPhase.INTERRUPTED, h.installer.state.value.phase)
        assertTrue(checkNotNull(h.journal.receipt).attempted)
        val before = h.sessions.abandons
        val reopened = h.reopen()
        reopened.recover()
        assertEquals(UpdateInstallPhase.INTERRUPTED, reopened.state.value.phase)
        assertEquals(1, h.sessions.commits)
        assertEquals(before, h.sessions.abandons)
        assertFalse(h.events.contains("finish"))
        h.sessions.canAbandon = false
        reopened.discardInterrupted()
        assertNotNull(h.journal.receipt)
        assertFalse(h.events.contains("finish"))
        h.sessions.canAbandon = true
        reopened.discardInterrupted()
        assertEquals(UpdateInstallPhase.CANCELLED, reopened.state.value.phase)
        assertNull(h.journal.receipt)
    }

    @Test fun forgedAndReplayedCallbacksCannotCertifyInstallation() = runBlocking {
        val h = Harness()
        h.installer.commit(h.prepare())
        val receipt = checkNotNull(h.journal.receipt)
        assertFalse(h.installer.handleResult(UpdateSessionCallback(99, receipt.token, UpdateCallbackKind.SUCCESS)))
        assertFalse(h.installer.handleResult(UpdateSessionCallback(7, "f".repeat(32), UpdateCallbackKind.SUCCESS)))
        assertEquals(UpdateSessionOutcome.NONE, h.journal.receipt?.outcome)
        assertTrue(h.installer.handleResult(UpdateSessionCallback(7, receipt.token, UpdateCallbackKind.SUCCESS)))
        assertFalse(h.installer.handleResult(UpdateSessionCallback(7, receipt.token, UpdateCallbackKind.ABORTED)))
        assertEquals(UpdateSessionOutcome.SUCCESS, h.journal.receipt?.outcome)
        h.installer.recover()
        assertEquals(UpdateInstallPhase.INTERRUPTED, h.installer.state.value.phase)
        assertNotNull(h.journal.receipt)
        assertFalse(h.events.contains("finish"))
    }

    @Test fun replacementRecoveryRequiresIndependentInstalledBytesEvenWithoutAnySuccessCallback() = runBlocking {
        val h = Harness()
        h.installer.commit(h.prepare())
        h.sessions.proof = InstalledUpdateProof.MATCH
        val reopened = h.reopen()
        reopened.recover()
        assertEquals(UpdateInstallPhase.INSTALLED, reopened.state.value.phase)
        assertNull(h.journal.receipt)
        assertEquals(1, h.sessions.commits)
        assertTrue(h.events.indexOf("write:INSTALLED") < h.events.indexOf("finish"))
        assertTrue(h.events.indexOf("finish") < h.events.indexOf("clear"))
    }

    @Test fun lostFinishAcknowledgmentKeepsVerifiedReceiptForIdempotentRecovery() = runBlocking {
        val h = Harness()
        h.installer.commit(h.prepare())
        h.sessions.proof = InstalledUpdateProof.MATCH
        h.gate.failFinish = true
        h.installer.recover()
        assertEquals(UpdateInstallPhase.INTERRUPTED, h.installer.state.value.phase)
        assertEquals(UpdateReceiptStep.INSTALLED, h.journal.receipt?.step)
        h.gate.failFinish = false
        val reopened = h.reopen()
        reopened.recover()
        assertEquals(UpdateInstallPhase.INSTALLED, reopened.state.value.phase)
        assertNull(h.journal.receipt)
        assertEquals(1, h.sessions.commits)
    }

    @Test fun unreadableInstalledIdentityNeverReleasesTheGateOrClaimsSuccess() = runBlocking {
        val h = Harness()
        h.installer.commit(h.prepare())
        h.sessions.proof = InstalledUpdateProof.UNAVAILABLE
        h.installer.recover()
        h.installer.discardInterrupted()
        assertEquals(UpdateInstallPhase.INTERRUPTED, h.installer.state.value.phase)
        assertNotNull(h.journal.receipt)
        assertNotNull(h.gate.pending)
        assertFalse(h.events.contains("finish"))
    }

    @Test fun processDeathDuringUncommittedPreparationRollsBackWithoutReplaying() = runBlocking {
        val h = Harness()
        h.prepare()
        val receipt = checkNotNull(h.journal.receipt).copy(step = UpdateReceiptStep.STAGING, sessionId = 7)
        h.journal.write(receipt)
        h.sessions.session = 7
        h.reopen().recover()
        assertNull(h.journal.receipt)
        assertNull(h.sessions.session)
        assertEquals(0, h.sessions.commits)
        assertTrue(h.events.indexOf("abandon") < h.events.indexOf("finish"))
    }

    @Test fun sessionCreatedJustBeforeAnIdWriteCrashIsEnumeratedAndAbandoned() = runBlocking {
        val h = Harness()
        h.prepare()
        // Android created this session, but the process died before it could persist its ID.
        h.sessions.session = 7
        assertNull(h.journal.receipt?.sessionId)
        h.reopen().recover()
        assertNull(h.sessions.session)
        assertNull(h.journal.receipt)
        assertEquals(0, h.sessions.commits)
        assertTrue(h.events.indexOf("abandon") < h.events.indexOf("finish"))
    }

    @Test fun preparationCancelledAtReadyHandoffUnwindsTheUndeliveredCapability() {
        val caller = QueueDispatcher()
        val io = QueueDispatcher()
        val h = Harness(io)
        val scope = CoroutineScope(caller + Job())
        var delivered = false
        val task = scope.launch { h.installer.prepare(h.update); delivered = true }
        caller.runAll()
        io.runAll()
        assertEquals(UpdateReceiptStep.PREPARED, h.journal.receipt?.step)
        task.cancel()
        caller.runAll()
        io.runAll()
        caller.runAll()
        assertFalse(delivered)
        assertNull(h.journal.receipt)
        assertEquals(UpdateInstallPhase.CANCELLED, h.installer.state.value.phase)
        scope.cancel()
    }

    @Test fun cancellingBeforeCommitIoEntryReleasesOnlyTheUnattemptedMatchingPreparation() {
        val caller = QueueDispatcher()
        val io = QueueDispatcher()
        val h = Harness(io)
        val scope = CoroutineScope(caller + Job())
        var capability: PreparedUpdateInstall? = null
        scope.launch { capability = h.prepare() }
        caller.runAll()
        io.runAll()
        caller.runAll()
        val prepared = checkNotNull(capability)
        val task = scope.launch { h.installer.commit(prepared) }
        caller.runAll()
        // The caller invoked commit, but its IO dispatcher has not executed the block.
        task.cancel()
        io.runAll()
        caller.runAll()
        io.runAll()
        caller.runAll()
        assertEquals(0, h.sessions.commits)
        assertEquals(UpdateInstallPhase.CANCELLED, h.installer.state.value.phase)
        assertNull(h.journal.receipt)
        assertNull(h.gate.pending)
        scope.launch { h.installer.recover() }
        caller.runAll()
        io.runAll()
        caller.runAll()
        assertEquals(UpdateInstallPhase.CANCELLED, h.installer.state.value.phase)
        scope.cancel()
    }

    @Test fun cancellingCommitReturnAfterAndroidCallRetainsTheAttemptedSessionWithoutReplay() {
        val caller = QueueDispatcher()
        val io = QueueDispatcher()
        val h = Harness(io)
        val scope = CoroutineScope(caller + Job())
        var capability: PreparedUpdateInstall? = null
        scope.launch { capability = h.prepare() }
        caller.runAll()
        io.runAll()
        caller.runAll()
        val task = scope.launch { h.installer.commit(checkNotNull(capability)) }
        caller.runAll()
        io.runAll()
        // Android was called, but commit's return has not reached its caller.
        assertEquals(1, h.sessions.commits)
        task.cancel()
        caller.runAll()
        io.runAll()
        caller.runAll()
        assertEquals(UpdateInstallPhase.INTERRUPTED, h.installer.state.value.phase)
        assertTrue(checkNotNull(h.journal.receipt).attempted)
        assertNotNull(h.gate.pending)
        assertEquals(7, h.sessions.session)
        assertFalse(h.events.contains("abandon"))
        assertFalse(h.events.contains("finish"))
        scope.launch { h.installer.recover() }
        caller.runAll()
        io.runAll()
        caller.runAll()
        assertEquals(1, h.sessions.commits)
        assertEquals(UpdateInstallPhase.INTERRUPTED, h.installer.state.value.phase)
        scope.cancel()
    }

    @Test fun receiptCodecRejectsCorruptionAndRoundTripsOnlyTheExpectedIdentity() = runBlocking {
        val h = Harness()
        h.installer.commit(h.prepare())
        val receipt = checkNotNull(h.journal.receipt)
        val encoded = UpdateReceiptCodec.encode(receipt)
        assertEquals(receipt, UpdateReceiptCodec.decode(encoded))
        for (raw in listOf("", "x".repeat(32_769), encoded.replace("schema=1", "schema=9"),
            encoded.replace("session=7", "session="), encoded.replace("token=${receipt.token}", "token=forged"))) {
            try { UpdateReceiptCodec.decode(raw); fail("Invalid receipt accepted") }
            catch (error: UpdateException) { assertEquals(UpdateProblem.STORAGE, error.problem) }
        }
    }

    private class Harness(dispatcher: CoroutineDispatcher = Dispatchers.Unconfined) {
        val events = mutableListOf<String>()
        val runtime = ApkUpdatePolicy.Runtime("17.26.2", "open-voyah-controls-frida-17.26.2", "b".repeat(64))
        private val signer = setOf("a".repeat(64))
        private val release = checkNotNull(ReleasePolicy.candidate("android-v0.19.0", false, false,
            "OpenVoyahControls-v0.19.0.apk", ReleasePolicy.DOWNLOAD_PREFIX + "android-v0.19.0/OpenVoyahControls-v0.19.0.apk",
            100, "sha256:" + "c".repeat(64)))
        private fun identity(version: String, code: Long) = ApkUpdatePolicy.Identity(ApkUpdatePolicy.PACKAGE,
            version, code, 28, false, false, signer, setOf("arm64-v8a"), runtime)
        val update = VerifiedUpdate(release, File(System.getProperty("java.io.tmpdir"), "ovc-update-fixture.apk").canonicalFile,
            identity("0.19.0", 21), identity("0.18.0", 20), "/data/app/old/base.apk", 100)
        val verifier = object : UpdateVerifier {
            override val installationProblem: UpdateProblem? = null
            override suspend fun verify(release: ReleasePolicy.Candidate, file: File) = update
        }
        val journal = MemoryJournal(events)
        val gate = FakeGate(events, journal)
        val sessions = FakeSessions(events, journal, gate)
        val installer = PackageUpdateInstaller(verifier, journal, sessions, gate, dispatcher)
        suspend fun prepare() = (installer.prepare(update) as UpdatePreparation.Ready).prepared
        fun reopen() = PackageUpdateInstaller(verifier, journal, sessions, gate, Dispatchers.Unconfined)
    }

    private class MemoryJournal(val events: MutableList<String>) : UpdateInstallJournal {
        var receipt: UpdateInstallReceipt? = null
        var failWrite: UpdateReceiptStep? = null
        override fun read() = receipt
        override fun write(receipt: UpdateInstallReceipt) {
            if (receipt.step == failWrite) throw UpdateException(UpdateProblem.STORAGE)
            this.receipt = UpdateReceiptCodec.decode(UpdateReceiptCodec.encode(receipt))
            events += "write:${receipt.step}"
        }
        override fun clear() { events += "clear"; receipt = null }
    }

    private class FakeGate(val events: MutableList<String>, val journal: MemoryJournal) : UpdateRuntimeGate {
        var pending: String? = null
        var failPause = false
        var failFinish = false
        override suspend fun pause(token: String) {
            assertEquals(token, journal.receipt?.token)
            pending = token
            events += "pause"
            if (failPause) throw UpdateException(UpdateProblem.DEVICE_PREPARATION)
        }
        override suspend fun finish(token: String) {
            assertEquals(token, journal.receipt?.token)
            assertTrue(journal.receipt?.step in setOf(UpdateReceiptStep.INSTALLED, UpdateReceiptStep.CANCELLED, UpdateReceiptStep.FAILED))
            events += "finish"
            pending = null
            if (failFinish) throw UpdateException(UpdateProblem.DEVICE_PREPARATION)
        }
        override fun pendingToken() = pending
    }

    private class FakeSessions(val events: MutableList<String>, val journal: MemoryJournal, val gate: FakeGate) : UpdatePackageSessions {
        var allowed = true
        var fileIdentity = "dev:inode:100:mtime:mode"
        var session: Int? = null
        var canAbandon = true
        var failCommit = false
        var commits = 0
        var abandons = 0
        var stageProblem: UpdateProblem? = null
        var proof = InstalledUpdateProof.DIFFERENT
        override fun canInstall() = allowed
        override fun sourceIdentity(file: File) = fileIdentity
        override fun originalInstalled(target: UpdateInstallTarget) = true
        override fun create(target: UpdateInstallTarget): Int {
            assertNotNull(gate.pending)
            events += "create"
            session = 7
            return 7
        }
        override suspend fun stage(sessionId: Int, target: UpdateInstallTarget, progress: (Float) -> Unit) {
            events += "stage"
            stageProblem?.let { throw UpdateException(it) }
            progress(1f)
        }
        override fun commit(sessionId: Int, token: String) {
            assertTrue(checkNotNull(journal.receipt).attempted)
            assertEquals(sessionId, journal.receipt?.sessionId)
            assertEquals(token, journal.receipt?.token)
            events += "commit"
            commits++
            if (failCommit) throw UpdateException(UpdateProblem.INSTALLATION)
        }
        override fun abandon(sessionId: Int?): Boolean {
            events += "abandon"
            abandons++
            if (!canAbandon) return false
            session = null
            return true
        }
        override fun abandonOrphanedSelfSessions() = abandon(session)
        override suspend fun installed(target: UpdateInstallTarget) = proof
        override fun deleteSource(target: UpdateInstallTarget) { events += "delete" }
    }

    private class QueueDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
}
