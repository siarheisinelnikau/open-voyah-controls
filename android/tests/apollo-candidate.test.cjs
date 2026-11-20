const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../hooks/apollo-candidate.js'), 'utf8');
const env = {};
vm.createContext(env);
vm.runInContext(source.replace(/^export /gm, '') +
    '\nglobalThis.api={createApolloFixtureAdapter,createApolloCandidateTrial,APOLLO_CANDIDATE_OPERATION,' +
    'APOLLO_FIXTURE_PACKAGE,APOLLO_AFFECTED_FLAGS,APOLLO_REQUIRED_SIGNATURES};', env);
const api = env.api;
const clone = value => JSON.parse(JSON.stringify(value));

function fixture() {
    const identity = {packageName: api.APOLLO_FIXTURE_PACKAGE, product: 'ovc_apollo_fixture',
        fingerprint: 'ovc/apollo-fixture:11/qualification', sdk: 30, abi: 'x86_64'};
    for (const key of ['settingsApk', 'settingsCertificate', 'canApk', 'canCertificate', 'providerApk', 'providerCertificate'])
        identity[key] = 'a'.repeat(64);
    const evidence = {identity, session: 'fixture-session',
        setup: {baiduPlatformMatched: true, asc: 1, sdb: 1, genuineAccountConfirmed: true,
            genuineLearningConfirmed: true, factoryTlcConfirmed: true},
        stationary: {session: 'fixture-session', gear: 'Parking', speedKph: 0, observedAtMs: 9_900, producerQualified: true},
        backup: {identity: clone(identity), digest: 'b'.repeat(64), copiedOffDevice: true,
            independentlyRestorable: true, restorationVerified: true,
            scopes: ['factory_settings', 'provider', 'account', 'vehicle_configuration', 'adcu_flags'],
            originalFlags: Array.from(api.APOLLO_AFFECTED_FLAGS)}};
    const events = [];
    let flags = Object.fromEntries(Array.from(api.APOLLO_AFFECTED_FLAGS, (key, i) => [key, i % 2 + 1]));
    let stores = {settings: {genuineLearning: true}, providerCache: 'original'};
    let unresolved = false;
    const provider = {doQuerySubscribeInfo: () => '{"subscriptionStatus":"0"}'};
    const originalQuery = provider.doQuerySubscribeInfo;
    const target = {kind: 'isolated_apollo_fixture', provider,
        inspect: () => evidence,
        snapshot: () => ({flags, stores}),
        restore: snapshot => { events.push('restore'); flags = clone(snapshot.flags); stores = clone(snapshot.stores); return true; },
        verifyRestore: snapshot => JSON.stringify({flags, stores}) === JSON.stringify(snapshot),
        hasUnresolved: () => unresolved,
        recordIntent: () => { events.push('intent'); unresolved = true; return true; },
        recordRestored: () => { events.push('restored'); unresolved = false; return true; },
        statusManager: {
            sendAdasSubStatusToADCU: enabled => { events.push('status'); assert.equal(enabled, true);
                flags = Object.fromEntries(Array.from(api.APOLLO_AFFECTED_FLAGS, key => [key, 2])); },
            asyncQueryAdasSubData: () => { events.push('refresh');
                const data = JSON.parse(provider.doQuerySubscribeInfo());
                assert.equal(data.subscriptionStatus, '1'); assert.equal(data.expireStatus, '0');
                assert.equal(stores.settings.genuineLearning, true);
                stores.providerCache = data;
            },
        }};
    const request = {id: 'c'.repeat(32), operation: api.APOLLO_CANDIDATE_OPERATION, confirmed: true,
        identity: clone(identity), session: evidence.session, backupDigest: evidence.backup.digest, confirmedAtMs: 9_800};
    const reviews = [{environment: 'isolated_fixture', operation: api.APOLLO_CANDIDATE_OPERATION,
        reviewId: 'fixture-reviewed', identity: clone(identity), signatures: Array.from(api.APOLLO_REQUIRED_SIGNATURES),
        controllerEffectsQualified: true}];
    const initial = clone(target.snapshot());
    const adapter = api.createApolloFixtureAdapter(target);
    const trial = options => api.createApolloCandidateTrial(adapter, {reviews, now: () => 10_000, ...options});
    return {target, evidence, events, request, reviews, trial, initial, originalQuery};
}

test('default shipped candidate denies even a complete fixture approval record', () => {
    const f = fixture();
    const result = f.trial({reviews: undefined}).run(f.request);
    assert.equal(result.state, 'blocked');
    assert.ok(result.reasons.includes('exact_operation_not_reviewed'));
    assert.deepEqual(f.events, []);
});

test('one explicit fixture trial substitutes the query, dispatches once and restores all original values', () => {
    const f = fixture();
    const trial = f.trial();
    const result = trial.run(f.request);
    assert.equal(result.state, 'fixture_exercised');
    assert.equal(result.productionActivated, false);
    assert.equal(result.restorationVerified, true);
    assert.deepEqual(f.events, ['intent', 'status', 'refresh', 'restore', 'restored']);
    assert.deepEqual(f.target.snapshot(), f.initial);
    assert.equal(f.target.provider.doQuerySubscribeInfo, f.originalQuery);
    assert.equal(trial.run(f.request).state, 'blocked');
});

test('identity, consent, equipment, learning, backup and stale state failures cause no writes', () => {
    const cases = [
        f => { f.evidence.identity.settingsApk = 'd'.repeat(64); },
        f => { f.request.confirmed = false; },
        f => { f.request.session = 'prior-session'; },
        f => { f.evidence.setup.sdb = 0; },
        f => { f.evidence.setup.genuineLearningConfirmed = false; },
        f => { f.evidence.backup.originalFlags.pop(); },
        f => { f.evidence.backup.independentlyRestorable = false; },
        f => { f.evidence.stationary.observedAtMs = 9_499; },
        f => { f.evidence.stationary.observedAtMs = 10_001; },
        f => { f.evidence.stationary.gear = 'Drive'; },
        f => { f.evidence.stationary.speedKph = 1; },
    ];
    for (const change of cases) {
        const f = fixture(); change(f);
        assert.equal(f.trial().run(f.request).state, 'blocked');
        assert.deepEqual(f.events, []);
    }
});

test('factory process cannot construct the fixture adapter', () => {
    const f = fixture();
    f.evidence.identity.packageName = 'com.qinggan.app.vehiclesetting';
    assert.throws(() => api.createApolloFixtureAdapter(f.target), /isolated/);
    assert.deepEqual(f.events, []);
});

test('a state change after the first dispatch prevents refresh and restores the snapshot', () => {
    const f = fixture();
    const original = f.target.statusManager.sendAdasSubStatusToADCU;
    f.target.statusManager.sendAdasSubStatusToADCU = value => { original(value); f.evidence.stationary.gear = 'Drive'; };
    const result = f.trial().run(f.request);
    assert.equal(result.state, 'failed_restored');
    assert.deepEqual(f.events, ['intent', 'status', 'restore', 'restored']);
    assert.deepEqual(f.target.snapshot(), f.initial);
});

test('ambiguous dispatch is never replayed, even if fixture rollback succeeds', () => {
    const f = fixture();
    f.target.statusManager.sendAdasSubStatusToADCU = () => { f.events.push('status'); throw Error('transport_lost'); };
    const trial = f.trial();
    assert.equal(trial.run(f.request).state, 'failed_restored');
    assert.equal(trial.run(f.request).state, 'blocked');
    assert.equal(f.events.filter(value => value === 'status').length, 1);
});

test('failed rollback leaves a durable unresolved attempt and blocks another trial', () => {
    const f = fixture(); f.target.restore = () => false;
    assert.equal(f.trial().run(f.request).state, 'unresolved');
    assert.equal(f.target.provider.doQuerySubscribeInfo, f.originalQuery);
    assert.ok(f.trial().run(f.request).reasons.includes('previous_attempt_unresolved'));
    assert.equal(f.events.filter(value => value === 'status').length, 1);
});

test('query ownership loss is preserved and reported unresolved while other state restoration still runs', () => {
    const f = fixture();
    const other = () => 'another-owner';
    f.target.statusManager.asyncQueryAdasSubData = () => { f.target.provider.doQuerySubscribeInfo = other; };
    assert.equal(f.trial().run(f.request).state, 'unresolved');
    assert.equal(f.target.provider.doQuerySubscribeInfo, other);
    assert.deepEqual(f.target.snapshot(), f.initial);
});

test('a failed journal commit blocks all candidate writes and cannot be retried through the same trial', () => {
    const f = fixture(); f.target.recordIntent = () => false;
    const trial = f.trial();
    assert.equal(trial.run(f.request).state, 'unresolved');
    assert.deepEqual(f.events, []);
    assert.equal(trial.run(f.request).state, 'blocked');
});

test('unsupported asynchronous fixture work stays unresolved even after restoring the immediate snapshot', () => {
    const f = fixture();
    f.target.statusManager.asyncQueryAdasSubData = () => Promise.resolve();
    const result = f.trial().run(f.request);
    assert.equal(result.state, 'unresolved');
    assert.equal(result.restorationVerified, false);
    assert.deepEqual(f.target.snapshot(), f.initial);
    assert.equal(f.target.provider.doQuerySubscribeInfo, f.originalQuery);
    assert.ok(f.trial().run(f.request).reasons.includes('previous_attempt_unresolved'));
});

test('production entry points do not load the candidate module', () => {
    const hooks = path.join(__dirname, '../hooks');
    for (const name of fs.readdirSync(hooks).filter(name => name === 'entry.js' || name.endsWith('-entry.js')))
        assert.doesNotMatch(fs.readFileSync(path.join(hooks, name), 'utf8'), /apollo-candidate/);
});
