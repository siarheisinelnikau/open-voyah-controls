const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

// The keyboard choice on firmware whose input method service always selects a fixed keyboard by language.
const source = ['report.js', 'keyboard-policy.js'].map(file => fs.readFileSync(path.join(__dirname, '../hooks', file), 'utf8')
    .replace(/^import [\s\S]*?;$/gm, '').replace(/^export /gm, '')).join('\n');
const policy = vm.runInNewContext(source + '\n({CHOICE_KEY, FALLBACK_KEY, READY, enabledIds, keyboardOverride, crashBreaker,' +
    ' installKeyboardPolicy})', {console});
const { CHOICE_KEY, FALLBACK_KEY, READY, enabledIds, keyboardOverride, crashBreaker, installKeyboardPolicy } = policy;

const YANDEX = 'ru.yandex.androidkeyboard/com.android.inputmethod.latin.LatinIME';
const AOSP = 'com.android.inputmethod.latin/.LatinIME';
const OEM = 'com.qinggan.app.qgime/.QingganIME';
const PASSENGER = 'com.qinggan.app.qgime.second/com.qinggan.app.qgime.QingganIME';
const ENABLED = `${OEM}:${AOSP}:${PASSENGER};-921088104:${YANDEX}`;

test('reads keyboard IDs from Android enabled list with subtypes', () => {
    assert.deepEqual([...enabledIds(ENABLED)], [OEM, AOSP, PASSENGER, YANDEX]);
    assert.equal(enabledIds('').size, 0);
    assert.equal(enabledIds(null).size, 0);
});

test('overrides the firmware only with an installed, enabled, non-OEM keyboard', () => {
    const base = {choice: YANDEX, known: true, enabled: ENABLED, tripped: null};
    assert.equal(keyboardOverride(base), YANDEX);
    assert.equal(keyboardOverride({...base, choice: AOSP}), AOSP);
    assert.equal(keyboardOverride({...base, choice: ''}), null);
    assert.equal(keyboardOverride({...base, known: false}), null, 'removed or disabled package');
    assert.equal(keyboardOverride({...base, enabled: `${OEM}:${AOSP}`}), null, 'switched off in Android');
    assert.equal(keyboardOverride({...base, tripped: YANDEX}), null, 'kept crashing');
    assert.equal(keyboardOverride({...base, choice: PASSENGER}), null, 'passenger service binds this package');
    assert.equal(keyboardOverride({...base, choice: OEM}), null);
    assert.equal(keyboardOverride({...base, choice: 'not a component; rm -rf /'}), null);
});

test('trips after three crashes within a minute and starts again for another keyboard', () => {
    const breaker = crashBreaker();
    assert.equal(breaker.record(YANDEX, 0), false);
    assert.equal(breaker.record(YANDEX, 30000), false);
    assert.equal(breaker.record(YANDEX, 59000), true);
    assert.equal(breaker.record(YANDEX, 60000), false, 'counts again after tripping');
    const spaced = crashBreaker();
    for (const time of [0, 61000, 122000]) assert.equal(spaced.record(YANDEX, time), false);
    const switched = crashBreaker();
    switched.record(YANDEX, 0); switched.record(YANDEX, 1);
    assert.equal(switched.record(AOSP, 2), false);
});

/** A stand-in for system_server: the service, its settings and the secure settings provider. */
function harness(options = {}) {
    const secure = new Map(Object.entries({[CHOICE_KEY]: YANDEX, enabled_input_methods: ENABLED, ...options.secure}));
    const calls = [];
    const lines = [];
    const installed = new Set(options.installed ?? [OEM, AOSP, PASSENGER, YANDEX]);
    const settings = {
        getCurrentUserId: () => 0,
        getEnabledInputMethodsStr: () => secure.get('enabled_input_methods'),
        getSelectedInputMethodSubtypeId: () => -1,
    };
    const method = name => {
        const entry = {
            implementation: null,
            call(self, ...args) { calls.push([name, ...args]); return entry.original?.(self, ...args); },
        };
        return {overload: () => entry, entry};
    };
    const update = method('original_update');
    const select = method('select');
    const disconnected = method('original_disconnected');
    select.entry.original = (self, id) => { self.mCurMethodId.value = id; };
    if (options.failSelect) select.entry.original = () => { throw new Error('java.lang.IllegalArgumentException: Unknown id'); };
    const service = {
        mSettings: {value: settings},
        mMethodMap: {value: {get: id => installed.has(id) ? {id} : null}},
        mCurMethodId: {value: options.current ?? OEM},
        mSwitchingController: {value: {resetCircularListLocked: () => calls.push(['reset_circular'])}},
        mContext: {value: {}},
    };
    const type = {
        updateInputMethodsFromSettingsLocked: update,
        setInputMethodLocked: select,
        onServiceDisconnected: disconnected,
    };
    const Java = {
        perform: callback => callback(),
        retain: value => value,
        cast: () => service,
        use(name) {
            if (name === 'com.android.server.inputmethod.InputMethodManagerService') {
                if (options.noService) throw new Error('ClassNotFoundException');
                return type;
            }
            if (name === 'com.android.server.InputMethodManagerService') throw new Error('ClassNotFoundException');
            if (name === 'android.provider.Settings$Secure') return {
                getStringForUser: (_, key) => secure.has(key) ? secure.get(key) : null,
                putStringForUser: (_, key, value) => { calls.push(['put', key, value]); secure.set(key, value); return true; },
            };
            if (name === 'android.app.ActivityThread') return {
                currentActivityThread: () => ({getSystemContext: () => ({getContentResolver: () => ({})})}),
            };
            if (name === 'android.os.ServiceManager') return {getService: () => ({})};
            throw new Error('unexpected class ' + name);
        },
    };
    let clock = 0;
    installKeyboardPolicy(Java, {log: line => lines.push(line), report: (stage, error) => lines.push('ERROR ' + stage),
        now: () => clock});
    return {secure, calls, lines, service, installed, update: update.entry, disconnected: disconnected.entry,
        tick: ms => { clock += ms; }};
}

test('applies the stored keyboard at install and when Android re-reads its settings', () => {
    const car = harness();
    assert.ok(car.lines.includes(READY));
    assert.deepEqual(car.calls, [['put', 'default_input_method', '']], 'install asks Android to re-read once');
    const selected = harness({secure: {default_input_method: OEM}});
    assert.deepEqual(selected.calls, [['put', 'default_input_method', null]], 'a stored value is removed instead');
    car.calls.length = 0;
    car.update.implementation.call(car.service, true);
    assert.deepEqual(car.calls, [['select', YANDEX, -1], ['reset_circular']]);
    assert.equal(car.service.mCurMethodId.value, YANDEX);
    assert.ok(car.lines.includes('OVC_KEYBOARD_APPLIED package=ru.yandex.androidkeyboard'));
});

test('install does not ask Android to re-read when the stored keyboard is already active', () => {
    const car = harness({current: YANDEX});
    assert.deepEqual(car.calls, []);
});

test('lets the firmware choose without a stored keyboard', () => {
    const car = harness({secure: {[CHOICE_KEY]: null}});
    assert.deepEqual(car.calls, []);
    car.update.implementation.call(car.service, false);
    assert.deepEqual(car.calls, [['original_update', false]]);
});

test('falls back to the firmware when the chosen keyboard is removed or switched off', () => {
    const car = harness();
    car.installed.delete(YANDEX);
    car.calls.length = 0;
    car.update.implementation.call(car.service, true);
    assert.deepEqual(car.calls, [['original_update', true]]);
    car.installed.add(YANDEX);
    car.secure.set('enabled_input_methods', `${OEM}:${AOSP}`);
    car.calls.length = 0;
    car.update.implementation.call(car.service, false);
    assert.deepEqual(car.calls, [['original_update', false]]);
});

test('drops a stored keyboard that is gone at install and records why', () => {
    const car = harness({installed: [OEM, AOSP, PASSENGER]});
    assert.equal(car.secure.get(CHOICE_KEY), '');
    assert.equal(car.secure.get(FALLBACK_KEY), 'unavailable ' + YANDEX);
    assert.ok(car.lines.includes('OVC_KEYBOARD_FALLBACK reason=unavailable package=ru.yandex.androidkeyboard'));
    assert.ok(car.lines.includes(READY));
});

test('a failing selection is reported and the firmware chooses instead', () => {
    const car = harness({failSelect: true, current: YANDEX});
    car.update.implementation.call(car.service, true);
    assert.deepEqual(car.calls.map(call => call[0]), ['select', 'original_update']);
    assert.ok(car.lines.includes('ERROR update'));
});

test('a keyboard that keeps crashing is dropped and the firmware keyboard returns', () => {
    const car = harness();
    car.update.implementation.call(car.service, true);
    const name = {getPackageName: () => 'ru.yandex.androidkeyboard'};
    car.calls.length = 0;
    car.disconnected.implementation.call(car.service, name);
    car.tick(10000);
    car.disconnected.implementation.call(car.service, {getPackageName: () => 'com.example.other'});
    car.disconnected.implementation.call(car.service, name);
    assert.equal(car.secure.get(CHOICE_KEY), YANDEX);
    car.tick(10000);
    car.disconnected.implementation.call(car.service, name);
    assert.equal(car.secure.get(CHOICE_KEY), '');
    assert.equal(car.secure.get(FALLBACK_KEY), 'crashed ' + YANDEX);
    assert.deepEqual(car.calls.filter(call => call[0] === 'put').map(call => call[1]),
        [FALLBACK_KEY, CHOICE_KEY, 'default_input_method']);
    assert.ok(car.lines.includes('OVC_KEYBOARD_FALLBACK reason=crashed package=ru.yandex.androidkeyboard'));
    car.calls.length = 0;
    car.update.implementation.call(car.service, false);
    assert.deepEqual(car.calls, [['original_update', false]]);
});

test('installs nothing and announces nothing where the service is missing', () => {
    const car = harness({noService: true});
    assert.ok(!car.lines.includes(READY));
    assert.ok(car.lines.includes('ERROR install'));
    assert.equal(car.update.implementation, null);
});
