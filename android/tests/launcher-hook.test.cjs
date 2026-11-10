const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = ['panel-icons.js', 'panel-state.js', 'launcher-buttons.js'].map(file => fs.readFileSync(path.join(__dirname, '../hooks', file), 'utf8')
    .replace(/^import .*;$/gm, '').replaceAll('export function', 'function')).join('\n') + '\ninstall(Java);';

function harness(config, options = {}) {
    let originalCalls = 0;
    const launched = [], logs = [], retries = [], timers = [];
    let inClick = false, inTheme = false;
    const click = { call(nav, view) {
        if (!inClick) return dispatchClick(nav, view);
        originalCalls++;
        return 'oem';
    }};
    function dispatchClick(nav, view) {
        inClick = true;
        try { return click.implementation.call(nav, view); }
        finally { inClick = false; }
    }
    let originalHolds = 0;
    const holds = [0,1].map(() => ({call() { originalHolds++; return false; }}));
    const init = { call(nav) {
        if (nav.replacement) {
            const [field, resource] = nav.replacement;
            nav.views[resource] = nav[field].value = makeView(nav, resource);
            nav.replacement = null;
        }
    } };
    const theme = { call(nav) {
        if (!inTheme) return dispatchTheme(nav);
        for (const view of Object.values(nav.views ?? {})) view.background = view.stock;
    }};
    function dispatchTheme(nav) {
        inTheme = true;
        try { return theme.implementation.call(nav); }
        finally { inTheme = false; }
    }
    const release = { call() {} };
    const selection = { call(nav) {
        nav.stockUpdates++;
        nav.checked = nav.stockChecked ?? -1;
    }};
    const manager = {
        resolveActivity(intent) {
            if (options.missing && options.missing === intent.component) return null;
            return {
                activityInfo: {value:{}},
                loadIcon() {
                    return {component:intent.component ?? intent.action, getIntrinsicWidth:() => 128, getIntrinsicHeight:() => 64};
                }
            };
        }
    };
    const context = {
        getContentResolver() { return {}; },
        getPackageManager() { return manager; },
        startActivity: { overload() { return { call(self, intent, extras) {
            if (options.failLaunch) throw new Error('Activity not found');
            launched.push({ intent, display: extras.display });
        } }; } }
    };
    const object = value => Object.assign(value, { equals(other) { return other === this; }, $dispose() {} });
    function makeView(nav, resource) {
        const view = object({
            stock: object({resource}), draws: 0, holdEnabled: options.stockHolds?.includes(resource) ?? false,
            getId: () => 10 + Object.keys(nav.views).indexOf(resource),
            getResources: () => ({getResourceEntryName: () => resource}),
            getLayoutParams: () => ({width: {value:116}, height: {value:90}}), getWidth: () => 0, getHeight: () => 0,
            getPaddingLeft: () => 4, getPaddingTop: () => 5, getPaddingRight: () => 6, getPaddingBottom: () => 7,
            setPadding(...insets) { this.insets = insets; },
            setBackground(background) { this.background = background; this.draws++; },
            getBackground() { return this.background; },
            setLongClickable(value) {
                if (!value && options.failRestore) return;
                this.holdEnabled = value;
            },
            isLongClickable() { return this.holdEnabled; },
            mListenerInfo: {value: {mOnClickListener: {value: nav}}}
        });
        view.background = view.stock;
        return view;
    }
    function makeBar(screen) {
        const nav = {isNav:true, mScreenId:{value:screen}, mContext:{value:context}, views:{}, equals(other) { return other === this; }};
        nav.stockUpdates = 0;
        nav.mScreenUpRadioGroup = {value: {
            getCheckedRadioButtonId: () => nav.checked,
            check: id => { nav.checked = id; }
        }};
        for (const [field, resource] of [
            ['mScreenUpHomeView', 'screen_up_home_icon'],
            ['mScreenUpItemView1', 'screen_up_icon_1'],
            ['mScreenUpItemView2', 'screen_up_icon_2'],
            ['mScreenUpItemView3', 'screen_up_icon_3'],
            ['mScreenUpItemView4', 'screen_up_icon_4']
        ]) {
            const view = makeView(nav, resource);
            nav[field] = {value:view};
            nav.views[resource] = view;
        }
        return nav;
    }
    const existing = (options.existing ?? []).map(makeBar);
    const classes = {
        'com.qinggan.mainlauncher.navigation.NavigationBar': {
            class: { isInstance(value) { return value.isNav === true; } }, onClick: {overload:() => click},
            initScreenUpViews: {overload:() => init}, updateTheme: options.noTheme ? undefined : {overload:() => theme},
            release: {overload:() => release},
            updateSelectedApp: options.noSelection ? undefined : {overload:() => selection}
        },
        'android.view.View': { performLongClick: { overload(...coordinates) { return holds[coordinates.length ? 1 : 0]; } } },
        'android.widget.RadioGroup': {},
        'android.provider.Settings$Global': { getString(resolver, key) { assert.equal(key, 'org.openvoyah.controls.panel'); return typeof config === 'string' ? config : JSON.stringify(config); } },
        'android.content.Intent': { $new(action) { return {
            action, addCategory(c) { this.category = c; }, setComponent(c) { this.component = c; }, addFlags(f) { this.flags = (this.flags ?? 0) | f; },
            putExtra: { overload() { return { call(intent, key, value) { intent.extras = {[key]:value}; } }; } }
        }; } },
        'android.content.ComponentName': { unflattenFromString(s) { return s; }, $new(pkg, cls) { return pkg + '/' + cls; } },
        'android.app.ActivityOptions': { makeBasic() { return { setLaunchDisplayId(id) { this.display = id; }, toBundle() { return { display: this.display }; } }; } },
        'android.app.ActivityThread': {currentApplication:() => context},
        'android.content.pm.PackageManager': {MATCH_DEFAULT_ONLY:{value:65536}},
        'android.graphics.drawable.LayerDrawable': {$new([states, icon]) { return {
            states, icon, setLayerGravity(index, gravity) { this.gravity = gravity; }, setLayerSize(index, width, height) { this.size = [width,height]; }
        }; }},
        'android.graphics.drawable.StateListDrawable': {$new() { return {entries:[], addState(ids, drawable) { this.entries.push({ids, drawable}); }}; }},
        'android.graphics.drawable.GradientDrawable': {$new() { return {setColor(color) { this.color = color; }, setCornerRadius(radius) { this.radius = radius; }}; }},
        'android.graphics.drawable.ColorDrawable': {$new(color) { return {color}; }},
        'android.R$attr': {state_checked: {value: 16842912}},
        'android.graphics.drawable.RippleDrawable': {$new(color, layer) { return object({color, layer}); }},
        'android.content.res.ColorStateList': {valueOf:color => color},
        'android.view.Gravity': {CENTER:{value:17}},
        'java.lang.ref.WeakReference': {$new(referent) { return {get: () => referent, $dispose() {}}; }},
        'android.util.Log': { i(tag, line) { logs.push(line); }, w(tag, line) { logs.push(line); } }
    };
    vm.runInNewContext(source, {
        Java: {
            array(name, values) { return values; }, retain(x) { return x; }, cast(x) { return x; },
            choose(name, callbacks) { existing.forEach(nav => callbacks.onMatch(Object.assign(nav, {$dispose(){}}))); callbacks.onComplete(); },
            scheduleOnMainThread(fn) { fn(); }, perform(fn) { fn(); },
            use(name) { if (options.noNav && name.endsWith('NavigationBar')) throw Error('class not loaded'); assert.ok(classes[name], name); return classes[name]; }
        },
        Process: { id: 42 }, console: { log(line) { logs.push(line); } },
        setTimeout(fn,ms) { retries.push({fn,ms}); }, setInterval(fn) { timers.push(fn); return fn; },
        clearInterval(fn) { timers.splice(timers.indexOf(fn),1); }
    });
    function bar(screen) {
        const nav = makeBar(screen);
        init.implementation.call(nav);
        return nav;
    }
    function performHold(view, coordinates) {
        return holds[coordinates.length ? 1 : 0].implementation.call(view, ...coordinates);
    }
    return {
        retries, logs, existing,
        bar,
        setConfig(value) { config = value; },
        tick() { timers.forEach(fn => fn()); },
        theme(nav) { dispatchTheme(nav); },
        select(nav, pkg, activity) { selection.implementation.call(nav, pkg, activity); },
        release(nav) { release.implementation.call(nav); },
        replace(nav, field, resource) {
            nav.replacement = [field, resource];
            init.implementation.call(nav);
        },
        tap(nav, name) { return dispatchClick(nav, nav.views[name]); },
        holdAndRelease(nav, name, ...coordinates) {
            const view = nav.views[name];
            if (!view.isLongClickable() || !performHold(view, coordinates)) dispatchClick(nav, view);
        },
        hold(screen, resource, isNav = true, ...coordinates) {
            const nav = bar(screen);
            const view = nav.views[resource] ?? makeView(nav, resource);
            if (!isNav) view.mListenerInfo.value.mOnClickListener.value = {isNav: false};
            return performHold(view, coordinates);
        },
        click(screen, resource) { const nav = bar(screen); return dispatchClick(nav, nav.views[resource] ?? makeView(nav, resource)); },
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

test('a voice shortcut launches the guarded voice entry point on its own display', () => {
    const h = harness(config({ navbar11: { kind: 'START_VOICE' } }));
    h.click(1, 'screen_up_home_icon');
    assert.equal(h.originalCalls, 0);
    assert.equal(h.launched[0].display, 1);
    assert.equal(h.launched[0].intent.component, 'org.openvoyah.controls/org.openvoyah.controls.PanelVoiceActivity');
});

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
    const h = harness(config({navbar01: {kind: 'CONTROLS_SETTINGS'}}));
    h.click(0, 'screen_up_icon_1');
    assert.equal(h.launched[0].intent.action, 'org.openvoyah.controls.action.OPEN_SETTINGS');
    assert.equal(h.launched[0].intent.component, 'org.openvoyah.controls/org.openvoyah.controls.MainActivity');
    assert.equal(h.launched[0].intent.flags & (0x04000000 | 0x20000000), 0x04000000 | 0x20000000);
});

test('long press runs OEM click once, consumes release, and never launches replacement', () => {
    for (const coordinates of [[], [18,24]]) {
        const h = harness(config({navbar01: action}));
        assert.equal(h.hold(0, 'screen_up_icon_1', true, ...coordinates), true);
        assert.equal(h.originalCalls, 1); assert.equal(h.originalHolds, 0); assert.equal(h.launched.length, 0);
        h.holdAndRelease(h.bar(0), 'screen_up_icon_1', ...coordinates);
        assert.equal(h.originalCalls, 2); assert.equal(h.launched.length, 0);
    }
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
test('new launcher bars enable holds only on assigned supported display positions', () => {
    const h = harness(config({navbar01: action, navbar15: action}));
    const enabled = screen => {
        const {views} = h.bar(screen);
        return Object.keys(views).filter(name => views[name].isLongClickable());
    };
    assert.deepEqual(enabled(0), ['screen_up_icon_1']);
    assert.deepEqual(enabled(1), ['screen_up_icon_4']);
    assert.deepEqual(enabled(2), []);
});

test('existing navigation, music and passenger radio buttons use the same component for their icon and tap', () => {
    const navigation = {kind:'LAUNCH_APP', component:'test.navigation/.Maps'};
    const radio = {kind:'LAUNCH_APP', component:'test.radio/.Listen'};
    const h = harness(config({navbar01:navigation, navbar02:action, navbar14:radio}), {existing:[0,1]});
    const [center, passenger] = h.existing;
    for (const [bar, name, app, display] of [
        [center, 'screen_up_icon_1', navigation, 0],
        [center, 'screen_up_icon_2', action, 0],
        [passenger, 'screen_up_icon_3', radio, 1]
    ]) {
        const view = bar.views[name];
        assert.equal(view.background.layer.icon.component, app.component);
        assert.deepEqual(view.background.layer.size, [56,28]);
        assert.deepEqual(view.insets, [4,5,6,7]);
        h.tap(bar, name);
        assert.equal(h.launched.at(-1).intent.component, app.component);
        assert.equal(h.launched.at(-1).display, display);
    }
    const climate = center.views.screen_up_icon_3;
    assert.equal(climate.background, climate.stock);
    assert.equal(h.tap(center, 'screen_up_icon_3'), 'oem');
});

test('assignment changes and OEM theme reload refresh icons and disabling restores stock', () => {
    const h = harness(config({navbar01:action}));
    const bar = h.bar(0), view = bar.views.screen_up_icon_1;
    assert.equal(view.background.layer.icon.component, action.component);
    h.theme(bar);
    assert.equal(view.background.layer.icon.component, action.component);
    const alternative = {kind:'LAUNCH_APP', component:'test.maps/.Maps'};
    h.setConfig(config({navbar01:alternative}));
    h.tick();
    assert.equal(view.background.layer.icon.component, alternative.component);
    const previousDraws = view.draws;
    h.tick();
    assert.equal(view.draws, previousDraws);
    h.setConfig({schema:2,enabled:true,actions:{navbar01:alternative}});
    h.tick();
    assert.equal(view.background, view.stock);
    assert.equal(view.isLongClickable(), false);
    h.setConfig(config({navbar01:alternative}));
    h.tick();
    assert.equal(view.background.layer.icon.component, alternative.component);
    const token = 'a'.repeat(32);
    h.setConfig({schema:1,enabled:false,restoreIcons:token});
    h.tick();
    assert.equal(view.background, view.stock);
    assert.equal(view.isLongClickable(), false);
    assert.ok(h.logs.includes('OVC_PANEL_ICONS_RESTORED ' + token));
    assert.equal(h.tap(bar, 'screen_up_icon_1'), 'oem');
    h.release(bar);
    h.setConfig(config({navbar01:alternative}));
    h.tick();
    assert.equal(view.background, view.stock);
});

test('reassigning and rebuilding the bar restore the exact previous hold flags and backgrounds', () => {
    const h = harness(config({navbar01:action}), {stockHolds:['screen_up_icon_2']});
    const bar = h.bar(0), navigation = bar.views.screen_up_icon_1, music = bar.views.screen_up_icon_2;
    assert.equal(navigation.isLongClickable(), true);
    h.setConfig(config({navbar02:action}));
    h.tick();
    assert.equal(navigation.isLongClickable(), false);
    assert.equal(navigation.background, navigation.stock);
    assert.equal(music.isLongClickable(), true);
    assert.equal(music.background.layer.icon.component, action.component);
    h.replace(bar, 'mScreenUpItemView2', 'screen_up_icon_2');
    assert.equal(music.isLongClickable(), true);
    assert.equal(music.background, music.stock);
    const replacement = bar.views.screen_up_icon_2;
    assert.equal(replacement.background.layer.icon.component, action.component);
    h.release(bar);
    assert.equal(replacement.isLongClickable(), true);
    assert.equal(replacement.background, replacement.stock);
});

test('a release token waits for native hold restoration and retries a failed restore', () => {
    const options = {};
    const h = harness(config({navbar01:action}), options);
    const bar = h.bar(0), view = bar.views.screen_up_icon_1;
    h.select(bar, 'com.example.player', '.Main');
    assert.equal(bar.checked, view.getId());
    const token = 'b'.repeat(32);
    const ack = 'OVC_PANEL_ICONS_RESTORED ' + token;
    options.failRestore = true;
    h.setConfig({schema:1, enabled:false, restoreIcons:token});
    h.tick();
    assert.equal(h.logs.includes(ack), false);
    assert.equal(view.isLongClickable(), true);
    assert.equal(bar.checked, -1);
    assert.equal(h.tap(bar, 'screen_up_icon_1'), 'oem');
    options.failRestore = false;
    h.tick();
    h.tick();
    assert.equal(h.logs.filter(log => log === ack).length, 1);
    assert.equal(view.isLongClickable(), false);
    assert.equal(view.background, view.stock);
});

test('unavailable or failed launches keep or restore the stock icon and action', () => {
    const missing = harness(config({navbar01:action}), {missing:action.component, failLaunch:true});
    const initial = missing.bar(0), first = initial.views.screen_up_icon_1;
    assert.equal(first.background, first.stock);
    assert.equal(first.isLongClickable(), false);
    assert.equal(missing.tap(initial, 'screen_up_icon_1'), 'oem');

    const failing = harness(config({navbar01:action, navbar02:action}), {failLaunch:true});
    const bar = failing.bar(0), navigation = bar.views.screen_up_icon_1;
    assert.notEqual(navigation.background, navigation.stock);
    assert.equal(failing.tap(bar, 'screen_up_icon_1'), 'oem');
    assert.equal(navigation.background, navigation.stock);
    assert.equal(navigation.isLongClickable(), false);
    assert.equal(bar.views.screen_up_icon_2.background.layer.icon.component, action.component);
    assert.equal(failing.originalCalls, 1);
});

test('a launcher without the confirmed theme method still handles clicks', () => {
    const h = harness(config({navbar01:action}), {noTheme:true});
    const bar = h.bar(0);
    assert.equal(bar.views.screen_up_icon_1.background, bar.views.screen_up_icon_1.stock);
    h.tap(bar, 'screen_up_icon_1');
    assert.equal(h.launched[0].intent.component, action.component);
    assert.equal(bar.views.screen_up_icon_1.isLongClickable(), true);
    const token = 'c'.repeat(32);
    h.setConfig({schema:1, enabled:false, restoreIcons:token});
    h.tick();
    assert.ok(h.logs.includes('OVC_PANEL_ICONS_RESTORED ' + token));
    assert.equal(bar.views.screen_up_icon_1.isLongClickable(), false);
});

test('selection follows the exact foreground component on its own display', () => {
    const h = harness(config({ navbar14: action }));
    const driver = h.bar(0);
    const passenger = h.bar(1);
    const player = passenger.views.screen_up_icon_3;
    h.select(driver, 'com.example.player', 'com.example.player.Main');
    assert.equal(driver.checked, -1);
    h.select(passenger, 'com.example.player', 'com.example.player.Main');
    assert.equal(passenger.checked, player.getId());
    assert.equal(passenger.stockUpdates, 1);
    assert.equal(player.background.layer.states.entries[0].ids[0], 16842912);
    h.select(passenger, 'com.example.player', '.OtherActivity');
    assert.equal(passenger.checked, -1);
    h.select(passenger, 'com.example.player', '.Main');
    assert.equal(passenger.checked, player.getId());
    h.setConfig(config({}));
    passenger.stockChecked = 30;
    h.select(passenger, 'com.example.player', '.Main');
    assert.equal(passenger.checked, 30);
    assert.equal(passenger.stockUpdates, 4);
});

test('missing foreground selection callback preserves icon and tap support', () => {
    const h = harness(config({ navbar02: action }), {noSelection:true});
    const bar = h.bar(0);
    assert.equal(bar.views.screen_up_icon_2.background.layer.icon.component, action.component);
    h.tap(bar, 'screen_up_icon_2');
    assert.equal(h.launched.length, 1);
});

test('every central app shortcut supplies the OEM panel width to the window agent', () => {
    const component = 'org.example.navigator/.Main';
    const action = {kind:'LAUNCH_APP',component};
    const h = harness({schema:1,enabled:true,actions:{navbar01:action,navbar02:action,navbar12:action}});
    const central = h.bar(0), passenger = h.bar(1);
    central.mLp = {value:{width:{value:171}}};
    passenger.mLp = {value:{width:{value:165}}};
    h.tap(central, 'screen_up_icon_1');
    h.tap(central, 'screen_up_icon_2');
    h.tap(passenger, 'screen_up_icon_1');
    assert.equal(h.launched[0].intent.extras['org.openvoyah.controls.extra.LEFT_PANEL_PX'], 171);
    assert.equal(h.launched[1].intent.extras['org.openvoyah.controls.extra.LEFT_PANEL_PX'], 171);
    assert.equal(h.launched[2].intent.extras, undefined);
});
