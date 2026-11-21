const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const source = (...files) => files.map(file => fs.readFileSync(path.join(__dirname, '../hooks', file), 'utf8')
    .replace(/^import [\s\S]*?;$/gm, '').replace(/^export /gm, '')).join('\n');

test('Android 9 and 11 resolve distinct window start signatures', () => {
    for (const sdk of [28, 30]) {
        const owner = sdk === 28 ? 'com.android.server.am' : 'com.android.server.wm';
        const entry = {
            returnType: {className:'void'},
            argumentTypes: [owner + '.ActivityRecord', 'android.app.ActivityOptions', owner + '.Task'].map(className => ({className})),
        };
        const Java = {use(name) {
            assert.equal(name, owner + '.ActivityStarter');
            return {setInitialState:{overloads:[entry], overload(...types) {
                assert.equal(sdk, 28);
                assert.equal(types[2], 'com.android.server.am.TaskRecord');
                return entry;
            }}};
        }};
        const install = vm.runInNewContext(source('window-android9.js', 'window-android11.js') +
            '\n({28:android9WindowHook, 30:android11WindowHook})')[sdk];
        let intercepted;
        install(Java, found => { intercepted = found; });
        assert.equal(intercepted, entry);
    }
});

test('only a Controls launch that asked for a floating window changes its windowing mode, once', () => {
    let changed = 0, marker = true, pkg = 'some.other.caller';
    const opts = {getLaunchBounds() { return {}; }, setLaunchWindowingMode(mode) { assert.equal(mode, 5); changed++; }};
    const intent = {
        getAction: () => 'android.intent.action.MAIN', getComponent: () => ({}),
        getBooleanExtra: () => marker, removeExtra: () => { marker = false; }
    };
    const record = {get launchedFromPackage() { return {value:pkg}; }, intent:{value:intent}, isResizeable:()=>true};
    const apply = vm.runInNewContext(source('report.js', 'window-profiles.js', 'window-policy.js') + '\napplyWindowRequest');
    assert.equal(apply(record, opts, 5), false);
    assert.equal(changed, 0);
    pkg = 'org.openvoyah.controls';
    assert.equal(apply(record, opts, 5), true);
    assert.equal(apply(record, opts, 5), false);
    assert.equal(changed, 1);
});

function steeringHarness(options = {}) {
    const press = {kind:'START_VOICE'}, long = {kind:'SYSTEM_SETTINGS'};
    const config = {schema:1, steeringEnabled:true, steering:{voiceShort:press, voiceLong:long, cameraShort:press}};
    if (options.source) config.steeringSource = options.source;
    let original = 0, launches = 0;
    const logs = [];
    const method = className => ({overload:() => ({returnType:{className}})});
    const callbacks = [0, 1].map(i => ({
        returnType:{className: options.wrongReturn && i === 1 ? 'void' : 'boolean'},
        call() { original++; return false; }
    }));
    const Event = Object.fromEntries([
        'KEYCODE_IVOKA', 'KEYCODE_SWC_USER_DEFINE', 'KEYCODE_SWC_AVM', 'KEYACTION_SHORT', 'KEYACTION_LONG'
    ].map((name, i) => [name, {value:i + 1}]));
    Object.assign(Event, {getKeyCode:method('int'), getAction:method('int'), isCanceled:method('boolean'),
        getDownTime:method('long'), class:{getClassLoader:()=>({})}});
    if (options.collision) Event.KEYCODE_SWC_AVM.value = Event.KEYCODE_IVOKA.value;
    const Java = {perform: fn => fn(), use(name) {
        if (name === options.missing) throw Error('java.lang.ClassNotFoundException: ' + name);
        return {
            'android.os.Build$VERSION':{SDK_INT:{value:options.sdk ?? 30}},
            'java.lang.Class':{forName(_name, initialize) { assert.equal(initialize,true); }},
            'android.app.ActivityThread':{currentApplication:()=>({getContentResolver:()=>({})})},
            // The read hook runs where the real JNI call would let another thread's callback in.
            'android.provider.Settings$Global':{getString:()=>{ options.onRead?.(); return options.raw ?? JSON.stringify(config); }},
            'com.qinggan.system.QGKeyEvent':Event,
            'com.qinggan.system.KeyManager$5':{onEvent:{overload:()=>callbacks[0]},onKeyEvent:{overload:()=>callbacks[1]}}
        }[name];
    }};
    vm.runInNewContext(source('report.js', 'java-types.js', 'steering-qg.js') + '\ninstallSteering(Java);', {
        Java, console:{log(line) { logs.push(line); }},
        createActionLauncher:() => () => {
            launches++;
            options.onLaunch?.();
            if (options.fail) throw Error('android.content.ActivityNotFoundException: Unable to find {private.app/.Main}');
            return true;
        }
    });
    const event = (stamp, gesture, key = 1, canceled = false) => ({
        getKeyCode:()=>key, getAction:()=>gesture, isCanceled:()=>canceled, getDownTime:()=>stamp
    });
    return {config, callbacks, event, logs, get launches() { return launches; }, get original() { return original; }};
}

test('OEM steering consumes one successful gesture and keeps the original on failure or reset', () => {
    const options = {};
    const h = steeringHarness(options), [first, second] = h.callbacks;
    assert.equal(first.implementation(h.event(100, 5)), true);
    h.config.steeringEnabled = false;
    assert.equal(second.implementation(h.event(100, 4)), true);
    assert.equal(h.launches, 1);
    h.config.steeringEnabled = true;
    options.fail = true;
    assert.equal(first.implementation(h.event(200, 4)), false);
    assert.equal(second.implementation(h.event(200, 4)), false);
    assert.equal(h.launches, 2);
    h.config.steering = {};
    assert.equal(first.implementation(h.event(300, 4)), false);
    assert.equal(h.original, 3);
});

test('OEM steering leaves unassigned camera, ambiguous time and a newly assigned duplicate native', () => {
    const h = steeringHarness(), [first, second] = h.callbacks;
    delete h.config.steering.cameraShort;
    assert.equal(first.implementation(h.event(100, 4, 3)), false);
    assert.equal(first.implementation(h.event(0, 4)), false);
    assert.equal(first.implementation(h.event(200, 4, 1, true)), false);
    h.config.steeringEnabled = false;
    assert.equal(first.implementation(h.event(300, 4)), false);
    h.config.steeringEnabled = true;
    assert.equal(second.implementation(h.event(300, 5)), false);
    assert.equal(second.implementation(h.event(250, 4)), false);
    assert.equal(h.launches, 0);
    assert.equal(h.original, 6);
});

test('OEM steering signature and key collisions leave both callbacks unmodified', () => {
    for (const options of [{sdk:29}, {wrongReturn:true}, {collision:true}]) {
        const h = steeringHarness(options);
        assert.ok(h.logs.some(line => line.startsWith('OVC_STEERING_UNSUPPORTED')));
        assert.ok(h.callbacks.every(callback => !callback.implementation));
    }
});

test('camera and custom gestures use named OEM codes on both supported SDKs', () => {
    for (const sdk of [28, 30]) {
        const h = steeringHarness({sdk}), [first, second] = h.callbacks;
        h.config.steering.customLong = {kind:'SYSTEM_SETTINGS'};
        assert.equal(first.implementation(h.event(100, 4, 3)), true);
        assert.equal(second.implementation(h.event(100, 4, 3)), true);
        assert.equal(first.implementation(h.event(200, 5, 2)), true);
        assert.equal(h.launches, 2);
        assert.equal(first.implementation(h.event(300, 5, 3)), false);
        assert.equal(h.original, 1);
        assert.ok(h.logs.some(line => line.startsWith('OVC_STEERING_READY protocol=2 ')));
    }
});

test('a delegated CAN route installs no competing QG consumer', () => {
    for (const source of ['can-terminal', 'can-edges']) {
        const h = steeringHarness({source});
        assert.ok(h.callbacks.every(callback => !callback.implementation));
        assert.ok(h.logs.some(line => line.includes('delegated=can')));
    }
});

test('a second callback during the Settings read joins the assigned launch instead of running natively', () => {
    for (const order of [[0, 1], [1, 0]]) {
        const options = {};
        const h = steeringHarness(options), [outer, inner] = order.map(index => h.callbacks[index]);
        let nested;
        options.onRead = () => { options.onRead = null; nested = inner.implementation(h.event(100, 4)); };
        assert.equal(outer.implementation(h.event(100, 4)), true);
        assert.equal(nested, true);
        assert.equal(h.launches, 1);
        assert.equal(h.original, 0);
    }
});

test('a second callback during the launch is consumed with it, and a failed launch keeps one native call', () => {
    const options = {};
    const h = steeringHarness(options), [first, second] = h.callbacks;
    let nested;
    options.onLaunch = () => { options.onLaunch = null; nested = second.implementation(h.event(100, 4)); };
    assert.equal(first.implementation(h.event(100, 4)), true);
    assert.equal(nested, true);
    assert.equal(h.launches, 1);
    assert.equal(h.original, 0);
    // A pending launch cannot be awaited; when it then fails the first callback still reaches the OEM.
    options.fail = true;
    options.onLaunch = () => { options.onLaunch = null; nested = second.implementation(h.event(200, 4)); };
    assert.equal(first.implementation(h.event(200, 4)), false);
    assert.equal(nested, true);
    assert.equal(h.launches, 2);
    assert.equal(h.original, 1);
});

test('a newer press published during the Settings read is never replaced by the older event', () => {
    const options = {};
    const h = steeringHarness(options), [first, second] = h.callbacks;
    let nested;
    options.onRead = () => { options.onRead = null; nested = second.implementation(h.event(200, 4)); };
    assert.equal(first.implementation(h.event(100, 4)), false);
    assert.equal(nested, true);
    assert.equal(first.implementation(h.event(200, 4)), true);
    assert.equal(h.launches, 1);
    assert.equal(h.original, 1);
});

test('interleaved callbacks for an unassigned or failed gesture both reach the original', () => {
    const options = {};
    const h = steeringHarness(options), [first, second] = h.callbacks;
    let nested;
    options.onRead = () => { options.onRead = null; nested = second.implementation(h.event(100, 4, 2)); };
    assert.equal(first.implementation(h.event(100, 4, 2)), false);
    assert.equal(nested, false);
    assert.equal(h.launches, 0);
    assert.equal(h.original, 2);
    options.fail = true;
    options.onRead = () => { options.onRead = null; nested = second.implementation(h.event(200, 4)); };
    assert.equal(first.implementation(h.event(200, 4)), false);
    assert.equal(nested, false);
    assert.equal(h.launches, 1);
    assert.equal(h.original, 4);
});

test('steering failures are reported once with key and gesture and never with their message', () => {
    const options = {fail:true};
    const h = steeringHarness(options), [first, second] = h.callbacks;
    for (let stamp = 100; stamp < 400; stamp += 100) {
        assert.equal(first.implementation(h.event(stamp, 5)), false);
        assert.equal(second.implementation(h.event(stamp, 5)), false);
    }
    assert.equal(first.implementation({getKeyCode() { throw Error('java.lang.IllegalStateException: private'); }}), false);
    assert.equal(h.launches, 3);
    assert.equal(h.original, 7);
    assert.deepEqual(h.logs.filter(line => line.startsWith('OVC_STEERING_ERROR')), [
        'OVC_STEERING_ERROR stage=launch kind=ActivityNotFoundException key=voice gesture=Long',
        'OVC_STEERING_ERROR stage=event kind=IllegalStateException',
    ]);
    assert.equal(h.logs.some(line => line.includes('private')), false);
});

test('a malformed document is a configuration error, not an unsupported firmware, and stays native', () => {
    const options = {raw:'{"schema":1,"steering":'};
    const h = steeringHarness(options), [first, second] = h.callbacks;
    assert.equal(first.implementation(h.event(100, 4)), false);
    assert.equal(second.implementation(h.event(100, 4)), false);
    assert.equal(h.launches, 0);
    assert.equal(h.original, 2);
    assert.deepEqual(h.logs, [
        'OVC_STEERING_ERROR stage=config kind=SyntaxError',
        'OVC_STEERING_READY protocol=2 sdk=30 voice custom camera',
        'OVC_STEERING_ERROR stage=config kind=SyntaxError key=voice gesture=Short',
    ]);
    options.raw = undefined;
    assert.equal(first.implementation(h.event(200, 4)), true);
    assert.equal(second.implementation(h.event(200, 4)), true);
    assert.equal(h.launches, 1);
});

test('an unsupported firmware is named by a code or exception class', () => {
    for (const [options, expected] of [
        [{sdk:29}, 'OVC_STEERING_UNSUPPORTED unsupported_sdk sdk=29'],
        [{wrongReturn:true}, 'OVC_STEERING_UNSUPPORTED callback_signature'],
        [{collision:true}, 'OVC_STEERING_UNSUPPORTED key_constants'],
        [{missing:'com.qinggan.system.KeyManager$5'}, 'OVC_STEERING_UNSUPPORTED ClassNotFoundException'],
    ]) {
        const h = steeringHarness(options);
        assert.deepEqual(h.logs, [expected]);
        assert.ok(h.callbacks.every(callback => !callback.implementation));
    }
});
