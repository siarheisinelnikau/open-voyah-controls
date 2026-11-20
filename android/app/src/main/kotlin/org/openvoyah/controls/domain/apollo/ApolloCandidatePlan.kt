package org.openvoyah.controls.domain.apollo

/** Source evidence is not device approval. These records must never come from settings import. */
internal data class ApolloTargetIdentity(
    val product: String,
    val fingerprint: String,
    val sdk: Int,
    val abi: String,
    val settingsApk: String,
    val settingsCertificate: String,
    val canApk: String,
    val canCertificate: String,
    val providerApk: String,
    val providerCertificate: String,
) {
    val complete: Boolean get() = product.isNotBlank() && fingerprint.isNotBlank() && sdk in setOf(28, 30) &&
        abi == "arm64-v8a" && listOf(settingsApk, settingsCertificate, canApk, canCertificate,
            providerApk, providerCertificate).all { it.matches(Regex("[0-9a-f]{64}")) }
}

internal enum class ApolloBackupScope {
    FACTORY_SETTINGS_DATA, PROVIDER_DATA, ACCOUNT_STATE, VEHICLE_CONFIGURATION, ADCU_ORIGINAL_FLAGS,
}

internal data class ApolloRollbackEvidence(
    val identity: ApolloTargetIdentity,
    val backupDigest: String,
    val scopes: Set<ApolloBackupScope>,
    val copiedOffDevice: Boolean,
    val independentlyRestorable: Boolean,
    val restorationVerified: Boolean,
    val originalFlags: Set<String>,
) {
    val complete: Boolean get() = backupDigest.matches(Regex("[0-9a-f]{64}")) &&
        scopes == ApolloBackupScope.entries.toSet() && copiedOffDevice && independentlyRestorable &&
        restorationVerified && originalFlags == ApolloCandidatePlan.AFFECTED_FLAGS
}

internal data class ApolloSetupEvidence(
    val baiduPlatformMatched: Boolean,
    val asc: Int?,
    val sdb: Int?,
    val genuineAccountConfirmed: Boolean,
    val genuineLearningConfirmed: Boolean,
    val factoryTlcConfirmed: Boolean,
)

internal data class ApolloStationaryEvidence(
    val session: String,
    val gear: String,
    val speedKph: Int,
    val observedAtElapsedMs: Long,
    val producerQualified: Boolean,
)

internal data class ApolloCandidateConfirmation(
    val operation: String,
    val identity: ApolloTargetIdentity,
    val session: String,
    val backupDigest: String,
    val confirmedAtElapsedMs: Long,
)

internal data class ApolloReviewedOperation(
    val reviewId: String,
    val operation: String,
    val identity: ApolloTargetIdentity,
    val signatures: Set<String>,
    val controllerEffectsQualified: Boolean,
)

internal enum class ApolloCandidateBlocker {
    EXACT_IDENTITY_MISSING, OPERATION_NOT_REVIEWED, INTERFACE_NOT_REVIEWED,
    EQUIPMENT_NOT_CONFIRMED, FACTORY_SETUP_NOT_CONFIRMED, ROLLBACK_NOT_VERIFIED,
    FRESH_PARK_AND_ZERO_REQUIRED, EXPLICIT_CONFIRMATION_REQUIRED, PREVIOUS_ATTEMPT_UNRESOLVED,
}

internal sealed interface ApolloCandidateDecision {
    data class Blocked(val reasons: Set<ApolloCandidateBlocker>) : ApolloCandidateDecision
    data class Prepared(val plan: ApolloCandidatePlan) : ApolloCandidateDecision
}

internal data class ApolloCandidatePlan(
    val reviewId: String,
    val identity: ApolloTargetIdentity,
    val session: String,
    val backupDigest: String,
) {
    companion object {
        const val OPERATION = "ApolloSubscriptionCandidate"
        val REQUIRED_SIGNATURES = setOf(
            "BaiduProviderUtil.doQuerySubscribeInfo(android.content.Context):java.lang.String",
            "DriveAssistanceAdasStatusManager.instance(android.content.Context):DriveAssistanceAdasStatusManager",
            "DriveAssistanceAdasStatusManager.sendAdasSubStatusToADCU(boolean):void",
            "DriveAssistanceAdasStatusManager.asyncQueryAdasSubData():void",
        )
        // Named OEM fields observed in the inspected settings APK, not enum ordinals.
        val AFFECTED_FLAGS = setOf(
            "RPA_FUNC_ENABLE", "HPP_FUNC_ENABLE", "GLC_FUNC_ENABLE", "ISLC_FUNC_ENABLE",
            "TLC_FUNC_ENABLE", "NOA_FUNC_ENABLE", "ELK_FUNC_ENABLE", "ESA_FUNC_ENABLE",
            "APA_FUNC_ENABLE_SA", "RPA_FUNC_ENABLE_SA", "HAVP_FUNC_ENABLE_SA", "ACC_FUNC_ENABLE_SA",
            "ICA_FUNC_ENABLE_SA", "PLC_FUNC_ENABLE_SA", "HANP_FUNC_ENABLE_SA", "ISA_FUNC_ENABLE_SA",
            "ISLC_FUNC_ENABLE_SA", "TLA_FUNC_ENABLE_SA",
        )
    }
}

/** No production operation is shipped. A Prepared plan is a review result, not an actuator receipt. */
internal class ApolloCandidatePolicy(
    private val reviews: List<ApolloReviewedOperation> = emptyList(),
) {
    fun evaluate(
        identity: ApolloTargetIdentity?,
        setup: ApolloSetupEvidence,
        rollback: ApolloRollbackEvidence?,
        stationary: ApolloStationaryEvidence?,
        confirmation: ApolloCandidateConfirmation?,
        session: String,
        nowElapsedMs: Long,
        unresolvedAttempt: Boolean,
    ): ApolloCandidateDecision {
        val reasons = linkedSetOf<ApolloCandidateBlocker>()
        if (identity?.complete != true) reasons += ApolloCandidateBlocker.EXACT_IDENTITY_MISSING
        val review = reviews.singleOrNull { it.identity == identity && it.operation == ApolloCandidatePlan.OPERATION &&
            it.reviewId.matches(Regex("[A-Za-z0-9_.-]{1,80}")) && it.controllerEffectsQualified }
        if (review == null) reasons += ApolloCandidateBlocker.OPERATION_NOT_REVIEWED
        if (review?.signatures != ApolloCandidatePlan.REQUIRED_SIGNATURES) reasons += ApolloCandidateBlocker.INTERFACE_NOT_REVIEWED
        if (!setup.baiduPlatformMatched || setup.asc != 1 || setup.sdb != 1)
            reasons += ApolloCandidateBlocker.EQUIPMENT_NOT_CONFIRMED
        if (!setup.genuineAccountConfirmed || !setup.genuineLearningConfirmed || !setup.factoryTlcConfirmed)
            reasons += ApolloCandidateBlocker.FACTORY_SETUP_NOT_CONFIRMED
        if (rollback?.complete != true || rollback.identity != identity) reasons += ApolloCandidateBlocker.ROLLBACK_NOT_VERIFIED
        fun fresh(at: Long, limit: Long) = nowElapsedMs >= 0 && at >= 0 && at <= nowElapsedMs && nowElapsedMs - at <= limit
        if (session.isBlank() || stationary == null || stationary.session != session || !stationary.producerQualified ||
            stationary.gear != "Parking" || stationary.speedKph != 0 || !fresh(stationary.observedAtElapsedMs, 500))
            reasons += ApolloCandidateBlocker.FRESH_PARK_AND_ZERO_REQUIRED
        if (confirmation == null || confirmation.operation != ApolloCandidatePlan.OPERATION ||
            confirmation.identity != identity || confirmation.session != session ||
            confirmation.backupDigest != rollback?.backupDigest || !fresh(confirmation.confirmedAtElapsedMs, 15_000))
            reasons += ApolloCandidateBlocker.EXPLICIT_CONFIRMATION_REQUIRED
        if (unresolvedAttempt) reasons += ApolloCandidateBlocker.PREVIOUS_ATTEMPT_UNRESOLVED
        return if (reasons.isNotEmpty()) ApolloCandidateDecision.Blocked(reasons) else {
            ApolloCandidateDecision.Prepared(ApolloCandidatePlan(checkNotNull(review).reviewId,
                checkNotNull(identity), session, checkNotNull(rollback).backupDigest))
        }
    }
}
