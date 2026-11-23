const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = ['report.js', 'display-events.js'].map(file => fs.readFileSync(path.join(__dirname, '../hooks', file), 'utf8')
    .replace(/^import .*;$/gm, '').replace(/^export /gm, '')).join('\n');
const SERVICE = 'com.qinggan.cluster.service.InstrumentClusterService$ClusterManagerService';

function harness(options = {}) {
    const broadcasts = [], logs = [];
    let originalCalls = 0;
    const app = {sendBroadcast(intent) {
        if (options.refuse) throw Error('java.lang.SecurityException: Permission Denial: private.receiver');
        broadcasts.push(intent);
    }};
    const update = {call() { originalCalls++; return 'oem result'; }};
    const classes = {
        'android.app.ActivityThread':{currentApplication:() => app},
        [SERVICE]:{updateNaviInfo:{overload(type) { assert.equal(type, 'com.qinggan.cluster.info.NaviInfo'); return update; }}},
        'android.content.Intent':{$new:action => ({
            action, extras:{}, types:{},
            setClassName(pkg, cls) { this.component = pkg + '/' + cls; },
            putExtra:{overload:(...types) => ({call(intent, name, value) { intent.extras[name] = value; intent.types[name] = types[1]; }})}
        })}
    };
    vm.runInNewContext(source + '\ninstallNavigationObserver(Java);', {
        Java:{perform:run => run(), use(name) {
            if (name === options.missing) throw Error('java.lang.ClassNotFoundException: ' + name);
            assert.ok(classes[name], name);
            return classes[name];
        }},
        console:{log:line => logs.push(line)}
    });
    return {options, broadcasts, logs, update:info => update.implementation.call({}, info),
        get originalCalls() { return originalCalls; }};
}

function navigation({state = 'GUIDING', road = 'Main Street', next = 'Second Avenue', meters = 250} = {}) {
    return {
        getGuideState:() => state === null ? null : {name:() => state},
        getCurrentRoadName:() => road, getTurnRoadName:() => next, getNextDistance:() => meters
    };
}

test('route and end events reach the Controls receiver once per change of data', () => {
    const h = harness();
    assert.deepEqual(h.logs, ['OVC_NAVIGATION_READY']);
    assert.equal(h.update(navigation()), 'oem result');
    assert.equal(h.broadcasts.length, 1);
    const [route] = h.broadcasts;
    assert.equal(route.action, 'org.openvoyah.controls.action.NAVIGATION_EVENT');
    assert.equal(route.component, 'org.openvoyah.controls/org.openvoyah.controls.LocalNavigationReceiver');
    assert.deepEqual(route.extras, {event:'route', road:'Main Street', nextRoad:'Second Avenue', meters:250});
    assert.deepEqual(route.types, {event:'java.lang.String', road:'java.lang.String', nextRoad:'java.lang.String', meters:'int'});
    h.update(navigation());
    assert.equal(h.broadcasts.length, 1);
    for (const state of ['START', 'SIMULATING']) h.update(navigation({state, meters:240}));
    assert.equal(h.broadcasts.length, 2);
    for (const info of [navigation({state:'IDLE'}), navigation({state:null}), null]) assert.equal(h.update(info), 'oem result');
    assert.equal(h.broadcasts.length, 3);
    assert.deepEqual(h.broadcasts[2].extras, {event:'end'});
    h.update(navigation());
    assert.deepEqual(h.broadcasts.map(intent => intent.extras.event), ['route', 'route', 'end', 'route']);
    assert.equal(h.originalCalls, 8);
});

test('a distance is sent as a non-negative int and omitted when it cannot be one', () => {
    const h = harness();
    const int64 = value => ({valueOf:() => value, toString:() => String(value)});
    for (const [index, [raw, expected]] of [
        [int64(1234), 1234], [12.6, 13], [0.4, 0], [999999.5, 1000000], [int64(2 ** 31 - 1), 2 ** 31 - 1],
        [-5, undefined], [NaN, undefined], [Infinity, undefined], [int64(2 ** 31), undefined], [null, undefined],
    ].entries()) {
        h.update(navigation({road:'Road ' + index, meters:raw}));
        const {extras} = h.broadcasts.at(-1);
        assert.equal(extras.road, 'Road ' + index);
        assert.equal(extras.meters, expected, 'distance ' + index);
        assert.equal('meters' in extras, expected !== undefined);
    }
    assert.equal(h.broadcasts.length, 10);
    assert.equal(h.logs.some(line => line.startsWith('OVC_NAVIGATION_ERROR')), false);
});

test('an unconvertible distance is left out and reported while the route is still sent', () => {
    const h = harness();
    // A boxed Java value: Frida exposes the static valueOf overloads, which reject a call without arguments.
    const boxed = {valueOf() { throw Error('valueOf(): argument count of 0 does not match any of: private'); }};
    for (const road of ['First', 'Second']) h.update(navigation({road, meters:boxed}));
    assert.deepEqual(h.broadcasts.map(intent => intent.extras), [
        {event:'route', road:'First', nextRoad:'Second Avenue'},
        {event:'route', road:'Second', nextRoad:'Second Avenue'},
    ]);
    assert.deepEqual(h.logs.filter(line => line.startsWith('OVC_NAVIGATION_ERROR')),
        ['OVC_NAVIGATION_ERROR stage=distance kind=Error']);
    assert.equal(h.logs.some(line => line.includes('private')), false);
});

test('road names are clamped to 120 characters', () => {
    const h = harness();
    h.update(navigation({road:'R'.repeat(200), next:'N'.repeat(121)}));
    assert.equal(h.broadcasts[0].extras.road, 'R'.repeat(120));
    assert.equal(h.broadcasts[0].extras.nextRoad, 'N'.repeat(120));
});

test('a failing getter or refused broadcast still returns the OEM result and is reported once', () => {
    const h = harness();
    const broken = {...navigation(), getCurrentRoadName() { throw Error('java.lang.IllegalStateException: private road'); }};
    for (let event = 0; event < 5; event++) assert.equal(h.update(broken), 'oem result');
    assert.equal(h.originalCalls, 5);
    assert.equal(h.broadcasts.length, 0);
    h.options.refuse = true;
    for (let event = 0; event < 3; event++) assert.equal(h.update(navigation()), 'oem result');
    // An unsent event is not remembered as sent, so it is retried with the next update.
    h.options.refuse = false;
    h.update(navigation());
    assert.equal(h.broadcasts.length, 1);
    assert.deepEqual(h.logs.filter(line => line.startsWith('OVC_NAVIGATION_ERROR')), [
        'OVC_NAVIGATION_ERROR stage=event kind=IllegalStateException',
        'OVC_NAVIGATION_ERROR stage=event kind=SecurityException',
    ]);
    assert.equal(h.logs.some(line => line.includes('private')), false);
});

test('a missing cluster service is reported by class and installs nothing', () => {
    const h = harness({missing:SERVICE});
    assert.deepEqual(h.logs, ['OVC_NAVIGATION_ERROR stage=install kind=ClassNotFoundException']);
});
