const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

// The framework side of the experimental reserved strip, against models of the two reviewed window managers.
const source = (...files) => files.map(file => fs.readFileSync(path.join(__dirname, '../hooks', file), 'utf8')
    .replace(/^import [\s\S]*?;$/gm, '').replace(/^export /gm, '')).join('\n');
const MODULES = ['window-profiles.js', 'window-reserve-common.js', 'window-reserve-android9.js',
    'window-reserve-android11.js', 'window-reserve.js'];
const field = value => ({value});
const LAUNCHER = 'com.qinggan.app.launcher';
const DECOR_FLAG = 0x00400000;
const NAV = 1;
const W = 1920, H = 720, DPI = 240;
const CUTOUT = 'android.view.DisplayCutout', RECT = 'android.graphics.Rect';

function rect(left, top, right, bottom) {
    return {left: field(left), top: field(top), right: field(right), bottom: field(bottom)};
}
const values = r => [r.left.value, r.top.value, r.right.value, r.bottom.value];
const clear = out => { out.left.value = 0; out.top.value = 0; out.right.value = 0; out.bottom.value = 0; };

function object(props) {
    const o = {...props};
    o.$isSameObject = other => other === o;
    o.$dispose = () => {};
    return o;
}

// `call` is the original implementation (what a Frida replacement calls); `invoke` is what other framework code
// sees through virtual dispatch.
function method(signature, original) {
    const m = {
        signature, implementation: null,
        call(receiver, ...args) { return original.apply(receiver, args); },
        invoke(receiver, ...args) { return (m.implementation ?? original).apply(receiver, args); }
    };
    return m;
}

function overloads(...methods) {
    return {overload(...types) {
        const found = methods.find(m => m.signature.join(',') === types.join(','));
        if (!found) throw Error('No overload ' + types.join(','));
        return found;
    }};
}

function declaredFields(map) {
    return {getDeclaredField(name) {
        if (!(name in map)) throw Error('NoSuchFieldException ' + name);
        return {getType: () => ({getName: () => map[name]})};
    }};
}

function arraySet() {
    const items = [];
    return {
        items,
        contains: value => items.includes(value),
        add(value) { if (!items.includes(value)) items.push(value); return true; },
        remove(value) { const i = items.indexOf(value); if (i >= 0) items.splice(i, 1); return i >= 0; },
        size: () => items.length,
        valueAt: index => items[index],
        removeAt: index => items.splice(index, 1)[0]
    };
}

function windowState({pkg = LAUNCHER, uid = 10050, type = 2003, frame = [0, 0, 171, H], display = 0, visible = true} = {}) {
    const win = object({
        frame, visible, controllable: null,
        mAttrs: field({type: field(type), privateFlags: field(0), providesInsetsTypes: field(null)}),
        mOwnerUid: field(uid),
        mRemoved: field(false),
        getDisplayId: () => display,
        getOwningPackage: () => pkg,
        getFrameLw: () => rect(...win.frame),
        isVisibleLw: () => win.visible && !win.mRemoved.value,
        getControllableInsetProvider: () => win.controllable
    });
    return win;
}

function newConfig() {
    const windowConfiguration = {bounds: null, getAppBounds() { return this.bounds && rect(...this.bounds); }};
    return {screenWidthDp: field(0), densityDpi: field(0), windowConfiguration: field(windowConfiguration)};
}

const readConfig = c => ({dp: c.screenWidthDp.value, appBounds: c.windowConfiguration.value.bounds});

function commonClasses() {
    return {
        'android.view.WindowManager$LayoutParams': {
            PRIVATE_FLAG_IS_SCREEN_DECOR: field(DECOR_FLAG), FIRST_SYSTEM_WINDOW: field(2000), LAST_SYSTEM_WINDOW: field(2999),
            TYPE_STATUS_BAR: field(2000), TYPE_NAVIGATION_BAR: field(2019), TYPE_INPUT_METHOD: field(2011),
            TYPE_WALLPAPER: field(2013), TYPE_TOAST: field(2005)
        },
        'android.os.UserHandle': {getAppId: uid => uid % 100000},
        'android.app.ActivityThread': {currentActivityThread: () => ({getSystemContext: () => ({
            getPackageManager: () => ({getPackageUid: pkg => {
                if (pkg !== LAUNCHER) throw Error('NameNotFoundException');
                return 10050;
            }})
        })})},
        'java.lang.Integer': {valueOf: value => value}
    };
}

function load(classes, sdk, clock) {
    const Java = {
        use(name) { if (!(name in classes)) throw Error('ClassNotFoundException ' + name); return classes[name]; },
        cast: value => value,
        retain: value => value,
        synchronized: (_lock, fn) => fn()
    };
    const context = {Java, Process: {getCurrentThreadId: () => 7}};
    const module = vm.runInNewContext(source(...MODULES) + '\n({systemReserve, choosePanel})', context);
    return {module, reserve: module.systemReserve(Java, sdk, {now: () => clock.now})};
}

function android9(options = {}) {
    const decor = arraySet();
    const panel = windowState();
    const app = windowState({pkg: 'org.example.app', uid: 10100, type: 1, frame: [0, 0, W, H]});
    const windows = [panel, app, ...(options.windows ?? [])];
    const frames = {mDock: field(rect(0, 0, W, H)), mRestricted: field(rect(0, 0, W, H))};
    let applied = null;
    let reconfigures = 0;
    const policy = object({
        mScreenDecorWindows: field(decor), mHasNavigationBar: field(options.navBar ?? false), mNavigationBar: field(null),
        getClass: () => ({getName: () => options.policyClass ?? 'com.android.server.policy.PhoneWindowManager'})
    });
    const width = method(['int', 'int', 'int', 'int', 'int', CUTOUT], w => w);
    const configWidth = method(['int', 'int', 'int', 'int', 'int', CUTOUT], function (...args) {
        return options.inline ? width.call(this, ...args) : width.invoke(this, ...args);
    });
    const insets = method(['int', 'int', 'int', CUTOUT, RECT], (_r, _w, _h, _c, out) => clear(out));
    const stable = method(['int', 'int', 'int', CUTOUT, RECT], function (...args) {
        return options.inline ? insets.call(this, ...args) : insets.invoke(this, ...args);
    });
    // PhoneWindowManager.adjustWindowParamsLw: drops a decor window whose new params lack the flag.
    const keep = method(['com.android.server.policy.WindowManagerPolicy$WindowState',
        'android.view.WindowManager$LayoutParams', 'boolean'], (win, attrs, permitted) => {
        const isDecor = (attrs.privateFlags.value & DECOR_FLAG) !== 0;
        if (decor.contains(win)) { if (!isDecor) decor.remove(win); }
        else if (isDecor && permitted) decor.add(win);
    });
    const layoutDecor = method(['com.android.server.wm.DisplayFrames', RECT, RECT, RECT], () => {});
    const dc = object({
        mDisplayId: field(0), mDisplayFrames: field(frames),
        getDisplayInfo: () => ({logicalWidth: field(W), logicalHeight: field(H), rotation: field(0)}),
        getConfiguration: () => applied,
        setLayoutNeeded() {}
    });
    const call = (m, ...args) => options.oemBypass ? m.call(policy, ...args) : m.invoke(policy, ...args);
    const screen = method(['android.content.res.Configuration'], function (config) {
        const id = this.mDisplayId.value;
        const appWidth = call(width, W, H, 0, 0, id, null);
        const out = rect(0, 0, 0, 0);
        call(insets, 0, W, H, null, out);
        config.screenWidthDp.value = Math.floor(call(configWidth, W, H, 0, 0, id, null) / (DPI / 160));
        config.densityDpi.value = DPI;
        config.windowConfiguration.value.bounds = [out.left.value, 0, out.left.value + appWidth, H];
    });
    const layout = () => {
        if (options.noLayout) return;
        const docked = decor.items.find(w => w.isVisibleLw() && w.getDisplayId() === 0);
        const left = docked ? docked.frame[2] : 0;
        frames.mDock.value = rect(left, 0, W, H);
        frames.mRestricted.value = rect(left, 0, W, H);
    };
    const wms = object({
        mWindowMap: field({values: () => ({toArray: () => windows.slice()})}),
        mPolicy: field(policy),
        mRoot: field({getDisplayContent: id => id === 0 ? dc : null}),
        requestTraversal: layout
    });
    const reconfigure = method(['com.android.server.wm.DisplayContent'], display => {
        reconfigures++;
        const c = newConfig();
        screen.invoke(display, c);
        applied = c;
        layout();
    });
    const compute = method(['int'], () => { const c = newConfig(); screen.invoke(dc, c); return c; });
    const stableLocked = method(['int', RECT], (id, out) => {
        clear(out);
        if (id === 0 || id === 1) stable.invoke(policy, 0, W, H, null, out);
    });
    const policyClass = {
        adjustWindowParamsLw: overloads(keep), getNonDecorDisplayWidth: overloads(width),
        getConfigDisplayWidth: overloads(configWidth), getNonDecorInsetsLw: overloads(insets),
        getStableInsetsLw: overloads(stable),
        layoutScreenDecorWindows: options.noDecorLayout ? overloads() : overloads(layoutDecor),
        class: declaredFields({mScreenDecorWindows: 'android.util.ArraySet'})
    };
    const classes = {
        ...commonClasses(),
        'android.app.ActivityManager': {getService: () => object({mWindowManager: field(wms)})},
        'com.android.server.am.ActivityManagerService': {},
        'com.android.server.wm.WindowManagerService': {getStableInsetsLocked: overloads(stableLocked),
            reconfigureDisplayLocked: overloads(reconfigure), computeNewConfiguration: overloads(compute)},
        'com.android.server.policy.PhoneWindowManager': policyClass,
        'com.android.server.wm.DisplayContent': {computeScreenConfiguration: overloads(screen)},
        'com.android.server.wm.WindowState': {}
    };
    reconfigure.call(wms, dc);
    return {classes, decor, panel, app, windows, frames, policy, wms, dc, width, insets, stableLocked, keep,
        reconfigures: () => reconfigures,
        hooks: [keep, width, configWidth, insets, stable, screen, stableLocked], applied: () => readConfig(applied)};
}

function android11(options = {}) {
    const panel = windowState();
    const app = windowState({pkg: 'org.example.app', uid: 10100, type: 1, frame: [0, 0, W, H]});
    const windows = [panel, app, ...(options.windows ?? [])];
    const insetsSource = {frame: [0, 0, 0, 0], visible: true,
        getFrame() { return rect(...this.frame); }, isVisible() { return this.visible; }};
    const provider = object({
        mControllable: field(options.controllable ?? true), mWin: field(null), controlTarget: null, pending: null,
        hasWindow() { return this.mWin.value !== null; }, getSource: () => insetsSource,
        isControllable() { return this.mControllable.value; }
    });
    let created = false;
    // InsetsSourceProvider.updateControlForTarget(): a windowless provider remembers the target.
    const control = method(['com.android.server.wm.InsetsControlTarget', 'boolean'], function (target) {
        if (this.mWin.value === null) { this.pending = target; return; }
        this.controlTarget = target;
    });
    // InsetsSourceProvider.setWindow(), reduced to the parts the adapter depends on.
    const setWindow = win => {
        const previous = provider.mWin.value;
        if (previous !== null && provider.mControllable.value) previous.controllable = null;
        provider.mWin.value = win;
        if (win === null) {
            insetsSource.frame = [0, 0, 0, 0];
            insetsSource.visible = false;
        } else if (provider.mControllable.value) {
            win.controllable = provider;
            if (provider.pending !== null) {
                control.invoke(provider, provider.pending, true);
                provider.pending = null;
            }
        }
    };
    if (options.navWindow) {
        created = true;
        provider.mWin.value = options.navWindow;
        options.navWindow.controllable = provider;
    }
    if (options.staleSelf) {
        // What an agent that ended while the mode was on leaves behind.
        created = true;
        provider.mWin.value = panel;
        panel.controllable = provider;
    }
    if (options.pendingTarget) {
        created = true;
        provider.pending = options.pendingTarget;
    }
    const controller = object({
        peekSourceProvider: type => type === NAV && created ? provider : null,
        getSourceProvider: type => { if (type !== NAV) throw Error('type'); created = true; return provider; },
        getRawInsetsState: () => ({peekSource: type => type === NAV && created ? insetsSource : null})
    });
    const policy = object({hasNavigationBar: () => options.navBar ?? false, getNavigationBar: () => null});
    const otherPolicy = object({});
    const width = method(['int', 'int', 'int', 'int', CUTOUT], w => w);
    const configWidth = method(['int', 'int', 'int', 'int', CUTOUT], function (...args) { return width.invoke(this, ...args); });
    const insets = method(['int', 'int', 'int', CUTOUT, RECT], (_r, _w, _h, _c, out) => clear(out));
    const stable = method(['int', 'int', 'int', CUTOUT, RECT], function (...args) { return insets.invoke(this, ...args); });
    let applied = null;
    const computeScreen = () => {
        const c = newConfig();
        const appWidth = width.invoke(policy, W, H, 0, 0, null);
        const out = rect(0, 0, 0, 0);
        insets.invoke(policy, 0, W, H, null, out);
        c.screenWidthDp.value = Math.floor(configWidth.invoke(policy, W, H, 0, 0, null) / (DPI / 160));
        c.densityDpi.value = DPI;
        c.windowConfiguration.value.bounds = [out.left.value, 0, out.left.value + appWidth, H];
        return c;
    };
    const layout = () => {
        if (options.noLayout) return;
        const win = provider.mWin.value;
        if (win !== null) { insetsSource.frame = win.frame.slice(); insetsSource.visible = win.isVisibleLw(); }
    };
    const dc = object({
        mDisplayId: field(0),
        getDisplayPolicy: () => policy,
        getInsetsStateController: () => controller,
        getDisplayInfo: () => ({logicalWidth: field(W), logicalHeight: field(H), rotation: field(0)}),
        getConfiguration: () => applied,
        setLayoutNeeded() {}
    });
    const setInsetProvider = method(['int', 'com.android.server.wm.WindowState',
        'com.android.internal.util.function.TriConsumer'], (type, win) => { if (type === NAV) setWindow(win); });
    const reconfigure = method([], () => { applied = computeScreen(); layout(); });
    const computeNew = method(['int'], () => computeScreen());
    const wms = object({
        mGlobalLock: field({}),
        mRoot: field({getDisplayContent: id => id === 0 ? dc : null}),
        mWindowMap: field({values: () => ({toArray: () => windows.slice()})}),
        requestTraversal: layout
    });
    const classes = {
        ...commonClasses(),
        'android.app.ActivityTaskManager': {getService: () => object({mWindowManager: field(wms)})},
        'com.android.server.wm.ActivityTaskManagerService': {},
        'com.android.server.wm.WindowManagerService': {computeNewConfiguration: overloads(computeNew)},
        'com.android.server.wm.DisplayContent': {setInsetProvider: overloads(setInsetProvider),
            reconfigureDisplayLocked: overloads(reconfigure)},
        'com.android.server.wm.DisplayPolicy': {
            getNonDecorDisplayWidth: overloads(width), getConfigDisplayWidth: overloads(configWidth),
            getNonDecorInsetsLw: overloads(insets), getStableInsetsLw: overloads(stable)
        },
        'com.android.server.wm.WindowState': {},
        'com.android.server.wm.InsetsSourceProvider': {updateControlForTarget: overloads(control)},
        'android.view.InsetsState': {ITYPE_NAVIGATION_BAR: field(NAV), toInternalType: () => ({contains: type => type === NAV})},
        'android.view.WindowInsets$Type': {navigationBars: () => 2},
        'android.view.ViewRootImpl': {sNewInsetsMode: field(options.insetsMode ?? 2), NEW_INSETS_MODE_FULL: field(2)},
        'java.lang.Integer': {valueOf: overloads(method(['int'], value => value))}
    };
    reconfigure.call(dc);
    return {classes, provider, insetsSource, panel, app, windows, policy, otherPolicy, width, insets, wms, control,
        hooks: [width, configWidth, insets, stable, control], applied: () => readConfig(applied)};
}

const reduced = {dp: Math.floor((W - 171) / (DPI / 160)), appBounds: [171, 0, W, H]};
const native = {dp: Math.floor(W / (DPI / 160)), appBounds: [0, 0, W, H]};

test('the panel is the one launcher window docked over the full height; several need the launcher to say which', () => {
    const {module} = load(android11().classes, 30, {now: 0});
    const display = {width: W, height: H};
    const base = {displayId: 0, alive: true, visible: true, eligible: true, pkg: LAUNCHER, appId: 10050, type: 2003,
        frame: [0, 0, 171, H]};
    const expected = {pkg: LAUNCHER, appId: 10050, width: 0};
    assert.equal(module.choosePanel(display, [base], expected).width, 171);
    // The window is what Android lays out; a differing resource value does not overrule a single candidate.
    assert.equal(module.choosePanel(display, [base], {...expected, width: 120}).width, 171);
    const wide = {...base, frame: [0, 0, 200, H]};
    assert.throws(() => module.choosePanel(display, [base, wide], expected), {code: 'ambiguous_panel'});
    assert.throws(() => module.choosePanel(display, [base, wide], {...expected, width: 120}), {code: 'ambiguous_panel'});
    assert.equal(module.choosePanel(display, [base, wide], {...expected, width: 199}).index, 1);
    for (const other of [{frame: [0, 40, 171, H]}, {frame: [4, 0, 175, H]}, {pkg: 'org.example.app'}, {displayId: 1},
        {visible: false}, {eligible: false}, {appId: 10100}, {frame: [0, 0, 960, H]}, {frame: [0, 0, 8, H]}]) {
        assert.throws(() => module.choosePanel(display, [{...base, ...other}], expected), {code: 'no_panel'});
    }
    assert.throws(() => module.choosePanel({width: H, height: W}, [base], expected), {code: 'unsupported_display'});
});

test('inspection reports what an attempt would find and changes nothing', () => {
    for (const [fixture, sdk] of [[android9(), 28], [android11(), 30]]) {
        const {reserve} = load(fixture.classes, sdk, {now: 0});
        assert.deepEqual({...reserve.inspect(171)}, {status: 'ready', width: 171});
        assert.equal(reserve.active(), false);
        assert.equal(reserve.width(), 0);
        assert.ok(fixture.hooks.every(hook => hook.implementation === null));
        assert.deepEqual(fixture.applied(), native);
        fixture.panel.visible = false;
        assert.equal(reserve.inspect(171).status, 'no_panel');
    }
    assert.equal(load(android9({policyClass: 'com.vendor.PolicyEx'}).classes, 28, {now: 0}).reserve.inspect().status, 'oem_policy');
    assert.equal(load(android9({navBar: true}).classes, 28, {now: 0}).reserve.inspect().status, 'system_nav_bar');
    assert.equal(load(android11({navBar: true}).classes, 30, {now: 0}).reserve.inspect().status, 'system_nav_bar');
});

test('Android 9 registers the panel as screen decor and aligns only the central display configuration', () => {
    for (const inline of [false, true]) {
        const f = android9({inline});
        const {reserve} = load(f.classes, 28, {now: 0});
        const result = reserve.apply(171);
        assert.deepEqual({...result}, {status: 'applied', width: 171});
        assert.equal(reserve.width(), 171);
        assert.ok(f.decor.contains(f.panel));
        assert.ok((f.panel.mAttrs.value.privateFlags.value & DECOR_FLAG) !== 0);
        assert.deepEqual(f.applied(), reduced);
        assert.deepEqual(values(f.frames.mDock.value), [171, 0, W, H]);
        // Another display and portrait-equivalent rotations keep their native values.
        assert.equal(f.width.invoke(f.policy, W, H, 0, 0, 1, null), W);
        assert.equal(f.width.invoke(f.policy, H, W, 1, 0, 0, null), H);
        const out = rect(0, 0, 0, 0);
        f.stableLocked.invoke(f.wms, 1, out);
        assert.equal(out.left.value, 0);
        f.stableLocked.invoke(f.wms, 0, out);
        assert.equal(out.left.value, 171);
        // Outside a display computation this release's inset getter cannot tell displays apart: no change.
        f.insets.invoke(f.policy, 0, W, H, null, out);
        assert.equal(out.left.value, 0);
    }
});

test('Android 9 keeps the decor registration across launcher relayouts and drops it on release', () => {
    const f = android9();
    const {reserve} = load(f.classes, 28, {now: 0});
    assert.equal(reserve.apply(0).status, 'applied');
    f.keep.invoke(f.policy, f.panel, {privateFlags: field(0)}, false);
    assert.ok(f.decor.contains(f.panel));
    f.keep.invoke(f.policy, f.app, {privateFlags: field(0)}, false);
    assert.equal(f.decor.contains(f.app), false);
    assert.equal(reserve.release().status, 'released');
    assert.equal(f.decor.size(), 0);
    assert.equal(f.panel.mAttrs.value.privateFlags.value & DECOR_FLAG, 0);
    assert.ok(f.hooks.every(hook => hook.implementation === null));
    assert.deepEqual(f.applied(), native);
    assert.equal(reserve.width(), 0);
    f.keep.invoke(f.policy, f.panel, {privateFlags: field(0)}, false);
    assert.equal(f.decor.contains(f.panel), false);
    assert.equal(reserve.release().status, 'released');
});

test('Android 9 refuses vendor policies and missing framework methods before changing state', () => {
    for (const [options, status] of [[{policyClass: 'com.qinggan.policy.PhoneWindowManagerEx'}, 'oem_policy'],
        [{noDecorLayout: true}, 'unsupported_framework'], [{navBar: true}, 'system_nav_bar']]) {
        const f = android9(options);
        const {reserve} = load(f.classes, 28, {now: 0});
        assert.equal(reserve.apply(171).status, status);
        assert.equal(f.decor.size(), 0);
        assert.ok(f.hooks.every(hook => hook.implementation === null));
        assert.deepEqual(f.applied(), native);
    }
});

test('a configuration path that bypasses the hooks is detected and fully rolled back', () => {
    const f = android9({oemBypass: true});
    const {reserve} = load(f.classes, 28, {now: 0});
    assert.equal(reserve.apply(171).status, 'verify_failed');
    assert.equal(f.decor.size(), 0);
    assert.equal(f.panel.mAttrs.value.privateFlags.value & DECOR_FLAG, 0);
    assert.ok(f.hooks.every(hook => hook.implementation === null));
    assert.equal(reserve.active(), false);
});

test('hooked framework methods never throw on failures inside the agent', () => {
    const f = android9();
    const {reserve} = load(f.classes, 28, {now: 0});
    assert.equal(reserve.apply(171).status, 'applied');
    const hostile = rect(0, 0, 0, 0);
    let stored = 0;
    Object.defineProperty(hostile.left, 'value', {get: () => stored, set: v => { if (v !== 0) throw Error('boom'); stored = v; }});
    assert.doesNotThrow(() => f.stableLocked.invoke(f.wms, 0, hostile));
    assert.equal(stored, 0);
    f.panel.$isSameObject = () => { throw Error('boom'); };
    assert.doesNotThrow(() => f.keep.invoke(f.policy, f.app, {privateFlags: field(DECOR_FLAG)}, true));
    assert.ok(f.decor.contains(f.app));
});

test('Android 9 re-registers a panel that a relayout removed outside the hook', () => {
    const clock = {now: 0};
    const f = android9();
    const {reserve} = load(f.classes, 28, clock);
    assert.equal(reserve.apply(171).status, 'applied');
    f.keep.call(f.policy, f.panel, {privateFlags: field(0)}, false);
    f.panel.mAttrs.value.privateFlags.value = 0;
    assert.equal(reserve.poll().status, 'reregistered');
    assert.ok(f.decor.contains(f.panel));
    clock.now = 2000;
    assert.equal(reserve.poll().status, 'verified');
});

test('Android 11 links the panel to the navigation source, keeps its control from apps and aligns display 0 only', () => {
    const focusedApp = object({});
    const f = android11({pendingTarget: focusedApp});
    const {reserve} = load(f.classes, 30, {now: 0});
    assert.equal(reserve.apply(171).status, 'applied');
    assert.equal(f.provider.mWin.value, f.panel);
    assert.equal(f.panel.controllable, f.provider);
    // No private field is written, and neither the pending nor a later focus target gets a leash.
    assert.equal(f.provider.mControllable.value, true);
    assert.equal(f.provider.controlTarget, null);
    f.control.invoke(f.provider, focusedApp, false);
    assert.equal(f.provider.controlTarget, null);
    assert.deepEqual(f.insetsSource.frame, [0, 0, 171, H]);
    assert.deepEqual(f.applied(), reduced);
    assert.equal(f.width.invoke(f.otherPolicy, W, H, 0, 0, null), W);
    const out = rect(0, 0, 0, 0);
    f.insets.invoke(f.otherPolicy, 0, W, H, null, out);
    assert.equal(out.left.value, 0);
    assert.equal(reserve.apply(171).unchanged, true);
    assert.equal(reserve.release().status, 'released');
    assert.equal(f.provider.mWin.value, null);
    assert.equal(f.panel.controllable, null);
    assert.equal(f.insetsSource.visible, false);
    assert.ok(f.hooks.every(hook => hook.implementation === null));
    assert.deepEqual(f.applied(), native);
    f.control.invoke(f.provider, focusedApp, false);
    assert.equal(f.provider.pending, focusedApp);
});

test('Android 11 refuses other insets modes, a real navigation bar and a foreign navigation source', () => {
    const foreign = windowState({pkg: 'com.android.systemui', uid: 10020, type: 2019, frame: [0, H - 48, W, H]});
    for (const [options, status] of [[{insetsMode: 1}, 'insets_mode'], [{navBar: true}, 'system_nav_bar'],
        [{navWindow: foreign}, 'already_reserved']]) {
        const f = android11(options);
        const {reserve} = load(f.classes, 30, {now: 0});
        assert.equal(reserve.apply(171).status, status);
        assert.equal(f.provider.mControllable.value, true);
        assert.equal(f.panel.controllable, null);
        assert.ok(f.hooks.every(hook => hook.implementation === null));
        assert.deepEqual(f.applied(), native);
    }
});

test('a registration found on the panel is someone else\'s unless a dead agent of this process is on record', () => {
    // Without the record, a launcher that reserves its own panel natively is left exactly as it is.
    const foreign11 = android11({staleSelf: true});
    const untouched = load(foreign11.classes, 30, {now: 0}).reserve;
    assert.equal(untouched.apply(171).status, 'already_reserved');
    assert.equal(untouched.release().status, 'released');
    assert.equal(foreign11.provider.mWin.value, foreign11.panel);
    assert.equal(foreign11.panel.controllable, foreign11.provider);

    const foreign9 = android9();
    foreign9.decor.add(foreign9.panel);
    foreign9.panel.mAttrs.value.privateFlags.value = DECOR_FLAG;
    const native9 = load(foreign9.classes, 28, {now: 0}).reserve;
    assert.equal(native9.apply(171).status, 'already_reserved');
    assert.equal(native9.release().status, 'released');
    assert.ok(foreign9.decor.contains(foreign9.panel));
    assert.equal(foreign9.panel.mAttrs.value.privateFlags.value, DECOR_FLAG);
    assert.ok(foreign9.hooks.every(hook => hook.implementation === null));

    // With the record, the leftover is replaced by a fresh registration or simply removed.
    const f = android11({staleSelf: true});
    const {reserve} = load(f.classes, 30, {now: 0});
    assert.equal(reserve.apply(171, true).status, 'applied');
    assert.equal(f.provider.mWin.value, f.panel);
    assert.equal(reserve.release().status, 'released');
    assert.equal(f.provider.mWin.value, null);
    assert.equal(f.panel.controllable, null);

    const stale = android11({staleSelf: true});
    assert.equal(load(stale.classes, 30, {now: 0}).reserve.release(true).status, 'released');
    assert.equal(stale.provider.mWin.value, null);
    assert.equal(stale.panel.controllable, null);

    // A launcher window that declares the insets type itself is native even then.
    const declared = android11({staleSelf: true});
    declared.panel.mAttrs.value.providesInsetsTypes.value = [NAV];
    const third = load(declared.classes, 30, {now: 0}).reserve;
    assert.equal(third.apply(171, true).status, 'already_reserved');
    assert.equal(third.release(true).status, 'released');
    assert.equal(declared.provider.mWin.value, declared.panel);

    const decorStale = android9();
    decorStale.decor.add(decorStale.panel);
    decorStale.panel.mAttrs.value.privateFlags.value = DECOR_FLAG;
    assert.equal(load(decorStale.classes, 28, {now: 0}).reserve.release(true).status, 'released');
    assert.equal(decorStale.decor.size(), 0);
    assert.equal(decorStale.panel.mAttrs.value.privateFlags.value, 0);
});

test('deferred verification, panel re-creation and panel loss are handled by poll()', () => {
    const clock = {now: 0};
    const f = android11();
    const {reserve} = load(f.classes, 30, clock);
    assert.equal(reserve.apply(171).status, 'applied');
    clock.now = 1000;
    assert.equal(reserve.poll().status, 'applied');
    clock.now = 2000;
    assert.equal(reserve.poll().status, 'verified');
    clock.now = 2500;
    assert.equal(reserve.poll().status, 'verified');

    f.panel.mRemoved.value = true;
    f.windows.splice(f.windows.indexOf(f.panel), 1);
    assert.equal(reserve.poll().status, 'waiting_panel');
    assert.equal(f.provider.mWin.value, null);
    const recreated = windowState();
    f.windows.push(recreated);
    assert.equal(reserve.poll().status, 'readopted');
    assert.equal(f.provider.mWin.value, recreated);
    assert.equal(recreated.controllable, f.provider);

    recreated.mRemoved.value = true;
    f.windows.splice(f.windows.indexOf(recreated), 1);
    clock.now = 3000;
    assert.equal(reserve.poll().status, 'waiting_panel');
    clock.now = 3000 + 15000;
    assert.equal(reserve.poll().status, 'panel_lost');
    assert.equal(reserve.active(), false);
    assert.equal(f.provider.mControllable.value, true);
    assert.deepEqual(f.applied(), native);
    assert.equal(reserve.poll().status, 'inactive');
});

test('a layout that never reserves the strip or a moved panel releases the experimental mode', () => {
    const clock = {now: 0};
    const f = android11({noLayout: true});
    const {reserve} = load(f.classes, 30, clock);
    assert.equal(reserve.apply(171).status, 'applied');
    clock.now = 2000;
    assert.equal(reserve.poll().status, 'verifying');
    clock.now = 7000;
    assert.equal(reserve.poll().status, 'verify_failed');
    assert.equal(reserve.active(), false);
    assert.deepEqual(f.applied(), native);

    const moved = android11();
    const second = load(moved.classes, 30, {now: 0}).reserve;
    assert.equal(second.apply(171).status, 'applied');
    moved.panel.frame = [0, 0, 200, H];
    assert.equal(second.poll().status, 'panel_moved');
    assert.equal(moved.provider.mWin.value, null);
    assert.deepEqual(moved.applied(), native);
});

test('ambiguous panels and unknown SDKs change nothing', () => {
    const twin = windowState({frame: [0, 0, 171, H]});
    const f = android11({windows: [twin]});
    const {reserve, module} = load(f.classes, 30, {now: 0});
    assert.equal(reserve.apply(171).status, 'ambiguous_panel');
    assert.equal(f.provider.mWin.value, null);
    const unknown = module.systemReserve({use: () => { throw Error('unused'); }}, 29, {});
    assert.equal(unknown.apply(171).status, 'unsupported_sdk');
    assert.equal(unknown.inspect().status, 'unsupported_sdk');
    assert.equal(unknown.release().status, 'unsupported_sdk');
});

test('a replacement that cannot be installed leaves none of the others behind', () => {
    for (const [fixture, sdk] of [[android9(), 28], [android11(), 30]]) {
        // The third replacement is refused, as a method the runtime cannot hook would be.
        const refused = fixture.hooks[2];
        let value = null;
        Object.defineProperty(refused, 'implementation', {
            get: () => value,
            set(next) { if (next !== null) throw Error('java.lang.IllegalStateException: private detail'); value = next; }
        });
        const {reserve} = load(fixture.classes, sdk, {now: 0});
        assert.equal(reserve.apply(171).status, 'failed');
        assert.ok(fixture.hooks.every(hook => hook.implementation === null));
        assert.equal(reserve.active(), false);
        assert.deepEqual(fixture.applied(), native);
        assert.equal(fixture.panel.controllable, null);
        assert.equal(fixture.panel.mAttrs.value.privateFlags.value & DECOR_FLAG, 0);
    }
});

test('taking over a leftover recomputes the configuration even when the new attempt is then refused', () => {
    const f = android9();
    f.decor.add(f.panel);
    f.panel.mAttrs.value.privateFlags.value = DECOR_FLAG;
    f.panel.visible = false;
    const before = f.reconfigures();
    const {reserve} = load(f.classes, 28, {now: 0});
    // The earlier agent's hooks ended with it; without a recomputation its reduced configuration would stay.
    assert.equal(reserve.apply(171, true).status, 'no_panel');
    assert.equal(f.decor.size(), 0);
    assert.equal(f.panel.mAttrs.value.privateFlags.value, 0);
    assert.equal(f.reconfigures(), before + 1);
});

test('on Android 9 only a decor window docked like the panel is taken for a leftover', () => {
    const f = android9();
    const topBar = windowState({frame: [0, 0, W, 48]});
    topBar.mAttrs.value.privateFlags.value = DECOR_FLAG;
    f.decor.add(topBar);
    f.decor.add(f.panel);
    f.panel.mAttrs.value.privateFlags.value = DECOR_FLAG;
    assert.equal(load(f.classes, 28, {now: 0}).reserve.release(true).status, 'released');
    // The launcher's own bar along the top keeps its registration.
    assert.deepEqual(f.decor.items, [topBar]);
    assert.equal(topBar.mAttrs.value.privateFlags.value, DECOR_FLAG);
    assert.equal(f.panel.mAttrs.value.privateFlags.value, 0);
});