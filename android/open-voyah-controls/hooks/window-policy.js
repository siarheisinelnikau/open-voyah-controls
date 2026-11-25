import { android9WindowHook } from './window-android9.js';
import { android11WindowHook } from './window-android11.js';
import { createReporter, errorKind } from './report.js';
import { systemTasks } from './window-tasks.js';
import { systemReserve } from './window-reserve.js';
import { android11DisplayMoves, displayMover } from './window-display-move.js';
import { RESERVE_GUARD, reserveSwitch } from './window-reserve-switch.js';
import {
    CENTRAL_DISPLAY, CONTROLS, OEM_LAUNCHER, PANEL_CONFIG, PANEL_EDGE, SECONDARY_DISPLAY, WINDOW_CONFIG, WINDOW_REQUEST,
    componentKey, fitsReservedStrip, launchProfile, profileFor, readNavigator, readWindowConfig, readWindowRequest,
    rectValues, sameBounds
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
    /** The width the launcher's own resources state for its panel at the present density, or 0. */
    const declared = () => {
        if (!dimension) return 0;
        try {
            const pixels = resources.getDimensionPixelOffset(dimension);
            return pixels > 0 && pixels <= 600 ? pixels : 0;
        } catch (_) {
            return 0;
        }
    };
    let lastBounds = null, lastDensity, width = 0;
    const measured = (bounds, density, reported) => {
        if (reported > 0 && reported <= 600) {
            width = reported;
        } else if (lastDensity !== density || !sameBounds(bounds, lastBounds)) {
            // A width measured before a rotation or a density change belongs to the old display frame.
            width = declared();
        }
        lastBounds = bounds;
        lastDensity = density;
        return width;
    };
    return Object.assign(measured, {declared});
}

/** Names this kernel boot and this system_server process, for the record that guards the reserved strip. */
function serverSession(Java) {
    return () => {
        const reader = Java.use('java.io.BufferedReader').$new(
            Java.use('java.io.FileReader').$new('/proc/sys/kernel/random/boot_id'));
        try {
            return {boot: String(reader.readLine()).trim(), pid: Java.use('android.os.Process').myPid()};
        } finally {
            reader.close();
        }
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
    const panelWidth = launcherPanelWidth(Java, context);
    const tasks = step('tasks_api', () => systemTasks(Java, sdk, panelWidth));
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
    // Experimental: Android itself keeps the panel strip clear. The framework side is built on first use, so an
    // agent with the option off touches nothing of it.
    // Uptime, not the wall clock: a head unit sets its time from the network or GNSS after boot, and a jump must
    // neither shorten the wait before the first change nor postpone the checks that follow it.
    let uptime;
    const monotonic = () => Number((uptime ??= Java.use('android.os.SystemClock')).elapsedRealtime());
    const reservation = reserveSwitch({
        reserve: systemReserve(Java, sdk, {now: monotonic}),
        session: serverSession(Java),
        read: () => raw(RESERVE_GUARD),
        write: document => Global.putString(resolver, RESERVE_GUARD, document) === true,
        currentUser: () => tasks.currentUser(),
        panelWidth: () => panelWidth.declared(),
        announce: line => console.log(line),
        now: monotonic,
    });
    const reservationWanted = () => !released && config?.enabled === true && config.systemInsets === true
        ? {attempt: config.systemInsetsAttempt, user: config.user} : null;
    /** Whether this agent still has to place the task itself, beside the panel. */
    const places = (profile, screen = CENTRAL_DISPLAY) =>
        screen !== CENTRAL_DISPLAY || !fitsReservedStrip(profile, reservation.width());

    const allowed = (launch, screen = CENTRAL_DISPLAY, displayId = launch?.display) => {
        const profile = !released && launch && launchProfile(config, navigator, launch, screen, displayId);
        // In the reserved strip a full-width task at the display density is already where it belongs.
        return profile && !places(profile, screen) && profile.dpi === 0 ? null : profile;
    };

    const canSetLaunchBounds = (launch, record) => launch.display === 0 && launch.user === tasks.currentUser()
        && tasks.freeformAvailable() && record.isResizeable();

    let homePending = false;
    /**
     * Android 11, after home was started on the central display: hide the placed apps that home now covers (see
     * the adapter's revealHome). Runs once the start has left ActivityStarter, like a deferred layout; a burst of
     * starts shares one pass.
     */
    function revealHomeLater() {
        if (homePending) return;
        homePending = true;
        const reveal = () => {
            homePending = false;
            try { if (!released) tasks.revealHome(); } catch (error) { reportError('home', error); }
        };
        try { setTimeout(() => Java.perform(reveal), 0); }
        catch (error) { homePending = false; throw error; }
    }

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
                if (current) {
                    tasks.apply(current, update.launch.user, taskId, update.originals, displayId,
                        screen === CENTRAL_DISPLAY ? reservation.width() : 0);
                }
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

    // Optional, and looked up once both start hooks are known to fit: a firmware without one of its members keeps
    // the rest of the window service and says displayMove=0.
    let moves = null;
    if (sdk === 30) {
        try { moves = android11DisplayMoves(Java); } catch (error) { report('display_move', error); }
    }
    /** The swipe between screens, wired to this agent's placement policy. Null without the Android 11 members. */
    const mover = moves && displayMover({
        api: moves,
        componentKey,
        prepare: (task, info) => tasks.prepareMove(task, componentKey(info.component).split('/')[0], info.user),
        placed: (component, user, taskId, displayId) => {
            const key = componentKey(component);
            if (!key || released) return;
            // The app keeps the density it had. Only a reviewed passenger default replaces it there.
            if (displayId === 0 || config?.displayDpi?.secondary != null) {
                applyAfterLaunch({key, user, edge:0, caller:CONTROLS, fromHome:false, display:displayId}, taskId, null, displayId);
            }
            if (displayId !== 0 && displayId === (config?.passengerDisplayId ?? 1)) {
                setTimeout(() => Java.perform(() => {
                    if (released) return;
                    try { tasks.placeBelowBar(taskId, key.split('/')[0], user, displayId); }
                    catch (error) { if (error?.code !== 'no_task') reportError('move_place', error); }
                }), 0);
            }
        },
        schedule: work => setTimeout(() => Java.perform(work), 0),
        report: (phase, error) => reportError(phase, error),
        announce: line => console.log(line),
    });
    const moveWanted = () => !released && config?.enabled === true && config.displayMove
        ? {fingers: config.displayMove.fingers, passenger: config.passengerDisplayId ?? 1, reveal: config.displayMove.reveal}
        : null;

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
                // Moved windows come back to the central display first, then it returns to its native layout and
                // the tasks are restored into it.
                const returned = mover ? mover.release() : true;
                const native = reservation.stop();
                result = tasks.release(command.user);
                if (!returned) throw Object.assign(Error('A moved window stayed on its screen'), {code:'rollback_failed'});
                if (!native) throw Object.assign(Error('The reserved strip was not released'), {code:'rollback_failed'});
            } else if (command.operation === 'recall') {
                if (!mover?.recall()) throw Object.assign(Error('Moved windows did not return'), {code:'rollback_failed'});
                result = {};
            } else if (command.operation === 'insets') {
                // Answers from memory: the app asks this when it shows the option, and must not change anything.
                const strip = reservation.status();
                result = {insets:strip.status, width:strip.width};
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
                    if (tasks.placedMode() === tasks.framework.freeform) {
                        target.setLaunchBounds(tasks.Rect.$new(...bounds));
                        target.setLaunchWindowingMode(tasks.framework.freeform);
                    } else {
                        // Multi-window takes no launch bounds, and freeform first would leave a caption bar. A freeform
                        // task does not cover the screen that started it, so Android does not pause that screen and
                        // starts an app whose process runs at once, in freeform, where its window adds the bar
                        // (maximize and close). Moved on to multi-window with the same resource sizes, the app sees no
                        // public configuration change, Android 11's ActivityThread drops it and the bar stays. A
                        // fullscreen task covers that screen: Android pauses it first, and the task is normally
                        // placed before its activity starts. An activity that does start first leaves fullscreen
                        // with a new size, which it is told about. Only a new task takes this mode.
                        target.setLaunchBounds(null);
                        target.setLaunchWindowingMode(tasks.framework.fullscreen);
                    }
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
            if (profile && places(profile) && canSetLaunchBounds(launch, args[0])) {
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
        if (sdk === 30 && !launch && !released && started.has(result)) {
            try {
                // The launcher's Home button starts its own activity again; the Home key starts the home intent.
                // Both end here with home's existing activity as the reported record.
                const shown = this.mLastStartActivityRecord.value;
                if (shown !== null && shown.isActivityTypeHome() && shown.getDisplayId() === 0) revealHomeLater();
            } catch (error) { reportError('home', error); }
        }
        if (sdk === 30 && !released && started.has(result)) {
            try {
                // Every other start that leaves an activity filling a fullscreen task under the panel: a picker or
                // chooser inside Controls' task, a gallery or browser in a task of its own. A launch this agent
                // places as a whole task is left to that. Placed here, before the activity's process draws it,
                // so it does not start once more at the new size.
                const shown = this.mLastStartActivityRecord.value;
                let managed = false;
                try { managed = launch != null && allowed(launch) != null; } catch (_) { }
                if (shown !== null && !managed && config?.enabled === true && config.user === tasks.userOf(shown) &&
                    reservation.width() === 0) tasks.placeGuest(shown);
            } catch (error) { reportError('guest', error); }
        }
        return result;
    };

    setInterval(() => Java.perform(() => {
        try { request(); } catch (error) { reportError('settings', error); }
        try { reservation.sync(reservationWanted()); } catch (error) { reportError('reserve', error); }
        try { mover?.sync(moveWanted()); } catch (error) { reportError('display_move', error); }
    }), 400);
    console.log('OVC_WINDOW_READY protocol=3 sdk=' + sdk + ' displayDpi=1 systemInsets=1 displayMove=' + (mover ? 1 : 0));
}
