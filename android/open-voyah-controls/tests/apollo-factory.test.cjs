const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = ['report.js', 'apollo-factory.js']
    .map(file => fs.readFileSync(path.join(__dirname, '../hooks', file), 'utf8')
        .replace(/^import .*;$/gm, '').replace(/^export /gm, '')).join('\n');

const CONFIG_KEY = 'org.openvoyah.controls.apolloFactory';
const STATE_KEY = 'org.openvoyah.controls.apolloFactoryState';
const CLAIM_KEY = 'org.openvoyah.controls.apolloFactoryClaim';
const REQUEST_A = 'a'.repeat(32);
const REQUEST_B = 'b'.repeat(32);
const REQUEST_C = 'c'.repeat(32);
const ISSUED = 1000;
const EXPIRES = 6000;
const BOOT_ID = 'a1b2c3d4-e5f6-1111-2222-333344445555';

function configDoc(parts, request = REQUEST_A, send = false, issued = ISSUED, expires = EXPIRES, boot = BOOT_ID) {
    return JSON.stringify({schema: 1, request,
        ...(send ? {send: true, boot, issued, expires} : {}), ...parts});
}

/** One Java member: overload selection, an implementation slot and a record of what the agent asked for. */
function member(options = {}) {
    const overloads = options.overloads ?? [];
    const target = function (...args) { target.calls.push(args); return options.returns; };
    target.calls = [];
    let implementation = null;
    Object.defineProperty(target, 'implementation', {
        get: () => implementation,
        set: value => {
            const fail = value === null ? options.throwOnRestore?.() : options.throwOnImplement?.();
            if (fail) throw new Error(value === null ? 'hook restore refused' : 'hook refused');
            implementation = value;
        },
    });
    target.overload = (...signature) => {
        if (!overloads.some(known => known.join() === signature.join())) throw new Error('unknown overload');
        return target;
    };
    // Simulates one Java call reaching the class: the agent's answer wins, otherwise the original value.
    target.invoke = (...args) => {
        target.calls.push(args);
        return target.implementation ? target.implementation(...args) : options.returns;
    };
    return target;
}

/**
 * A head unit with the factory settings classes present. `missing` names classes the installed software does not
 * offer; `failImplement` names parts whose second hook assignment throws after the first one was installed.
 */
function headUnit({ config = '', missing = [], sendFails = false, failImplement = [], failRestore = [],
    putFails = [], readFails = [], nullReads = [], claim = '', boot = BOOT_ID } = {}) {
    const published = [];
    const sends = [];
    const failures = { implement: new Set(failImplement), restore: new Set(failRestore) };
    let stateDoc = '';
    let claimValue = claim;
    let now = ISSUED;
    const classes = {
        'android.os.Build': { PRODUCT: { value: 'H97D' } },
        'java.io.FileReader': { $new: path => ({ path }) },
        'java.io.BufferedReader': { $new: reader => ({ readLine: () => reader.path === '/proc/sys/kernel/random/boot_id'
            ? boot : null, close: () => {} }) },
        'android.provider.Settings$Global': {
            getString: (resolver, key) => {
                if (readFails.includes(key)) throw new Error('record unreadable');
                if (nullReads.includes(key)) return null;
                return key === CONFIG_KEY ? config
                    : key === STATE_KEY ? stateDoc : key === CLAIM_KEY ? claimValue : null;
            },
            putString: (resolver, key, value) => {
                published.push({ key, value });
                if (putFails.includes(key)) return false;
                if (key === STATE_KEY) stateDoc = value;
                if (key === CLAIM_KEY) claimValue = value;
                return true;
            },
        },
        'android.app.ActivityThread': {
            currentApplication: () => ({
                getPackageName: () => 'com.qinggan.app.vehiclesetting',
                getContentResolver: () => 'resolver',
                getApplicationContext: () => 'context',
            }),
        },
        'com.qinggan.app.vehiclesetting.fragments.driveassistance.adas.BaiduProviderUtil': {
            doQuerySubscribeInfo: member({ overloads: [['android.content.Context']],
                throwOnImplement: () => failures.implement.has('subscription'),
                throwOnRestore: () => failures.restore.has('subscription') }),
        },
        'com.qinggan.app.vehiclesetting.fragments.driveassistance.adas.DriveAssistanceAdasStatusManager': {
            instance: member({
                overloads: [['android.content.Context']],
                returns: {
                    sendAdasSubStatusToADCU: value => {
                        if (sendFails) throw new Error('controller refused');
                        sends.push(['bundle', value]);
                    },
                    asyncQueryAdasSubData: () => sends.push(['refresh']),
                },
            }),
        },
        'com.qinggan.app.vehiclesetting.fragments.driveassistance.utils.Utils': {
            is97Y: member({ overloads: [[]], returns: false,
                throwOnImplement: () => failures.implement.has('equipment'),
                throwOnRestore: () => failures.restore.has('equipment') }),
            is97C: member({ overloads: [[]], returns: false,
                throwOnImplement: () => failures.implement.has('equipment'),
                throwOnRestore: () => failures.restore.has('equipment') }),
        },
        'com.qinggan.utils.AppCommonUtils': {
            isOverSeaVehicle: member({ overloads: [[]], returns: true,
                throwOnImplement: () => failures.implement.has('region'),
                throwOnRestore: () => failures.restore.has('region') }),
        },
    };
    for (const name of missing) delete classes[name];
    let tick = null;
    const logs = [];
    const api = vm.runInNewContext(source + '\n({installApolloFactory, apolloConfig, apolloState, SUBSCRIPTION_ANSWER, APOLLO_STATE_KEY, APOLLO_CLAIM_KEY})',
        { console: { log: line => logs.push(line) }, setInterval: fn => (tick = fn), Date: { now: () => now },
          JSON, Object, Number, Set, String });
    const Java = { perform: run => run(), use: name => { if (!(name in classes)) throw new Error('missing ' + name); return classes[name]; } };
    api.installApolloFactory(Java);
    return {
        api, classes, logs, published, sends, Java, tick,
        setNow: value => { now = value; },
        healRestore: () => failures.restore.clear(),
        failImplementNow: part => { failures.implement.clear(); failures.implement.add(part); },
        claim: () => claimValue,
        state: () => JSON.parse(published.at(-1).value),
        install: () => api.installApolloFactory(Java),
        config: value => {
            published.length = 0;
            config = value;
        },
    };
}

test('only the document Controls writes asks for anything, and an unreadable one asks for nothing', () => {
    const { apolloConfig } = headUnit().api;
    assert.deepEqual({ ...apolloConfig(configDoc({ subscription: true })) },
        { subscription: true, request: REQUEST_A, send: false });
    assert.deepEqual({ ...apolloConfig(configDoc({ controller: true, equipment: true }, REQUEST_B, true)) },
        { controller: true, equipment: true, request: REQUEST_B, send: true, boot: BOOT_ID,
          issued: ISSUED, expires: EXPIRES });
    for (const raw of ['', null, 'not json', '{"schema":2,"subscription":true}', '{"schema":1}',
        '{"schema":1,"subscription":false}', '{"schema":1,"subscription":"yes"}', '{"schema":1,"other":true}',
        '{"schema":1,"subscription":true}', configDoc({ subscription: true }, 'not-a-request'),
        configDoc({ subscription: true }, REQUEST_A.toUpperCase()),
        configDoc({}, REQUEST_A), configDoc({ subscription: true }).padEnd(600, 'x')]) {
        assert.equal(apolloConfig(raw), null, String(raw));
    }
    // A permit without a controller switch, a deadline or a wall-clock issue time is not a send request.
    assert.equal(apolloConfig(JSON.stringify({schema: 1, request: REQUEST_A, send: true, subscription: true})).send, false);
    for (const bad of [{issued: ISSUED}, {expires: EXPIRES}, {issued: 0, expires: EXPIRES},
        {issued: EXPIRES, expires: ISSUED}, {issued: '1000', expires: EXPIRES},
        {issued: ISSUED, expires: EXPIRES, boot: 'not-a-boot-id'}, {issued: ISSUED, expires: EXPIRES}]) {
        assert.equal(apolloConfig(JSON.stringify({schema: 1, request: REQUEST_A, send: true, controller: true,
            ...bad})).send, false, JSON.stringify(bad));
    }
});

test('the published state ties each part to the request and never claims a verified controller state', () => {
    const { apolloState } = headUnit().api;
    assert.deepEqual(JSON.parse(apolloState(['subscription'], null, null, new Set(['controller']), new Set(), REQUEST_A, 2)),
        { schema: 1, request: REQUEST_A, sent: 2, subscription: 'applied', controller: 'unavailable', equipment: 'off' });
    assert.deepEqual(JSON.parse(apolloState([], REQUEST_B, null, new Set(), new Set(), REQUEST_B, 1)),
        { schema: 1, request: REQUEST_B, sent: 1, subscription: 'off', controller: 'requested', equipment: 'off' });
    assert.deepEqual(JSON.parse(apolloState([], null, REQUEST_C, new Set(), new Set(), REQUEST_C, 1)),
        { schema: 1, request: REQUEST_C, sent: 1, subscription: 'off', controller: 'uncertain', equipment: 'off' });
    assert.deepEqual(JSON.parse(apolloState([], null, null, new Set(), new Set(['equipment']), REQUEST_A, 0)),
        { schema: 1, request: REQUEST_A, sent: 0, subscription: 'off', controller: 'off', equipment: 'partial' });
    assert.deepEqual(JSON.parse(apolloState([], null, null, new Set(), new Set(), null, 0)),
        { schema: 1, sent: 0, subscription: 'off', controller: 'off', equipment: 'off' });
});

test('the subscription answer exists only while the driver wants it and is taken back afterwards', () => {
    const unit = headUnit();
    const query = unit.classes['com.qinggan.app.vehiclesetting.fragments.driveassistance.adas.BaiduProviderUtil']
        .doQuerySubscribeInfo;
    const { SUBSCRIPTION_ANSWER, APOLLO_STATE_KEY, APOLLO_CLAIM_KEY } = unit.api;

    unit.config(configDoc({ subscription: true }));
    unit.tick();
    assert.equal(query.invoke('context'), SUBSCRIPTION_ANSWER);
    assert.equal(unit.state().subscription, 'applied');
    assert.equal(unit.state().request, REQUEST_A);
    assert.equal(unit.published.at(-1).key, APOLLO_STATE_KEY);
    assert.equal(APOLLO_CLAIM_KEY, CLAIM_KEY);

    // The same document read again changes nothing, so the factory page is not disturbed every second.
    const answers = () => query.calls.length;
    const before = answers();
    unit.tick();
    assert.equal(answers(), before);

    unit.config('');
    unit.tick();
    assert.equal(query.implementation, null);
    assert.equal(unit.state().subscription, 'off');
});

test('a controller request is claimed before the call, sent once, and never repeated by another switch', () => {
    const unit = headUnit();

    unit.config(configDoc({ controller: true }, REQUEST_A, true));
    unit.tick();
    assert.deepEqual(unit.sends, [['bundle', true], ['refresh']]);
    assert.equal(unit.claim(), REQUEST_A);
    assert.equal(unit.state().controller, 'requested');
    assert.equal(unit.state().sent, 1);

    // Turning another part on or off creates a new request for the factory page, not another ADCU send.
    unit.config(configDoc({ controller: true, subscription: true }, REQUEST_B));
    unit.tick();
    assert.equal(unit.sends.length, 2);
    assert.equal(unit.state().request, REQUEST_B);
    // The earlier request is not carried under the new id.
    assert.equal(unit.state().controller, 'off');
    unit.config(configDoc({ controller: true }, REQUEST_C));
    unit.tick();
    assert.equal(unit.sends.length, 2);

    unit.config('');
    unit.tick();
    assert.equal(unit.state().controller, 'off');
});

test('a controller switch without a Park-checked permit sends nothing', () => {
    const unit = headUnit();
    unit.config(configDoc({ controller: true }, REQUEST_A));
    unit.tick();
    assert.equal(unit.sends.length, 0);
    assert.equal(unit.claim(), '');
    assert.equal(unit.state().controller, 'off');
});

test('a permit from another boot is refused even when the clock is back inside its window', () => {
    const otherBoot = 'b1b2c3d4-e5f6-2222-3333-444455556666';
    const unit = headUnit({ boot: otherBoot });
    unit.config(configDoc({ controller: true }, REQUEST_A, true, ISSUED, EXPIRES, BOOT_ID));
    unit.tick();
    assert.equal(unit.sends.length, 0);
    assert.equal(unit.claim(), '');
    assert.equal(unit.state().controller, 'off');
});

test('no send happens unless the replay claim was stored successfully', () => {
    const unit = headUnit({ putFails: [CLAIM_KEY] });
    unit.config(configDoc({ controller: true }, REQUEST_A, true));
    unit.tick();
    assert.equal(unit.sends.length, 0);
    assert.equal(unit.claim(), '');
    assert.equal(unit.state().controller, 'unavailable');
});

test('a permit issued before a reboot or with a reset clock cannot send for the boot agent', () => {
    for (const [issued, expires, now] of [[ISSUED, EXPIRES, ISSUED + 60000], [900000, 906000, 200]]) {
        const unit = headUnit();
        unit.setNow(now);
        unit.config(configDoc({ controller: true }, REQUEST_A, true, issued, expires));
        unit.tick();
        assert.equal(unit.sends.length, 0, `${issued}/${expires} at ${now}`);
        assert.equal(unit.claim(), '');
        assert.equal(unit.state().controller, 'off');
    }
});

test('a claim with no recorded result is reported uncertain, never sent again after a restart', () => {
    const unit = headUnit({ claim: REQUEST_A });
    unit.config(configDoc({ controller: true }, REQUEST_A, true));
    unit.tick();
    assert.equal(unit.sends.length, 0);
    assert.equal(unit.state().controller, 'uncertain');

    unit.install();
    unit.tick();
    assert.equal(unit.sends.length, 0);
    assert.equal(unit.state().controller, 'uncertain');
});

test('an unreadable previous record blocks a send instead of treating it as an empty history', () => {
    for (const key of [STATE_KEY, CLAIM_KEY]) {
        const unit = headUnit({ readFails: [key] });
        unit.config(configDoc({ controller: true }, REQUEST_A, true));
        unit.tick();
        assert.equal(unit.sends.length, 0, key);
        assert.equal(unit.claim(), '', key);
        assert.equal(unit.state().controller, 'uncertain', key);
    }
});

test('a null read of either replay record blocks the controller request', () => {
    for (const [key, claim] of [[STATE_KEY, ''], [CLAIM_KEY, REQUEST_A]]) {
        const unit = headUnit({ nullReads: [key], claim });
        unit.config(configDoc({ controller: true }, REQUEST_A, true));
        unit.tick();
        assert.equal(unit.sends.length, 0, key);
        assert.equal(unit.claim(), claim, key);
        assert.equal(unit.state().controller, 'uncertain', key);
    }
});

test('a rejected send is reported as unavailable, never as a request the controller accepted', () => {
    const unit = headUnit({ sendFails: true });
    unit.config(configDoc({ controller: true }, REQUEST_A, true));
    unit.tick();
    assert.equal(unit.sends.length, 0);
    assert.equal(unit.state().controller, 'uncertain');
    assert.equal(unit.state().sent, 0);
    // The claim stays, so the same request is never sent twice even if the process restarts.
    unit.install();
    unit.tick();
    assert.equal(unit.sends.length, 0);
});

test('the equipment part answers the factory page without touching the readings behind it', () => {
    const unit = headUnit();
    const utils = unit.classes['com.qinggan.app.vehiclesetting.fragments.driveassistance.utils.Utils'];
    const region = unit.classes['com.qinggan.utils.AppCommonUtils'];

    unit.config(configDoc({ equipment: true }, REQUEST_A));
    unit.tick();
    assert.equal(utils.is97C.invoke(), true);
    assert.equal(utils.is97Y.invoke(), false);
    assert.equal(region.isOverSeaVehicle.invoke(), false);
    assert.equal(unit.state().equipment, 'applied');

    unit.config('');
    unit.tick();
    assert.equal(utils.is97C.implementation, null);
    assert.equal(region.isOverSeaVehicle.implementation, null);
    assert.equal(unit.state().equipment, 'off');
});

test('a partial equipment branch is installed not at all, so unavailable cannot hide a running substitution', () => {
    for (const missing of ['com.qinggan.app.vehiclesetting.fragments.driveassistance.utils.Utils',
        'com.qinggan.utils.AppCommonUtils']) {
        const unit = headUnit({ missing: [missing] });
        const utils = unit.classes['com.qinggan.app.vehiclesetting.fragments.driveassistance.utils.Utils'];
        const region = unit.classes['com.qinggan.utils.AppCommonUtils'];
        const query = unit.classes['com.qinggan.app.vehiclesetting.fragments.driveassistance.adas.BaiduProviderUtil']
            .doQuerySubscribeInfo;

        unit.config(configDoc({ subscription: true, equipment: true }, REQUEST_A));
        unit.tick();
        assert.equal(unit.state().equipment, 'unavailable');
        for (const predicate of [utils?.is97Y, utils?.is97C, region?.isOverSeaVehicle]) {
            if (predicate) assert.equal(predicate.implementation, null, missing);
        }
        assert.notEqual(query.implementation, null);
        assert.equal(unit.state().subscription, 'applied');
    }
});

test('a hook installation that throws mid-part rolls back the hooks installed before it', () => {
    for (const failImplement of ['equipment', 'region', 'subscription']) {
        const unit = headUnit({ failImplement: [failImplement] });
        const utils = unit.classes['com.qinggan.app.vehiclesetting.fragments.driveassistance.utils.Utils'];
        const region = unit.classes['com.qinggan.utils.AppCommonUtils'];
        const query = unit.classes['com.qinggan.app.vehiclesetting.fragments.driveassistance.adas.BaiduProviderUtil']
            .doQuerySubscribeInfo;

        unit.config(configDoc({ subscription: true, equipment: true }, REQUEST_A));
        unit.tick();
        if (failImplement === 'subscription') {
            assert.equal(query.implementation, null);
            assert.equal(unit.state().subscription, 'unavailable');
        } else {
            assert.notEqual(query.implementation, null);
            assert.equal(unit.state().subscription, 'applied');
            assert.equal(unit.state().equipment, 'unavailable');
            for (const predicate of [utils.is97Y, utils.is97C, region.isOverSeaVehicle]) {
                assert.equal(predicate.implementation, null, failImplement);
            }
        }
    }
});

test('a hook that cannot be removed stays on record as a partial substitution until it is restored', () => {
    const unit = headUnit({ failImplement: ['region'], failRestore: ['equipment'] });
    const utils = unit.classes['com.qinggan.app.vehiclesetting.fragments.driveassistance.utils.Utils'];
    const region = unit.classes['com.qinggan.utils.AppCommonUtils'];

    unit.config(configDoc({ subscription: true, equipment: true }, REQUEST_A));
    unit.tick();
    assert.equal(unit.state().equipment, 'partial');
    assert.notEqual(utils.is97C.implementation, null, 'a failed removal is not silently forgotten');
    assert.notEqual(utils.is97Y.implementation, null);
    assert.equal(region.isOverSeaVehicle.implementation, null);
    assert.equal(unit.state().subscription, 'applied');

    unit.config('');
    unit.tick();
    assert.equal(unit.state().equipment, 'partial', 'retry still failing keeps the honest status');

    unit.healRestore();
    unit.tick();
    assert.equal(utils.is97C.implementation, null);
    assert.equal(utils.is97Y.implementation, null);
    assert.equal(unit.state().equipment, 'off');
});

test('a failed reinstall cannot erase an earlier hook that is still pending removal', () => {
    const unit = headUnit({ failImplement: ['region'], failRestore: ['equipment'] });
    const utils = unit.classes['com.qinggan.app.vehiclesetting.fragments.driveassistance.utils.Utils'];

    unit.config(configDoc({ equipment: true }, REQUEST_A));
    unit.tick();
    assert.equal(unit.state().equipment, 'partial');
    assert.notEqual(utils.is97Y.implementation, null);

    // The next attempt now fails on the very first hook, whose earlier removal is still pending.
    unit.failImplementNow('equipment');
    unit.config(configDoc({ equipment: true }, REQUEST_B));
    unit.tick();
    assert.equal(unit.state().equipment, 'partial', 'the pending hook must stay on record');
    assert.notEqual(utils.is97Y.implementation, null);

    unit.healRestore();
    unit.tick();
    assert.equal(utils.is97Y.implementation, null, 'the first hook must still be tracked for removal');
    assert.equal(utils.is97C.implementation, null);
});

test('a factory settings process without the subscription class is reported, not guessed at', () => {
    const unit = headUnit({
        missing: ['com.qinggan.app.vehiclesetting.fragments.driveassistance.adas.BaiduProviderUtil'],
    });
    unit.config(configDoc({ subscription: true }, REQUEST_A));
    unit.tick();
    assert.equal(unit.state().subscription, 'unavailable');
    assert.equal(unit.logs.some(line => line.startsWith('OVC_APOLLO_READY')), true);
});

test('Controls gates the send on live Park, bounds it across reboot and rolls back a failed switch', () => {
    const bridge = fs.readFileSync(path.join(__dirname,
        '../app/src/main/java/org/openvoyah/controls/data/frida/FridaBridge.java'), 'utf8');
    assert.match(bridge, /boolean send = enabled && "controller"\.equals\(part\)/);
    assert.match(bridge, /sendRecognizedVoice\("ParkingPreflight", new JSONObject\(\)\)/);
    assert.match(bridge, /"OVC_PARKING_READY"\.equals\(parking\.message\(\)\)/);
    assert.match(bridge, /String boot = send \? currentBootId\(\) : ""/);
    assert.match(bridge, /currentBootId\(\)|kernel\/random\/boot_id/);
    assert.match(bridge, /long issued = send \? System\.currentTimeMillis\(\) : 0L/);
    assert.match(bridge, /if \(saved\) restoreApolloPart\(part, previous\)/);
    assert.match(bridge, /publishApolloConfig\(send\)/);
});
