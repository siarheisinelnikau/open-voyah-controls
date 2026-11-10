const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const source = (...files) => files.map(file => fs.readFileSync(path.join(__dirname, '../hooks', file), 'utf8')
    .replace(/^import [\s\S]*?;$/gm, '').replace(/^export /gm, '')).join('\n');

test('Android 9 and 11 install distinct window adapters and leave unrelated launches alone', () => {
    for (const sdk of [28, 30]) {
        let calls = 0, changed = 0, marker = true, pkg = 'some.other.caller';
        const owner = sdk === 28 ? 'com.android.server.am' : 'com.android.server.wm';
        const entry = {
            returnType: {className:'void'},
            argumentTypes: [owner + '.ActivityRecord', 'android.app.ActivityOptions', owner + '.Task'].map(className => ({className})),
            call() { calls++; return 7; }
        };
        const opts = {getLaunchBounds() { return {}; }, setLaunchWindowingMode(mode) { assert.equal(mode, 5); changed++; }};
        const intent = {
            getAction: () => 'android.intent.action.MAIN', getComponent: () => ({}),
            getBooleanExtra: () => marker, removeExtra: () => { marker = false; }
        };
        const record = {get launchedFromPackage() { return {value:pkg}; }, intent:{value:intent}, isResizeable:()=>true};
        const Java = {perform: fn => fn(), use(name) {
            if (name === 'android.os.Build$VERSION') return {SDK_INT:{value:sdk}};
            if (name === 'android.app.WindowConfiguration') return {WINDOWING_MODE_FREEFORM:{value:5}};
            assert.equal(name, owner + '.ActivityStarter');
            return {setInitialState:{overloads:[entry], overload(...types) {
                assert.equal(types[2], 'com.android.server.am.TaskRecord');
                return entry;
            }}};
        }};
        vm.runInNewContext(source('window-android9.js', 'window-android11.js', 'window-profiles.js', 'window-policy.js') + '\ninstallWindowPolicy(Java);',
            {Java, systemTasks() { throw Error('Live adapter unavailable'); }, console:{log(){}}});
        assert.equal(entry.implementation(record, opts), 7);
        assert.equal(changed, 0);
        pkg = 'org.openvoyah.controls';
        entry.implementation(record, opts);
        entry.implementation(record, opts);
        assert.equal(changed, 1);
        assert.equal(calls, 3);
    }
});

function steeringHarness(options = {}) {
    const press = {kind:'START_VOICE'}, long = {kind:'SYSTEM_SETTINGS'};
    const config = {schema:1, steeringEnabled:true, steering:{voiceShort:press, voiceLong:long, cameraShort:press}};
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
        getDownTime:method('long')});
    if (options.collision) Event.KEYCODE_SWC_AVM.value = Event.KEYCODE_IVOKA.value;
    const Java = {perform: fn => fn(), use(name) {
        return {
            'android.os.Build$VERSION':{SDK_INT:{value:options.sdk ?? 30}},
            'android.app.ActivityThread':{currentApplication:()=>({getContentResolver:()=>({})})},
            'android.provider.Settings$Global':{getString:()=>JSON.stringify(config)},
            'com.qinggan.system.QGKeyEvent':Event,
            'com.qinggan.system.KeyManager$5':{onEvent:{overload:()=>callbacks[0]},onKeyEvent:{overload:()=>callbacks[1]}}
        }[name];
    }};
    vm.runInNewContext(source('steering-android11.js') + '\ninstallSteering(Java);', {
        Java, console:{log(line) { logs.push(line); }},
        createActionLauncher:() => () => { launches++; if (options.fail) throw Error('Unavailable'); return true; }
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

test('OEM steering leaves camera, ambiguous time and a newly assigned duplicate native', () => {
    const h = steeringHarness(), [first, second] = h.callbacks;
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
    for (const options of [{sdk:28}, {wrongReturn:true}, {collision:true}]) {
        const h = steeringHarness(options);
        assert.ok(h.logs.some(line => line.startsWith('OVC_STEERING_UNSUPPORTED')));
        assert.ok(h.callbacks.every(callback => !callback.implementation));
    }
});
