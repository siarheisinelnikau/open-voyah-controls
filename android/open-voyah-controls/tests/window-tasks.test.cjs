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
const rectList = value => [value.left.value, value.top.value, value.right.value, value.bottom.value];
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
    const overrides = [], guests = [];
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
        androidDisplay: id => id === (options.secondaryId ?? 2) ? {
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
        activityBounds: activity => activity.bounds ?? null,
        activityPlacement: () => options.activityPlacement !== false,
        sizeCompat: activity => activity.sizeCompat === true,
        preinstalled: activity => activity.preinstalled === true,
        placeActivity: (activity, target) => {
            activity.bounds = target === null ? null : Array.from(target);
            if (target !== null) guests.push(activity);
        },
        topBar: () => options.topBar ?? 0,
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
    const agent = vm.runInNewContext(source('window-profiles.js','window-pairs.js','window-tasks.js','window-policy.js') +
        '\nsystemTasks(Java, sdk, launcherPanelWidth(Java, context))', {
        Java, sdk, context, android9Tasks:()=>api, android11Tasks:()=>api,
    });
    return {
        agent, api, task, placements:()=>placements, density:()=>override.densityDpi.value,
        scans:()=>scans, writes:()=>writes, notifications:()=>notifications, resourceReads:()=>resourceReads,
        reveals:()=>reveals, guests, overrides, pinned:()=>override.pinned ?? null,
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

function pairHarness(rejectRight) {
    const left = harness(30, {displayId:0, multiWindow:true, dpi:180, stableInsets:[0, 60, 0, 0]});
    const right = harness(30, {displayId:0, multiWindow:true, dpi:190});
    const find = left.api.find;
    left.api.find = id => id === 46 ? right.task : find(id);
    left.api.id = task => task === right.task ? 46 : 45;
    right.task.getChildAt(0).packageName.value = 'org.example.radio';
    for (const method of ['getOverride', 'requestedBounds', 'setOverride', 'place']) {
        const call = left.api[method];
        left.api[method] = (task, ...args) => {
            if (task !== right.task) return call(task, ...args);
            if (rejectRight && method === 'place' && args[0] !== null) throw Error('Android refused the second window');
            return right.api[method](task, ...args);
        };
    }
    return {left, right, profiles:[profile, {...profile, component:'org.example.radio/org.example.radio.Main', dpi:0}]};
}

test('a pair shares the window service frame and both tasks are restored on release', () => {
    const {left, right, profiles} = pairHarness(false);
    assert.equal(left.agent.applyPair(profiles, [45,46], 50, 0), 'applied');
    assert.equal(left.mode(), 6);
    assert.equal(right.mode(), 6);
    assert.deepEqual(left.bounds(), [145,60,1033,720]);
    assert.deepEqual(right.bounds(), [1033,60,1920,720]);
    assert.equal(left.density(), 200);
    assert.equal(right.density(), 0);
    left.agent.release(0);
    assert.equal(left.mode(), 1);
    assert.equal(right.mode(), 1);
    assert.equal(left.density(), 180);
    assert.equal(right.density(), 190);
});

test('app settings apply split DPI in place, then a standalone launch takes its ordinary DPI', () => {
    const {left, right, profiles} = pairHarness(false);
    left.agent.applyPair(profiles, [45,46], 50, 0);
    const leftBounds = left.bounds();
    const rightBounds = right.bounds();
    const updated = {...profiles[0], width:100, dpi:200, splitDpi:160};
    left.agent.apply(updated, 0);
    assert.equal(left.density(), 160);
    assert.deepEqual(left.bounds(), leftBounds);
    assert.deepEqual(right.bounds(), rightBounds);
    assert.equal(left.placements(), 1);
    left.agent.apply(updated, 0, 45);
    assert.equal(left.density(), 200);
    assert.deepEqual(left.bounds(), [145,60,1920,720]);
});

test('a compact measured panel wins over an older launch hint and uses the inset only once', () => {
    const h = harness(30, {displayId:0, multiWindow:true, stableInsets:[80,60,0,0]});
    h.api.launcherPanelEdge = () => 80;
    const full = {width:100, dpi:0, panelPx:0};
    assert.deepEqual(Array.from(h.agent.boundsFor(full, 145)), [80,60,1920,720]);
    h.api.launcherPanelEdge = () => null;
    assert.deepEqual(Array.from(h.agent.boundsFor(full)), [145,60,1920,720]);
});

test('central bounds use the actual top status bar when Android omits it and count overlapping insets once', () => {
    const full = {width:100, dpi:0, panelPx:0};
    const customStatus = harness(30, {displayId:0, topBar:50, stableInsets:[0,24,0,0]});
    assert.deepEqual(Array.from(customStatus.agent.boundsFor(full)), [145,50,1920,720]);
    const systemStatus = harness(30, {displayId:0, topBar:50, stableInsets:[0,72,0,0]});
    assert.deepEqual(Array.from(systemStatus.agent.boundsFor(full)), [145,72,1920,720]);
});

test('failure to place the second task restores the first geometry and both density overrides', () => {
    const {left, right, profiles} = pairHarness(true);
    assert.throws(() => left.agent.applyPair(profiles, [45,46], 50, 0), /Android refused/);
    assert.equal(left.placements(), 2);
    for (const task of [left, right]) {
        assert.equal(task.mode(), 1);
        assert.deepEqual(task.bounds(), [0,0,1920,720]);
        assert.equal(task.pinned(), null);
    }
    assert.equal(left.density(), 180);
    assert.equal(right.density(), 190);
});

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

function livePolicy(sdk, h, options = {}) {
    const prefix = 'org.openvoyah.controls.';
    const settings = new Map([[prefix + 'windowProfiles', JSON.stringify({schema:1, user:0, enabled:true,
        apps:[{...profile, keepPanel:true}]})]]);
    const queue = [], logs = [];
    let tick, acceptClear = true, sequence = 0;
    // The reserved strip is replaced by a recorder: what the policy asks of it and what it answers.
    const strip = {width:0, wanted:[], stops:0, stopped:true, dependencies:null, closed:0};
    // The swipe between screens is replaced by a recorder too.
    const moves = {api:{}, dependencies:null, wanted:[], recalls:0, releases:0, recalled:true, returned:true};
    const context = {getContentResolver:()=>({}), getPackageManager:()=>({getResourcesForApplication:()=>({
        getIdentifier:()=>1, getDimensionPixelOffset:()=>171})})};
    const owner = sdk === 28 ? 'com.android.server.am' : 'com.android.server.wm';
    const types = [owner + '.ActivityRecord', owner + '.ActivityRecord', '', '', '', '',
        'android.app.ActivityOptions', owner + (sdk === 28 ? '.TaskRecord' : '.Task')];
    // What Android was asked for at each start, after the policy's early hook.
    const starts = [];
    const early = {argumentTypes:types.slice(0, 6).map((_, index)=>({className:index === 5 ? owner + '.ActivityRecord' : ''})),
        call(_receiver, _record, options) {
            const value = options?.getLaunchBounds();
            starts.push({mode:options?.getLaunchWindowingMode() ?? 0, bounds:value ? rectList(value) : null});
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
    vm.runInNewContext(source('report.js','window-profiles.js','window-pairs.js','window-policy.js') +
        '\ninstallLiveWindowPolicy(Java, sdk, early, hooks, createReporter("OVC_WINDOW_ERROR"))', {
        Java, sdk, early, hooks, systemTasks:()=>h.agent, setInterval:fn=>{ tick = fn; }, setTimeout:fn=>queue.push(fn),
        console:{log:line=>logs.push(line)},
        systemReserve:(_java, level)=>({level}), RESERVE_GUARD:prefix + 'windowInsetsGuard',
        android11DisplayMoves:()=>{
            if (options.movesMissing) throw Error('java.lang.NoSuchMethodError: moveStackToDisplay');
            return moves.api;
        },
        displayMover:dependencies=>{
            moves.dependencies = dependencies;
            return {sync:want=>moves.wanted.push(want), recall:()=>{ moves.recalls++; return moves.recalled; },
                release:()=>{ moves.releases++; return moves.returned; }};
        },
        reserveSwitch:dependencies=>{
            strip.dependencies = dependencies;
            return {sync:want=>strip.wanted.push(want), width:()=>strip.width,
                status:()=>({status:strip.width > 0 ? 'verified' : 'inactive', width:strip.width}),
                stop() { strip.stops++; strip.width = 0; return strip.stopped; }};
        },
    });
    return {
        queue, logs, hooks, late, strip, settings, moves, starts,
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
        /**
         * Controls opens another app's activity inside its own task, as an "open file" does with the document
         * picker: an intent without NEW_TASK, the result returned to Controls.
         */
        guest({pkg = 'com.android.documentsui', root = 'org.openvoyah.controls', displayId = 0, taskMode = 1,
            preinstalled = false, resizeable = true, sizeCompat = false} = {}) {
            const options = {getLaunchDisplayId:()=>-1, getLaunchWindowingMode:()=>0, getLaunchBounds:()=>null};
            const owner = {root, getWindowingMode:()=>taskMode};
            const record = {
                launchedFromPackage:field('org.openvoyah.controls'), packageName:field(pkg),
                intent:field({getAction:()=>'android.intent.action.OPEN_DOCUMENT', hasCategory:()=>false,
                    getFlags:()=>0, getIntExtra:()=>0, getBooleanExtra:()=>false, getComponent:()=>null}),
                isActivityTypeStandardOrUndefined:()=>true, isActivityTypeHome:()=>false, isResizeable:()=>resizeable,
                getDisplayId:()=>displayId, getTask:()=>owner, equals:other=>other === record,
                finishing:field(false), preinstalled, sizeCompat, delivered:0,
                ensureActivityConfiguration() { record.delivered++; },
            };
            const from = {getDisplayId:()=>displayId, getWindowingMode:()=>1, isActivityTypeHome:()=>false};
            const start = () => late.implementation.call({mLastStartActivityRecord:field(record)}, record, from, null,
                null, null, null, options, null, []);
            start();
            return {record, start};
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
        request(operation, fields = {}) {
            const id = (++sequence).toString(16).padStart(32, '0');
            settings.set(prefix + 'windowRequest', JSON.stringify({schema:1, id, user:0,
                expires:Date.now()+4000, operation, component:profile.component, ...fields}));
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

test('where placed tasks use multi-window a launch starts full screen, never in freeform', () => {
    // From a fullscreen screen (the app grid, Home), with no launch options of its own. A freeform start would let an
    // app whose process runs draw a caption bar that the later move to multi-window does not remove.
    const h = harness(30, {displayId:0, dpi:180, cold:true, multiWindow:true});
    const service = livePolicy(30, h);
    service.launch();
    assert.deepEqual(service.starts, [{mode:1, bounds:null}]);
    assert.equal(h.mode(), 1);
    service.queue.shift()();
    assert.equal(h.mode(), 6);
    const placed = h.bounds();
    assert.notDeepEqual(placed, [0, 0, 1920, 720]);
    assert.equal(service.request('release').status, 'applied');
    assert.equal(h.mode(), 1);
    assert.deepEqual(h.bounds(), [0, 0, 1920, 720]);
    assert.equal(h.density(), 180);

    // A caller's own freeform request is placed the same way and gets its window back on release.
    const native = [240, 24, 1790, 710];
    const requested = harness(30, {displayId:0, cold:true, multiWindow:true});
    const other = livePolicy(30, requested);
    other.launch(5, native);
    assert.deepEqual(other.starts, [{mode:1, bounds:null}]);
    other.queue.shift()();
    assert.equal(requested.mode(), 6);
    assert.deepEqual(requested.bounds(), placed);
    other.request('release');
    assert.equal(requested.mode(), 5);
    assert.deepEqual(requested.bounds(), native);

    // Without a usable multi-window mode placed tasks stay freeform, so the launch takes their bounds at once.
    const older = harness(30, {displayId:0, cold:true});
    const freeform = livePolicy(30, older);
    freeform.launch();
    assert.equal(freeform.starts[0].mode, 5);
    assert.deepEqual(freeform.starts[0].bounds, placed);
    assert.equal(older.mode(), 5);
});

test('on Android 11 a desktop wallpaper picker opened from Controls is placed beside the panel', () => {
    const h = harness(30, {displayId:0});
    const service = livePolicy(30, h);
    // App grid -> desktop wallpaper: ActivityResultContracts.OpenDocument(), DocumentsUI's PickActivity in Controls' task.
    const picker = service.guest();
    assert.deepEqual(picker.record.bounds, [145, 0, 1920, 720]);
    assert.equal(h.guests.length, 1);
    // The configuration is delivered at once, for an activity that is already running.
    assert.equal(picker.record.delivered, 1);
    // Started again in place (delivered to the same activity): nothing to change.
    picker.start();
    assert.equal(h.guests.length, 1);
    assert.ok(!service.logs.some(line => line.startsWith('OVC_WINDOW_ERROR')));
    // Android 9 and a window service that was turned off leave it to Android.
    const older = livePolicy(28, harness(28, {displayId:0}));
    assert.equal(older.guest().record.bounds, undefined);
    service.request('release');
    assert.equal(picker.record.bounds, null);
    assert.equal(picker.record.delivered, 2);
    assert.equal(service.guest().record.bounds, undefined);
    const missing = livePolicy(30, harness(30, {displayId:0, activityPlacement:false}));
    assert.equal(missing.guest().record.bounds, undefined);
});

test('on Android 11 choosers, galleries and other screens in any fullscreen task keep clear of the panel', () => {
    const h = harness(30, {displayId:0});
    const service = livePolicy(30, h);
    const placed = [
        // The system chooser inside Controls' task, in a task of its own, and inside a factory app's task.
        {pkg:'android'}, {pkg:'android', root:'android'}, {pkg:'android', root:'com.qinggan.app.vehiclesetting'},
        // A gallery or crop screen and AOSP settings started in tasks of their own by another app.
        {pkg:'com.android.gallery3d', root:'com.android.gallery3d', preinstalled:true},
        {pkg:'com.android.settings', root:'com.android.settings', preinstalled:true},
        // A third-party screen another app opened: its whole task is not placed, the activity is.
        {pkg:'org.example.app', root:'org.example.app'},
    ];
    for (const start of placed) assert.deepEqual(service.guest(start).record.bounds, [145, 0, 1920, 720], JSON.stringify(start));
    // Screens that keep clear of the panel themselves, tasks that are not fullscreen and other displays stay.
    const kept = [
        {pkg:'org.openvoyah.controls'}, {pkg:'com.qinggan.app.vehiclesetting', root:'com.qinggan.app.vehiclesetting'},
        {pkg:'com.baidu.naviauto', root:'com.baidu.naviauto'}, {pkg:'com.android.systemui', root:'com.android.systemui'},
        {taskMode:6}, {taskMode:5}, {displayId:1},
        // Vendor apps the firmware ships (projection, media), activities that cannot take another size.
        {pkg:'com.huawei.hicar', root:'com.huawei.hicar', preinstalled:true},
        {pkg:'com.tencent.wecarflow', root:'com.tencent.wecarflow', preinstalled:true},
        {resizeable:false}, {sizeCompat:true},
    ];
    for (const start of kept) assert.equal(service.guest(start).record.bounds, undefined, JSON.stringify(start));
    // A launch from the panel, Controls or home is placed as a whole task instead.
    service.launch();
    assert.equal(h.guests.length, placed.length);
    // A task with a density of its own (a density-only profile) is this agent's whole-task business.
    const dense = livePolicy(30, harness(30, {displayId:0, dpi:200}));
    assert.equal(dense.guest({root:'org.example.app', pkg:'org.example.app'}).record.bounds, undefined);
    assert.ok(!service.logs.some(line => line.startsWith('OVC_WINDOW_ERROR')));
});

test('a task placed as a whole takes back the bounds its activities had of their own', () => {
    const h = harness(30, {displayId:0, multiWindow:true});
    h.task.getChildAt(0).bounds = [145, 0, 1920, 720];
    h.agent.apply({...profile, width:60}, 0, 45);
    assert.equal(h.mode(), 6);
    assert.equal(h.task.getChildAt(0).bounds, null);
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
        assert.deepEqual(service.logs, ['OVC_WINDOW_READY protocol=3 sdk=' + sdk + ' displayDpi=1 systemInsets=1 displayMove='
            + (sdk === 30 ? '1 pairs=1 passengerDesk=1' : '0')]);
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

test('the swipe between screens follows the window document and the service switch', () => {
    const h = harness(30, {displayId:0});
    const service = livePolicy(30, h);
    service.tick();
    assert.equal(service.moves.wanted.at(-1), null);
    service.setProfiles(documentWith([], {displayMove:{fingers:2}}));
    service.tick();
    assert.deepEqual({...service.moves.wanted.at(-1)}, {fingers:2, passenger:1, reveal:'home'});
    for (const off of [documentWith([], {displayMove:{fingers:2}, enabled:false}), documentWith([]),
        documentWith([], {displayMove:{fingers:5}})]) {
        service.setProfiles(off);
        service.tick();
        assert.equal(service.moves.wanted.at(-1), null, off);
    }
    // A moved window comes back to the central display through the policy that placed it there.
    const placed = service.moves.dependencies.placed;
    service.setProfiles(documentWith([], {displayMove:{fingers:3}}));
    service.tick();
    placed(profile.component, 0, 45, 0);
    assert.equal(service.queue.length, 1);
    service.queue.shift()();
    assert.equal(h.mode(), 5);
    assert.deepEqual(h.bounds(), [145, 0, 1920, 720]);
});

test('the app can bring moved windows back, and turning the service off returns them first', () => {
    const h = harness(30, {displayId:0});
    const service = livePolicy(30, h);
    assert.deepEqual(service.request('recall'), {status:'applied'});
    assert.equal(service.moves.recalls, 1);
    service.moves.recalled = false;
    assert.deepEqual(service.request('recall'), {status:'rollback_failed'});

    assert.equal(service.request('release').status, 'applied');
    assert.equal(service.moves.releases, 1);
    service.tick();
    assert.equal(service.moves.wanted.at(-1), null);

    const stuck = livePolicy(30, harness(30, {displayId:0}));
    stuck.moves.returned = false;
    assert.equal(stuck.request('release').status, 'rollback_failed');
    // The rest of the release still ran.
    assert.equal(stuck.strip.stops, 1);
});

test('without the Android 11 members the window service still starts and says the swipe is unavailable', () => {
    const service = livePolicy(30, harness(30, {displayId:0}), {movesMissing:true});
    assert.deepEqual(service.logs, ['OVC_WINDOW_ERROR stage=display_move kind=NoSuchMethodError',
        'OVC_WINDOW_READY protocol=3 sdk=30 displayDpi=1 systemInsets=1 displayMove=0 pairs=1 passengerDesk=1']);
    assert.deepEqual(service.request('recall'), {status:'rollback_failed'});
    const android9 = livePolicy(28, harness(28, {displayId:0}));
    assert.equal(android9.moves.dependencies, null);
});

test('a window leaving the central display keeps its density and drops the sizes pinned beside the panel', () => {
    const h = harness(30, {displayId:0, multiWindow:true});
    assert.equal(h.agent.apply(profile, 0, 45, null, 0).status, 'applied');
    assert.equal(h.mode(), 6);
    assert.notEqual(h.pinned(), null);
    h.agent.prepareMove(h.task, 'org.example.app', 0);
    assert.equal(h.mode(), 1);
    assert.equal(h.density(), 200, 'the app keeps its scale on the other screen');
    assert.equal(h.pinned(), null);
});

test('the passenger workspace only accepts the designated public built-in display and returns the live bar height', () => {
    const passenger = harness(30, {secondaryId:1, topBar:50});
    const service = livePolicy(30, passenger);
    assert.deepEqual(service.request('passenger-desktop'), {status:'applied', top:50});
    assert.equal(passenger.placements(), 0);
    assert.equal(passenger.writes(), 0);
    for (const [sdk, options] of [[30, {secondaryId:2}], [30, {flags:4}], [30, {owner:1000}], [30, {type:5}], [28, {}]]) {
        const display = harness(sdk, {secondaryId:1, topBar:50, ...options});
        assert.deepEqual(livePolicy(sdk, display).request('passenger-desktop'), {status:'no_display'});
        assert.equal(display.placements(), 0);
    }
    assert.equal(service.request('release').status, 'applied');
    assert.deepEqual(service.request('passenger-desktop'), {status:'no_profile'});
});

test('the task manager lists the running central task and closes only a freshly matched identity', () => {
    const h = harness(30, {displayId:0, multiWindow:true});
    const info = {baseActivity:field({flattenToString:()=>profile.component})};
    let closed = false, attempts = 0;
    h.api.am.getTasks = count => ({size:()=>closed ? 0 : 1, get:()=>info});
    const find = h.api.find;
    h.api.find = id => closed ? null : find(id);
    h.api.removeTask = id => { attempts++; closed = id === 45; return closed; };
    const service = livePolicy(30, h);
    assert.match(service.logs[0], /taskManager=1$/);
    assert.deepEqual(service.request('workspace-tasks'), {status:'applied', tasks:[{id:45, component:profile.component}]});
    assert.equal(service.request('close-workspace-task', {taskId:99}).status, 'no_task');
    assert.equal(service.request('close-workspace-task', {taskId:45, component:profile.component + 'Other'}).status, 'no_task');
    assert.equal(attempts, 0);
    assert.equal(service.request('close-workspace-task', {taskId:45}).status, 'applied');
    assert.equal(attempts, 1);
    assert.deepEqual(service.request('workspace-tasks'), {status:'applied', tasks:[]});
    assert.equal(service.request('close-workspace-task', {taskId:45}).status, 'no_task');
    assert.equal(attempts, 1);
});

test('the task manager does not expose shared tasks, another display, framework or another user', () => {
    for (const [options, mutate] of [
        [{displayId:1}, () => {}],
        [{displayId:0, shared:true}, () => {}],
        [{displayId:0}, h => { h.task.getChildAt(0).preinstalled = true; }],
        [{displayId:0}, h => { h.api.userOf = () => 1; }],
        [{displayId:0, mode:2}, () => {}],
    ]) {
        const h = harness(30, options);
        h.api.am.getTasks = () => ({size:()=>1, get:()=>({baseActivity:field({flattenToString:()=>profile.component})})});
        h.api.removeTask = () => assert.fail('protected task reached the native remove call');
        mutate(h);
        const service = livePolicy(30, h);
        assert.deepEqual(service.request('workspace-tasks'), {status:'applied', tasks:[]});
        assert.equal(service.request('close-workspace-task', {taskId:45}).status, 'no_task');
    }
});

test('on the passenger display a moved window goes below the clock bar at its own density', () => {
    const h = harness(30, {multiWindow:true, topBar:50, dpi:200});
    assert.deepEqual({...h.agent.placeBelowBar(45, 'org.example.app', 0, 2)}, {status:'applied', top:50});
    assert.equal(h.mode(), 6);
    assert.deepEqual(h.bounds(), [0, 50, 1920, 720]);
    assert.deepEqual([...h.pinned()], [0, 50, 1920, 720]);
    assert.equal(h.density(), 200);

    assert.deepEqual({...harness(30, {multiWindow:true, dpi:200}).agent.placeBelowBar(45, 'org.example.app', 0, 2)},
        {status:'native'});
    const freeformOnly = harness(30, {topBar:50, dpi:200});
    assert.throws(() => freeformOnly.agent.placeBelowBar(45, 'org.example.app', 0, 2), {code:'unsupported_window'});
    assert.equal(freeformOnly.mode(), 1);
});

test('a window arriving on the passenger display keeps its density unless a passenger default is set', () => {
    const h = harness(30, {displayId:0});
    const service = livePolicy(30, h);
    service.setProfiles(documentWith([], {displayMove:{fingers:3}}));
    service.tick();
    const placed = service.moves.dependencies.placed;
    placed(profile.component, 0, 45, 1);
    assert.equal(service.queue.length, 1, 'only the placement below the bar is queued');
    service.queue.length = 0;
    placed(profile.component, 0, 45, 6);
    assert.equal(service.queue.length, 0, 'nothing is changed on the cluster');
});
