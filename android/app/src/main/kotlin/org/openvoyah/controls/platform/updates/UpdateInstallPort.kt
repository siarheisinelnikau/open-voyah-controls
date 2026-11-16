package org.openvoyah.controls.platform.updates

import android.content.Intent
import kotlinx.coroutines.flow.StateFlow
import org.openvoyah.controls.data.updates.UpdateProblem

internal enum class UpdateInstallPhase {
    IDLE, PREPARING, STAGING, AWAITING_APPROVAL, INSTALLING, VERIFYING, INSTALLED, INTERRUPTED, FAILED, CANCELLED,
}

internal data class UpdateInstallApproval(val sessionId: Int, val intent: Intent)

internal data class UpdateInstallState(
    val phase: UpdateInstallPhase = UpdateInstallPhase.IDLE,
    val progress: Float? = null,
    val problem: UpdateProblem? = null,
    val approval: UpdateInstallApproval? = null,
    val message: String? = null,
)

/** An opaque capability returned only after durable, positively verified device preparation. */
internal interface PreparedUpdateInstall

internal sealed interface UpdatePreparation {
    data class Ready(val prepared: PreparedUpdateInstall) : UpdatePreparation
    data class Blocked(val problem: UpdateProblem, val message: String? = null) : UpdatePreparation
}

/**
 * The implementation owns the persisted package session and bridge pause receipt across process death.
 * prepare must freshly verify APK bytes/identity and actual OEM quiescence; preferences alone are not proof.
 * It must roll back an interrupted preparation. commit must persist before calling Android and retain an
 * ambiguous attempt for recover. INSTALLED requires independent installed-byte, signer and version checks.
 * Finishing success, cancellation or failure leaves Controls disabled for an explicit user reconnect.
 */
internal interface UpdateInstallPort {
    val state: StateFlow<UpdateInstallState>
    suspend fun prepare(update: VerifiedUpdate): UpdatePreparation
    suspend fun commit(prepared: PreparedUpdateInstall)
    suspend fun cancel(prepared: PreparedUpdateInstall)
    suspend fun discardInterrupted()
    suspend fun recover()
    fun takeApproval(approval: UpdateInstallApproval): Intent?
    fun approvalUnavailable(sessionId: Int)
}
