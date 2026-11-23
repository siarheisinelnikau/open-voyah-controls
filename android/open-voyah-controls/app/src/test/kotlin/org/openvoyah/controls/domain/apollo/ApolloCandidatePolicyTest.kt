package org.openvoyah.controls.domain.apollo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApolloCandidatePolicyTest {
    private val identity = ApolloTargetIdentity("f6808", "fixture/h97d:11/qualification", 30, "arm64-v8a",
        "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), "e".repeat(64), "f".repeat(64))
    private val setup = ApolloSetupEvidence(true, 1, 1, true, true, true)
    private val rollback = ApolloRollbackEvidence(identity, "0".repeat(64), ApolloBackupScope.entries.toSet(),
        true, true, true, ApolloCandidatePlan.AFFECTED_FLAGS)
    private val stationary = ApolloStationaryEvidence("trip-1", "Parking", 0, 900, true)
    private val confirmation = ApolloCandidateConfirmation(ApolloCandidatePlan.OPERATION, identity, "trip-1", rollback.backupDigest, 800)
    private val review = ApolloReviewedOperation("fixture-review", ApolloCandidatePlan.OPERATION, identity,
        ApolloCandidatePlan.REQUIRED_SIGNATURES, true)

    private fun decide(
        reviews: List<ApolloReviewedOperation> = listOf(review),
        identity: ApolloTargetIdentity? = this.identity,
        setup: ApolloSetupEvidence = this.setup,
        rollback: ApolloRollbackEvidence? = this.rollback,
        stationary: ApolloStationaryEvidence? = this.stationary,
        confirmation: ApolloCandidateConfirmation? = this.confirmation,
        now: Long = 1_000,
        unresolved: Boolean = false,
    ) = ApolloCandidatePolicy(reviews).evaluate(identity, setup, rollback, stationary, confirmation, "trip-1", now, unresolved)

    private fun assertBlocked(reason: ApolloCandidateBlocker, decision: ApolloCandidateDecision) {
        assertTrue(decision is ApolloCandidateDecision.Blocked)
        assertTrue((decision as ApolloCandidateDecision.Blocked).reasons.contains(reason))
    }

    @Test fun shippedPolicyHasNoProductionApproval() {
        val decision = ApolloCandidatePolicy().evaluate(identity, setup, rollback, stationary, confirmation, "trip-1", 1_000, false)
        assertBlocked(ApolloCandidateBlocker.OPERATION_NOT_REVIEWED, decision)
    }

    @Test fun completeInjectedReviewProducesOnlyAnImmutableReviewPlan() {
        val decision = decide() as ApolloCandidateDecision.Prepared
        assertEquals("fixture-review", decision.plan.reviewId)
        assertEquals(identity, decision.plan.identity)
        assertEquals(rollback.backupDigest, decision.plan.backupDigest)
    }

    @Test fun matchingProductCannotAdmitDifferentFirmwareOrPackage() {
        assertBlocked(ApolloCandidateBlocker.OPERATION_NOT_REVIEWED,
            decide(identity = identity.copy(settingsApk = "1".repeat(64))))
        assertBlocked(ApolloCandidateBlocker.OPERATION_NOT_REVIEWED,
            decide(identity = identity.copy(fingerprint = "other/build")))
        assertBlocked(ApolloCandidateBlocker.EXACT_IDENTITY_MISSING, decide(identity = identity.copy(providerApk = "")))
    }

    @Test fun ambiguousReviewAndMissingMethodEvidenceStayClosed() {
        assertBlocked(ApolloCandidateBlocker.OPERATION_NOT_REVIEWED, decide(reviews = listOf(review, review)))
        assertBlocked(ApolloCandidateBlocker.INTERFACE_NOT_REVIEWED,
            decide(reviews = listOf(review.copy(signatures = review.signatures.drop(1).toSet()))))
        assertBlocked(ApolloCandidateBlocker.OPERATION_NOT_REVIEWED,
            decide(reviews = listOf(review.copy(controllerEffectsQualified = false))))
    }

    @Test fun everyOriginalFlagAndIndependentBackupIsRequired() {
        for (scope in ApolloBackupScope.entries) {
            assertBlocked(ApolloCandidateBlocker.ROLLBACK_NOT_VERIFIED, decide(rollback = rollback.copy(scopes = rollback.scopes - scope)))
        }
        assertBlocked(ApolloCandidateBlocker.ROLLBACK_NOT_VERIFIED,
            decide(rollback = rollback.copy(originalFlags = rollback.originalFlags.drop(1).toSet())))
        assertBlocked(ApolloCandidateBlocker.ROLLBACK_NOT_VERIFIED, decide(rollback = rollback.copy(restorationVerified = false)))
        assertBlocked(ApolloCandidateBlocker.ROLLBACK_NOT_VERIFIED, decide(rollback = rollback.copy(copiedOffDevice = false)))
        assertBlocked(ApolloCandidateBlocker.ROLLBACK_NOT_VERIFIED,
            decide(rollback = rollback.copy(identity = identity.copy(canApk = "2".repeat(64)))))
    }

    @Test fun historicalFutureOrWrongSessionReadingsCannotSatisfyParkGate() {
        for (sample in listOf(stationary.copy(observedAtElapsedMs = 499), stationary.copy(observedAtElapsedMs = 1_001),
            stationary.copy(gear = "Drive"), stationary.copy(speedKph = 1), stationary.copy(producerQualified = false),
            stationary.copy(session = "previous-trip"))) {
            assertBlocked(ApolloCandidateBlocker.FRESH_PARK_AND_ZERO_REQUIRED, decide(stationary = sample))
        }
    }

    @Test fun confirmationBindsExactIdentitySessionOperationBackupAndTime() {
        for (consent in listOf(confirmation.copy(operation = "OtherOperation"), confirmation.copy(session = "previous-trip"),
            confirmation.copy(backupDigest = "9".repeat(64)), confirmation.copy(identity = identity.copy(product = "other")),
            confirmation.copy(confirmedAtElapsedMs = 1_001))) {
            assertBlocked(ApolloCandidateBlocker.EXPLICIT_CONFIRMATION_REQUIRED, decide(confirmation = consent))
        }
        assertBlocked(ApolloCandidateBlocker.EXPLICIT_CONFIRMATION_REQUIRED, decide(now = 16_001))
    }

    @Test fun subscriptionCandidateDoesNotBypassRealLearningOrEquipment() {
        assertBlocked(ApolloCandidateBlocker.EQUIPMENT_NOT_CONFIRMED, decide(setup = setup.copy(sdb = 0)))
        assertBlocked(ApolloCandidateBlocker.EQUIPMENT_NOT_CONFIRMED, decide(setup = setup.copy(asc = null)))
        assertBlocked(ApolloCandidateBlocker.FACTORY_SETUP_NOT_CONFIRMED,
            decide(setup = setup.copy(genuineLearningConfirmed = false)))
        assertBlocked(ApolloCandidateBlocker.FACTORY_SETUP_NOT_CONFIRMED,
            decide(setup = setup.copy(genuineAccountConfirmed = false)))
    }

    @Test fun unresolvedAttemptNeverBecomesEligibleAgainAutomatically() {
        assertBlocked(ApolloCandidateBlocker.PREVIOUS_ATTEMPT_UNRESOLVED, decide(unresolved = true))
    }
}
