const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = ['launcher-buttons.js', 'navbar-oversea.js'].map(file =>
    fs.readFileSync(__dirname + '/../hooks/' + file, 'utf8')
        .replace(/^import .*;$/gm, '').replaceAll('export function', 'function')).join('\n');

function harness(options = {}) {
    const config = {schema:1, enabled:true, actions:{}};
    const logs = [], launches = [], states = [], bars = [], hooks = [];
    let stock = 0, stockHolds = 0;
    const object = value => Object.assign(value, {equals(other) { return this === other; }, $dispose() {}});
    const context = {
        getContentResolver() { return {}; },
        startActivity:{overload() { return {call(self, intent, bundle) {
            if (options.failLaunch) throw Error('Target disappeared');
            launches.push({display:bundle.display, intent});
        }}; }}
    };
    const layouts = [
        ['com.qinggan.launcher.navigation.NavigationBarMain', 'mScreenUpItemView1', 'mScreenUpItemView2', 'mScreenUpHomeView', 'mScreenUpAllAppView'],
        ['com.qinggan.launcher.navigation.NavigationBarSecond', 'mScreenUpHomeView', 'mScreenUpSeatView', 'mScreenUpAllAppView']
    ];
    const classes = {};
    layouts.forEach(([name, ...fields], screen) => {
        const bar = object({screen, mScreenId:{value:screen}});
        for (const field of fields) bar[field] = {value:object({mListenerInfo:{value:{mOnClickListener:{value:bar}}}})};
        const click = {returnType:{className: options.badSignature && screen === 1 ? 'boolean' : 'void'}, call() { stock++; }};
        const constructor = {call() {}};
        hooks.push({click, constructor}); bars.push(bar);
        classes[name] = {
            class:{isInstance: value => value.screen === screen},
            onClick:{overload:() => click}, updateTheme:{overload:() => ({returnType:{className:'void'}})},
            $init:{overloads:[constructor]}
        };
    });
    const holds = [0, 1].map(() => ({call() { stockHolds++; return false; }}));
    classes['android.view.View'] = {performLongClick:{overload:(...args) => holds[args.length ? 1 : 0]}};
    classes['android.provider.Settings$Global'] = {getString:() => JSON.stringify(config)};
    classes['android.app.ActivityThread'] = {currentApplication:() => context};
    classes['android.content.Intent'] = {$new(action) { return {
        action, addCategory(category) { this.category = category; }, setComponent(component) { this.component = component; },
        addFlags(flags) { this.flags = (this.flags ?? 0) | flags; },
        putExtra:{overload() { return {call(intent, key, value) { intent.extras = {[key]:value}; }}; }}
    }; }};
    classes['android.content.ComponentName'] = {unflattenFromString:value => value, $new:(pkg, cls) => pkg + '/' + cls};
    classes['android.app.ActivityOptions'] = {makeBasic() { return {
        setLaunchDisplayId(display) { this.display = display; }, toBundle() { return {display:this.display}; }
    }; }};
    const Java = {
        use(name) { if (!classes[name]) throw Error('Class unavailable'); return classes[name]; },
        perform:fn => fn(), scheduleOnMainThread:fn => fn(), retain:value => value, cast:value => value,
        choose(name, callback) { callback.onMatch(bars[layouts.findIndex(layout => layout[0] === name)]); callback.onComplete(); }
    };
    vm.runInNewContext(source + '\ninstallOversea(Java);', {
        Java, Process:{id:42}, console:{log:value => logs.push(value)},
        installPanelState(Java, Nav, panels, read, action, build, owns, lifecycle) {
            const tracked = new Set(), failed = new Set();
            const state = {
                lifecycle, disposed:false,
                track(nav) { tracked.add(nav); },
                active(nav, view) { return tracked.has(nav) && !failed.has(view) && Object.values(nav).some(field => field?.value === view); },
                failed(nav, slot) { failed.add(nav[panels[nav.screen].find(item => item[2] === slot)[0]].value); },
                finishScan:() => true,
                dispose() { this.disposed = true; }
            };
            states.push(state); return state;
        }
    });
    return {config, logs, launches, states, bars, hooks, holds,
        click(screen, field) { return hooks[screen].click.implementation.call(bars[screen], bars[screen][field].value); },
        hold(screen, field, ...coordinates) { return holds[coordinates.length ? 1 : 0].implementation.call(bars[screen][field].value, ...coordinates); },
        holdAndRelease(screen, field, ...coordinates) {
            if (!this.hold(screen, field, ...coordinates)) this.click(screen, field);
        },
        get stock() { return stock; }, get stockHolds() { return stockHolds; }};
}
const action = {kind:'LAUNCH_APP', component:'org.example.player/.Main'};

test('overseas center and passenger mappings launch on their own display', () => {
    const h = harness();
    h.config.actions = {navbar01:action, navbar12:action};
    h.click(0, 'mScreenUpItemView1'); h.click(1, 'mScreenUpSeatView');
    assert.deepEqual(h.launches.map(item => item.display), [0, 1]);
    assert.equal(h.stock, 0);
    assert.equal(h.logs.filter(line => line.startsWith('OVC_READY ')).length, 1);
});

test('overseas home and app drawer remain native unless explicitly assigned', () => {
    const h = harness();
    h.click(0, 'mScreenUpHomeView'); h.click(0, 'mScreenUpAllAppView'); h.click(1, 'mScreenUpAllAppView');
    assert.equal(h.stock, 3);
    h.config.actions = {mainHome:action, mainApps:action, passengerApps:action};
    h.click(0, 'mScreenUpHomeView'); h.click(0, 'mScreenUpAllAppView'); h.click(1, 'mScreenUpAllAppView');
    assert.deepEqual(h.launches.map(item => item.display), [0, 0, 1]);
});

test('overseas Controls app menu uses the real apps intent and flags on the assigned display', () => {
    const h = harness();
    h.config.actions = {mainApps:{kind:'CONTROLS_APPS'}, passengerApps:{kind:'CONTROLS_APPS'}};
    h.click(0, 'mScreenUpAllAppView'); h.click(1, 'mScreenUpAllAppView');
    assert.deepEqual(h.launches.map(item => item.display), [0, 1]);
    for (const launch of h.launches) {
        assert.equal(launch.intent.action, 'org.openvoyah.controls.action.OPEN_APPS');
        assert.equal(launch.intent.component, 'org.openvoyah.controls/org.openvoyah.controls.MainActivity');
        assert.equal(launch.intent.flags, 0x04000000 | 0x20000000 | 0x10000000 | 0x00200000);
        assert.equal(launch.intent.category, undefined);
        assert.equal(launch.intent.extras, undefined);
    }
    assert.equal(h.stock, 0);
    h.click(0, 'mScreenUpHomeView'); h.click(1, 'mScreenUpSeatView');
    assert.equal(h.stock, 2);
});

test('overseas Controls app-menu hold calls native exactly once and consumes release', () => {
    for (const coordinates of [[], [18, 24]]) {
        const h = harness();
        h.config.actions.mainApps = {kind:'CONTROLS_APPS'};
        h.holdAndRelease(0, 'mScreenUpAllAppView', ...coordinates);
        assert.equal(h.stock, 1);
        assert.equal(h.stockHolds, 0);
        assert.equal(h.launches.length, 0);
    }
});

test('overseas cleared, disabled and invalid app-menu assignments keep native tap and hold', () => {
    for (const state of ['cleared', 'unassigned', 'disabled', 'wrongSchema']) {
        const h = harness();
        h.config.actions.mainApps = {kind:'CONTROLS_APPS'};
        if (state === 'cleared') h.config.actions.mainApps = {kind:'NONE'};
        if (state === 'unassigned') delete h.config.actions.mainApps;
        if (state === 'disabled') h.config.enabled = false;
        if (state === 'wrongSchema') h.config.schema = 2;
        h.click(0, 'mScreenUpAllAppView');
        assert.equal(h.hold(0, 'mScreenUpAllAppView'), false);
        assert.equal(h.stock, 1);
        assert.equal(h.stockHolds, 1);
        assert.equal(h.launches.length, 0);
    }
});

test('overseas failed app-menu launch retains native fallback without disabling music', () => {
    const options = {failLaunch:true}, h = harness(options);
    h.config.actions = {mainApps:{kind:'CONTROLS_APPS'}, navbar02:action};
    h.click(0, 'mScreenUpAllAppView');
    assert.equal(h.stock, 1); assert.equal(h.launches.length, 0);
    options.failLaunch = false;
    h.click(0, 'mScreenUpAllAppView');
    assert.equal(h.stock, 2); assert.equal(h.launches.length, 0);
    h.click(0, 'mScreenUpItemView2');
    assert.equal(h.launches[0].intent.component, action.component);
    assert.equal(h.launches[0].display, 0);
});

test('hold runs the original action once and failure restores native taps', () => {
    const options = {}, h = harness(options);
    h.config.actions.navbar01 = action;
    assert.equal(h.hold(0, 'mScreenUpItemView1'), true);
    assert.equal(h.stock, 1); assert.equal(h.launches.length, 0);
    assert.equal(h.hold(1, 'mScreenUpSeatView'), false);
    options.failLaunch = true;
    h.click(0, 'mScreenUpItemView1');
    options.failLaunch = false;
    h.click(0, 'mScreenUpItemView1');
    assert.equal(h.stock, 3); assert.equal(h.launches.length, 0);
});

test('disabled and malformed assignments preserve stock behavior', () => {
    const h = harness();
    h.config.actions.navbar01 = {kind:'LAUNCH_APP', component:'invalid'};
    h.click(0, 'mScreenUpItemView1');
    h.config.actions.navbar01 = action; h.config.enabled = false;
    h.click(0, 'mScreenUpItemView1');
    assert.equal(h.stock, 2); assert.equal(h.launches.length, 0);
});

test('restoration acknowledgment waits for both overseas bars with the same request', () => {
    const h = harness(), [main, passenger] = h.states;
    main.lifecycle.restored('a'.repeat(32)); passenger.lifecycle.restored('b'.repeat(32));
    assert.equal(h.logs.filter(line => line.startsWith('OVC_PANEL_ICONS_RESTORED ')).length, 0);
    passenger.lifecycle.restored('a'.repeat(32));
    assert.equal(h.logs.filter(line => line.startsWith('OVC_PANEL_ICONS_RESTORED ')).length, 1);
});

test('unsupported second-bar signature leaves both native click paths intact', () => {
    const h = harness({badSignature:true});
    assert.ok(h.hooks.every(hook => !hook.click.implementation));
    assert.ok(!h.logs.some(line => line.startsWith('OVC_READY ')));
});

test('a newly constructed bar is tracked before accepting an assignment', () => {
    const h = harness(); h.config.actions.navbar01 = action;
    const old = h.bars[0];
    const nav = {...old, equals(other) { return this === other; }, $dispose() {}};
    h.bars[0] = nav;
    h.click(0, 'mScreenUpItemView1'); assert.equal(h.stock, 1);
    h.hooks[0].constructor.implementation.call(nav);
    h.click(0, 'mScreenUpItemView1'); assert.equal(h.launches.length, 1);
});
