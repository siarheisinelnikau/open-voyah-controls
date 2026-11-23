const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = ['report.js', 'apollo-factory.js']
    .map(file => fs.readFileSync(path.join(__dirname, '../hooks', file), 'utf8')
        .replace(/^import .*;$/gm, '').replace(/^export /gm, '')).join('\n');

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
function headUnit({ config = '', missing = [] } = {}) {
    const published = [];
    const sends = [];
    const classes = {
        'android.os.Build': { PRODUCT: { value: 'H97D' } },
        'android.provider.Settings$Global': {
            getString: (resolver, key) => (key === 'org.openvoyah.controls.apolloFactory' ? config : null),
            putString: (resolver, key, value) => published.push({ key, value }),
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
                    sendAdasSubStatusToADCU: value => sends.push(['bundle', value]),
                    asyncQueryAdasSubData: () => sends.push(['refresh']),
                },
            }),
        },
        'com.qinggan.app.basevehiclesetting.canbustools.CanBusTool$3': {
            onVehicleStateChanged: member({ overloads: [['java.lang.String', 'java.lang.String']] }),
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
    const api = vm.runInNewContext(source + '\n({installApolloFactory, apolloConfig, apolloState, apolloTrigger, SUBSCRIPTION_ANSWER, APOLLO_STATE_KEY})',
        { console: { log: line => logs.push(line) }, setInterval: fn => (tick = fn), Date, JSON, Object, Number, Set, String });
    const Java = { perform: run => run(), use: name => { if (!(name in classes)) throw new Error('missing ' + name); return classes[name]; } };
    api.installApolloFactory(Java);
    return {
        api, classes, logs, published, sends, tick,
        state: () => JSON.parse(published.at(-1).value),
        config: value => {
            published.length = 0;
            classes['android.provider.Settings$Global'].getString = () => value;
        },
    };
}

test('only the document Controls writes asks for anything, and an unreadable one asks for nothing', () => {
    const { apolloConfig } = headUnit().api;
    assert.deepEqual({ ...apolloConfig('{"schema":1,"subscription":true}') }, { subscription: true });
    assert.deepEqual({ ...apolloConfig('{"schema":1,"controller":true,"equipment":true}') },
        { controller: true, equipment: true });
    for (const raw of ['', null, 'not json', '{"schema":2,"subscription":true}', '{"schema":1}',
        '{"schema":1,"subscription":false}', '{"schema":1,"subscription":"yes"}', '{"schema":1,"other":true}',
        '{"schema":1,"subscription":true}'.padEnd(600, 'x')]) {
        assert.equal(apolloConfig(raw), null, String(raw));
    }
});

test('the published state names what was applied, what is off and what the software does not offer', () => {
    const { apolloState } = headUnit().api;
    assert.deepEqual(JSON.parse(apolloState(['subscription'], new Set(['controller']), 2)),
        { schema: 1, subscription: 'applied', controller: 'unavailable', equipment: 'off', sent: 2 });
    assert.deepEqual(JSON.parse(apolloState([], new Set(), 0)),
        { schema: 1, subscription: 'off', controller: 'off', equipment: 'off', sent: 0 });
});

test('only the readings the factory software itself repeats ask for another send', () => {
    const { apolloTrigger } = headUnit().api;
    assert.equal(apolloTrigger('HUM_VCU_READY', 1), true);
    assert.equal(apolloTrigger('BMS_STATE', '3'), true);
    for (const [name, value] of [['HUM_VCU_READY', 0], ['BMS_STATE', 2], ['SOC_LEVEL', 1], ['HUM_VCU_READY', 'x']]) {
        assert.equal(apolloTrigger(name, value), false, name + '=' + value);
    }
});

test('the subscription answer exists only while the driver wants it and is taken back afterwards', () => {
    const unit = headUnit();
    const query = unit.classes['com.qinggan.app.vehiclesetting.fragments.driveassistance.adas.BaiduProviderUtil']
        .doQuerySubscribeInfo;
    const { SUBSCRIPTION_ANSWER, APOLLO_STATE_KEY } = unit.api;

    unit.config('{"schema":1,"subscription":true}');
    unit.tick();
    assert.equal(query.invoke('context'), SUBSCRIPTION_ANSWER);
    assert.equal(unit.state().subscription, 'applied');
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

test('the controller part sends once on its own and again when the vehicle reaches READY', () => {
    const unit = headUnit();
    const changed = unit.classes['com.qinggan.app.basevehiclesetting.canbustools.CanBusTool$3'].onVehicleStateChanged;

    unit.config('{"schema":1,"controller":true}');
    unit.tick();
    assert.deepEqual(unit.sends, [['bundle', true], ['refresh']]);

    changed.invoke('HUM_VCU_READY', 1);
    assert.equal(unit.sends.length, 4);
    changed.invoke('BMS_STATE', 3);
    assert.equal(unit.sends.length, 6);
    // Any other reading is the factory software's own business.
    changed.invoke('HUM_VCU_READY', 0);
    changed.invoke('SOC_LEVEL', 1);
    assert.equal(unit.sends.length, 6);
    assert.equal(unit.state().controller, 'applied');
});

test('the equipment part answers the factory page without touching the readings behind it', () => {
    const unit = headUnit();
    const utils = unit.classes['com.qinggan.app.vehiclesetting.fragments.driveassistance.utils.Utils'];
    const region = unit.classes['com.qinggan.utils.AppCommonUtils'];

    unit.config('{"schema":1,"equipment":true}');
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

test('software without a class leaves that part unavailable and keeps the rest working', () => {
    const unit = headUnit({ missing: ['com.qinggan.app.vehiclesetting.fragments.driveassistance.utils.Utils'] });
    const query = unit.classes['com.qinggan.app.vehiclesetting.fragments.driveassistance.adas.BaiduProviderUtil']
        .doQuerySubscribeInfo;

    unit.config('{"schema":1,"subscription":true,"equipment":true}');
    unit.tick();
    assert.equal(unit.state().subscription, 'applied');
    assert.equal(unit.state().equipment, 'unavailable');
    assert.notEqual(query.implementation, null);
});

test('a factory settings process without the subscription class is reported, not guessed at', () => {
    const unit = headUnit({
        missing: ['com.qinggan.app.vehiclesetting.fragments.driveassistance.adas.BaiduProviderUtil'],
    });
    unit.config('{"schema":1,"subscription":true}');
    unit.tick();
    assert.equal(unit.state().subscription, 'unavailable');
    assert.equal(unit.logs.some(line => line.startsWith('OVC_APOLLO_READY')), true);
});
