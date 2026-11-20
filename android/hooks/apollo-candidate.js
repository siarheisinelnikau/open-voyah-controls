// This module is deliberately absent from every production entry point.
// It exercises a reversible candidate against an independently authored, synchronous fixture.
export const APOLLO_CANDIDATE_OPERATION = 'ApolloSubscriptionCandidate';
export const APOLLO_FIXTURE_PACKAGE = 'org.openvoyah.controls.fixture.apollo';
export const SHIPPED_APOLLO_APPROVALS = Object.freeze([]);
export const APOLLO_AFFECTED_FLAGS = Object.freeze([
    'RPA_FUNC_ENABLE', 'HPP_FUNC_ENABLE', 'GLC_FUNC_ENABLE', 'ISLC_FUNC_ENABLE',
    'TLC_FUNC_ENABLE', 'NOA_FUNC_ENABLE', 'ELK_FUNC_ENABLE', 'ESA_FUNC_ENABLE',
    'APA_FUNC_ENABLE_SA', 'RPA_FUNC_ENABLE_SA', 'HAVP_FUNC_ENABLE_SA', 'ACC_FUNC_ENABLE_SA',
    'ICA_FUNC_ENABLE_SA', 'PLC_FUNC_ENABLE_SA', 'HANP_FUNC_ENABLE_SA', 'ISA_FUNC_ENABLE_SA',
    'ISLC_FUNC_ENABLE_SA', 'TLA_FUNC_ENABLE_SA',
]);
const SCOPES = ['factory_settings', 'provider', 'account', 'vehicle_configuration', 'adcu_flags'];
const IDENTITY_FIELDS = ['packageName', 'product', 'fingerprint', 'sdk', 'abi', 'settingsApk',
    'settingsCertificate', 'canApk', 'canCertificate', 'providerApk', 'providerCertificate'];
const SIGNATURES = Object.freeze([
    'BaiduProviderUtil.doQuerySubscribeInfo(android.content.Context):java.lang.String',
    'DriveAssistanceAdasStatusManager.instance(android.content.Context):DriveAssistanceAdasStatusManager',
    'DriveAssistanceAdasStatusManager.sendAdasSubStatusToADCU(boolean):void',
    'DriveAssistanceAdasStatusManager.asyncQueryAdasSubData():void',
]);
export const APOLLO_REQUIRED_SIGNATURES = SIGNATURES;
const hash = value => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value);
const sameIdentity = (a, b) => a && b && IDENTITY_FIELDS.every(key => a[key] === b[key]);
const sameSet = (a, b) => Array.isArray(a) && a.length === b.length && new Set(a).size === a.length &&
    b.every(value => a.includes(value));
const copy = value => JSON.parse(JSON.stringify(value));
const fresh = (at, now, maximum) => Number.isSafeInteger(at) && Number.isSafeInteger(now) &&
    at >= 0 && now >= at && now - at <= maximum;
function sync(value) {
    if (value && typeof value.then === 'function') {
        const error = Error('Asynchronous fixture work is unsupported');
        error.pendingAsync = true;
        throw error;
    }
    return value;
}

/** Accepts plain fixture objects only. No Java bridge, native shell or real factory adapter is supplied. */
export function createApolloFixtureAdapter(fixture) {
    if (fixture?.kind !== 'isolated_apollo_fixture') throw Error('An isolated Apollo fixture is required');
    const first = fixture.inspect?.();
    if (first?.identity?.packageName !== APOLLO_FIXTURE_PACKAGE ||
        first?.identity?.product !== 'ovc_apollo_fixture') throw Error('An isolated Apollo fixture is required');
    const provider = fixture.provider;
    const manager = fixture.statusManager;
    if (typeof provider?.doQuerySubscribeInfo !== 'function' ||
        typeof manager?.sendAdasSubStatusToADCU !== 'function' ||
        typeof manager?.asyncQueryAdasSubData !== 'function' ||
        ['snapshot', 'restore', 'verifyRestore', 'recordIntent', 'recordRestored', 'hasUnresolved']
            .some(name => typeof fixture[name] !== 'function')) throw Error('Incomplete fixture contract');
    const originalQuery = provider.doQuerySubscribeInfo;
    let overrideInstalled = false;
    let ownedQuery = null;
    return Object.freeze({
        inspect: () => copy(sync(fixture.inspect())),
        snapshot: () => copy(sync(fixture.snapshot())),
        hasUnresolved: () => sync(fixture.hasUnresolved()),
        recordIntent: record => sync(fixture.recordIntent(copy(record))),
        recordRestored: id => sync(fixture.recordRestored(id)),
        installQueryOverride() {
            if (overrideInstalled || provider.doQuerySubscribeInfo !== originalQuery) throw Error('Query owner changed');
            // These are the observed stock data fields, independent of genuine learning and login state.
            ownedQuery = () => JSON.stringify({subscriptionStatus: '1', expireStatus: '0',
                remainDays: '30', expiredDays: '0', isMqtt: false});
            provider.doQuerySubscribeInfo = ownedQuery;
            overrideInstalled = true;
        },
        sendStatus: () => sync(manager.sendAdasSubStatusToADCU(true)),
        refresh: () => sync(manager.asyncQueryAdasSubData()),
        detachQuery() {
            if (overrideInstalled) {
                if (provider.doQuerySubscribeInfo !== ownedQuery) throw Error('Query owner changed');
                provider.doQuerySubscribeInfo = originalQuery;
            }
            overrideInstalled = false;
        },
        restore: snapshot => sync(fixture.restore(copy(snapshot))),
        verifyRestore: snapshot => sync(fixture.verifyRestore(copy(snapshot))),
    });
}

function blockers(evidence, reviews, request, now, unresolved) {
    const reasons = [];
    const identity = evidence?.identity;
    const hashes = IDENTITY_FIELDS.slice(5);
    if (!identity || identity.packageName !== APOLLO_FIXTURE_PACKAGE || identity.product !== 'ovc_apollo_fixture' ||
        typeof identity.fingerprint !== 'string' || identity.fingerprint.length < 1 ||
        ![28, 30].includes(identity.sdk) || identity.abi !== 'x86_64' || hashes.some(key => !hash(identity[key])))
        reasons.push('exact_fixture_identity_missing');
    const matches = Array.isArray(reviews) ? reviews.filter(review => review?.environment === 'isolated_fixture' &&
        review.operation === APOLLO_CANDIDATE_OPERATION && sameIdentity(review.identity, identity) &&
        typeof review.reviewId === 'string' && /^[A-Za-z0-9_.-]{1,80}$/.test(review.reviewId) &&
        review.controllerEffectsQualified === true && sameSet(review.signatures, SIGNATURES)) : [];
    if (matches.length !== 1) reasons.push('exact_operation_not_reviewed');
    if (evidence?.setup?.baiduPlatformMatched !== true || evidence.setup.asc !== 1 || evidence.setup.sdb !== 1)
        reasons.push('equipment_not_confirmed');
    if (evidence?.setup?.genuineAccountConfirmed !== true || evidence.setup.genuineLearningConfirmed !== true ||
        evidence.setup.factoryTlcConfirmed !== true) reasons.push('factory_setup_not_confirmed');
    const backup = evidence?.backup;
    if (!backup || !sameIdentity(backup.identity, identity) || !hash(backup.digest) ||
        backup.copiedOffDevice !== true || backup.independentlyRestorable !== true || backup.restorationVerified !== true ||
        !sameSet(backup.scopes, SCOPES) || !sameSet(backup.originalFlags, APOLLO_AFFECTED_FLAGS))
        reasons.push('rollback_not_verified');
    const stationary = evidence?.stationary;
    if (typeof evidence?.session !== 'string' || !/^[A-Za-z0-9_.-]{1,80}$/.test(evidence.session) ||
        stationary?.session !== evidence.session || stationary.gear !== 'Parking' || stationary.speedKph !== 0 ||
        stationary.producerQualified !== true || !fresh(stationary.observedAtMs, now, 500))
        reasons.push('fresh_park_and_zero_required');
    if (!request || !/^[a-f0-9]{32}$/.test(request.id) || request.operation !== APOLLO_CANDIDATE_OPERATION ||
        request.confirmed !== true || request.session !== evidence?.session ||
        !sameIdentity(request.identity, identity) || request.backupDigest !== backup?.digest ||
        !fresh(request.confirmedAtMs, now, 15_000)) reasons.push('explicit_confirmation_required');
    if (unresolved !== false) reasons.push('previous_attempt_unresolved');
    return reasons;
}

/** Runs one disposable fixture trial and restores it. No accepted response claims actual activation. */
export function createApolloCandidateTrial(adapter, {reviews = SHIPPED_APOLLO_APPROVALS, now} = {}) {
    if (typeof now !== 'function') throw Error('A monotonic clock is required');
    let consumed = false;
    return Object.freeze({
        run(request) {
            if (consumed) return {state: 'blocked', reasons: ['attempt_already_consumed'], productionActivated: false};
            const check = () => blockers(adapter.inspect(), reviews, request, now(), adapter.hasUnresolved());
            let denied, snapshot;
            try {
                denied = check();
                if (denied.length === 0) snapshot = adapter.snapshot();
            } catch (_) {
                return {state: 'blocked', reasons: ['read_only_preflight_unavailable'], productionActivated: false};
            }
            if (denied.length > 0) return {state: 'blocked', reasons: denied, productionActivated: false};
            const originalFlags = snapshot?.flags;
            if (!originalFlags || !sameSet(Object.keys(originalFlags), APOLLO_AFFECTED_FLAGS) ||
                Object.values(originalFlags).some(value => !Number.isSafeInteger(value)))
                return {state: 'blocked', reasons: ['original_controller_values_missing'], productionActivated: false};
            consumed = true;
            let began = false;
            let intentAttempted = false;
            let touched = false;
            let failure = null;
            let pendingAsync = false;
            let restored = false;
            try {
                intentAttempted = true;
                if (adapter.recordIntent({id: request.id, operation: APOLLO_CANDIDATE_OPERATION,
                    session: request.session, backupDigest: request.backupDigest}) !== true)
                    throw Error('intent_not_durable');
                began = true;
                // This trial owns the unresolved journal entry. Recheck every other prerequisite before writes.
                const guard = () => {
                    const reasons = blockers(adapter.inspect(), reviews, request, now(), false);
                    if (reasons.length > 0) throw Error(reasons.join(','));
                };
                guard();
                touched = true;
                adapter.installQueryOverride();
                guard();
                adapter.sendStatus();
                guard();
                adapter.refresh();
            } catch (error) {
                pendingAsync = error?.pendingAsync === true;
                failure = typeof error?.message === 'string' ? error.message.slice(0, 160) : 'candidate_failed';
            } finally {
                if (touched) {
                    let queryDetached = false;
                    try { adapter.detachQuery(); queryDetached = true; } catch (_) { /* Preserve another owner. */ }
                    try {
                        // Never replace individual original values with a blanket "disabled" request.
                        const stateRestored = adapter.restore(snapshot) === true && adapter.verifyRestore(snapshot) === true;
                        restored = queryDetached && stateRestored;
                    } catch (_) { restored = false; }
                } else if (began) {
                    try { restored = adapter.verifyRestore(snapshot) === true; } catch (_) { restored = false; }
                } else restored = !intentAttempted;
                if (pendingAsync) restored = false;
                if (restored && began) {
                    try { restored = adapter.recordRestored(request.id) === true; } catch (_) { restored = false; }
                }
            }
            return {state: restored ? (failure ? 'failed_restored' : 'fixture_exercised') : 'unresolved',
                failure, restorationVerified: restored, productionActivated: false, replayAllowed: false};
        },
    });
}
