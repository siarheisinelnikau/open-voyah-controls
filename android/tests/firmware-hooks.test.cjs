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

test('OEM steering consumes one successful gesture and keeps the original on failure or reset', () => {
    const press = {kind:'START_VOICE'}, long = {kind:'SYSTEM_SETTINGS'};
    const config = {schema:1, steeringEnabled:true, steering:{voiceShort:press, voiceLong:long}};
    let original = 0, launches = 0, fail = false;
    const callbacks = [0, 1].map(() => ({call() { original++; return false; }}));
    const Event = Object.fromEntries([
        'KEYCODE_IVOKA', 'KEYCODE_SWC_USER_DEFINE', 'KEYCODE_SWC_AVM', 'KEYACTION_SHORT', 'KEYACTION_LONG'
    ].map((name, i) => [name, {value:i + 1}]));
    const Java = {perform: fn => fn(), use(name) {
        return {
            'android.os.Build$VERSION':{SDK_INT:{value:30}},
            'android.app.ActivityThread':{currentApplication:()=>({getContentResolver:()=>({})})},
            'android.provider.Settings$Global':{getString:()=>JSON.stringify(config)},
            'com.qinggan.system.QGKeyEvent':Event,
            'com.qinggan.system.KeyManager$5':{onEvent:{overload:()=>callbacks[0]},onKeyEvent:{overload:()=>callbacks[1]}}
        }[name];
    }};
    vm.runInNewContext(source('steering-android11.js') + '\ninstallSteering(Java);', {
        Java, console:{log(){}},
        createActionLauncher:() => () => { launches++; if (fail) throw Error('Unavailable'); return true; }
    });
    const event = (stamp, gesture) => ({getKeyCode:()=>1, getAction:()=>gesture, isCanceled:()=>false, getDownTime:()=>stamp});
    assert.equal(callbacks[0].implementation(event(100, 5)), true);
    assert.equal(callbacks[1].implementation(event(100, 4)), true);
    assert.equal(launches, 1);
    fail = true;
    assert.equal(callbacks[0].implementation(event(200, 4)), false);
    assert.equal(callbacks[1].implementation(event(200, 4)), false);
    assert.equal(launches, 2);
    config.steering = {};
    assert.equal(callbacks[0].implementation(event(300, 4)), false);
    assert.equal(original, 3);
});
