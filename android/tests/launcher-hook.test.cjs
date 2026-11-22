const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = ['report.js', 'panel-icons.js', 'panel-state.js', 'home-navi-card.js', 'home-weather-card.js', 'launcher-buttons.js'].map(file => fs.readFileSync(path.join(__dirname, '../hooks', file), 'utf8')
    .replace(/^import .*;$/gm, '').replaceAll('export function', 'function')).join('\n') + '\ninstall(Java);';

function harness(config, options = {}) {
    let originalCalls = 0;
    const launched = [], logs = [], retries = [], timers = [];
    let inClick = false, inTheme = false, iconReads = 0, decodes = 0;
    const click = { call(nav, view) {
        if (!inClick) return dispatchClick(nav, view);
        originalCalls++;
        if (options.failNative) throw new Error('java.lang.IllegalStateException: private native action');
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
            const packageName = intent.component?.split('/')[0] ?? 'com.android.settings';
            return {
                activityInfo: {value:{packageName:{value:packageName}}},
                loadIcon() {
                    return {component:intent.component ?? intent.action, getIntrinsicWidth:() => 128, getIntrinsicHeight:() => 64};
                }
            };
        },
        // Apps name a panel glyph only through options.glyphs: {package: drawable id}.
        getApplicationInfo: {overload(...types) {
            assert.deepEqual(types, ['java.lang.String', 'int']);
            return {call(self, packageName, flags) {
                assert.equal(self, manager);
                assert.equal(flags, 0x80);
                if (options.failGlyph) throw new Error('android.content.pm.PackageManager$NameNotFoundException');
                const id = options.glyphs?.[packageName];
                const metaData = id === undefined ? null
                    : {getInt: key => key === 'org.openvoyah.controls.PANEL_ICON' ? id : 0};
                return {packageName, metaData: {value: metaData}};
            }};
        }},
        getDrawable(packageName, id, app) {
            assert.equal(app.packageName, packageName);
            if (options.missingGlyph) return null;
            const [width, height] = options.glyphSize ?? [234, 201];
            return {glyph:id, packageName, getIntrinsicWidth:() => width, getIntrinsicHeight:() => height};
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
            getContext: () => context,
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
            ['mScreenUpAllAppView', 'screen_up_all_app_icon'],
            ['mScreenUpItemView1', 'screen_up_icon_1'],
            ['mScreenUpItemView2', 'screen_up_icon_2'],
            ['mScreenUpItemView3', 'screen_up_icon_3'],
            ['mScreenUpItemView4', 'screen_up_icon_4']
        ]) {
            if (options.missingFields?.includes(field)) continue;
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
        'android.provider.Settings$Global': { getString(resolver, key) {
            if (key === 'org.openvoyah.controls.panelIcons') {
                iconReads++;
                return options.panelIcons === undefined ? null : JSON.stringify(options.panelIcons);
            }
            assert.equal(key, 'org.openvoyah.controls.panel'); return typeof config === 'string' ? config : JSON.stringify(config);
        } },
        'android.util.Base64': {decode(data, flags) { assert.equal(flags, 0); return Array.from(data, c => c.charCodeAt(0)); }},
        'android.graphics.BitmapFactory': {decodeByteArray(bytes, offset, length) {
            decodes++;
            const text = String.fromCharCode(...bytes.slice(offset, offset + length));
            return text === 'broken' ? null : {png: text, $dispose() {}};
        }},
        'android.graphics.drawable.BitmapDrawable': {$new(resources, bitmap) {
            const [width, height] = options.publishedSize ?? [112, 112];
            return {bitmap, getIntrinsicWidth: () => width, getIntrinsicHeight: () => height};
        }},
        'android.content.Intent': { $new(action) { return {
            action, addCategory(c) { this.category = c; }, setComponent(c) { this.component = c; }, addFlags(f) { this.flags = (this.flags ?? 0) | f; },
            putExtra: { overload() { return { call(intent, key, value) { intent.extras = {[key]:value}; } }; } }
        }; } },
        'android.content.ComponentName': { unflattenFromString(s) { return s; }, $new(pkg, cls) { return pkg + '/' + cls; } },
        'android.app.ActivityOptions': { makeBasic() { return { setLaunchDisplayId(id) { this.display = id; }, toBundle() { return { display: this.display }; } }; } },
        'android.app.ActivityThread': {currentApplication:() => context},
        'android.content.pm.PackageManager': {MATCH_DEFAULT_ONLY:{value:65536}},
        'android.graphics.drawable.LayerDrawable': {$new(layers) { return {
            layers, icon: layers[0],
            setLayerGravity(index, gravity) { this.gravity = [index, gravity]; },
            setLayerSize(index, width, height) { this.sizedLayer = index; this.size = [width,height]; }
        }; }},
        'android.graphics.drawable.RippleDrawable': {$new(color, layer, mask) { return object({color, layer, mask}); }},
        'android.graphics.drawable.GradientDrawable': {$new() { return {setColor(color) { this.color = color; }, setCornerRadius(radius) { this.radius = radius; }}; }},
        'android.content.res.ColorStateList': {valueOf:color => color},
        'android.view.Gravity': {CENTER:{value:17}},
        'java.lang.ref.WeakReference': {$new(referent) { return {get: () => referent, $dispose() {}}; }},
        'android.util.Log': { i(tag, line) { logs.push(line); }, w(tag, line) { logs.push(line); } }
    };
    vm.runInNewContext(source, {
        Java: {
            array(name, values) { return values; }, retain(x) { return x; }, cast(x) { return x; },
            choose(name, callbacks) {
                if (options.failChoose) throw new Error('java.lang.IllegalStateException: private heap scan');
                existing.forEach(nav => callbacks.onMatch(Object.assign(nav, {$dispose(){}}))); callbacks.onComplete();
            },
            scheduleOnMainThread(fn) { fn(); }, perform(fn) { fn(); },
            use(name) {
                if (options.noNav && name.endsWith('NavigationBar')) throw Error('class not loaded');
                // A launcher without the factory navigation card: the home card reports it and stays off.
                if (name.startsWith('com.qinggan.app.launcher.homecard.')) throw Error('java.lang.ClassNotFoundException: ' + name);
                assert.ok(classes[name], name); return classes[name];
            }
        },
        Process: { id: 42 }, console: { log(line) { logs.push(line); } },
        setTimeout(fn,ms) { retries.push({fn,ms}); }, setInterval(fn) { timers.push(fn); return fn; },
        clearInterval(fn) {
            if (options.failDispose) throw new Error('java.lang.IllegalStateException: private timer');
            timers.splice(timers.indexOf(fn),1);
        }
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
        retries, logs, existing, get iconReads() { return iconReads; }, get decodes() { return decodes; },
        hooks: {click, init, selection, holds, theme, release},
        holdView(view, ...coordinates) { return performHold(view, coordinates); },
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
        tapView(nav, view) { return dispatchClick(nav, view); },
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

test('voice shortcuts target the real QuickVoiceActivity directly on their own display', () => {
    const component = 'org.openvoyah.controls/org.openvoyah.controls.QuickVoiceActivity';
    for (const [screen, slot, resource] of [[0, 'navbar01', 'screen_up_icon_1'], [1, 'navbar11', 'screen_up_home_icon']]) {
        const h = harness(config({ [slot]: { kind: 'START_VOICE' } }));
        const bar = h.bar(screen);
        assert.equal(bar.views[resource].background.layer.icon.component, component);
        h.tap(bar, resource);
        assert.equal(h.originalCalls, 0);
        assert.equal(h.launched.length, 1);
        const launch = h.launched[0];
        assert.equal(launch.display, screen);
        assert.equal(launch.intent.component, component);
        assert.equal(launch.intent.flags, 0x20000000 | 0x10000000 | 0x00200000);
        assert.equal(launch.intent.action, undefined);
        assert.equal(launch.intent.category, undefined);
        assert.equal(launch.intent.extras, undefined);
    }
});

test('unavailable or refused direct voice launches retain one native fallback', () => {
    const component = 'org.openvoyah.controls/org.openvoyah.controls.QuickVoiceActivity';
    for (const options of [{missing:component}, {failLaunch:true}]) {
        const h = harness(config({navbar01:{kind:'START_VOICE'}, navbar02:action}), options);
        const bar = h.bar(0), voice = bar.views.screen_up_icon_1;
        assert.equal(h.tap(bar, 'screen_up_icon_1'), 'oem');
        assert.equal(h.originalCalls, 1);
        assert.equal(h.launched.length, 0);
        assert.equal(voice.background, voice.stock);
        options.missing = null;
        options.failLaunch = false;
        h.tap(bar, 'screen_up_icon_2');
        assert.equal(h.launched.at(-1).intent.component, action.component);
        assert.equal(h.originalCalls, 1);
    }
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

test('explicit center Home and All apps assignments use their own fields and display', () => {
    const home = {kind:'LAUNCH_APP', component:'org.example.home/.Main'};
    const apps = {kind:'LAUNCH_APP', component:'org.example.apps/.Main'};
    const h = harness(config({mainHome:home, mainApps:apps, navbar11:action}));
    const center = h.bar(0), passenger = h.bar(1);
    for (const [resource, assigned] of [['screen_up_home_icon', home], ['screen_up_all_app_icon', apps]]) {
        const view = center.views[resource];
        assert.equal(view.background.layer.icon.component, assigned.component);
        assert.equal(view.isLongClickable(), true);
        h.tap(center, resource);
        assert.equal(h.launched.at(-1).intent.component, assigned.component);
        assert.equal(h.launched.at(-1).display, 0);
    }
    h.tap(passenger, 'screen_up_home_icon');
    assert.equal(h.launched.at(-1).intent.component, action.component);
    assert.equal(h.launched.at(-1).display, 1);
    assert.equal(h.tap(passenger, 'screen_up_all_app_icon'), 'oem');
    assert.equal(h.originalCalls, 1);
    assert.equal(h.launched.length, 3);
});

test('Controls app menu uses the explicit apps entry point, task flags, display and activity icon', () => {
    const apps = {kind:'CONTROLS_APPS'};
    const h = harness(config({mainApps:apps, navbar15:apps}));
    const component = 'org.openvoyah.controls/org.openvoyah.controls.MainActivity';
    for (const [screen, resource] of [[0, 'screen_up_all_app_icon'], [1, 'screen_up_icon_4']]) {
        const bar = h.bar(screen), view = bar.views[resource];
        assert.equal(view.background.layer.icon.component, component);
        h.tap(bar, resource);
        const launch = h.launched.at(-1);
        assert.equal(launch.intent.action, 'org.openvoyah.controls.action.OPEN_APPS');
        assert.equal(launch.intent.component, component);
        assert.equal(launch.intent.flags, 0x04000000 | 0x20000000 | 0x10000000 | 0x00200000);
        assert.equal(launch.intent.category, undefined);
        assert.equal(launch.intent.extras, undefined);
        assert.equal(launch.display, screen);
    }
    assert.equal(h.originalCalls, 0);
    assert.equal(h.click(0, 'screen_up_home_icon'), 'oem');
    assert.equal(h.click(1, 'screen_up_all_app_icon'), 'oem');
    assert.equal(h.originalCalls, 2);
});

test('Controls app-menu hold invokes native once and consumes release for both hold signatures', () => {
    for (const coordinates of [[], [18, 24]]) {
        const h = harness(config({mainApps:{kind:'CONTROLS_APPS'}}));
        h.holdAndRelease(h.bar(0), 'screen_up_all_app_icon', ...coordinates);
        assert.equal(h.originalCalls, 1);
        assert.equal(h.originalHolds, 0);
        assert.equal(h.launched.length, 0);
    }
});

test('clearing or disabling the Controls app menu restores the native icon, hold and tap', () => {
    for (const next of [config({mainApps:{kind:'NONE'}}), config({}), {schema:1, enabled:false, actions:{mainApps:{kind:'CONTROLS_APPS'}}}]) {
        const h = harness(config({mainApps:{kind:'CONTROLS_APPS'}})), bar = h.bar(0);
        const view = bar.views.screen_up_all_app_icon;
        assert.notEqual(view.background, view.stock);
        h.setConfig(next); h.tick();
        assert.equal(view.background, view.stock);
        assert.equal(view.isLongClickable(), false);
        assert.equal(h.tap(bar, 'screen_up_all_app_icon'), 'oem');
        assert.equal(h.originalCalls, 1);
        assert.equal(h.launched.length, 0);
    }
});

test('unavailable and failed Controls app-menu launches preserve native action and independent music', () => {
    for (const options of [{missing:'org.openvoyah.controls/org.openvoyah.controls.MainActivity'}, {failLaunch:true}]) {
        const h = harness(config({mainApps:{kind:'CONTROLS_APPS'}, navbar02:action}), options);
        const bar = h.bar(0), view = bar.views.screen_up_all_app_icon;
        assert.equal(h.tap(bar, 'screen_up_all_app_icon'), 'oem');
        assert.equal(h.originalCalls, 1);
        assert.equal(h.launched.length, 0);
        assert.equal(view.background, view.stock);
        assert.equal(view.isLongClickable(), false);
        options.failLaunch = false; options.missing = null;
        assert.equal(h.tap(bar, 'screen_up_all_app_icon'), 'oem');
        assert.equal(h.originalCalls, 2);
        h.tap(bar, 'screen_up_icon_2');
        assert.equal(h.launched.at(-1).intent.component, action.component);
    }
});

test('assigned center Home and All apps holds invoke the native click once and consume release', () => {
    for (const [slot, resource] of [['mainHome', 'screen_up_home_icon'], ['mainApps', 'screen_up_all_app_icon']]) {
        for (const coordinates of [[], [18, 24]]) {
            const h = harness(config({[slot]:action}));
            h.holdAndRelease(h.bar(0), resource, ...coordinates);
            assert.equal(h.originalCalls, 1);
            assert.equal(h.originalHolds, 0);
            assert.equal(h.launched.length, 0);
        }
    }
});

test('cleared and disabled center assignments restore the native icon, hold flag and click', () => {
    for (const [slot, resource] of [['mainHome', 'screen_up_home_icon'], ['mainApps', 'screen_up_all_app_icon']]) {
        for (const next of [config({}), {schema:1, enabled:false, actions:{[slot]:action}}]) {
            const h = harness(config({[slot]:action})), bar = h.bar(0), view = bar.views[resource];
            assert.notEqual(view.background, view.stock);
            h.setConfig(next); h.tick();
            assert.equal(view.background, view.stock);
            assert.equal(view.isLongClickable(), false);
            assert.equal(h.tap(bar, resource), 'oem');
            assert.equal(h.originalCalls, 1);
            assert.equal(h.launched.length, 0);
        }
    }
});

test('unavailable and failed center assignments preserve native behavior without losing music', () => {
    const music = {kind:'LAUNCH_APP', component:'org.example.music/.Main'};
    for (const [slot, resource] of [['mainHome', 'screen_up_home_icon'], ['mainApps', 'screen_up_all_app_icon']]) {
        for (const options of [{missing:action.component}, {failLaunch:true}]) {
            const h = harness(config({[slot]:action, navbar02:music}), options);
            const bar = h.bar(0), view = bar.views[resource];
            assert.equal(h.tap(bar, resource), 'oem');
            assert.equal(h.originalCalls, 1);
            assert.equal(h.launched.length, 0);
            assert.equal(view.background, view.stock);
            assert.equal(view.isLongClickable(), false);
            options.failLaunch = false;
            options.missing = null;
            // A failed button retains native fallback; an independent assignment stays usable.
            h.tap(bar, 'screen_up_icon_2');
            assert.equal(h.launched.at(-1).intent.component, music.component);
        }
    }
});

test('missing optional fields keep center navigation, music and their foreground selection usable', () => {
    for (const missingFields of [['mScreenUpHomeView'], ['mScreenUpAllAppView'], ['mScreenUpHomeView', 'mScreenUpAllAppView']]) {
        const h = harness(config({navbar01:action, navbar02:action, mainHome:action, mainApps:action}), {missingFields});
        const bar = h.bar(0);
        for (const resource of ['screen_up_icon_1', 'screen_up_icon_2']) {
            h.tap(bar, resource);
            assert.equal(h.launched.at(-1).display, 0);
            assert.equal(h.launched.at(-1).intent.component, action.component);
        }
        h.select(bar, 'com.example.player', '.Main');
        assert.equal(bar.checked, bar.views.screen_up_icon_1.getId());
        for (const [field, resource] of [['mScreenUpHomeView', 'screen_up_home_icon'], ['mScreenUpAllAppView', 'screen_up_all_app_icon']]) {
            if (!missingFields.includes(field)) {
                h.tap(bar, resource);
                assert.equal(h.launched.at(-1).intent.component, action.component);
            }
        }
        assert.equal(h.originalCalls, 0);
        assert.ok(h.logs.some(line => line.startsWith('OVC_READY NavigationBar')));
    }
});

test('center Home and All apps require both the observed resource and owned view field', () => {
    for (const [slot, resource] of [['mainHome', 'screen_up_home_icon'], ['mainApps', 'screen_up_all_app_icon']]) {
        const h = harness(config({[slot]:action})), bar = h.bar(0), owned = bar.views[resource];
        const impostor = {...owned};
        assert.equal(h.tapView(bar, impostor), 'oem');
        owned.getResources = () => ({getResourceEntryName: () => 'screen_up_icon_3'});
        assert.equal(h.tap(bar, resource), 'oem');
        assert.equal(h.originalCalls, 2);
        assert.equal(h.launched.length, 0);
    }
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
        assert.deepEqual(view.background.layer.gravity, [0, 17]);
        assert.equal(view.background.layer.sizedLayer, 0);
        assert.equal(view.background.mask, null);
        assert.deepEqual(view.insets, [4,5,6,7]);
        h.tap(bar, name);
        assert.equal(h.launched.at(-1).intent.component, app.component);
        assert.equal(h.launched.at(-1).display, display);
    }
    const climate = center.views.screen_up_icon_3;
    assert.equal(climate.background, climate.stock);
    assert.equal(h.tap(center, 'screen_up_icon_3'), 'oem');
});

test('Controls entries draw the app panel glyph without a plate and keep a rounded pressed ripple', () => {
    const glyph = 0x7f080042;
    const h = harness(config({mainApps:{kind:'CONTROLS_APPS'}, navbar01:{kind:'START_VOICE'}, navbar02:action}),
        {glyphs:{'org.openvoyah.controls':glyph}});
    const bar = h.bar(0);
    for (const name of ['screen_up_all_app_icon', 'screen_up_icon_1']) {
        const {background} = bar.views[name];
        assert.deepEqual([background.layer.icon.glyph, background.layer.icon.packageName], [glyph, 'org.openvoyah.controls']);
        assert.equal(background.layer.layers.length, 1);
        // As tall as the 56 px app icons beside it, so the wide mark runs wider instead of shrinking.
        assert.deepEqual(background.layer.size, [65,56]);
        assert.deepEqual(background.layer.gravity, [0, 17]);
        assert.equal(background.color, 0x26ffffff);
        // The glyph paints only its strokes, so the ripple gets the button's rounded bounds as its mask.
        assert.equal(background.mask.color, -1);
        assert.equal(background.mask.radius, 90 * 0.12);
    }
    const music = bar.views.screen_up_icon_2;
    assert.equal(music.background.layer.icon.component, action.component);
    assert.equal(music.background.mask, null);
    h.theme(bar);
    assert.equal(bar.views.screen_up_all_app_icon.background.layer.icon.glyph, glyph);
    const token = 'b'.repeat(32);
    h.setConfig({schema:1, enabled:false, restoreIcons:token});
    h.tick();
    for (const view of Object.values(bar.views)) assert.equal(view.background, view.stock);
    assert.ok(h.logs.includes('OVC_PANEL_ICONS_RESTORED ' + token));
});

test('panel glyphs stay inside their button', () => {
    for (const [glyphSize, size] of [[[348, 100], [116,33]], [[100, 200], [28,56]], [[200, 200], [56,56]]]) {
        const h = harness(config({mainApps:{kind:'CONTROLS_APPS'}}), {glyphs:{'org.openvoyah.controls':0x7f080042}, glyphSize});
        assert.deepEqual(h.bar(0).views.screen_up_all_app_icon.background.layer.size, size);
    }
});

test('icons Controls published for the panel win over every other icon and follow their version', () => {
    const navigator = {kind:'LAUNCH_APP', component:'ru.yandex.yandexnavi/.core.NavigatorActivity'};
    const player = {kind:'LAUNCH_APP', component:'com.example.player/.Main'};
    const actions = {navbar01:navigator, navbar02:player, mainApps:{kind:'CONTROLS_APPS'}};
    const options = {glyphs:{'org.openvoyah.controls':0x7f080042},
        panelIcons:{schema:1, icons:{'ru.yandex.yandexnavi':'arrow', 'org.openvoyah.controls':'wings'}}};
    const h = harness({schema:1, enabled:true, actions, customIcons:'v1'}, options);
    const bar = h.bar(0);
    for (const [name, png] of [['screen_up_icon_1', 'arrow'], ['screen_up_all_app_icon', 'wings']]) {
        const {background} = bar.views[name];
        assert.equal(background.layer.icon.bitmap.png, png);
        assert.deepEqual(background.layer.size, [56,56]);
        assert.equal(background.mask.radius, 90 * 0.12);
    }
    // An app without a published icon keeps its own.
    assert.equal(bar.views.screen_up_icon_2.background.layer.icon.component, player.component);
    assert.equal(h.iconReads, 1);
    assert.equal(h.decodes, 2);
    h.theme(bar);
    assert.equal(h.iconReads, 1);
    assert.equal(h.decodes, 2);
    // A new version is read again; without one the other icons return.
    options.panelIcons = {schema:1, icons:{'com.example.player':'note'}};
    h.setConfig({schema:1, enabled:true, actions, customIcons:'v2'});
    h.tick();
    assert.equal(h.iconReads, 2);
    assert.equal(bar.views.screen_up_icon_2.background.layer.icon.bitmap.png, 'note');
    assert.equal(bar.views.screen_up_icon_1.background.layer.icon.component, navigator.component);
    assert.equal(bar.views.screen_up_all_app_icon.background.layer.icon.glyph, 0x7f080042);
    h.setConfig({schema:1, enabled:true, actions});
    h.tick();
    assert.equal(bar.views.screen_up_icon_2.background.layer.icon.component, player.component);
    const token = 'd'.repeat(32);
    h.setConfig({schema:1, enabled:false, restoreIcons:token});
    h.tick();
    for (const view of Object.values(bar.views)) assert.equal(view.background, view.stock);
});

test('a missing, damaged or unreadable published icon document leaves the other icons', () => {
    const player = {kind:'LAUNCH_APP', component:'com.example.player/.Main'};
    for (const panelIcons of [undefined, {schema:2, icons:{'com.example.player':'note'}}, {schema:1, icons:null},
        {schema:1, icons:{'com.example.player':'broken'}}, {schema:1, icons:{'com.example.player':7}}]) {
        const h = harness({schema:1, enabled:true, actions:{navbar02:player}, customIcons:'v1'}, {panelIcons});
        const {background} = h.bar(0).views.screen_up_icon_2;
        assert.equal(background.layer.icon.component, player.component);
        assert.equal(background.mask, null);
    }
});

test('a missing or unreadable panel glyph falls back to the launcher icon', () => {
    const component = 'org.openvoyah.controls/org.openvoyah.controls.MainActivity';
    for (const options of [{failGlyph:true}, {missingGlyph:true}]) {
        const h = harness(config({mainApps:{kind:'CONTROLS_APPS'}}), {glyphs:{'org.openvoyah.controls':0x7f080042}, ...options});
        const {background} = h.bar(0).views.screen_up_all_app_icon;
        assert.equal(background.layer.icon.component, component);
        assert.deepEqual(background.layer.size, [56,28]);
        assert.equal(background.mask, null);
        assert.ok(!h.logs.some(line => line.includes('OVC_PANEL_ICONS_ERROR')));
    }
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
    // The launcher's own slider marks the checked button; the icon adds no plate of its own.
    assert.equal(player.background.layer.layers.length, 1);
    assert.equal(player.background.layer.layers[0].component, action.component);
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

test('click, hold and selection failures are bounded error lines with their slot instead of logcat text', () => {
    const options = {failLaunch:true};
    const h = harness(config({navbar01:action}), options);
    for (let press = 0; press < 3; press++) assert.equal(h.tap(h.bar(0), 'screen_up_icon_1'), 'oem');
    assert.equal(h.originalCalls, 3);
    options.failLaunch = false;
    const broken = h.bar(0), view = broken.views.screen_up_icon_1;
    view.getResources = () => { throw new Error('java.lang.IllegalStateException: private resources'); };
    assert.equal(h.holdView(view), false);
    assert.equal(h.originalHolds, 1);
    options.failNative = true;
    assert.equal(h.holdView(h.bar(0).views.screen_up_icon_1, 18, 24), true);
    options.failNative = false;
    const selecting = h.bar(0);
    selecting.mScreenUpRadioGroup = {get value() { throw new Error('java.lang.IllegalStateException: private group'); }};
    h.select(selecting, 'com.example.player', '.Main');
    assert.equal(selecting.stockUpdates, 1);
    assert.deepEqual(h.logs.filter(line => line.startsWith('OVC_PANEL_ERROR')), [
        'OVC_PANEL_ERROR stage=click kind=Error slot=navbar01',
        'OVC_PANEL_ERROR stage=hold kind=IllegalStateException',
        'OVC_PANEL_ERROR stage=hold kind=IllegalStateException slot=navbar01',
        'OVC_PANEL_ERROR stage=selection kind=IllegalStateException',
    ]);
    assert.equal(h.logs.some(line => line.includes('private') || line.includes('Activity not found')), false);
});

test('a failed installation resets every hook even when dispose throws and retries in 30 s', () => {
    const options = {failChoose:true, failDispose:true};
    const h = harness(config({navbar01:action}), options);
    const {click, init, selection, holds, theme, release} = h.hooks;
    for (const hook of [click, init, selection, ...holds, theme, release]) assert.equal(hook.implementation, null);
    assert.deepEqual(h.retries.map(retry => retry.ms), [30000]);
    assert.deepEqual(h.logs.filter(line => line.startsWith('OVC_')), [
        'OVC_HOME_CARD_ERROR stage=install kind=ClassNotFoundException',
        'OVC_HOME_WEATHER_ERROR stage=install kind=ClassNotFoundException',
        'OVC_PANEL_ERROR stage=install kind=IllegalStateException',
        'OVC_PANEL_ERROR stage=rollback kind=IllegalStateException',
    ]);
    options.failChoose = false; options.failDispose = false;
    h.retries[0].fn();
    h.click(0, 'screen_up_icon_1');
    assert.equal(h.launched.length, 1);
    assert.equal(h.originalCalls, 0);
    assert.equal(h.logs.filter(line => line === 'OVC_PANEL_SELECTION_READY').length, 1);
});

test('selection readiness is announced only after the rest of the installation succeeded', () => {
    const h = harness(config({navbar01:action}), {existing:[0]});
    const ready = h.logs.findIndex(line => line.startsWith('OVC_READY NavigationBar'));
    assert.ok(ready >= 0);
    assert.deepEqual(h.logs.slice(ready + 1).filter(line => line.startsWith('OVC_')), ['OVC_PANEL_SELECTION_READY']);
    assert.equal(harness(config({navbar01:action}), {noSelection:true}).logs.includes('OVC_PANEL_SELECTION_READY'), false);
});
