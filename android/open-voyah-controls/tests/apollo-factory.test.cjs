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
const REQUEST_A = 'a'.repeat(32);
const REQUEST_B = 'b'.repeat(32);
const REQUEST_C = 'c'.repeat(32);

function configDoc(parts, request = REQUEST_A, send = false, expires = 6000) {
    return JSON.stringify({schema: 1, request, ...(send ? {send: true, expires} : {}), ...parts});
}

/** One Java member: overload selection, an implementation slot and a record of what the agent asked for. */
function member(options = {}) {
    const overloads = options.overloads ?? [];
    const target = function (...args) { target.calls.push(args); return options.returns; };
    target.calls = [];
    target.implementation = null;
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
 * offer, which is how an older or different build must be handled.
 */
function headUnit({ config = '', missing = [], sendFails = false } = {}) {
    const published = [];
    const sends = [];
    let stateDoc = '';
    let now = 1000;
    const classes = {
        'android.os.Build': { PRODUCT: { value: 'H97D' } },
        'android.os.SystemClock': { elapsedRealtime: () => now },
        'android.provider.Settings$Global': {
            getString: (resolver, key) => (key === CONFIG_KEY ? config : key === STATE_KEY ? stateDoc : null),
            putString: (resolver, key, value) => {
                published.push({ key, value });
                if (key === STATE_KEY) stateDoc = value;
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
            doQuerySubscribeInfo: member({ overloads: [['android.content.Context']] }),
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
            is97Y: member({ overloads: [[]], returns: false }),
            is97C: member({ overloads: [[]], returns: false }),
        },
        'com.qinggan.utils.AppCommonUtils': { isOverSeaVehicle: member({ overloads: [[]], returns: true }) },
    };
    for (const name of missing) delete classes[name];
    let tick = null;
    const logs = [];
    const api = vm.runInNewContext(source + '\n({installApolloFactory, apolloConfig, apolloState, SUBSCRIPTION_ANSWER, APOLLO_STATE_KEY})',
        { console: { log: line => logs.push(line) }, setInterval: fn => (tick = fn), Date, JSON, Object, Number, Set, String });
    const Java = { perform: run => run(), use: name => { if (!(name in classes)) throw new Error('missing ' + name); return classes[name]; } };
    api.installApolloFactory(Java);
    return {
        api, classes, logs, published, sends, Java, tick,
        setNow: value => { now = value; },
        state: () => JSON.parse(published.at(-1).value),
        install: () => api.installApolloFactory(Java),
        config: value => {
            published.length = 0;
            classes['android.provider.Settings$Global'].getString = (resolver, key) =>
                key === CONFIG_KEY ? value : key === STATE_KEY ? stateDoc : null;
        },
    };
}

test('only the document Controls writes asks for anything, and an unreadable one asks for nothing', () => {
    const { apolloConfig } = headUnit().api;
    assert.deepEqual({ ...apolloConfig(configDoc({ subscription: true })) },
        { subscription: true, request: REQUEST_A, send: false });
    assert.deepEqual({ ...apolloConfig(configDoc({ controller: true, equipment: true }, REQUEST_B, true)) },
        { controller: true, equipment: true, request: REQUEST_B, send: true, expires: 6000 });
    for (const raw of ['', null, 'not json', '{"schema":2,"subscription":true}', '{"schema":1}',
        '{"schema":1,"subscription":false}', '{"schema":1,"subscription":"yes"}', '{"schema":1,"other":true}',
        '{"schema":1,"subscription":true}', configDoc({ subscription: true }, 'not-a-request'),
        configDoc({ subscription: true }, REQUEST_A.toUpperCase()),
        configDoc({}, REQUEST_A), configDoc({ subscription: true }).padEnd(600, 'x')]) {
        assert.equal(apolloConfig(raw), null, String(raw));
    }
    // A permit without a controller switch or without a deadline is not a send request.
    assert.equal(apolloConfig(JSON.stringify({schema: 1, request: REQUEST_A, send: true, subscription: true})).send, false);
    for (const expires of [null, 0, -1, '6000', 1.5]) {
        assert.equal(apolloConfig(JSON.stringify({schema: 1, request: REQUEST_A, send: true, expires,
            controller: true})).send, false, String(expires));
    }
});

test('the published state ties each part to the request and never claims a verified controller state', () => {
    const { apolloState } = headUnit().api;
    assert.deepEqual(JSON.parse(apolloState(['subscription'], false, new Set(['controller']), REQUEST_A, 2)),
        { schema: 1, request: REQUEST_A, sent: 2, subscription: 'applied', controller: 'unavailable', equipment: 'off' });
    assert.deepEqual(JSON.parse(apolloState([], true, new Set(), REQUEST_B, 1)),
        { schema: 1, request: REQUEST_B, sent: 1, subscription: 'off', controller: 'requested', equipment: 'off' });
    assert.deepEqual(JSON.parse(apolloState([], false, new Set(), null, 0)),
        { schema: 1, sent: 0, subscription: 'off', controller: 'off', equipment: 'off' });
});

test('the subscription answer exists only while the driver wants it and is taken back afterwards', () => {
    const unit = headUnit();
    const query = unit.classes['com.qinggan.app.vehiclesetting.fragments.driveassistance.adas.BaiduProviderUtil']
        .doQuerySubscribeInfo;
    const { SUBSCRIPTION_ANSWER, APOLLO_STATE_KEY } = unit.api;

    unit.config(configDoc({ subscription: true }));
    unit.tick();
    assert.equal(query.invoke('context'), SUBSCRIPTION_ANSWER);
    assert.equal(unit.state().subscription, 'applied');
    assert.equal(unit.state().request, REQUEST_A);
    assert.equal(unit.published.at(-1).key, APOLLO_STATE_KEY);

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

test('a controller request is sent once and a neighbouring switch never sends it again', () => {
    const unit = headUnit();

    unit.config(configDoc({ controller: true }, REQUEST_A, true));
    unit.tick();
    assert.deepEqual(unit.sends, [['bundle', true], ['refresh']]);
    assert.equal(unit.state().controller, 'requested');
    assert.equal(unit.state().sent, 1);

    // Turning another part on or off creates a new request for the factory page, not another ADCU send.
    unit.config(configDoc({ controller: true, subscription: true }, REQUEST_B));
    unit.tick();
    assert.equal(unit.sends.length, 2);
    assert.equal(unit.state().request, REQUEST_B);
    assert.equal(unit.state().controller, 'requested');
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
    assert.equal(unit.state().controller, 'off');
});

test('a permit left by a stopped agent expires instead of sending without a fresh check', () => {
    const unit = headUnit();
    unit.config(configDoc({ controller: true }, REQUEST_A, true, 900));
    unit.tick();
    assert.equal(unit.sends.length, 0);
    assert.equal(unit.state().controller, 'off');

    const fresh = headUnit();
    fresh.config(configDoc({ controller: true }, REQUEST_A, true, 1100));
    fresh.tick();
    assert.equal(fresh.sends.length, 2);
});

test('a rejected send is reported as unavailable, never as a request the controller accepted', () => {
    const unit = headUnit({ sendFails: true });
    unit.config(configDoc({ controller: true }, REQUEST_A, true));
    unit.tick();
    assert.equal(unit.sends.length, 0);
    assert.equal(unit.state().controller, 'unavailable');
    assert.equal(unit.state().sent, 0);
});

test('a restarted factory app does not replay a request it already accepted', () => {
    const unit = headUnit();
    unit.config(configDoc({ controller: true }, REQUEST_A, true));
    unit.tick();
    assert.equal(unit.sends.length, 2);
    unit.install();
    unit.tick();
    assert.equal(unit.sends.length, 2);
    assert.equal(unit.state().controller, 'requested');
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

test('software without a class leaves that part unavailable and keeps the rest working', () => {
    const unit = headUnit({
        missing: ['com.qinggan.app.vehiclesetting.fragments.driveassistance.utils.Utils'],
    });
    const query = unit.classes['com.qinggan.app.vehiclesetting.fragments.driveassistance.adas.BaiduProviderUtil']
        .doQuerySubscribeInfo;

    unit.config(configDoc({ subscription: true, equipment: true }, REQUEST_A));
    unit.tick();
    assert.equal(unit.state().subscription, 'applied');
    assert.equal(unit.state().equipment, 'unavailable');
    assert.notEqual(query.implementation, null);
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

test('Controls gates the controller send on live Park, correlates the report and rolls back a failed switch', () => {
    const bridge = fs.readFileSync(path.join(__dirname,
        '../app/src/main/java/org/openvoyah/controls/data/frida/FridaBridge.java'), 'utf8');
    assert.match(bridge, /boolean send = enabled && "controller"\.equals\(part\)/);
    assert.match(bridge, /sendRecognizedVoice\("ParkingPreflight", new JSONObject\(\)\)/);
    assert.match(bridge, /"OVC_PARKING_READY"\.equals\(parking\.message\(\)\)/);
    assert.match(bridge, /SystemClock\.elapsedRealtime\(\) \+ 5000L/);
    assert.match(bridge, /if \(saved\) restoreApolloPart\(part, previous\)/);
    assert.match(bridge, /publishApolloConfig\(send\)/);
});
