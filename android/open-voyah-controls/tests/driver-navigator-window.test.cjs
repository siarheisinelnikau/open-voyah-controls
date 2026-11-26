const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../hooks/driver-navigator-window.js'), 'utf8')
    .replace(/^import .*;$/gm, '').replace(/^export /gm, '');
const {driverNavigatorRequest, driverNavigatorSession, createDriverNavigatorWindow} =
    vm.runInNewContext(source + '\n({driverNavigatorRequest, driverNavigatorSession, createDriverNavigatorWindow})', {
        driverMediaGeometry: ({display, owner, attached, shown, width, height}) =>
            display === 'Cluster-Media-Display' && owner === 'com.qinggan.instrumentcard' && attached && shown ? {width, height} : null,
        createActionPreflight: () => () => true,
    });

const command = (id, operation = 'open', expires = 2500) =>
    JSON.stringify({schema: 1, id: id.repeat(32), operation, expires});

function sessionFixture() {
    const events = [];
    const platform = {
        mount: host => { events.push(['mount', host]); return {}; },
        poll: () => 'waiting',
        close: () => events.push(['release']),
    };
    const session = driverNavigatorSession(platform, (id, status) => events.push([id[0], status]),
        stage => events.push([stage]));
    return {events, platform, session};
}

test('command expiry and restart prevent stale or repeated launches', () => {
    assert.equal(driverNavigatorRequest(command('a', 'open', 999), 1000), null);
    assert.equal(driverNavigatorRequest(command('a', 'open', 5001), 1000), null);
    assert.equal(driverNavigatorRequest(command('a', 'launch'), 1000), null);
    const {events, session} = sessionFixture();
    session.reset(command('a'), 1000);
    session.sync(command('a'), 'view', true, 1010);
    session.sync(command('b'), 'view', true, 1020);
    session.sync(command('b'), 'view', true, 1030);
    assert.deepEqual(events, [['mount', 'view']]);
});

test('a pending mount closes on timeout before reporting the failure', () => {
    const {events, session} = sessionFixture();
    session.reset(null, 1000);
    session.sync(command('a'), 'view', true, 1000);
    session.sync(null, 'view', true, 7499);
    assert.equal(session.mounted, true);
    session.sync(null, 'view', true, 7500);
    assert.deepEqual(events, [['mount', 'view'], ['release'], ['a', 'no_frame']]);
    assert.equal(session.mounted, false);
});

test('one visible window is shown and then closed after the OEM display is lost', () => {
    const {events, platform, session} = sessionFixture();
    platform.poll = (_, host) => host ? 'shown' : 'cancelled';
    session.reset(null, 1000);
    session.sync(command('a'), 'view', true, 1000);
    session.sync(command('b'), 'view', true, 1100);
    assert.equal(session.showing, true);
    session.sync(null, null, true, 1200);
    session.sync(command('b'), 'new-display', true, 1300);
    session.sync(command('c', 'close'), null, true, 1400);
    assert.deepEqual(events, [['mount', 'view'], ['a', 'shown'], ['b', 'shown'], ['release'], ['c', 'restored']]);
});

test('denied commands preserve the OEM card and an incomplete release is retried', () => {
    const {events, platform, session} = sessionFixture();
    platform.mount = () => 'denied';
    session.reset(null, 1000);
    session.sync(command('a'), 'view', true, 1000);
    session.sync(command('b'), 'view', false, 1100);
    platform.mount = () => ({});
    session.sync(command('c'), 'view', true, 1200);
    let releases = 0;
    platform.close = () => { if (++releases === 1) throw Error('Busy'); };
    assert.equal(session.close(), false);
    session.sync(command('c'), 'view', true, 1300);
    assert.deepEqual(events, [['a', 'denied'], ['b', 'unavailable'], ['navigator_close'], ['c', 'cancelled']]);
});

test('the display adapter is unavailable outside Android 11', () => {
    const Java = {use: name => {
        assert.equal(name, 'android.os.Build$VERSION');
        return {SDK_INT: {value: 28}};
    }};
    assert.equal(createDriverNavigatorWindow(Java), null);
});

function displayFixture() {
    const events = [];
    const state = {uid: 1000, allowed: true, owner: 'com.qinggan.cluster', frame: 0, displays: [], elapsed: 1000};
    const list = items => ({size: () => items.length, get: index => items[index], add: item => items.push(item)});
    const component = {getPackageName: () => 'ru.yandex.yandexnavi'};
    const intent = {getComponent: () => component, addFlags() {}};
    const child = {
        getDisplay: () => ({isValid: () => true, getDisplayId: () => 27}),
        release: () => events.push('display'),
    };
    const texture = {getTimestamp: () => state.frame, setDefaultBufferSize() {}};
    const animator = {cancel() {}, alpha: () => animator, setStartDelay: () => animator, setDuration: () => animator, start() {}};
    let tag = null, alpha = 0;
    const cover = {
        animate: () => animator, setTag: (key, value) => { if (typeof key === 'number') tag = value; },
        getTag: () => tag, getAlpha: () => alpha, setAlpha: value => { alpha = value; },
        getSurfaceTexture: () => texture, setClickable() {}, setFocusable() {}, setImportantForAccessibility() {},
        setOpaque() {}, measure() {}, layout() {},
    };
    const overlay = {add: () => events.push('mount'), remove: () => events.push('remove'), findViewWithTag: () => null};
    const services = {
        power: {isInteractive: () => true},
        display: {createVirtualDisplay: (_, width, height, dpi, surface, flags) => {
            events.push(['create', width, height, dpi, flags]);
            return child;
        }},
        activity: {
            isActivityStartAllowedOnDisplay: (_, id, launch) => {
                events.push(['permit', id, launch]);
                return state.allowed;
            },
            getRunningTasks: () => list(state.displays.map(displayId => ({
                displayId, numActivities: {value: 1}, baseActivity: {value: component}, topActivity: {value: component},
            }))),
        },
    };
    const context = {
        getPackageName: () => 'com.qinggan.instrumentcard',
        getResources: () => ({getDisplayMetrics: () => ({density: {value: 1}})}),
        getSystemService: service => services[service],
        getPackageManager: () => ({hasSystemFeature: () => true, getLaunchIntentForPackage: () => intent}),
        checkSelfPermission: () => -1,
        startActivity: (_, options) => { events.push(['start', options]); },
    };
    const host = {
        isHardwareAccelerated: () => true, getWindowVisibility: () => 0, getContext: () => context,
        isAttachedToWindow: () => true, isShown: () => true, getWidth: () => 574, getHeight: () => 464,
        getLocalVisibleRect: () => true, getLocationOnScreen: out => { out[0] = 66; out[1] = 200; },
        getDisplay: () => ({
            isValid: () => true, getType: () => 5, getState: () => 2, getDisplayId: () => 5,
            getName: () => 'Cluster-Media-Display', getOwnerPackageName: () => state.owner,
            getRealSize: out => { out.x.value = 1920; out.y.value = 720; },
        }),
        getOverlay: () => overlay, equals: value => value === host, $dispose() {},
    };
    const classes = {
        'android.os.Build$VERSION': {SDK_INT: {value: 30}},
        'android.os.Process': {myUid: () => state.uid},
        'android.os.SystemClock': {elapsedRealtime: () => state.elapsed, uptimeMillis: () => 1000},
        'android.graphics.Rect': {$new: () => ({width: () => 574, height: () => 464})},
        'android.graphics.Point': {$new: () => ({x: {value: 0}, y: {value: 0}})},
        'android.app.ActivityOptions': {makeBasic: () => ({setLaunchDisplayId: id => ({toBundle: () => id})})},
        'android.view.TextureView': {$new: () => cover},
        'android.view.ViewOverlay': {getOverlayView: {overload: () => ({call: () => overlay})}},
        'android.view.Surface': {$new: () => ({release: () => events.push('surface')})},
        'android.hardware.display.DisplayManager': {class: 'display'},
        'android.app.ActivityManager': {class: 'activity'},
        'android.app.ActivityManager$RunningTaskInfo': {class: {getField: () => ({getInt: task => task.displayId})}},
        'android.os.PowerManager': {class: 'power'},
        'java.lang.String': {$new: value => value},
        'java.util.ArrayList': {$new: () => list([])},
    };
    const adapter = () => createDriverNavigatorWindow({use: name => classes[name], cast: value => value,
        array: (_, values) => values, retain: value => value});
    return {state, host, events, adapter, intent};
}

test('the OEM owner, existing task and actual display launch permission are verified before starting Navigator', () => {
    const {state, host, events, adapter, intent} = displayFixture();
    state.owner = 'example.app';
    assert.equal(adapter().mount(host), 'no_slot');
    state.owner = 'com.qinggan.cluster';
    state.uid = 10001;
    assert.equal(adapter().mount(host), 'denied');
    state.uid = 1000;
    state.displays = [0];
    assert.equal(adapter().mount(host), 'in_use');
    assert.deepEqual(events, []);
    state.displays = [];
    state.allowed = false;
    const api = adapter();
    const window = api.mount(host);
    assert.equal(api.poll(window, host), 'denied');
    api.close(window);
    assert.deepEqual(events, ['mount', ['create', 574, 464, 160, 1288], ['permit', 27, intent], 'display', 'surface', 'remove']);
});

test('the cover requires a frame and task on its child display and releases after suspend', () => {
    const {state, host, events, adapter} = displayFixture();
    const api = adapter();
    const window = api.mount(host);
    assert.equal(api.poll(window, host), 'waiting');
    assert.ok(events.some(event => Array.isArray(event) && event[0] === 'start' && event[1] === 27));
    state.displays = [27];
    assert.equal(api.poll(window, host), 'waiting');
    state.frame = 123;
    assert.equal(api.poll(window, host), 'shown');
    state.elapsed = 1500;
    assert.equal(api.poll(window, host), 'cancelled');
    api.close(window);
    assert.deepEqual(events.slice(-3), ['display', 'surface', 'remove']);
});
