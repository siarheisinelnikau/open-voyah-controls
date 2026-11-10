import { android9WindowHook } from './window-android9.js';
import { android11WindowHook } from './window-android11.js';
import { systemTasks } from './window-tasks.js';
import {
    CENTRAL_DISPLAY, CONTROLS, OEM_LAUNCHER, PANEL_CONFIG, PANEL_EDGE, SECONDARY_DISPLAY, WINDOW_CONFIG, WINDOW_REQUEST,
    componentKey, launchProfile, profileFor, readNavigator, readWindowConfig, readWindowRequest, windowBounds
} from './window-profiles.js';

const REQUEST = CONTROLS + '.extra.FLOATING_WINDOW';
const MAIN = 'android.intent.action.MAIN';
const LAUNCHER = 'android.intent.category.LAUNCHER';

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

function readPanelResource(context) {
    try {
        const resources = context.getPackageManager().getResourcesForApplication(OEM_LAUNCHER);
        const id = resources.getIdentifier('navigation_bar_width', 'dimen', OEM_LAUNCHER);
        const pixels = id === 0 ? 0 : resources.getDimensionPixelOffset(id);
        return pixels > 0 && pixels <= 600 ? pixels : 0;
    } catch (_) { return 0; }
}

export function installWindowPolicy(Java) {
    Java.perform(() => {
        const sdk = Java.use('android.os.Build$VERSION').SDK_INT.value;
        const install = {28: android9WindowHook, 30: android11WindowHook}[sdk];
        if (!install) return;
        const mode = Java.use('android.app.WindowConfiguration').WINDOWING_MODE_FREEFORM.value;
        let early;
        try {
            install(Java, entry => {
                early = entry;
                entry.implementation = function (...args) {
                    try { applyWindowRequest(args[0], args[1], mode); } catch (_) { }
                    return entry.call(this, ...args);
                };
            });
            installLiveWindowPolicy(Java, sdk, early);
        } catch (error) {
            console.log('OVC_WINDOW_ERROR ' + (error.stack || error));
            if (early) console.log('OVC_WINDOW_READY sdk=' + sdk + ' launch-only');
        }
    });
}

function installLiveWindowPolicy(Java, sdk, early) {
    const tasks = systemTasks(Java, sdk);
    const Global = Java.use('android.provider.Settings$Global');
    const context = Java.use('android.app.ActivityThread').currentActivityThread().getSystemContext();
    const resolver = Java.retain(context.getContentResolver());
    const Options = Java.use('android.app.ActivityOptions');
    const Starter = Java.use(sdk === 28 ? 'com.android.server.am.ActivityStarter' : 'com.android.server.wm.ActivityStarter');
    const recordType = sdk === 28 ? 'com.android.server.am.ActivityRecord' : 'com.android.server.wm.ActivityRecord';
    const taskType = sdk === 28 ? 'com.android.server.am.TaskRecord' : 'com.android.server.wm.Task';
    const lateEntries = Starter.startActivityUnchecked.overloads.filter(entry => {
        const types = entry.argumentTypes.map(type => type.className);
        return entry.returnType.className === 'int' && types[0] === recordType && types[1] === recordType &&
            types[6] === 'android.app.ActivityOptions' && types[7] === taskType;
    });
    if (lateEntries.length !== 1) throw Error('Android window completion signature unavailable');
    const late = lateEntries[0];
    const Manager = Java.use('android.app.ActivityManager');
    const started = new Set([Manager.START_SUCCESS.value, Manager.START_TASK_TO_FRONT.value, Manager.START_DELIVERED_TO_TOP.value]);
    const panelResource = readPanelResource(context);
    let measuredPanel = 0;
    let windowRaw, panelRaw, config, navigator, lastId;
    let lastNavigator = "";
    let released = false;
    let loggedError = '';
    const raw = key => {
        const value = Global.getString(resolver, key);
        return value === null ? '' : String(value);
    };
    const allowed = (launch, screen = CENTRAL_DISPLAY, displayId = launch?.display) =>
        !released && launch && launchProfile(config, navigator, launch, screen, displayId);
    const panel = launch => Math.max(panelResource, measuredPanel, launch?.edge ?? 0);

    const canSetLaunchBounds = (launch, record) => launch.display === 0 && launch.user === tasks.currentUser()
        && tasks.freeformAvailable() && record.isResizeable();

    function applyAfterLaunch(launch, taskId, originals, displayId = 0) {
        Java.scheduleOnMainThread(() => {
            try {
                // Read a just-saved default outside ActivityStarter's lock before applying density.
                refresh();
                const screen = tasks.screenOf(displayId);
                if (!screen) return;
                const current = allowed(launch, screen, displayId);
                if (current) tasks.apply(current, launch.user, panel(launch), taskId, originals, displayId);
                else if (!released && config?.enabled && config.user === launch.user && screen === SECONDARY_DISPLAY) {
                    tasks.releaseSecondary(launch.key, launch.user, taskId, displayId);
                }
            } catch (error) {
                if (error.code === 'no_task') return;
                const message = String(error);
                if (message !== loggedError) {
                    console.log('OVC_WINDOW_ERROR launch ' + message);
                    loggedError = message;
                }
            }
        });
    }

    function refresh() {
        const windows = raw(WINDOW_CONFIG);
        const buttons = raw(PANEL_CONFIG);
        if (windowRaw !== windows) {
            windowRaw = windows;
            config = readWindowConfig(windows);
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
            Global.putString(resolver, WINDOW_REQUEST, null);
            if (command.operation === 'release') {
                released = true;
                result = tasks.release(command.user);
            } else {
                const profile = profileFor(config, command.component, command.user, true, CENTRAL_DISPLAY,
                    command.operation === 'reset');
                if (!profile || released) {
                    throw Object.assign(Error('Inactive configuration'), {code:'no_profile'});
                }
                result = tasks.apply(profile, command.user, panel());
            }
            result.status ??= 'applied';
        } catch (error) {
            result = {status:error.code || 'failed'};
            if (!error.code) console.log('OVC_WINDOW_ERROR ' + (error.stack || error));
        }
        console.log('OVC_WINDOW_RESULT ' + command.id + ' ' + JSON.stringify(result));
    }

    refresh();
    early.implementation = function (...args) {
        const [record, options] = args;
        try {
            applyWindowRequest(record, options, tasks.framework.freeform);
            const launch = explicitLaunch(record, options, args[5], tasks);
            const profile = allowed(launch);
            if (launch?.edge) record.intent.value.removeExtra(PANEL_EDGE);
            // Service launches can omit a source display. Wait for their actual task in that case.
            if (profile && canSetLaunchBounds(launch, record)) {
                const bounds = windowBounds(tasks.frame(), profile, panel(launch));
                if (bounds !== null) {
                    const target = options ?? Options.makeBasic();
                    target.setLaunchDisplayId(0);
                    target.setLaunchBounds(tasks.Rect.$new(...bounds));
                    target.setLaunchWindowingMode(tasks.framework.freeform);
                    args[1] = target;
                }
            }
        } catch (_) { }
        return early.call(this, ...args);
    };

    late.implementation = function (...args) {
        let launch;
        let profile;
        let originals;
        try {
            launch = explicitLaunch(args[0], args[6], args[1], tasks);
            profile = allowed(launch);
            if (launch?.edge) measuredPanel = launch.edge;
            if (profile && canSetLaunchBounds(launch, args[0])) {
                // Preserve the task before Android consumes the early bounds on a warm start.
                originals = tasks.captureLaunch(profile, launch.user);
            }
        } catch (_) { }
        const result = late.call(this, ...args);
        if (launch && !released && started.has(result)) {
            try {
                // Android reports the reused activity separately when a task is brought to the front.
                const completed = sdk === 28 ? args[8]?.[0] : this.mLastStartActivityRecord.value;
                const record = completed?.getTask() ? completed : args[0];
                const task = record.getTask();
                const actualDisplay = record.getDisplayId();
                if (task && (launch.requestedDisplay < 0 || launch.requestedDisplay === actualDisplay)) {
                    const screen = tasks.screenOf(actualDisplay);
                    if (screen && (allowed(launch, screen, actualDisplay) || screen === SECONDARY_DISPLAY)) {
                        applyAfterLaunch(launch, tasks.id(task), actualDisplay === 0 ? originals : null, actualDisplay);
                    }
                }
            } catch (_) { }
        }
        return result;
    };

    setInterval(() => Java.perform(() => {
        try { request(); } catch (error) {
            const message = String(error);
            if (message !== loggedError) { loggedError = message; console.log('OVC_WINDOW_ERROR ' + message); }
        }
    }), 400);
    console.log('OVC_WINDOW_READY protocol=3 sdk=' + sdk + ' displayDpi=1');
}
