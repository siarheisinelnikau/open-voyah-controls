const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../hooks/launcher-buttons.js'), 'utf8').replace('export function install', 'function install') + '\ninstall(Java);';

function harness(config, options = {}) {
    let originalCalls = 0;
    const launched = [], logs = [], retries = [];
    const click = { call() { originalCalls++; return 'oem'; } };
    let originalHolds = 0;
    const longClick = { call() { originalHolds++; return false; } };
    const init = { call() {} };
    const context = {
        getContentResolver() { return {}; },
        startActivity: { overload() { return { call(self, intent, extras) {
            if (options.failLaunch) throw new Error('Activity not found');
            launched.push({ intent, display: extras.display });
        } }; } }
    };
    const classes = {
        'com.qinggan.mainlauncher.navigation.NavigationBar': { class: { isInstance(value) { return value.isNav === true; } }, onClick: { overload() { return click; } }, initScreenUpViews: { overload() { return init; } } },
        'android.view.View': { performLongClick: { overload() { return longClick; } } },
        'android.provider.Settings$Global': { getString(resolver, key) { assert.equal(key, 'org.openvoyah.controls.panel'); return typeof config === 'string' ? config : JSON.stringify(config); } },
        'android.content.Intent': { $new(action) { return { action, addCategory(c) { this.category = c; }, setComponent(c) { this.component = c; }, addFlags(f) { this.flags = f; } }; } },
        'android.content.ComponentName': { unflattenFromString(s) { return s; }, $new(pkg, cls) { return pkg + '/' + cls; } },
        'android.app.ActivityOptions': { makeBasic() { return { setLaunchDisplayId(id) { this.display = id; }, toBundle() { return { display: this.display }; } }; } },
        'android.util.Log': { i(tag, line) { logs.push(line); }, w(tag, line) { logs.push(line); } }
    };
    vm.runInNewContext(source, { Java: { cast(x) { return x; }, choose(name, callbacks) { callbacks.onComplete(); }, scheduleOnMainThread(fn) { fn(); }, perform(fn) { fn(); }, use(name) { if(options.noNav && name.endsWith('NavigationBar'))throw Error('class not loaded'); assert.ok(classes[name], name); return classes[name]; } }, Process: { id: 42 }, console: { log(line) { logs.push(line); } }, setTimeout(fn,ms) { retries.push({fn,ms}); } });
    return {
        retries, logs,
        hold(screen, resource, isNav = true) {
            const owner = { isNav, mScreenId: { value: screen }, mContext: { value: context } };
            return longClick.implementation.call({ mListenerInfo: { value: { mOnClickListener: { value: owner } } }, getId() { return 10; }, getResources() { return { getResourceEntryName() { return resource; } }; } });
        },
        init(screen) {
            const enabled = [];
            const nav = { mScreenId: { value: screen } };
            for (const name of ['mScreenUpHomeView', 'mScreenUpItemView1', 'mScreenUpItemView2', 'mScreenUpItemView3', 'mScreenUpItemView4']) nav[name] = { value: { setLongClickable(value) { if (value) enabled.push(name); } } };
            init.implementation.call(nav); return enabled;
        },
        click(screen, resource) { return click.implementation.call({ mScreenId: { value: screen }, mContext: { value: context } }, { getId() { return 10; }, getResources() { return { getResourceEntryName() { return resource; } }; } }); },
        launched, logs, get originalCalls() { return originalCalls; }, get originalHolds() { return originalHolds; }
    };
}
test('missing NavigationBar schedules recovery and installs when class becomes available', () => {
    const options={noNav:true};const h=harness({schema:1,enabled:false,actions:{}},options);
    assert.equal(h.retries.length,1);assert.equal(h.retries[0].ms,30000);
    assert.ok(h.logs.some(s=>s.includes('OVC_PANEL_ERROR')));
    options.noNav=false;h.retries[0].fn();
    h.click(0,'screen_up_icon_1');assert.equal(h.originalCalls,1);
});
const action = { kind: 'LAUNCH_APP', component: 'com.example.player/.Main' };
const config = actions => ({ schema: 1, enabled: true, actions });

test('navigation launches chosen activity on central display and suppresses OEM handler', () => {
    const h = harness(config({ navbar01: action })); h.click(0, 'screen_up_icon_1');
    assert.equal(h.originalCalls, 0); assert.equal(h.launched.length, 1); assert.equal(h.launched[0].display, 0);
    assert.equal(h.launched[0].intent.component, action.component);
});
test('music has an independent mapping', () => {
    const h = harness(config({ navbar02: action })); h.click(0, 'screen_up_icon_1'); h.click(0, 'screen_up_icon_2');
    assert.equal(h.originalCalls, 1); assert.equal(h.launched.length, 1);
});
test('five passenger positions route to display one', () => {
    const views = ['screen_up_home_icon', 'screen_up_icon_1', 'screen_up_icon_2', 'screen_up_icon_3', 'screen_up_icon_4'];
    views.forEach((view, i) => {
        const h = harness(config({ ['navbar1' + (i + 1)]: action })); h.click(1, view);
        assert.equal(h.originalCalls, 0); assert.equal(h.launched[0].display, 1);
    });
});
test('home, climate, all-apps and other displays preserve OEM actions', () => {
    const h = harness(config({ navbar01: action, navbar11: action }));
    for (const [display, view] of [[0, 'screen_up_home_icon'], [0, 'screen_up_temperature_content'], [1, 'screen_up_all_app_icon'], [2, 'screen_up_icon_1']]) h.click(display, view);
    assert.equal(h.originalCalls, 4); assert.equal(h.launched.length, 0);
});
test('disabled and unconfigured hooks call original exactly once', () => {
    for (const value of [null, { schema: 1, enabled: false, actions: { navbar01: action } }, config({})]) {
        const h = harness(value); h.click(0, 'screen_up_icon_1'); assert.equal(h.originalCalls, 1);
    }
});
test('bad config and unsupported schema fail open', () => {
    for (const value of ['invalid JSON', { schema: 2, enabled: true, actions: { navbar01: action } }]) {
        const h = harness(value); h.click(0, 'screen_up_icon_1'); assert.equal(h.originalCalls, 1);
    }
});
test('failed activity launch falls back without double invocation', () => {
    const h = harness(config({ navbar01: action }), { failLaunch: true }); h.click(0, 'screen_up_icon_1'); assert.equal(h.originalCalls, 1); assert.equal(h.launched.length, 0);
});
test('arbitrary intent strings and unknown actions are rejected', () => {
    for (const a of [{ kind: 'LAUNCH_APP', component: 'intent://unsafe' }, { kind: 'SHELL', component: 'anything' }, { kind: 'NONE' }]) {
        const h = harness(config({ navbar01: a })); h.click(0, 'screen_up_icon_1'); assert.equal(h.originalCalls, 1);
    }
});
test('settings actions run on the requested display', () => {
    for (const kind of ['SYSTEM_SETTINGS', 'CONTROLS_SETTINGS']) {
        const h = harness(config({ navbar11: { kind } })); h.click(1, 'screen_up_home_icon'); assert.equal(h.originalCalls, 0); assert.equal(h.launched[0].display, 1);
    }
});

test('long press runs OEM click once, consumes release, and never launches replacement', () => {
    const h = harness(config({ navbar01: action }));
    assert.equal(h.hold(0, 'screen_up_icon_1'), true);
    assert.equal(h.originalCalls, 1); assert.equal(h.originalHolds, 0); assert.equal(h.launched.length, 0);
});
test('long press preserves OEM long handler for disabled, invalid and unmapped buttons', () => {
    for (const value of [null, config({}), { schema: 1, enabled: false, actions: { navbar01: action } }, 'bad json', config({ navbar01: { kind: 'NONE' } })]) {
        const h = harness(value); assert.equal(h.hold(0, 'screen_up_icon_1'), false);
        assert.equal(h.originalHolds, 1); assert.equal(h.originalCalls, 0);
    }
});
test('long press does not capture other listeners or climate and home', () => {
    const h = harness(config({ navbar01: action }));
    h.hold(0, 'screen_up_icon_1', false); h.hold(0, 'screen_up_home_icon'); h.hold(0, 'screen_up_temperature_content');
    assert.equal(h.originalHolds, 3); assert.equal(h.originalCalls, 0);
});
test('new launcher bars enable holds only on supported display positions', () => {
    const h = harness(config({}));
    assert.deepEqual(h.init(0), ['mScreenUpItemView1', 'mScreenUpItemView2']);
    assert.equal(h.init(1).length, 5); assert.equal(h.init(2).length, 0);
});
