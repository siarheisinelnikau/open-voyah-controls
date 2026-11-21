const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const source = (...files) => files.map(file => fs.readFileSync(path.join(__dirname, '../hooks', file), 'utf8')
    .replace(/^import [\s\S]*?;$/gm, '').replace(/^export /gm, '')).join('\n');
const profile = {component:'org.example.app/org.example.app.Main', width:80, dpi:200, panelPx:0};
const field = value => ({value});
const rect = (left, top, right, bottom) => ({left:field(left),top:field(top),right:field(right),bottom:field(bottom),
    isEmpty:()=>right <= left || bottom <= top});
const conf = dpi => ({densityDpi:field(dpi)});
const fill = (out, values) => ['left', 'top', 'right', 'bottom'].forEach((side, index) => { out[side].value = values[index]; });

function harness(sdk, options = {}) {
    let displayId = options.displayId ?? 2;
    let mode = options.mode ?? 1;
    let listed = !options.cold;
    let override = conf(options.dpi ?? 0);
    let displayBounds = [0, 0, 1920, 720];
    let density = 240, resourceWidth = 145;
    let bounds = rect(...displayBounds);
    let placements = 0, scans = 0, writes = 0, notifications = 0, resourceReads = 0, reveals = 0;
    const overrides = [];
    const record = {
        packageName: field('org.example.app'),
        finishing: field(false),
        getDisplayId: () => displayId,
        ensureActivityConfiguration: () => { notifications++; }
    };
    const records = [record];
    if (options.shared) records.push({...record, packageName:field('org.example.other')});
    const task = {
        getChildCount: () => { scans++; return records.length; },
        getChildAt: index => records[index],
        getWindowingMode: () => mode,
        getBounds: () => bounds,
        isActivityTypeStandardOrUndefined: () => true,
        isResizeable: () => true,
        getConfiguration: () => conf(override.densityDpi.value || 240)
    };
    const api = {
        am: {getTasks: () => ({size:()=>listed ? 1 : 0, get:()=>({baseActivity:field({getPackageName:()=>'org.example.app'})})})},
        runningId: () => 45,
        lock: callback => callback(),
        user: () => 0,
        userOf: () => 0,
        isLocked: () => false,
        isLeaf: () => true,
        supportsFreeform: () => true,
        id: () => 45,
        find: id => id === 45 ? task : null,
        display: () => ({getConfiguration: () => ({windowConfiguration:field({getBounds:()=>rect(...displayBounds)}),
            densityDpi:field(density)})}),
        androidDisplay: id => id === 2 ? {
            getType: () => options.type ?? 1,
            getFlags: () => options.flags ?? 0,
            getOwnerUid: () => options.owner ?? 0,
        } : null,
        windowManager: {getStableInsets: (_, out) => fill(out, options.stableInsets ?? [0, 0, 0, 0])},
        unusedNavigationBar: out => {
            if (options.navigationBar instanceof Error) throw options.navigationBar;
            if (!options.navigationBar) return false;
            fill(out, options.navigationBar);
            return true;
        },
        requestedBounds: () => bounds,
        minimum: () => options.minimum ?? [200, 200],
        getOverride: () => override,
        densityConfig: (_, dpi, pinned = null) => ({...conf(dpi), pinned}),
        resourcesMatch: (a, b) => a.densityDpi.value === b.densityDpi.value &&
            JSON.stringify(a.pinned ?? null) === JSON.stringify(b.pinned ?? null),
        setOverride: (_, value) => { override = value; writes++; overrides.push({mode, pinned:value.pinned ?? null}); },
        revealHome: () => { reveals++; return true; },
        multiWindowAvailable: () => {
            if (options.multiWindow instanceof Error) throw options.multiWindow;
            return options.multiWindow === true;
        },
        place: (_, target, placed) => {
            placements++;
            mode = target === null ? 1 : placed;
            bounds = target ?? rect(...displayBounds);
        },
    };
    const classes = {
        'android.graphics.Rect': {$new: (...args) => rect(...(args.length ? args : [0,0,0,0]))},
        'android.content.res.Configuration': {$new: value => ({...conf(value?.densityDpi.value ?? 0), pinned:value?.pinned ?? null}),
            SCREENLAYOUT_LONG_YES:field(32),SCREENLAYOUT_SIZE_XLARGE:field(4)},
        'android.app.WindowConfiguration': {
            WINDOWING_MODE_FULLSCREEN:field(1),WINDOWING_MODE_FREEFORM:field(5),WINDOWING_MODE_MULTI_WINDOW:field(6),
            WINDOWING_MODE_SPLIT_SCREEN_PRIMARY:field(3),WINDOWING_MODE_SPLIT_SCREEN_SECONDARY:field(4)},
        'android.content.Intent': {FLAG_ACTIVITY_LAUNCH_ADJACENT:field(0x1000)},
        'android.view.Display': {TYPE_BUILT_IN:field(1),TYPE_INTERNAL:field(1),FLAG_PRIVATE:field(4)},
        'android.app.ActivityManager': {RESIZE_MODE_SYSTEM:field(0)},
        'android.app.ActivityTaskManager': {RESIZE_MODE_SYSTEM:field(0)},
        'java.lang.System': {identityHashCode:()=>22},
    };
    const Java = {use: name => classes[name], cast: value => value, retain:value=>value};
    const resources = {getIdentifier:()=>1, getDimensionPixelOffset:()=>{ resourceReads++; return resourceWidth; }};
    const context = {getPackageManager:()=>({getResourcesForApplication:()=>resources})};
    const agent = vm.runInNewContext(source('window-profiles.js','window-tasks.js','window-policy.js') +
        '\nsystemTasks(Java, sdk, launcherPanelWidth(Java, context))', {
        Java, sdk, context, android9Tasks:()=>api, android11Tasks:()=>api,
    });
    return {
        agent, task, placements:()=>placements, density:()=>override.densityDpi.value,
        scans:()=>scans, writes:()=>writes, notifications:()=>notifications, resourceReads:()=>resourceReads,
        reveals:()=>reveals, overrides, pinned:()=>override.pinned ?? null,
        configure: (frame, dpi, panel) => { displayBounds = frame; density = dpi; resourceWidth = panel; },
        mode:()=>mode,
        bounds:()=>[bounds.left.value, bounds.top.value, bounds.right.value, bounds.bottom.value],
        markStarted:()=>{ listed = true; },
        launchAt: frame => { mode = 5; bounds = rect(...frame); },
        move: id => {
            displayId = id;
            mode = 1;
            bounds = rect(...displayBounds);
        },
    };
}

test('secondary app density is opt-in, reversible and never places a task', () => {
    for (const sdk of [28, 30]) {
        const h = harness(sdk, {dpi:180});
        assert.equal(h.agent.screenOf(2), 'secondary');
        assert.equal(h.agent.apply(profile, 0, 45, null, 2).status, 'density_only');
        assert.equal(h.density(), 200);
        h.agent.releaseSecondary(profile.component, 0, 45, 2);
        assert.equal(h.density(), 180);
        assert.equal(h.placements(), 0);
    }
});

test('virtual and private displays remain outside the app task density policy', () => {
    for (const options of [{type:5}, {flags:4}, {owner:10023}]) {
        const h = harness(30, options);
        assert.equal(h.agent.screenOf(2), null);
        assert.throws(() => h.agent.apply(profile, 0, 45, null, 2), {code:'no_display'});
        assert.equal(h.density(), 0);
        assert.equal(h.placements(), 0);
    }
});

test('window release clears tracked density across displays without moving the task', () => {
    const h = harness(30, {displayId:0, dpi:180});
    const result = h.agent.apply(profile, 0, 45);
    assert.equal(result.left, 500);
    assert.equal(h.placements(), 1);
    h.move(2);
    h.agent.release(0);
    assert.equal(h.density(), 180);
    assert.equal(h.placements(), 1);
});

test('a measured rail follows its display frame and refreshes after rotation or density changes', () => {
    const fullWidth = {...profile, width:100};
    for (const sdk of [28, 30]) {
        const h = harness(sdk, {displayId:0});
        assert.equal(h.agent.boundsFor(fullWidth, 180)[0], 180);
        assert.equal(h.agent.apply(fullWidth, 0, 45).left, 180);
        assert.equal(h.agent.boundsFor(fullWidth, 112)[0], 112);
        assert.equal(h.agent.apply(fullWidth, 0, 45).left, 112);
        assert.equal(h.resourceReads(), 0);

        h.configure([24, 40, 744, 1320], 240, 155);
        assert.equal(h.agent.apply(fullWidth, 0, 45).left, 179);
        assert.equal(h.agent.apply(fullWidth, 0, 45).left, 179);
        assert.equal(h.resourceReads(), 1);

        h.configure([24, 40, 744, 1320], 300, 195);
        assert.equal(h.agent.apply(fullWidth, 0, 45).left, 219);
        assert.equal(h.resourceReads(), 2);
    }
});

test('a navigation bar that Android reserves without a window does not shorten a placed task', () => {
    const fullWidth = {...profile, width:100};
    for (const sdk of [28, 30]) {
        // The supported head unit: a 50 px status bar on top and a forced, never-added 80 px navigation bar below.
        const unit = {displayId:0, stableInsets:[0, 50, 0, 80]};
        const h = harness(sdk, {...unit, navigationBar:[0, 0, 0, 80]});
        assert.deepEqual(Array.from(h.agent.boundsFor(fullWidth)), [145, 50, 1920, 720]);
        const applied = h.agent.apply(fullWidth, 0, 45);
        assert.equal(applied.left, 145);
        assert.deepEqual(h.bounds(), [145, 50, 1920, 720]);

        // A display cutout keeps its own share of the stable insets.
        const cutout = harness(sdk, {displayId:0, stableInsets:[0, 50, 0, 104], navigationBar:[0, 0, 0, 80]});
        assert.deepEqual(Array.from(cutout.agent.boundsFor(fullWidth)), [145, 50, 1920, 696]);

        // A navigation bar that is really there, or a framework the agent cannot read, keeps the stable insets.
        for (const navigationBar of [null, new Error('signature')]) {
            const kept = harness(sdk, {...unit, navigationBar});
            assert.deepEqual(Array.from(kept.agent.boundsFor(fullWidth)), [145, 50, 1920, 640]);
        }
    }
});

test('on Android 11 a placed task moves to multi-window, which has no caption bar, and is restored as it was', () => {
    const fullWidth = {...profile, width:100};
    const h = harness(30, {displayId:0, multiWindow:true, stableInsets:[0, 50, 0, 80], navigationBar:[0, 0, 0, 80]});
    assert.equal(h.agent.apply(fullWidth, 0, 45).status, 'applied');
    assert.equal(h.mode(), 6);
    assert.deepEqual(h.bounds(), [145, 50, 1920, 720]);
    h.agent.apply(fullWidth, 0, 45);
    assert.equal(h.placements(), 1);
    h.agent.release(0);
    assert.equal(h.mode(), 1);
    assert.equal(h.placements(), 2);

    // A launch arrives in freeform, the only mode that takes launch bounds, and leaves it with the same bounds.
    const launched = harness(30, {displayId:0, multiWindow:true});
    launched.launchAt([145, 0, 1920, 720]);
    launched.agent.apply(fullWidth, 0, 45);
    assert.equal(launched.mode(), 6);
    assert.deepEqual(launched.bounds(), [145, 0, 1920, 720]);

    // A task that was freeform before the agent placed it goes back to freeform, with its own bounds.
    const floating = harness(30, {displayId:0, multiWindow:true});
    floating.launchAt([300, 100, 1500, 600]);
    floating.agent.apply(fullWidth, 0, 45);
    assert.equal(floating.mode(), 6);
    floating.agent.release(0);
    assert.equal(floating.mode(), 5);
    assert.deepEqual(floating.bounds(), [300, 100, 1500, 600]);
});

test('a multi-window task gets resource sizes fixed to its bounds before its mode changes, and loses them on release', () => {
    const fullWidth = {...profile, width:100};
    const h = harness(30, {displayId:0, multiWindow:true});
    h.launchAt([145, 0, 1920, 720]);
    h.agent.apply(fullWidth, 0, 45);
    assert.equal(h.mode(), 6);
    assert.deepEqual(Array.from(h.pinned()), [145, 0, 1920, 720]);
    // Written once, while the task was still freeform; applying again writes nothing.
    assert.deepEqual(h.overrides.map(entry => entry.mode), [5]);
    h.agent.apply(fullWidth, 0, 45);
    assert.equal(h.overrides.length, 1);
    h.agent.release(0);
    assert.equal(h.mode(), 5);
    assert.equal(h.pinned(), null);

    // A freeform placement where multi-window is unavailable never fixes the sizes.
    const freeform = harness(30, {displayId:0});
    freeform.agent.apply(fullWidth, 0, 45);
    assert.equal(freeform.mode(), 5);
    assert.ok(freeform.overrides.every(entry => entry.pinned === null));
});

test('a multi-window task left by an earlier agent returns to fullscreen, and a foreign one is left alone', () => {
    const fullWidth = {...profile, width:100};
    // This agent process never tracked the task: an earlier one placed it.
    const leftover = harness(30, {displayId:0, multiWindow:true, mode:6});
    leftover.agent.apply(fullWidth, 0, 45);
    assert.equal(leftover.mode(), 6);
    leftover.agent.release(0);
    assert.equal(leftover.mode(), 1);
    assert.equal(leftover.pinned(), null);

    // An earlier agent's density goes as well: Android never gives a task a density of its own.
    const dense = harness(30, {displayId:0, multiWindow:true, mode:6, dpi:320});
    dense.agent.apply(fullWidth, 0, 45);
    assert.equal(dense.density(), 200);
    dense.agent.release(0);
    assert.equal(dense.mode(), 1);
    assert.equal(dense.density(), 0);

    // A task this agent placed stays its own when an organizer for that mode appears later.
    const options = {displayId:0, multiWindow:true};
    const placed = harness(30, options);
    placed.agent.apply(fullWidth, 0, 45);
    assert.equal(placed.mode(), 6);
    options.multiWindow = false;
    placed.agent.release(0);
    assert.equal(placed.mode(), 1);

    // With a task organizer for that mode, multi-window tasks are not this agent's.
    for (const multiWindow of [false, new Error('organizer')]) {
        const foreign = harness(30, {displayId:0, multiWindow, mode:6});
        assert.throws(() => foreign.agent.apply(fullWidth, 0, 45), {code:'unsupported_task'});
        assert.equal(foreign.placements(), 0);
    }
});

test('without a usable multi-window mode a placed task stays freeform', () => {
    const fullWidth = {...profile, width:100};
    for (const [sdk, multiWindow] of [[28, true], [30, false], [30, new Error('organizer')]]) {
        const h = harness(sdk, {displayId:0, multiWindow});
        h.agent.apply(fullWidth, 0, 45);
        assert.equal(h.mode(), 5);
        h.agent.release(0);
        assert.equal(h.mode(), 1);
    }
});

test('both task lookup paths validate activities once and skip unchanged resize and density writes', () => {
    for (const sdk of [28, 30]) {
        const h = harness(sdk, {displayId:0});
        assert.equal(h.agent.apply(profile, 0, 45).status, 'applied');
        assert.equal(h.agent.apply(profile, 0).status, 'applied');
        assert.equal(h.scans(), 2);
        assert.equal(h.placements(), 1);
        assert.equal(h.writes(), 1);
        assert.equal(h.notifications(), 1);
        h.agent.release(0);
        assert.equal(h.density(), 0);
        assert.equal(h.placements(), 2);

        const shared = harness(sdk, {displayId:0, shared:true});
        assert.throws(() => shared.agent.apply(profile, 0, 45), {code:'no_task'});
        assert.throws(() => shared.agent.apply(profile, 0), {code:'no_task'});
        assert.equal(shared.placements(), 0);
        assert.equal(shared.writes(), 0);
    }
});

test('an impossible warm launch restores the window captured before provisional launch bounds', () => {
    for (const sdk of [28, 30]) {
        const h = harness(sdk, {displayId:0, dpi:180, minimum:[800, 500]});
        h.configure([0, 0, 720, 1280], 240, 180);
        const prior = h.agent.captureLaunch(profile, 0);
        h.launchAt(h.agent.boundsFor(profile));
        const launch = h.agent.rememberLaunch(h.task, profile.component, 0, prior, {mode:1, bounds:null});
        assert.throws(() => h.agent.apply(profile, 0, 45, launch), {code:'no_space'});
        assert.equal(h.mode(), 1);
        assert.equal(h.density(), 180);
        assert.equal(h.placements(), 1);
        h.agent.release(0);
        assert.equal(h.placements(), 1);
    }
});

test('native split and trusted adjacent launches are left to Android', () => {
    const inspect = vm.runInNewContext(source('window-profiles.js','window-policy.js') + '\nexplicitLaunch');
    const tasks = {framework:{fullscreen:1,freeform:5,splitPrimary:3,splitSecondary:4,launchAdjacent:0x1000}, userOf:()=>0};
    let flags = 0;
    const activity = {
        launchedFromPackage:field('org.openvoyah.controls'),
        packageName:field('org.example.app'),
        isActivityTypeStandardOrUndefined:()=>true,
        intent:field({
            getAction:()=>'android.intent.action.MAIN',
            hasCategory:category=>category==='android.intent.category.LAUNCHER',
            getFlags:()=>flags,
            getIntExtra:()=>0,
            getComponent:()=>({
                getPackageName:()=>'org.example.app',
                flattenToString:()=>profile.component,
            }),
        }),
    };
    const from = mode => ({getDisplayId:()=>0,getWindowingMode:()=>mode,isActivityTypeHome:()=>false});
    assert.equal(inspect(activity, null, from(1), tasks).key, profile.component);
    assert.equal(inspect(activity, null, from(3), tasks), null);
    assert.equal(inspect(activity, null, from(4), tasks), null);
    flags = tasks.framework.launchAdjacent;
    assert.equal(inspect(activity, null, from(5), tasks), null);
    assert.equal(inspect(activity, null, from(1), tasks).key, profile.component);
});

function livePolicy(sdk, h) {
    const prefix = 'org.openvoyah.controls.';
    const settings = new Map([[prefix + 'windowProfiles', JSON.stringify({schema:1, user:0, enabled:true,
        apps:[{...profile, keepPanel:true}]})]]);
    const queue = [], logs = [];
    let tick, acceptClear = true, sequence = 0;
    // The reserved strip is replaced by a recorder: what the policy asks of it and what it answers.
    const strip = {width:0, wanted:[], stops:0, stopped:true, dependencies:null, closed:0};
    const context = {getContentResolver:()=>({}), getPackageManager:()=>({getResourcesForApplication:()=>({
        getIdentifier:()=>1, getDimensionPixelOffset:()=>171})})};
    const owner = sdk === 28 ? 'com.android.server.am' : 'com.android.server.wm';
    const types = [owner + '.ActivityRecord', owner + '.ActivityRecord', '', '', '', '',
        'android.app.ActivityOptions', owner + (sdk === 28 ? '.TaskRecord' : '.Task')];
    const early = {argumentTypes:types.slice(0, 6).map((_, index)=>({className:index === 5 ? owner + '.ActivityRecord' : ''})),
        call(_receiver, _record, options) {
            const value = options?.getLaunchBounds();
            if (value && options.getLaunchWindowingMode() === 5) {
                h.launchAt([value.left.value, value.top.value, value.right.value, value.bottom.value]);
            }
        }};
    const late = {
        returnType:{className:'int'}, argumentTypes:types.map(className=>({className})),
        call(receiver, ...args) {
            early.implementation.call(receiver, args[0], args[6], null, false, 0, args[1]);
            h.markStarted();
            args[8][0] = args[0];
            return 0;
        },
    };
    const classes = {
        [owner + '.ActivityStarter']:{startActivityUnchecked:{overloads:[late]}},
        'android.app.ActivityThread':{currentActivityThread:()=>({getSystemContext:()=>context})},
        'android.provider.Settings$Global':{
            getString:(_resolver,key)=>settings.get(key) ?? null,
            putString:(_resolver,key,value)=>{ if (acceptClear) settings.set(key,value); return acceptClear; },
        },
        'android.app.ActivityManager':{START_SUCCESS:field(0), START_TASK_TO_FRONT:field(2), START_DELIVERED_TO_TOP:field(3)},
        'java.io.FileReader':{$new:file=>({file})},
        'java.io.BufferedReader':{$new:reader=>({readLine:()=>reader.file === '/proc/sys/kernel/random/boot_id'
            ? '5f1c8a9e-0b7d-4c1a-9f5e-2d3c4b5a6978\n' : null, close() { strip.closed++; }})},
        'android.os.Process':{myPid:()=>900},
        'android.os.SystemClock':{elapsedRealtime:()=>73500},
    };
    // The deferred layout must not use scheduleOnMainThread: it would hook epoll_wait in all of system_server.
    const Java = {use:name=>classes[name], retain:value=>value, perform:fn=>fn(),
        scheduleOnMainThread() { throw Error('The window agent must stay off the main-thread scheduler'); }};
    const hooks = [];
    vm.runInNewContext(source('report.js','window-profiles.js','window-policy.js') +
        '\ninstallLiveWindowPolicy(Java, sdk, early, hooks, createReporter("OVC_WINDOW_ERROR"))', {
        Java, sdk, early, hooks, systemTasks:()=>h.agent, setInterval:fn=>{ tick = fn; }, setTimeout:fn=>queue.push(fn),
        console:{log:line=>logs.push(line)},
        systemReserve:(_java, level)=>({level}), RESERVE_GUARD:prefix + 'windowInsetsGuard',
        reserveSwitch:dependencies=>{
            strip.dependencies = dependencies;
            return {sync:want=>strip.wanted.push(want), width:()=>strip.width,
                status:()=>({status:strip.width > 0 ? 'verified' : 'inactive', width:strip.width}),
                stop() { strip.stops++; strip.width = 0; return strip.stopped; }};
        },
    });
    return {
        queue, logs, hooks, late, strip, settings,
        setClear:value=>{ acceptClear = value; }, setProfiles:value=>settings.set(prefix + 'windowProfiles', value),
        tick:()=>tick(),
        launch(nativeMode = 0, nativeBounds = null, sourceMode = 1) {
            let mode = nativeMode, bounds = nativeBounds && rect(...nativeBounds);
            const options = {
                getLaunchDisplayId:()=>0, getLaunchWindowingMode:()=>mode, getLaunchBounds:()=>bounds,
                setLaunchDisplayId() {}, setLaunchWindowingMode:value=>{ mode = value; }, setLaunchBounds:value=>{ bounds = value; },
            };
            const record = {
                launchedFromPackage:field('com.qinggan.app.launcher'), packageName:field('org.example.app'),
                intent:field({
                    getAction:()=>'android.intent.action.MAIN', hasCategory:()=>true, getFlags:()=>0, getIntExtra:()=>0,
                    getComponent:()=>({getPackageName:()=>'org.example.app', flattenToString:()=>profile.component}),
                }),
                isActivityTypeStandardOrUndefined:()=>true, isActivityTypeHome:()=>false, isResizeable:()=>true,
                getDisplayId:()=>0, getTask:()=>h.task, equals:other=>other === record,
            };
            const from = {getDisplayId:()=>0, getWindowingMode:()=>sourceMode, isActivityTypeHome:()=>true};
            return late.implementation.call({mLastStartActivityRecord:field(record)}, record, from, null, null, null,
                null, options, null, []);
        },
        /** The launcher's Home button: its own MAIN intent, delivered to the existing home activity. */
        home(displayId = 0) {
            const options = {getLaunchDisplayId:()=>-1, getLaunchWindowingMode:()=>0, getLaunchBounds:()=>null};
            const record = {
                launchedFromPackage:field('com.qinggan.app.launcher'), packageName:field('com.qinggan.app.launcher'),
                intent:field({
                    getAction:()=>'android.intent.action.MAIN', hasCategory:()=>false, getFlags:()=>0x10000000,
                    getIntExtra:()=>0, getBooleanExtra:()=>false, getComponent:()=>({
                        getPackageName:()=>'com.qinggan.app.launcher',
                        flattenToString:()=>'com.qinggan.app.launcher/com.qinggan.app.launcher.activity.MainActivity'}),
                }),
                isActivityTypeStandardOrUndefined:()=>false, isActivityTypeHome:()=>true, isResizeable:()=>false,
                getDisplayId:()=>displayId, getTask:()=>null, equals:other=>other === record,
            };
            const from = {getDisplayId:()=>displayId, getWindowingMode:()=>5, isActivityTypeHome:()=>false};
            return late.implementation.call({mLastStartActivityRecord:field(record)}, record, from, null, null, null,
                null, options, null, []);
        },
        request(operation) {
            const id = (++sequence).toString(16).padStart(32, '0');
            settings.set(prefix + 'windowRequest', JSON.stringify({schema:1, id, user:0,
                expires:Date.now()+4000, operation, component:profile.component}));
            tick();
            const marker = 'OVC_WINDOW_RESULT ' + id + ' ';
            return JSON.parse(logs.find(line=>line.startsWith(marker)).slice(marker.length));
        },
    };
}

test('back-to-back starts schedule one layout and disabling before delivery restores the native launch', () => {
    for (const sdk of [28, 30]) {
        const h = harness(sdk, {displayId:0, dpi:180, cold:true});
        const service = livePolicy(sdk, h);
        const native = [240, 24, 1790, 710];
        service.launch(5, native);
        service.launch();
        assert.equal(service.queue.length, 1);
        assert.notDeepEqual(h.bounds(), native);
        assert.equal(service.request('release').status, 'applied');
        assert.deepEqual(h.bounds(), native);
        service.queue.shift()();
        assert.equal(h.mode(), 5);
        assert.equal(h.density(), 180);
        assert.equal(h.placements(), 1);
    }
});

test('on Android 11 Home on the central display hides the placed apps it now covers, once per burst', () => {
    const h = harness(30, {displayId:0});
    const service = livePolicy(30, h);
    service.home();
    service.home();
    assert.equal(service.queue.length, 1);
    service.queue.shift()();
    assert.equal(h.reveals(), 1);
    // Another display, an ordinary app start and Android 9 leave visibility to Android.
    service.home(1);
    service.launch();
    for (const run of service.queue.splice(0)) run();
    assert.equal(h.reveals(), 1);
    const nine = harness(28, {displayId:0});
    const older = livePolicy(28, nine);
    older.home();
    assert.equal(older.queue.length, 0);
    // Turning the window service off ends it as well.
    service.request('release');
    service.home();
    assert.equal(service.queue.length, 0);
    assert.ok(!service.logs.some(line => line.startsWith('OVC_WINDOW_ERROR')));
});

test('a cold start too large for the workspace returns to its native placement and density', () => {
    for (const sdk of [28, 30]) {
        const h = harness(sdk, {displayId:0, dpi:180, cold:true, minimum:[2000, 500]});
        const service = livePolicy(sdk, h);
        service.launch();
        assert.equal(h.mode(), 5);
        service.queue.shift()();
        assert.equal(h.mode(), 1);
        assert.equal(h.density(), 180);
        assert.deepEqual(h.bounds(), [0, 0, 1920, 720]);
        assert.ok(service.logs.includes('OVC_WINDOW_ERROR stage=launch kind=no_space'));
        service.request('release');
        assert.equal(h.placements(), 1);
    }
});

test('a native freeform launch without explicit bounds waits for Android placement', () => {
    const h = harness(30, {displayId:0, cold:true});
    const service = livePolicy(30, h);
    service.launch(5);
    assert.equal(h.mode(), 1);
    h.launchAt([300, 10, 1700, 680]);
    service.queue.shift()();
    assert.equal(h.placements(), 1);
    service.request('release');
    assert.deepEqual(h.bounds(), [300, 10, 1700, 680]);
});

test('a failed request acknowledgement precedes any write and diagnostics expose only known outcomes', () => {
    const h = harness(30, {displayId:0});
    const service = livePolicy(30, h);
    service.setClear(false);
    assert.equal(service.request('apply').status, 'failed');
    assert.equal(h.placements(), 0);
    assert.equal(h.writes(), 0);
    service.setClear(true);
    h.agent.apply = () => { throw Object.assign(Error('private fixture payload'), {code:'private fixture code'}); };
    assert.equal(service.request('apply').status, 'failed');
    assert.ok(service.logs.includes('OVC_WINDOW_ERROR stage=request kind=failed'));
    assert.equal(service.logs.some(line=>line.includes('private fixture')), false);
});

test('a start that fails inside the hooks still reaches Android and is reported once with its cause', () => {
    for (const sdk of [28, 30]) {
        const h = harness(sdk, {displayId:0, cold:true});
        const service = livePolicy(sdk, h);
        h.agent.captureLaunch = () => { throw new TypeError('private field layout'); };
        for (let attempt = 0; attempt < 12; attempt++) assert.equal(service.launch(), 0);
        const reports = service.logs.filter(line=>line.startsWith('OVC_WINDOW_ERROR stage=capture '));
        assert.deepEqual(reports, ['OVC_WINDOW_ERROR stage=capture kind=failed cause=TypeError',
            'OVC_WINDOW_ERROR stage=capture kind=failed cause=TypeError repeated=10']);
        assert.equal(service.logs.some(line=>line.includes('private')), false);
    }
});

test('a rejected profile document is reported once and leaves launches native', () => {
    const h = harness(30, {displayId:0, cold:true});
    const service = livePolicy(30, h);
    service.setProfiles('{"schema":7,"apps":"private"}');
    service.tick();
    service.tick();
    assert.deepEqual(service.logs.filter(line=>line.includes('stage=config')),
        ['OVC_WINDOW_ERROR stage=config kind=config_invalid bytes=29']);
    service.launch();
    assert.equal(service.queue.length, 0);
    assert.equal(h.placements(), 0);
});

test('installation failures name their step, announce no readiness and leave no hook behind', () => {
    for (const [sdk, broken, expected] of [
        [29, null, 'OVC_WINDOW_ERROR stage=install kind=unsupported_sdk sdk=29'],
        [30, 'starter', 'OVC_WINDOW_ERROR stage=install kind=starter_signature cause=Error'],
        [28, 'tasks', 'OVC_WINDOW_ERROR stage=install kind=tasks_api cause=ClassNotFoundException'],
        [30, 'completion', 'OVC_WINDOW_ERROR stage=install kind=completion_signature cause=Error'],
    ]) {
        const logs = [];
        const owner = sdk === 28 ? 'com.android.server.am' : 'com.android.server.wm';
        const names = [owner + '.ActivityRecord', 'android.app.ActivityOptions', owner + (sdk === 28 ? '.TaskRecord' : '.Task'),
            'boolean', 'int', owner + '.ActivityRecord'];
        const early = {returnType:{className:'void'}, argumentTypes:names.map(className=>({className})), call() {}};
        const late = {returnType:{className:broken === 'completion' ? 'void' : 'int'}, call() {},
            argumentTypes:[names[0], names[0], '', '', '', '', names[1], names[2]].map(className=>({className}))};
        const Java = {perform:fn=>fn(), retain:value=>value, use(name) {
            if (name === 'android.os.Build$VERSION') return {SDK_INT:{value:sdk}};
            if (name === owner + '.ActivityStarter') return {
                setInitialState:{overloads:broken === 'starter' ? [] : [early], overload:()=>early},
                startActivityUnchecked:{overloads:[late]},
            };
            if (name === 'android.app.ActivityThread') {
                return {currentActivityThread:()=>({getSystemContext:()=>({getContentResolver:()=>({}),
                    getPackageManager:()=>({getResourcesForApplication:()=>({getIdentifier:()=>0})})})})};
            }
            if (name === 'android.app.ActivityManager') {
                return {START_SUCCESS:field(0), START_TASK_TO_FRONT:field(2), START_DELIVERED_TO_TOP:field(3)};
            }
            return {getString:()=>null};
        }};
        vm.runInNewContext(source('report.js','window-android9.js','window-android11.js','window-profiles.js','window-policy.js') +
            '\ninstallWindowPolicy(Java);', {
            Java, console:{log:line=>logs.push(line)}, setInterval() {}, setTimeout() {},
            systemTasks() {
                if (broken === 'tasks') throw Error('java.lang.ClassNotFoundException: com.android.server.am.Private');
                return {framework:{freeform:5}};
            },
        });
        assert.deepEqual(logs, [expected]);
        assert.equal(early.implementation ?? null, null);
        assert.equal(late.implementation ?? null, null);
    }
});

test('a complete installation announces the request protocol after both hooks are in place', () => {
    for (const sdk of [28, 30]) {
        const h = harness(sdk, {displayId:0});
        const service = livePolicy(sdk, h);
        assert.deepEqual(service.logs, ['OVC_WINDOW_READY protocol=3 sdk=' + sdk + ' displayDpi=1 systemInsets=1']);
        assert.equal(service.hooks.length, 2);
        assert.ok(service.hooks.every(hook=>typeof hook.implementation === 'function'));
    }
});

const documentWith = (apps, extra = {}) => JSON.stringify({schema:1, user:0, enabled:true, apps, ...extra});

test('the reserved strip follows the profile document, the service switch and its own record', () => {
    for (const sdk of [28, 30]) {
        const h = harness(sdk, {displayId:0});
        const service = livePolicy(sdk, h);
        service.tick();
        assert.equal(service.strip.wanted.at(-1), null);
        service.setProfiles(documentWith([], {systemInsets:true, systemInsetsAttempt:3}));
        service.tick();
        assert.deepEqual({...service.strip.wanted.at(-1)}, {attempt:3, user:0});
        service.setProfiles(documentWith([], {systemInsets:true}));
        service.tick();
        assert.deepEqual({...service.strip.wanted.at(-1)}, {attempt:0, user:0});
        for (const off of [documentWith([]), documentWith([], {systemInsets:true, enabled:false}),
            documentWith([], {systemInsets:'yes'}), documentWith([], {systemInsets:true, systemInsetsAttempt:-1})]) {
            service.setProfiles(off);
            service.tick();
            assert.equal(service.strip.wanted.at(-1), null, off);
        }

        // What the switch is given: the framework side for this release, the record and the launcher's own width.
        const given = service.strip.dependencies;
        assert.equal(given.reserve.level, sdk);
        assert.equal(given.read(), '');
        assert.equal(given.write('{"state":"armed"}'), true);
        assert.equal(service.settings.get('org.openvoyah.controls.windowInsetsGuard'), '{"state":"armed"}');
        assert.equal(given.read(), '{"state":"armed"}');
        service.setClear(false);
        assert.equal(given.write('{"state":"clear"}'), false);
        service.setClear(true);
        assert.equal(given.panelWidth(), 171);
        assert.equal(given.currentUser(), 0);
        // Uptime, so that a wall clock set after boot cannot shorten or stretch the waiting.
        assert.equal(given.now(), 73500);
        assert.equal(given.releaseTasks, undefined);
        assert.deepEqual({...given.session()}, {boot:'5f1c8a9e-0b7d-4c1a-9f5e-2d3c4b5a6978', pid:900});
        assert.equal(service.strip.closed, 1);
    }
});

test('turning the window service off releases the reserved strip before the tasks and reports what is left', () => {
    const h = harness(30, {displayId:0});
    const service = livePolicy(30, h);
    service.setProfiles(documentWith([], {systemInsets:true, systemInsetsAttempt:1}));
    service.tick();
    service.strip.width = 171;
    assert.equal(service.request('release').status, 'applied');
    assert.equal(service.strip.stops, 1);
    // A released service asks for nothing any more, whatever the document still says.
    assert.equal(service.strip.wanted.at(-1), null);

    const stuck = livePolicy(30, harness(30, {displayId:0}));
    stuck.strip.stopped = false;
    assert.equal(stuck.request('release').status, 'rollback_failed');
    assert.equal(stuck.strip.stops, 1);
});

test('the app can ask for the state of the reserved strip without changing anything', () => {
    const h = harness(30, {displayId:0});
    const service = livePolicy(30, h);
    assert.deepEqual(service.request('insets'), {insets:'inactive', width:0, status:'applied'});
    service.strip.width = 171;
    assert.deepEqual(service.request('insets'), {insets:'verified', width:171, status:'applied'});
    assert.equal(h.placements(), 0);
    assert.equal(h.writes(), 0);
    assert.equal(service.strip.stops, 0);
    // It is answered after the service was released as well: the option's page still needs to say "off".
    service.request('release');
    assert.deepEqual(service.request('insets'), {insets:'inactive', width:0, status:'applied'});
});

test('in the reserved strip a full-width launch keeps the placement Android gives it', () => {
    for (const sdk of [28, 30]) {
        const h = harness(sdk, {displayId:0, cold:true});
        const service = livePolicy(sdk, h);
        service.setProfiles(documentWith([], {systemInsets:true}));
        service.tick();
        service.strip.width = 171;
        assert.equal(service.launch(), 0);
        assert.equal(service.queue.length, 0);
        assert.equal(h.mode(), 1);
        assert.equal(h.placements(), 0);
        assert.equal(h.writes(), 0);

        // Until Android has the strip, and again once it gives it up, this agent places the task itself.
        service.strip.width = 0;
        service.launch();
        assert.equal(h.mode(), 5);
        service.queue.shift()();
        assert.deepEqual(h.bounds(), [145, 0, 1920, 720]);
    }
});

test('a profile that asks for more than the reserved strip gives is still placed beside it', () => {
    for (const [app, left] of [[{...profile, dpi:0}, 500], [{...profile, dpi:0, width:100, panelPx:300}, 300]]) {
        const h = harness(30, {displayId:0, cold:true});
        const service = livePolicy(30, h);
        service.setProfiles(documentWith([{...app, keepPanel:true}], {systemInsets:true}));
        service.tick();
        service.strip.width = 171;
        service.launch();
        assert.equal(h.mode(), 5);
        service.queue.shift()();
        assert.equal(h.bounds()[0], left);
        assert.equal(h.mode(), 5);
    }
});

test('in the reserved strip a full-width profile with its own density receives the density only', () => {
    for (const sdk of [28, 30]) {
        const h = harness(sdk, {displayId:0, cold:true});
        const service = livePolicy(sdk, h);
        service.setProfiles(documentWith([], {systemInsets:true, displayDpi:{central:200, secondary:null}}));
        service.tick();
        service.strip.width = 171;
        service.launch();
        assert.equal(h.mode(), 1);
        assert.equal(service.queue.length, 1);
        service.queue.shift()();
        assert.equal(h.density(), 200);
        assert.equal(h.mode(), 1);
        assert.equal(h.placements(), 0);
        assert.deepEqual(service.logs.filter(line=>line.startsWith('OVC_WINDOW_ERROR')), []);
        // An explicit request from the app still places the running task, as it always did.
        assert.equal(service.request('apply').status, 'applied');
        assert.equal(h.mode(), 5);
        assert.equal(service.request('release').status, 'applied');
        assert.equal(h.mode(), 1);
        assert.equal(h.density(), 0);
    }
});
