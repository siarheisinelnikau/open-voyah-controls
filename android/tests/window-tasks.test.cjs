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

function harness(sdk, options = {}) {
    let displayId = options.displayId ?? 2;
    let mode = options.mode ?? 1;
    let override = conf(options.dpi ?? 0);
    let bounds = rect(0, 0, 1920, 720);
    let placements = 0;
    const record = {
        packageName: field('org.example.app'),
        finishing: field(false),
        getDisplayId: () => displayId,
        ensureActivityConfiguration() { }
    };
    const task = {
        getChildCount: () => 1,
        getChildAt: () => record,
        getWindowingMode: () => mode,
        getBounds: () => bounds,
        isActivityTypeStandardOrUndefined: () => true,
        isResizeable: () => true,
        getConfiguration: () => conf(override.densityDpi.value || 240)
    };
    const api = {
        lock: callback => callback(),
        user: () => 0,
        userOf: () => 0,
        isLocked: () => false,
        isLeaf: () => true,
        supportsFreeform: () => true,
        id: () => 45,
        find: id => id === 45 ? task : null,
        display: () => ({getConfiguration: () => ({windowConfiguration:field({getBounds:()=>rect(0,0,1920,720)})})}),
        androidDisplay: id => id === 2 ? {
            getType: () => options.type ?? 1,
            getFlags: () => options.flags ?? 0,
            getOwnerUid: () => options.owner ?? 0,
        } : null,
        windowManager: {getStableInsets: () => {}},
        requestedBounds: () => bounds,
        minimum: () => [200, 200],
        getOverride: () => override,
        densityConfig: (_, dpi) => conf(dpi),
        setOverride: (_, value) => { override = value; },
        place: (_, target) => {
            placements++;
            mode = target === null ? 1 : 5;
            bounds = target ?? rect(0,0,1920,720);
        },
    };
    const classes = {
        'android.graphics.Rect': {$new: (...args) => rect(...(args.length ? args : [0,0,0,0]))},
        'android.content.res.Configuration': {$new: value => conf(value?.densityDpi.value ?? 0),
            SCREENLAYOUT_LONG_YES:field(32),SCREENLAYOUT_SIZE_XLARGE:field(4)},
        'android.app.WindowConfiguration': {
            WINDOWING_MODE_FULLSCREEN:field(1),WINDOWING_MODE_FREEFORM:field(5),
            WINDOWING_MODE_SPLIT_SCREEN_PRIMARY:field(3),WINDOWING_MODE_SPLIT_SCREEN_SECONDARY:field(4)},
        'android.content.Intent': {FLAG_ACTIVITY_LAUNCH_ADJACENT:field(0x1000)},
        'android.view.Display': {TYPE_BUILT_IN:field(1),TYPE_INTERNAL:field(1),FLAG_PRIVATE:field(4)},
        'android.app.ActivityManager': {RESIZE_MODE_SYSTEM:field(0)},
        'android.app.ActivityTaskManager': {RESIZE_MODE_SYSTEM:field(0)},
        'java.lang.System': {identityHashCode:()=>22},
    };
    const Java = {use: name => classes[name], cast: value => value};
    const agent = vm.runInNewContext(source('window-profiles.js','window-tasks.js') + '\nsystemTasks(Java, sdk)', {
        Java, sdk, android9Tasks:()=>api, android11Tasks:()=>api,
    });
    return {
        agent, placements:()=>placements, density:()=>override.densityDpi.value,
        move: id => {
            displayId = id;
            mode = 1;
            bounds = rect(0,0,1920,720);
        },
    };
}

test('secondary app density is opt-in, reversible and never places a task', () => {
    for (const sdk of [28, 30]) {
        const h = harness(sdk, {dpi:180});
        assert.equal(h.agent.screenOf(2), 'secondary');
        assert.equal(h.agent.apply(profile, 0, 145, 45, null, 2).status, 'density_only');
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
        assert.throws(() => h.agent.apply(profile, 0, 145, 45, null, 2), {code:'no_display'});
        assert.equal(h.density(), 0);
        assert.equal(h.placements(), 0);
    }
});

test('window release clears tracked density across displays without moving the task', () => {
    const h = harness(30, {displayId:0, dpi:180});
    const result = h.agent.apply(profile, 0, 145, 45);
    assert.equal(result.left, 500);
    assert.equal(h.placements(), 1);
    h.move(2);
    h.agent.release(0);
    assert.equal(h.density(), 180);
    assert.equal(h.placements(), 1);
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
