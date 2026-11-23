const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = ['report.js', 'panel-state.js'].map(file => fs.readFileSync(path.join(__dirname, '../hooks', file), 'utf8')
    .replace(/^import .*;$/gm, '').replace(/^export /gm, '')).join('\n');

function harness(options = {}) {
    const handles = [], weakReferences = [], mainQueue = [], timers = new Set(), logs = [];
    let onMainThread = false, scheduled = 0, iconDraws = 0, state;
    let config = {schema:1, enabled:true, actions:{navbar01:{kind:'LAUNCH_APP', component:'org.example.player/.Main'}}};
    const targetOf = object => object?.nativeObject ?? object;
    function object(value) {
        const target = {...value};
        target.equals = other => targetOf(other) === target;
        return target;
    }
    const requireMain = () => assert.equal(onMainThread, true, 'view work must use the main thread');
    function main(run) {
        const previous = onMainThread;
        onMainThread = true;
        try { return run(); } finally { onMainThread = previous; }
    }
    function flush() { while (mainQueue.length) main(mainQueue.shift()); }
    const theme = {call() {}};
    const Nav = {updateTheme:{overload:() => theme}};
    const context = {getPackageManager:() => ({resolveActivity:() => ({activityInfo:{value:{}}})})};
    const classes = {
        'java.lang.ref.WeakReference':{$new(referent) {
            const reference = object({referent:targetOf(referent), get() { return this.referent; }});
            weakReferences.push(reference);
            return reference;
        }},
        'android.view.View':{}, 'android.widget.RadioGroup':{},
        'android.app.ActivityThread':{currentApplication:() => options.noApplication ? null : context},
        'android.content.pm.PackageManager':{MATCH_DEFAULT_ONLY:{value:65536}}
    };
    const Java = {
        use(name) { assert.ok(classes[name], name); return classes[name]; },
        cast:targetOf,
        retain(value) {
            const handle = Object.create(targetOf(value));
            handle.nativeObject = targetOf(value);
            handle.disposed = false;
            handle.$dispose = () => { assert.equal(handle.disposed, false, 'a handle must be disposed once'); handle.disposed = true; };
            handles.push(handle);
            return handle;
        },
        perform:run => run(),
        scheduleOnMainThread(run) { scheduled++; mainQueue.push(run); }
    };
    vm.runInNewContext(source + '\nstate = installPanelState(Java, Nav, panels, readConfig, actionFor, buildIntent, ownsView, lifecycle);', {
        Java, Nav, panels:[[['button', '', 'navbar01']]], readConfig:() => config,
        actionFor:(value, slot) => value?.schema === 1 && value.enabled === true ? value.actions?.[slot] ?? null : null,
        buildIntent:action => action, ownsView:() => true, lifecycle:{skipRelease:true, contextOf:() => context},
        createPanelIcon:() => () => {
            requireMain(); iconDraws++;
            if (options.failIcon) throw Error('java.lang.OutOfMemoryError: private bitmap');
            return object({kind:'assigned'});
        },
        console:{log:line => logs.push(line)},
        setInterval:run => { timers.add(run); return run; }, clearInterval:run => timers.delete(run),
        set state(value) { state = value; }
    });
    main(() => state.finishScan());

    function bar() {
        const stock = object({kind:'stock'});
        const view = object({
            background:stock, insets:[1, 2, 3, 4], hold:false, mutations:0,
            isLongClickable() { requireMain(); return this.hold; },
            setLongClickable(value) { requireMain(); this.mutations++; if (!value && options.failRestore) return; this.hold = value; },
            getBackground() { requireMain(); return this.background; },
            setBackground(value) { requireMain(); this.mutations++; this.background = targetOf(value); },
            setPadding(...insets) { requireMain(); this.mutations++; this.insets = insets; },
            getPaddingLeft() { requireMain(); return this.insets[0]; },
            getPaddingTop() { requireMain(); return this.insets[1]; },
            getPaddingRight() { requireMain(); return this.insets[2]; },
            getPaddingBottom() { requireMain(); return this.insets[3]; }
        });
        const group = object({checked:7, getCheckedRadioButtonId() { requireMain(); return this.checked; },
            check(id) { requireMain(); this.checked = id; }});
        const nav = object({mScreenId:{value:0}, button:{value:view}});
        main(() => state.track(nav));
        return {nav, view, stock, group};
    }
    return {
        options, state, theme, logs, handles, bar, main, flush,
        tick(flushMain = true) { for (const timer of timers) timer(); if (flushMain) flush(); },
        collect(...objects) {
            const targets = new Set(objects.map(targetOf));
            for (const reference of weakReferences) if (targets.has(reference.referent)) reference.referent = null;
        },
        setConfig:value => { config = value; },
        get liveHandles() { return handles.filter(handle => !handle.disposed).length; },
        get scheduled() { return scheduled; }, get queued() { return mainQueue.length; },
        get iconDraws() { return iconDraws; }, get timerCount() { return timers.size; }
    };
}

test('collected overseas bars release retained handles while assignments stay unchanged', () => {
    const h = harness();
    let retired = h.bar(); h.tick();
    assert.equal(h.liveHandles, 4);
    for (let i = 0; i < 30; i++) {
        h.collect(retired.nav, retired.view, retired.group);
        const current = h.bar();
        const draws = h.iconDraws, mutations = current.view.mutations;
        assert.equal(h.liveHandles, 8);
        h.tick();
        assert.equal(h.liveHandles, 4);
        assert.equal(h.iconDraws, draws);
        assert.equal(current.view.mutations, mutations);
        assert.equal(h.state.active(current.nav, current.view, {kind:'LAUNCH_APP', component:'org.example.player/.Main'}), true);
        retired = current;
    }
    h.collect(retired.nav, retired.view, retired.group); h.tick();
    assert.equal(h.liveHandles, 0);
});

test('unchanged live panels cause no queued work, view reapply or new retained handles', () => {
    const h = harness(), current = h.bar(); h.tick();
    const scheduled = h.scheduled, draws = h.iconDraws, mutations = current.view.mutations;
    const handles = h.handles.length, background = current.view.background;
    for (let i = 0; i < 10; i++) h.tick();
    assert.equal(h.scheduled, scheduled);
    assert.equal(h.iconDraws, draws);
    assert.equal(current.view.mutations, mutations);
    assert.equal(current.view.background, background);
    assert.equal(h.handles.length, handles);
    assert.equal(h.liveHandles, 4);
});

test('a collected owner restores a still-live view and selection only on the main thread', () => {
    const h = harness(), current = h.bar(); h.tick();
    h.main(() => h.state.select(current.nav, current.group, 42));
    assert.equal(h.liveHandles, 5);
    h.collect(current.nav);
    h.tick(false);
    assert.equal(h.queued, 1);
    assert.notEqual(current.view.background, current.stock);
    assert.equal(current.group.checked, 42);
    h.flush();
    assert.equal(current.view.background, current.stock);
    assert.equal(current.view.hold, false);
    assert.equal(current.group.checked, 7);
    assert.equal(h.liveHandles, 0);
});

test('failed collected-bar restoration retries without reapplying unchanged live bars', () => {
    const h = harness(), retired = h.bar(); h.tick();
    const current = h.bar(), mutations = current.view.mutations, draws = h.iconDraws;
    h.collect(retired.nav); h.options.failRestore = true;
    h.tick();
    assert.equal(h.liveHandles, 8);
    assert.equal(retired.view.hold, true);
    assert.equal(current.view.mutations, mutations);
    assert.equal(h.iconDraws, draws);
    h.options.failRestore = false; h.tick();
    assert.equal(h.liveHandles, 4);
    assert.equal(retired.view.background, retired.stock);
    assert.equal(current.view.mutations, mutations);
    assert.equal(h.iconDraws, draws);
});

test('collected panels are reclaimed even when the application context is temporarily absent', () => {
    const h = harness(), retired = h.bar(); h.tick();
    h.collect(retired.nav, retired.view);
    h.options.noApplication = true; h.tick();
    assert.equal(h.liveHandles, 0);
});

test('disposal cancels queued refreshes, restores live views and releases every retained handle once', () => {
    const h = harness(), retired = h.bar(); h.tick();
    const current = h.bar();
    h.main(() => h.state.select(current.nav, current.group, 42));
    h.collect(retired.nav, retired.view); h.tick(false);
    assert.equal(h.queued, 1);
    h.state.dispose();
    assert.equal(h.timerCount, 0);
    assert.equal(h.theme.implementation, null);
    const draws = h.iconDraws;
    h.flush();
    assert.equal(h.liveHandles, 0);
    assert.equal(current.view.background, current.stock);
    assert.equal(current.view.hold, false);
    assert.equal(current.group.checked, 7);
    assert.equal(h.iconDraws, draws);
    assert.equal(h.state.finishScan(), false);
    h.main(() => h.state.track(current.nav)); h.tick();
    assert.equal(h.liveHandles, 0);
    h.state.dispose(); h.flush();
    assert.equal(h.liveHandles, 0);
});

test('icon and restore failures are reported once per stage and slot without their message', () => {
    const h = harness({failIcon:true});
    const first = h.bar(), second = h.bar();
    for (const {nav, view, stock} of [first, second]) {
        assert.equal(view.background, stock);
        assert.equal(view.hold, true);
        assert.equal(h.state.active(nav, view, {kind:'LAUNCH_APP', component:'org.example.player/.Main'}), true);
    }
    h.options.failRestore = true;
    h.setConfig({schema:1, enabled:false}); h.tick(); h.tick();
    assert.equal(first.view.hold, true);
    assert.deepEqual(h.logs.filter(line => line.startsWith('OVC_PANEL_ICONS_ERROR')), [
        'OVC_PANEL_ICONS_ERROR stage=icon kind=OutOfMemoryError slot=navbar01',
        'OVC_PANEL_ICONS_ERROR stage=restore kind=Error slot=navbar01',
    ]);
    assert.equal(h.logs.some(line => line.includes('private') || line.includes('Could not')), false);
});
