import { android9WindowHook } from './window-android9.js';
import { android11WindowHook } from './window-android11.js';
import { createReporter, errorKind } from './report.js';
import { systemTasks } from './window-tasks.js';
import {
    CENTRAL_DISPLAY, CONTROLS, OEM_LAUNCHER, PANEL_CONFIG, PANEL_EDGE, SECONDARY_DISPLAY, WINDOW_CONFIG, WINDOW_REQUEST,
    componentKey, launchProfile, profileFor, readNavigator, readWindowConfig, readWindowRequest, rectValues, sameBounds
} from './window-profiles.js';

const REQUEST = CONTROLS + '.extra.FLOATING_WINDOW';
const MAIN = 'android.intent.action.MAIN';
const LAUNCHER = 'android.intent.category.LAUNCHER';
const failures = new Set(['no_display', 'no_profile', 'no_space', 'no_task', 'unsupported_task', 'unsupported_window',
    'placement_changed', 'density_rejected', 'rollback_failed']);

/** The status of a request receipt. The app accepts only these values; anything else is reported as `failed`. */
function failureStatus(error) {
    return failures.has(error?.code) ? error.code : 'failed';
}

/** Names the install step that failed, so an unsupported firmware is told apart from a broken agent. */
function step(code, task) {
    try { return task(); }
    catch (cause) { throw Object.assign(Error(code), {code, cause}); }
}

export function applyWindowRequest(record, options, mode) {
    if (!record || !options || String(record.launchedFromPackage.value) !== CONTROLS) return false;
    const intent = record.intent.value;
    if (!intent || String(intent.getAction()) !== MAIN
            || !intent.getComponent() || !intent.getBooleanExtra(REQUEST, false)) return false;
    intent.removeExtra(REQUEST);
    if (!record.isResizeable() || !options.getLaunchBounds()) return false;
    options.setLaunchWindowingMode(mode);
    return true;
}

function explicitLaunch(record, options, source, tasks) {
    const intent = record?.intent.value;
    const requestedDisplay = options?.getLaunchDisplayId() ?? -1;
    const sourceDisplay = source?.getDisplayId() ?? -1;
    const display = requestedDisplay >= 0 ? requestedDisplay : sourceDisplay;
    const requestedMode = options?.getLaunchWindowingMode() ?? 0;
    if (!intent || String(intent.getAction()) !== MAIN || !intent.hasCategory(LAUNCHER)
        || (requestedMode !== 0 && requestedMode !== tasks.framework.fullscreen && requestedMode !== tasks.framework.freeform)
        || !record.isActivityTypeStandardOrUndefined()) return null;
    const component = intent.getComponent();
    if (!component || String(component.getPackageName()) !== String(record.packageName.value)) return null;
    const key = componentKey(String(component.flattenToString()));
    if (!key) return null;
    const caller = String(record.launchedFromPackage.value);
    const sourceMode = source?.getWindowingMode();
    const inNativeSplit = sourceMode != null &&
        (sourceMode === tasks.framework.splitPrimary || sourceMode === tasks.framework.splitSecondary);
    const nativeAdjacent = caller === CONTROLS && sourceMode != null && sourceMode !== tasks.framework.fullscreen &&
        (intent.getFlags() & tasks.framework.launchAdjacent) !== 0;
    if (inNativeSplit || nativeAdjacent) return null;
    const edge = intent.getIntExtra(PANEL_EDGE, 0);
    const trusted = caller === OEM_LAUNCHER && display === 0 && edge > 0 && edge <= 600;
    return {key, display, requestedDisplay, caller, fromHome: source?.isActivityTypeHome() === true,
        user: tasks.userOf(record), edge: trusted ? edge : 0};
}

function nativeLaunchPlacement(options, source, framework) {
    const mode = options?.getLaunchWindowingMode() ?? 0;
    const bounds = options?.getLaunchBounds();
    if (mode === framework.fullscreen || (mode === 0 && !bounds && source?.getWindowingMode() === framework.fullscreen)) {
        return {mode:framework.fullscreen, bounds:null};
    }
    if (mode === framework.freeform && bounds && !bounds.isEmpty()) return {mode, bounds:rectValues(bounds)};
    // Android will choose the native placement. Capture the resulting task before resizing it.
    return null;
}

function launcherPanelWidth(Java, context) {
    let resources, dimension;
    try {
        resources = Java.retain(context.getPackageManager().getResourcesForApplication(OEM_LAUNCHER));
        dimension = resources.getIdentifier('navigation_bar_width', 'dimen', OEM_LAUNCHER);
    } catch (_) { }
    let lastBounds = null, lastDensity, width = 0;
    return (bounds, density, reported) => {
        if (reported > 0 && reported <= 600) {
            width = reported;
        } else if (lastDensity !== density || !sameBounds(bounds, lastBounds)) {
            // A width measured before a rotation or a density change belongs to the old display frame.
            width = 0;
            if (dimension) {
                try {
                    const pixels = resources.getDimensionPixelOffset(dimension);
                    if (pixels > 0 && pixels <= 600) width = pixels;
                } catch (_) { }
            }
        }
        lastBounds = bounds;
        lastDensity = density;
        return width;
    };
}

export function installWindowPolicy(Java) {
    Java.perform(() => {
        const report = createReporter('OVC_WINDOW_ERROR');
        const sdk = Java.use('android.os.Build$VERSION').SDK_INT.value;
        const install = {28: android9WindowHook, 30: android11WindowHook}[sdk];
        if (!install) {
            report('install', {code:'unsupported_sdk'}, {sdk});
            return;
        }
        const hooks = [];
        try {
            let early;
            step('starter_signature', () => install(Java, entry => { early = entry; }));
            installLiveWindowPolicy(Java, sdk, early, hooks, report);
        } catch (error) {
            // A start hook without its completion hook cannot be released, so nothing stays half-installed and
            // no readiness is announced: the supervisor then reports this worker as not ready.
            for (const hook of hooks) hook.implementation = null;
            report('install', error, error?.cause === undefined ? {} : {cause:errorKind(error.cause)});
        }
    });
}

function installLiveWindowPolicy(Java, sdk, early, hooks, report) {
    const recordType = sdk === 28 ? 'com.android.server.am.ActivityRecord' : 'com.android.server.wm.ActivityRecord';
    const taskType = sdk === 28 ? 'com.android.server.am.TaskRecord' : 'com.android.server.wm.Task';
    // Both hooks read the calling activity from this position; refuse a variant that moved it.
    step('starter_signature', () => {
        if (early.argumentTypes[5]?.className !== recordType) throw Error('Unexpected source position');
    });
    const { Global, context, Options, Manager } = step('framework_api', () => ({
        Global: Java.use('android.provider.Settings$Global'),
        context: Java.use('android.app.ActivityThread').currentActivityThread().getSystemContext(),
        Options: Java.use('android.app.ActivityOptions'),
        Manager: Java.use('android.app.ActivityManager'),
    }));
    const tasks = step('tasks_api', () => systemTasks(Java, sdk, launcherPanelWidth(Java, context)));
    const resolver = Java.retain(context.getContentResolver());
    const late = step('completion_signature', () => {
        const Starter = Java.use(sdk === 28 ? 'com.android.server.am.ActivityStarter' : 'com.android.server.wm.ActivityStarter');
        const entries = Starter.startActivityUnchecked.overloads.filter(entry => {
            const types = entry.argumentTypes.map(type => type.className);
            return entry.returnType.className === 'int' && types[0] === recordType && types[1] === recordType &&
                types[6] === 'android.app.ActivityOptions' && types[7] === taskType;
        });
        if (entries.length !== 1) throw Error('Android window completion signature unavailable');
        return entries[0];
    });
    const started = new Set([Manager.START_SUCCESS.value, Manager.START_TASK_TO_FRONT.value, Manager.START_DELIVERED_TO_TOP.value]);
    const starting = [];
    const pendingLaunches = new Map();
    let windowRaw, panelRaw, config, navigator, lastId;
    let lastNavigator = "";
    let released = false;
    const reportError = (phase, error) => report(phase, {code:failureStatus(error)},
        error?.code === undefined ? {cause:errorKind(error)} : {});
    const raw = key => {
        const value = Global.getString(resolver, key);
        return value === null ? '' : String(value);
    };
    const allowed = (launch, screen = CENTRAL_DISPLAY, displayId = launch?.display) =>
        !released && launch && launchProfile(config, navigator, launch, screen, displayId);

    const canSetLaunchBounds = (launch, record) => launch.display === 0 && launch.user === tasks.currentUser()
        && tasks.freeformAvailable() && record.isResizeable();

    function applyAfterLaunch(launch, taskId, originals, displayId = 0) {
        const key = [launch.user, displayId, taskId, launch.key].join(':');
        const pending = pendingLaunches.get(key);
        if (pending) {
            pending.launch = launch;
            pending.originals ??= originals;
            return;
        }
        const update = {launch, originals};
        pendingLaunches.set(key, update);
        const apply = () => {
            pendingLaunches.delete(key);
            if (released) return;
            try {
                // Read a just-saved default outside ActivityStarter's lock before applying density.
                refresh();
                const screen = tasks.screenOf(displayId);
                if (!screen) return;
                const current = allowed(update.launch, screen, displayId);
                if (current) tasks.apply(current, update.launch.user, taskId, update.originals, displayId);
                else if (!released && config?.enabled && config.user === update.launch.user && screen === SECONDARY_DISPLAY) {
                    tasks.releaseSecondary(update.launch.key, update.launch.user, taskId, displayId);
                }
            } catch (error) {
                if (error?.code !== 'no_task') reportError('launch', error);
            }
        };
        // Runs once the current start has left ActivityStarter's lock; tasks.apply then takes the service lock
        // itself, like a request from the settings timer. Java.scheduleOnMainThread is avoided on purpose: it
        // attaches a permanent epoll_wait interceptor to every thread of system_server.
        try { setTimeout(() => Java.perform(apply), 0); }
        catch (error) { pendingLaunches.delete(key); throw error; }
    }

    function refresh() {
        const windows = raw(WINDOW_CONFIG);
        const buttons = raw(PANEL_CONFIG);
        if (windowRaw !== windows) {
            windowRaw = windows;
            config = readWindowConfig(windows);
            // A rejected document disables the policy; say so once instead of silently doing nothing.
            if (windows !== '' && config === null) report('config', {code:'config_invalid'}, {bytes:windows.length});
        }
        if (panelRaw !== buttons) {
            panelRaw = buttons;
            navigator = readNavigator(buttons);
        }
        const identity = config?.enabled && navigator ? config.user + ':' + navigator : '';
        if (identity !== lastNavigator) {
            lastNavigator = identity;
            if (identity && !released) {
                // Protect an existing navigator when the worker starts or the assignment changes.
                applyAfterLaunch({key:navigator, user:config.user, edge:0});
            }
        }
    }

    function request() {
        refresh();
        const command = readWindowRequest(raw(WINDOW_REQUEST), Date.now());
        if (!command || command.id === lastId) return;
        lastId = command.id;
        let result;
        try {
            // Acknowledge consumption before any change. An agent reload must not replay this request.
            if (!Global.putString(resolver, WINDOW_REQUEST, null)) throw Error('Window request could not be consumed');
            if (command.operation === 'release') {
                released = true;
                result = tasks.release(command.user);
            } else {
                const profile = profileFor(config, command.component, command.user, true, CENTRAL_DISPLAY,
                    command.operation === 'reset');
                if (!profile || released) {
                    throw Object.assign(Error('Inactive configuration'), {code:'no_profile'});
                }
                result = tasks.apply(profile, command.user);
            }
            result.status ??= 'applied';
        } catch (error) {
            result = {status:failureStatus(error)};
            if (result.status === 'failed') reportError('request', error);
        }
        console.log('OVC_WINDOW_RESULT ' + command.id + ' ' + JSON.stringify(result));
    }

    refresh();
    hooks.push(early, late);
    early.implementation = function (...args) {
        const [record, options] = args;
        try {
            applyWindowRequest(record, options, tasks.framework.freeform);
            const launch = explicitLaunch(record, options, args[5], tasks);
            const profile = allowed(launch);
            if (launch?.edge) record.intent.value.removeExtra(PANEL_EDGE);
            // Service launches can omit a source display. Wait for their actual task in that case.
            if (profile && launch.display === 0) {
                const start = starting.find(item => record.equals(item.record));
                const cold = start && canSetLaunchBounds(launch, record) && nativeLaunchPlacement(options, args[5], tasks.framework);
                const bounds = (cold || launch.edge) && tasks.boundsFor(profile, launch.edge);
                if (cold && bounds) {
                    const target = options ?? Options.makeBasic();
                    target.setLaunchDisplayId(0);
                    target.setLaunchBounds(tasks.Rect.$new(...bounds));
                    target.setLaunchWindowingMode(tasks.framework.freeform);
                    args[1] = target;
                    start.cold = cold;
                }
            }
        } catch (error) { reportError('start', error); }
        return early.call(this, ...args);
    };

    late.implementation = function (...args) {
        let launch;
        let start;
        try {
            launch = explicitLaunch(args[0], args[6], args[1], tasks);
            const profile = allowed(launch);
            if (profile && canSetLaunchBounds(launch, args[0])) {
                // Preserve the task before Android consumes the early bounds on a warm start.
                start = {record:args[0], tasks:tasks.captureLaunch(profile, launch.user), cold:null};
                starting.unshift(start);
            }
        } catch (error) { reportError('capture', error); }
        let result;
        try { result = late.call(this, ...args); }
        finally { if (start) starting.splice(starting.indexOf(start), 1); }
        if (launch && (!released || start?.cold) && started.has(result)) {
            try {
                // Android reports the reused activity separately when a task is brought to the front.
                const completed = sdk === 28 ? args[8]?.[0] : this.mLastStartActivityRecord.value;
                const record = completed?.getTask() ? completed : args[0];
                const task = record.getTask();
                const actualDisplay = record.getDisplayId();
                if (task && (launch.requestedDisplay < 0 || launch.requestedDisplay === actualDisplay)) {
                    const screen = tasks.screenOf(actualDisplay);
                    const originals = actualDisplay === 0 && start?.cold
                        ? tasks.rememberLaunch(task, launch.key, launch.user, start.tasks, start.cold) : null;
                    if (released) {
                        if (originals) tasks.release(launch.user);
                    } else if (screen && (allowed(launch, screen, actualDisplay) || screen === SECONDARY_DISPLAY)) {
                        applyAfterLaunch(launch, tasks.id(task), originals, actualDisplay);
                    }
                }
            } catch (error) { reportError('completion', error); }
        }
        return result;
    };

    setInterval(() => Java.perform(() => {
        try { request(); } catch (error) { reportError('settings', error); }
    }), 400);
    console.log('OVC_WINDOW_READY protocol=3 sdk=' + sdk + ' displayDpi=1');
}
