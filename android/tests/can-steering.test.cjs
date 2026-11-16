const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = file => fs.readFileSync(path.join(__dirname, '../hooks', file), 'utf8')
    .replace(/^import .*;$/gm, '').replace(/^export /gm, '');

function harness(options = {}) {
    let time = 100, launches = 0;
    const native = [], logs = [];
    const config = {schema:1, steeringSource:'can-terminal', steeringEnabled:true,
        steering:{voiceShort:{kind:'START_VOICE'}, voiceLong:{kind:'SYSTEM_SETTINGS'}, cameraShort:{kind:'START_VOICE'}}};
    const callback = {returnType:{className:options.badSignature ? 'boolean' : 'void'},
        call(_owner, code, event) { native.push([code, event]); }};
    const app = {getPackageName:()=>options.package || 'com.qinggan.canbus.service', getContentResolver:()=>({})};
    const Codes = {IVOKA:{value:16},SWS_KEY_USER_DEFINED:{value:65},SWS_KEY_PHOTO:{value:76}};
    if (options.collision) Codes.SWS_KEY_PHOTO.value = 16;
    const Status = {KEYDOWN:{value:1},KEYUP:{value:0},SHORT_PRESSED:{value:3},LONG_PRESSED:{value:4}};
    Codes.class = Status.class = {getClassLoader:()=>({})};
    const Java = {perform:run=>run(),use(name) {
        const types = {
            'android.app.ActivityThread':{currentApplication:()=>app},
            'android.os.Build$VERSION':{SDK_INT:{value:options.sdk ?? 28}},
            'java.lang.Class':{forName(_name, initialize) { assert.equal(initialize,true); }},
            'android.os.SystemClock':{elapsedRealtime:()=>time},
            'android.provider.Settings$Global':{getString:()=>options.invalidJson ? '{' : JSON.stringify(config)},
            'com.qinggan.canbus.CanBoxCommType$KEY_CODE':Codes,
            'com.qinggan.canbus.CanBoxCommType$KEY_STATUS':Status,
            'com.qinggan.canbus.service.BaseCanBusComponent':{onCarKeyChanged:{overload(...args) {
                assert.deepEqual(args, ['int','int']); return callback;
            }}}
        };
        if (!types[name] || name === options.missing) throw Error('Class unavailable');
        return types[name];
    }};
    let emit;
    vm.runInNewContext(source('java-types.js') + '\n' + source('can-keys.js') + '\n' + source('can-edges.js') + '\n' + source('can-steering.js') + '\ninstallCanSteering(Java);', {
        Java, console:{log:line=>logs.push(line)},
        createActionIntent:()=>()=>({}), createActionPreflight:()=>()=>true, setTimeout, clearTimeout,
        createActionLauncher:()=>() => {
            launches++;
            if (options.reenter) emit(16, 3);
            if (options.fail) throw Error('Activity unavailable');
            return options.accepted !== false;
        }
    });
    emit = (code, event) => callback.implementation.call({}, code, event);
    return {config,callback,logs,native,emit,advance:ms=>time+=ms,setTime:t=>time=t,get launches(){return launches;}};
}

test('CAN routes one completed gesture and forwards original edges unchanged on SDK 28/30', () => {
    for (const sdk of [28,30]) {
        const h=harness({sdk});
        h.emit(16,1); h.emit(16,3); h.emit(16,3); h.emit(16,0);
        assert.equal(h.launches,1);
        assert.deepEqual(h.native,[[16,1],[16,0]]);
        h.emit(16,1); h.emit(16,4); h.emit(16,3); h.emit(16,0);
        assert.equal(h.launches,2);
        assert.ok(h.logs.some(s=>s.startsWith('OVC_CAN_READY protocol=2 terminal edges')));
    }
});

test('CAN edge-only streams and terminal events without a fresh press remain native', () => {
    const h=harness();
    h.emit(16,3); h.emit(16,1); h.advance(1200); h.emit(16,0);
    assert.equal(h.launches,0); assert.equal(h.native.length,3);
});

test('a terminal after release belongs to the same press only for a bounded interval', () => {
    const h=harness();
    h.emit(16,1); h.emit(16,0); h.advance(100); h.emit(16,3);
    assert.equal(h.launches,1);
    h.emit(16,1); h.emit(16,0); h.advance(501); h.emit(16,3);
    assert.equal(h.launches,1); assert.equal(h.native.length,5);
});

test('an unsuccessful launch gets native fallback without retrying the custom action', () => {
    for (const options of [{fail:true},{accepted:false}]) {
        const h=harness(options);
        h.emit(16,1); h.emit(16,3); h.emit(16,3); h.emit(16,0);
        assert.equal(h.launches,1); assert.equal(h.native.length,4);
    }
});

test('a reentrant terminal event cannot dispatch a second action', () => {
    const h=harness({reenter:true});
    h.emit(16,1); h.emit(16,3);
    assert.equal(h.launches,1); assert.equal(h.native.length,1);
});

test('chords, including unmapped keys, keep native behavior', () => {
    const h=harness();
    h.emit(16,1); h.emit(99,1); h.emit(16,3); h.emit(99,0); h.emit(16,0);
    h.emit(99,1); h.emit(76,1); h.emit(76,3); h.emit(99,0); h.emit(76,0);
    assert.equal(h.launches,0); assert.equal(h.native.length,10);
    h.emit(76,1); h.emit(76,3); assert.equal(h.launches,1);
});

test('lost releases and a backwards clock cannot keep assignments captured indefinitely', () => {
    const h=harness();
    h.emit(99,1); h.advance(15001); h.emit(16,1); h.emit(16,3);
    assert.equal(h.launches,1);
    h.emit(76,1); h.setTime(1); h.emit(76,3);
    assert.equal(h.launches,1);
});

test('configuration changes and an unassigned first terminal are never replayed', () => {
    const h=harness();
    h.emit(16,1); h.config.steering.voiceShort={kind:'SYSTEM_SETTINGS'}; h.emit(16,3);
    h.emit(76,1); delete h.config.steering.cameraShort; h.emit(76,3);
    h.config.steering.cameraShort={kind:'START_VOICE'}; h.emit(76,3);
    assert.equal(h.launches,0); assert.equal(h.native.length,5);
});

test('disabled, malformed and non-CAN configurations never consume events', () => {
    for (const change of [h=>h.config.steeringEnabled=false,h=>h.config.steeringSource='qg']) {
        const h=harness(); change(h); h.emit(16,1); h.emit(16,3);
        assert.equal(h.launches,0); assert.equal(h.native.length,2);
    }
    const h=harness({invalidJson:true}); h.emit(16,1); h.emit(16,3);
    assert.equal(h.launches,0); assert.equal(h.native.length,2);
});

test('incompatible process, signatures and key constants install no CAN hook', () => {
    for (const options of [{sdk:29},{package:'org.other.app'},{badSignature:true},{collision:true},
        {missing:'com.qinggan.canbus.CanBoxCommType$KEY_STATUS'}]) {
        const h=harness(options);
        assert.equal(h.callback.implementation,undefined);
        assert.ok(h.logs.some(s=>s.startsWith('OVC_CAN_ERROR')));
    }
});
