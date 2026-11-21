package org.openvoyah.controls.platform.updates

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openvoyah.controls.data.frida.FridaUpdateGate
import org.openvoyah.controls.data.updates.UpdateException
import org.openvoyah.controls.data.updates.UpdateProblem
import org.openvoyah.controls.diagnostics.ControlsLog
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal enum class InstalledUpdateProof { MATCH, DIFFERENT, UNAVAILABLE }
internal enum class UpdateCallbackKind { APPROVAL, SUCCESS, ABORTED, FAILURE }
internal data class UpdateSessionCallback(
    val sessionId: Int, val token: String, val kind: UpdateCallbackKind,
    val approval: UpdateInstallApproval? = null,
)

/** All package-manager, file and session operations stay behind this Android boundary. */
internal interface UpdatePackageSessions {
    fun canInstall(): Boolean
    fun sourceIdentity(file: File): String
    fun originalInstalled(target: UpdateInstallTarget): Boolean
    fun create(target: UpdateInstallTarget): Int
    suspend fun stage(sessionId: Int, target: UpdateInstallTarget, progress: (Float) -> Unit)
    fun commit(sessionId: Int, token: String)
    fun abandon(sessionId: Int?): Boolean
    fun abandonOrphanedSelfSessions(): Boolean
    suspend fun installed(target: UpdateInstallTarget): InstalledUpdateProof
    fun deleteSource(target: UpdateInstallTarget)
}

/** One journal and mutex own the update; an ambiguous commit is never submitted again. */
internal class PackageUpdateInstaller(
    private val verifier: UpdateVerifier,
    private val journal: UpdateInstallJournal,
    private val sessions: UpdatePackageSessions,
    private val runtime: UpdateRuntimeGate,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : UpdateInstallPort {
    constructor(context: Context, runtime: UpdateRuntimeGate) : this(
        UpdateApkVerifier(context), SharedUpdateInstallJournal(context), AndroidUpdatePackageSessions(context), runtime,
    )

    private class Prepared(val owner: PackageUpdateInstaller, val token: String, val update: VerifiedUpdate) : PreparedUpdateInstall
    private val operation = Mutex()
    private val mutable = MutableStateFlow(UpdateInstallState())
    override val state = mutable.asStateFlow()
    @Volatile private var activeSession: Int? = null
    private var preparedCapability: Prepared? = null

    override suspend fun prepare(update: VerifiedUpdate): UpdatePreparation {
        var handoff: Prepared? = null
        try { return withContext(io) {
        operation.withLock {
            var receipt: UpdateInstallReceipt? = null
            var paused = false
            var inspected = false
            try {
                val pending = journal.read()
                val pausedToken = try { runtime.pendingToken() }
                catch (failure: Exception) {
                    report("pending_token", UpdateProblem.DEVICE_PREPARATION, failure)
                    throw UpdateException(UpdateProblem.DEVICE_PREPARATION)
                }
                inspected = true
                if (pending != null || pausedToken != null) {
                    interrupted(UpdateProblem.INSTALLATION)
                    return@withLock UpdatePreparation.Blocked(UpdateProblem.INSTALLATION)
                }
                verifier.installationProblem?.let { throw UpdateException(it) }
                if (!sessions.canInstall()) throw UpdateException(UpdateProblem.INSTALL_PERMISSION)
                mutable.value = UpdateInstallState(UpdateInstallPhase.PREPARING)
                val fresh = fresh(update)
                val target = target(fresh, sessions.sourceIdentity(fresh.file))
                receipt = UpdateInstallReceipt(newToken(), target)
                journal.write(receipt)
                runtime.pause(receipt.token)
                paused = true
                coroutineContext.ensureActive()
                receipt = receipt.copy(step = UpdateReceiptStep.PREPARED)
                journal.write(receipt)
                mutable.value = UpdateInstallState(UpdateInstallPhase.PREPARING, progress = 1f)
                val capability = Prepared(this@PackageUpdateInstaller, receipt.token, fresh)
                preparedCapability = capability
                handoff = capability
                UpdatePreparation.Ready(capability)
            } catch (cancelled: CancellationException) {
                receipt?.let { saved ->
                    if (paused) withContext(NonCancellable) { cancelBeforeCommit(saved) }
                    else interrupted(UpdateProblem.DEVICE_PREPARATION)
                }
                throw cancelled
            } catch (failure: Exception) {
                val problem = problem(failure, if (receipt == null) UpdateProblem.INSTALLATION else UpdateProblem.DEVICE_PREPARATION)
                report(if (paused) "prepare_after_pause" else "prepare", problem, failure)
                // A failed pause may have changed runtime state. Only a deliberate discard may resolve that uncertainty.
                val saved = receipt
                if (saved != null && paused) withContext(NonCancellable) { cancelBeforeCommit(saved, UpdateInstallPhase.FAILED, problem) }
                else if (receipt != null) interrupted(problem)
                else if (!inspected) interrupted(problem)
                else mutable.value = UpdateInstallState(UpdateInstallPhase.FAILED, problem = problem)
                UpdatePreparation.Blocked(problem)
            }
        }
        } } catch (cancelled: CancellationException) {
            // withContext can cancel the return after Ready was built but before its caller receives the capability.
            handoff?.let { capability -> withContext(NonCancellable + io) {
                operation.withLock {
                    val receipt = readReceipt()
                    if (receipt?.token == capability.token && !receipt.attempted) cancelBeforeCommit(receipt)
                }
            } }
            throw cancelled
        }
    }

    override suspend fun commit(prepared: PreparedUpdateInstall) {
        val capability = prepared as? Prepared ?: return
        if (capability.owner !== this) return
        var gateUncertain = false
        try { withContext(io) {
        operation.withLock {
            if (capability !== preparedCapability) return@withLock
            var current = readReceipt() ?: return@withLock
            if (current.token != capability.token || current.step != UpdateReceiptStep.PREPARED || current.attempted) return@withLock
            try {
                val expected = checkNotNull(current.target)
                val fresh = fresh(capability.update)
                if (target(fresh, sessions.sourceIdentity(fresh.file)) != expected) throw UpdateException(UpdateProblem.SIZE_OR_DIGEST)
                if (!sessions.canInstall()) throw UpdateException(UpdateProblem.INSTALL_PERMISSION)
                // The gate rechecks actual shutdown and refuses another transaction's token.
                gateUncertain = true
                runtime.pause(current.token)
                gateUncertain = false
                coroutineContext.ensureActive()
                val id = sessions.create(expected)
                activeSession = id
                current = current.copy(step = UpdateReceiptStep.STAGING, sessionId = id)
                try { journal.write(current) }
                catch (failure: Exception) {
                    sessions.abandon(id)
                    throw failure
                }
                mutable.value = UpdateInstallState(UpdateInstallPhase.STAGING, progress = 0f)
                sessions.stage(id, expected) { progress ->
                    mutable.value = UpdateInstallState(UpdateInstallPhase.STAGING, progress.coerceIn(0f, 1f))
                }
                coroutineContext.ensureActive()
                if (!sessions.originalInstalled(expected)) throw UpdateException(UpdateProblem.VERSION)
                current = current.copy(step = UpdateReceiptStep.ATTEMPTED, attempted = true)
                // This synchronous durable write precedes the first Android commit call.
                journal.write(current)
                preparedCapability = null
                mutable.value = UpdateInstallState(UpdateInstallPhase.INSTALLING)
                sessions.commit(id, current.token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report(when {
                    current.attempted -> "commit_attempted"
                    gateUncertain -> "commit_gate"
                    else -> "commit_staging"
                }, problem(failure, UpdateProblem.INSTALLATION), failure)
                if (current.attempted || gateUncertain) interrupted(problem(failure, UpdateProblem.INSTALLATION))
                else withContext(NonCancellable) {
                    cancelBeforeCommit(current, UpdateInstallPhase.FAILED, problem(failure, UpdateProblem.INSTALLATION))
                }
            }
        }
        } } catch (cancelled: CancellationException) {
            // Cancellation may prevent the IO block (or its mutex) from ever being entered.
            // Reload the durable boundary rather than trusting the caller's invocation flag.
            withContext(NonCancellable + io) {
                operation.withLock {
                    val receipt = readReceipt()
                    if (receipt?.token == capability.token) {
                        if (receipt.attempted || gateUncertain) interrupted(UpdateProblem.INSTALLATION)
                        else cancelBeforeCommit(receipt)
                    }
                }
            }
            throw cancelled
        }
    }

    override suspend fun cancel(prepared: PreparedUpdateInstall) = withContext(io) {
        operation.withLock {
            val capability = prepared as? Prepared ?: return@withLock
            if (capability.owner !== this@PackageUpdateInstaller) return@withLock
            val receipt = readReceipt() ?: return@withLock
            if (receipt.token == capability.token && !receipt.attempted) cancelBeforeCommit(receipt)
        }
    }

    override suspend fun recover() = withContext(io) {
        operation.withLock {
            val receipt = readReceipt()
            if (receipt == null) {
                if (mutable.value.phase != UpdateInstallPhase.INTERRUPTED) {
                    try {
                        if (runtime.pendingToken() != null) interrupted(UpdateProblem.DEVICE_PREPARATION)
                    } catch (failure: Exception) {
                        report("recover_pending_token", UpdateProblem.DEVICE_PREPARATION, failure)
                        interrupted(UpdateProblem.DEVICE_PREPARATION)
                    }
                }
                return@withLock
            }
            activeSession = receipt.sessionId
            if (receipt.target == null) {
                finish(receipt, UpdateInstallPhase.CANCELLED)
                return@withLock
            }
            if (!receipt.attempted) {
                if (receipt.step == UpdateReceiptStep.PREPARED && preparedCapability?.token == receipt.token) return@withLock
                // No Android commit can precede its durable flag. Preparation can safely be abandoned.
                cancelBeforeCommit(receipt,
                    if (receipt.step == UpdateReceiptStep.FAILED) UpdateInstallPhase.FAILED else UpdateInstallPhase.CANCELLED,
                    receipt.problem)
                return@withLock
            }
            if (receipt.outcome == UpdateSessionOutcome.NONE && mutable.value.approval?.sessionId == receipt.sessionId) return@withLock
            mutable.value = UpdateInstallState(UpdateInstallPhase.VERIFYING)
            when (proof(receipt.target)) {
                InstalledUpdateProof.MATCH -> finish(receipt, UpdateInstallPhase.INSTALLED)
                InstalledUpdateProof.UNAVAILABLE -> interrupted(UpdateProblem.INSTALLATION)
                InstalledUpdateProof.DIFFERENT -> when {
                    receipt.step in setOf(UpdateReceiptStep.CANCELLED, UpdateReceiptStep.FAILED) -> {
                        if (abandon(receipt.sessionId)) finish(receipt,
                            if (receipt.step == UpdateReceiptStep.FAILED) UpdateInstallPhase.FAILED else UpdateInstallPhase.CANCELLED)
                        else interrupted(UpdateProblem.INSTALLATION)
                    }
                    receipt.outcome == UpdateSessionOutcome.ABORTED || receipt.outcome == UpdateSessionOutcome.FAILURE -> {
                        if (abandon(receipt.sessionId)) finish(receipt,
                            if (receipt.outcome == UpdateSessionOutcome.ABORTED) UpdateInstallPhase.CANCELLED else UpdateInstallPhase.FAILED)
                        else interrupted(UpdateProblem.INSTALLATION)
                    }
                    else -> interrupted(UpdateProblem.INSTALLATION)
                }
            }
        }
    }

    override suspend fun discardInterrupted() = withContext(io) {
        operation.withLock {
            val receipt = try { journal.read() } catch (failure: Exception) {
                report("discard_read_receipt", null, failure)
                null
            }
            if (receipt != null) {
                activeSession = receipt.sessionId
                val installed = receipt.target?.let { proof(it) }
                if (receipt.attempted && installed == InstalledUpdateProof.MATCH) {
                    finish(receipt, UpdateInstallPhase.INSTALLED)
                } else if (installed == InstalledUpdateProof.UNAVAILABLE || !abandon(receipt.sessionId)) {
                    interrupted(UpdateProblem.INSTALLATION)
                } else finish(receipt, UpdateInstallPhase.CANCELLED)
                return@withLock
            }
            // A lost receipt never authorizes an install. Explicit discard must abandon every owned self-update session.
            try {
                val token = runtime.pendingToken()
                if (!sessions.abandonOrphanedSelfSessions()) { interrupted(UpdateProblem.INSTALLATION); return@withLock }
                val resolved = UpdateInstallReceipt(token ?: newToken(), null, UpdateReceiptStep.CANCELLED)
                journal.write(resolved)
                finish(resolved, UpdateInstallPhase.CANCELLED)
            } catch (failure: Exception) {
                report("discard", UpdateProblem.DEVICE_PREPARATION, failure)
                interrupted(UpdateProblem.DEVICE_PREPARATION)
            }
        }
    }

    /** Invoked only by the explicit, non-exported receiver on an IO coroutine. */
    internal suspend fun handleResult(callback: UpdateSessionCallback): Boolean = withContext(io) {
        operation.withLock {
            val receipt = readReceipt() ?: return@withLock false
            if (!receipt.attempted || receipt.sessionId != callback.sessionId || receipt.token != callback.token ||
                receipt.outcome != UpdateSessionOutcome.NONE || receipt.step != UpdateReceiptStep.ATTEMPTED) return@withLock false
            activeSession = receipt.sessionId
            try {
                if (callback.kind == UpdateCallbackKind.APPROVAL) {
                    if (receipt.approvalReceived || callback.approval?.sessionId != receipt.sessionId) return@withLock false
                    journal.write(receipt.copy(approvalReceived = true))
                    mutable.value = UpdateInstallState(UpdateInstallPhase.AWAITING_APPROVAL, approval = callback.approval)
                } else {
                    val outcome = when (callback.kind) {
                        UpdateCallbackKind.SUCCESS -> UpdateSessionOutcome.SUCCESS
                        UpdateCallbackKind.ABORTED -> UpdateSessionOutcome.ABORTED
                        else -> UpdateSessionOutcome.FAILURE
                    }
                    journal.write(receipt.copy(outcome = outcome))
                    mutable.value = UpdateInstallState(UpdateInstallPhase.VERIFYING)
                }
                true
            } catch (failure: Exception) {
                report("session_result", UpdateProblem.STORAGE, failure)
                interrupted(UpdateProblem.STORAGE)
                false
            }
        }
    }

    override fun takeApproval(approval: UpdateInstallApproval): Intent? {
        while (true) {
            val before = mutable.value
            if (before.approval !== approval || activeSession != approval.sessionId) return null
            if (mutable.compareAndSet(before, UpdateInstallState(UpdateInstallPhase.INSTALLING))) return approval.intent
        }
    }

    override fun approvalUnavailable(sessionId: Int) {
        if (activeSession == sessionId) interrupted(UpdateProblem.INSTALLATION)
    }

    private suspend fun fresh(update: VerifiedUpdate): VerifiedUpdate {
        val current = verifier.verify(update.release, update.file)
        if (current.installedPath != update.installedPath || current.installedUpdatedAt != update.installedUpdatedAt ||
            current.installedIdentity.versionCode != update.installedIdentity.versionCode ||
            current.installedIdentity.version != update.installedIdentity.version ||
            current.installedIdentity.signers != update.installedIdentity.signers) throw UpdateException(UpdateProblem.VERSION)
        return current
    }

    private suspend fun cancelBeforeCommit(receipt: UpdateInstallReceipt,
                                          phase: UpdateInstallPhase = UpdateInstallPhase.CANCELLED,
                                          problem: UpdateProblem? = null) {
        if (receipt.attempted || !abandon(receipt.sessionId)) { interrupted(UpdateProblem.INSTALLATION); return }
        finish(receipt, phase, problem)
    }

    private suspend fun finish(receipt: UpdateInstallReceipt, phase: UpdateInstallPhase, problem: UpdateProblem? = receipt.problem) {
        try {
            val step = when (phase) {
                UpdateInstallPhase.INSTALLED -> UpdateReceiptStep.INSTALLED
                UpdateInstallPhase.FAILED -> UpdateReceiptStep.FAILED
                else -> UpdateReceiptStep.CANCELLED
            }
            // Persist independent verification or confirmed abandonment before releasing activation protection.
            journal.write(receipt.copy(step = step, problem = problem))
            runtime.finish(receipt.token)
            journal.clear()
            activeSession = null
            preparedCapability = null
            mutable.value = UpdateInstallState(phase,
                problem = if (phase == UpdateInstallPhase.FAILED) problem ?: UpdateProblem.INSTALLATION else null)
            receipt.target?.let { target ->
                runCatching { sessions.deleteSource(target) }.onFailure { report("delete_download", null, it) }
            }
        } catch (cancelled: CancellationException) {
            // A cancelled caller is not a failed step: the attempt stays recoverable and is recorded as interrupted.
            ControlsLog.info("update_install", "step", "finish", "outcome", "interrupted")
            interrupted(UpdateProblem.DEVICE_PREPARATION)
            throw cancelled
        } catch (failure: Exception) {
            // Covers the journal writes and runtime.finish: the runtime stays paused until a deliberate discard.
            report("finish", UpdateProblem.DEVICE_PREPARATION, failure)
            interrupted(UpdateProblem.DEVICE_PREPARATION)
        }
    }

    private suspend fun proof(target: UpdateInstallTarget): InstalledUpdateProof = try {
        sessions.installed(target)
    } catch (cancelled: CancellationException) { interrupted(UpdateProblem.INSTALLATION); throw cancelled }
    catch (failure: Exception) {
        report("verify", null, failure)
        InstalledUpdateProof.UNAVAILABLE
    }

    private fun abandon(sessionId: Int?): Boolean = try {
        // A process can die between Android creating a session and our durable ID write.
        if (sessionId == null) sessions.abandonOrphanedSelfSessions() else sessions.abandon(sessionId)
    } catch (failure: Exception) {
        report("abandon", null, failure)
        false
    }

    private fun readReceipt(): UpdateInstallReceipt? = try { journal.read() }
    catch (failure: Exception) {
        report("read_receipt", UpdateProblem.STORAGE, failure)
        interrupted(UpdateProblem.STORAGE)
        null
    }

    /** Every failure becomes a fixed problem on the screen; the step and the exception type are only recorded here. */
    private fun report(step: String, problem: UpdateProblem?, failure: Throwable) {
        if (problem == null) ControlsLog.warn("update_install", failure, "step", step)
        else ControlsLog.warn("update_install", failure, "step", step, "problem", problem)
    }

    private fun interrupted(problem: UpdateProblem) {
        mutable.value = UpdateInstallState(UpdateInstallPhase.INTERRUPTED, problem = problem)
    }

    private fun problem(failure: Exception, fallback: UpdateProblem): UpdateProblem =
        (failure as? UpdateException)?.problem ?: fallback

    private fun target(update: VerifiedUpdate, sourceIdentity: String) = UpdateInstallTarget(
        update.identity.packageName, update.identity.version, update.identity.versionCode, update.release.bytes,
        update.release.sha256, update.identity.signers, update.file.canonicalPath, sourceIdentity,
        update.installedPath, update.installedIdentity.version, update.installedIdentity.versionCode,
        update.installedUpdatedAt, update.installedIdentity.signers,
    )

    companion object {
        @Volatile private var shared: PackageUpdateInstaller? = null

        @Synchronized fun get(context: Context): PackageUpdateInstaller = shared ?: PackageUpdateInstaller(
            context.applicationContext, FridaUpdateGate(context.applicationContext),
        ).also { shared = it }

        private fun newToken() = UUID.randomUUID().toString().replace("-", "")
    }
}
