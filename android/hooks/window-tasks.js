import { android9Tasks } from './window-task-android9.js';
import { android11Tasks } from './window-task-android11.js';
import { CENTRAL_DISPLAY, SECONDARY_DISPLAY, rectValues, sameBounds, windowBounds } from './window-profiles.js';

function error(code) {
    const failure = Error(code);
    failure.code = code;
    return failure;
}

export function systemTasks(Java, sdk) {
    const Rect = Java.use('android.graphics.Rect');
    const Configuration = Java.use('android.content.res.Configuration');
    const WindowConfiguration = Java.use('android.app.WindowConfiguration');
    const Display = Java.use('android.view.Display');
    const ResizeMode = Java.use(sdk === 28 ? 'android.app.ActivityManager' : 'android.app.ActivityTaskManager');
    const System = Java.use('java.lang.System');
    const TaskInfo = Java.use('android.app.ActivityManager$RunningTaskInfo');
    const framework = {
        Configuration,
        layoutBase: Configuration.SCREENLAYOUT_LONG_YES.value | Configuration.SCREENLAYOUT_SIZE_XLARGE.value,
        freeform: WindowConfiguration.WINDOWING_MODE_FREEFORM.value,
        fullscreen: WindowConfiguration.WINDOWING_MODE_FULLSCREEN.value,
        splitPrimary: WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY.value,
        splitSecondary: WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY.value,
        launchAdjacent: Java.use('android.content.Intent').FLAG_ACTIVITY_LAUNCH_ADJACENT.value,
        resizeSystem: ResizeMode.RESIZE_MODE_SYSTEM.value
    };
    const api = (sdk === 28 ? android9Tasks : android11Tasks)(Java, framework);
    const saved = new Map();
    const toRect = bounds => bounds === null ? null : Rect.$new(...bounds);
    const key = task => api.id(task) + ':' + System.identityHashCode(task);
    const knownMode = mode => mode === framework.fullscreen || mode === framework.freeform;

    function screenOf(displayId) {
        if (displayId === 0) return CENTRAL_DISPLAY;
        if (displayId < 0) return null;
        try {
            const display = api.androidDisplay(displayId);
            const internal = sdk === 28 ? Display.TYPE_BUILT_IN.value : Display.TYPE_INTERNAL.value;
            if (display && display.getType() === internal && display.getOwnerUid() === 0 &&
                (display.getFlags() & Display.FLAG_PRIVATE.value) === 0) return SECONDARY_DISPLAY;
        } catch (_) { }
        return null;
    }

    function frame() {
        const display = api.display();
        if (!display) throw error('no_display');
        const bounds = rectValues(display.getConfiguration().windowConfiguration.value.getBounds());
        const insets = Rect.$new();
        api.windowManager.getStableInsets(0, insets);
        const [left, top, right, bottom] = rectValues(insets);
        return {bounds, stable: [bounds[0] + left, bounds[1] + top, bounds[2] - right, bounds[3] - bottom]};
    }

    function activities(task, pkg, user, displayId) {
        if (!task || !api.isLeaf(task) || !task.isActivityTypeStandardOrUndefined()) return null;
        const records = [];
        for (let i = 0, count = task.getChildCount(); i < count; i++) {
            const record = Java.cast(task.getChildAt(i), api.activity);
            if (record.finishing.value) continue;
            const expectedDisplay = displayId ?? records[0]?.getDisplayId() ?? record.getDisplayId();
            if (String(record.packageName.value) !== pkg || api.userOf(record) !== user ||
                record.getDisplayId() !== expectedDisplay || expectedDisplay < 0) return null;
            records.push(record);
        }
        return records.length === 0 ? null : records;
    }

    function* runningTasks(pkg, user) {
        const list = api.am.getTasks(64);
        for (let i = 0; i < list.size(); i++) {
            const info = Java.cast(list.get(i), TaskInfo);
            const base = info.baseActivity.value;
            if (base === null || String(base.getPackageName()) !== pkg) continue;
            const task = api.find(api.runningId(info));
            if (activities(task, pkg, user, 0)) yield task;
        }
    }

    function matchingTask(pkg, user, exactId, displayId) {
        if (exactId === undefined) return runningTasks(pkg, user).next().value ?? null;
        const task = api.find(exactId);
        return activities(task, pkg, user, displayId) ? task : null;
    }

    function geometry(task) {
        const bounds = api.requestedBounds(task);
        return task.getWindowingMode() === framework.freeform && bounds && !bounds.isEmpty() ? rectValues(bounds) : null;
    }

    function snapshot(task) {
        return {mode:task.getWindowingMode(), bounds:geometry(task), override:Configuration.$new(api.getOverride(task))};
    }

    function captureLaunch(profile, user) {
        return api.lock(() => {
            const originals = new Map();
            for (const task of runningTasks(profile.component.split('/')[0], user)) {
                if (knownMode(task.getWindowingMode())) originals.set(key(task), snapshot(task));
            }
            return originals;
        });
    }

    function notify(records) {
        // Ask Android to deliver the configuration. It may recreate an activity according to its manifest.
        for (const record of records) record.ensureActivityConfiguration(0, false);
    }

    function restore(task, records, prior, geometryAllowed = true, originalDpi = prior.override.densityDpi.value) {
        if (!knownMode(task.getWindowingMode())) throw error('rollback_failed');
        const current = geometry(task);
        if (geometryAllowed && (task.getWindowingMode() !== prior.mode ||
            (prior.bounds !== null && !sameBounds(current, prior.bounds)))) {
            api.place(task, toRect(prior.bounds));
        }
        api.setOverride(task, prior.override);
        if (api.getOverride(task).densityDpi.value !== originalDpi) {
            api.setOverride(task, api.densityConfig(task, originalDpi));
        }
        notify(records);
        if (api.getOverride(task).densityDpi.value !== originalDpi ||
            (geometryAllowed && (task.getWindowingMode() !== prior.mode ||
                (prior.bounds !== null && !sameBounds(geometry(task), prior.bounds))))) throw error('rollback_failed');
    }

    function apply(profile, user, measuredPanel, taskId, originals = null, displayId = 0) {
        const pkg = profile.component.split('/')[0];
        return api.lock(() => {
            if (api.user() !== user || api.isLocked()) throw error('unsupported_task');
            const screen = screenOf(displayId);
            if (!screen || (displayId !== 0 && taskId === undefined)) throw error('no_display');
            const task = matchingTask(pkg, user, taskId, displayId);
            if (!task) throw error('no_task');
            const records = activities(task, pkg, user, displayId);
            if (!records || !knownMode(task.getWindowingMode())) throw error('unsupported_task');
            const before = snapshot(task);
            const taskKey = key(task);
            const dpi = profile.dpi;
            const changeDensity = before.override.densityDpi.value !== dpi;
            const canPlace = screen === CENTRAL_DISPLAY && api.supportsFreeform() && task.isResizeable();
            if (screen === CENTRAL_DISPLAY && !canPlace && dpi === 0 && !changeDensity) throw error('unsupported_window');
            const bounds = canPlace ? windowBounds(frame(), profile, measuredPanel, ...api.minimum(task)) : null;
            if (canPlace && bounds === null) throw error('no_space');
            const changeBounds = bounds !== null && (before.mode !== framework.freeform || !sameBounds(before.bounds, bounds));
            try {
                if (changeBounds) api.place(task, toRect(bounds));
                if (bounds !== null && (task.getWindowingMode() !== framework.freeform || !sameBounds(geometry(task), bounds))) {
                    throw error('placement_changed');
                }
                if (api.getOverride(task).densityDpi.value !== dpi) {
                    api.setOverride(task, api.densityConfig(task, dpi));
                    notify(records);
                }
                const actualDpi = task.getConfiguration().densityDpi.value;
                if (dpi !== 0 && actualDpi !== dpi) throw error('density_rejected');
                const actual = rectValues(task.getBounds());
                if (bounds !== null && !sameBounds(actual, bounds)) throw error('placement_changed');
                if (changeBounds || changeDensity || (originals && canPlace)) {
                    const prior = originals?.get(taskKey) ?? (originals && canPlace && before.mode === framework.freeform
                        ? {mode: framework.fullscreen, bounds:null, override: Configuration.$new()} : before);
                    const savedTask = saved.get(taskKey);
                    if (saved.size >= 24) {
                        for (const [id, entry] of saved) {
                            const existing = api.find(entry.taskId);
                            if (!existing || key(existing) !== id) saved.delete(id);
                        }
                    }
                    const entry = savedTask ?? {taskId:api.id(task), pkg, user, dpi:prior.override.densityDpi.value,
                        central:null, appliedDpi:null};
                    if (canPlace) entry.central ??= prior;
                    if (dpi !== entry.dpi) entry.appliedDpi = dpi;
                    else entry.appliedDpi = null;
                    saved.set(taskKey, entry);
                }
                return {width:actual[2] - actual[0], dpi:actualDpi, left:actual[0],
                    status:canPlace ? 'applied' : 'density_only'};
            } catch (failure) {
                try { restore(task, records, before, displayId === 0); } catch (_) { throw error('rollback_failed'); }
                throw failure;
            }
        });
    }

    function restoreDensity(task, records, entry) {
        if (entry.appliedDpi === null) return;
        if (api.getOverride(task).densityDpi.value !== entry.appliedDpi) {
            entry.appliedDpi = null;
            return;
        }
        if (!knownMode(task.getWindowingMode())) throw error('rollback_failed');
        api.setOverride(task, api.densityConfig(task, entry.dpi));
        notify(records);
        if (api.getOverride(task).densityDpi.value !== entry.dpi) throw error('rollback_failed');
        entry.appliedDpi = null;
    }

    function releaseSecondary(component, user, taskId, displayId) {
        return api.lock(() => {
            if (!saved.size || api.user() !== user || api.isLocked() || screenOf(displayId) !== SECONDARY_DISPLAY) return;
            const task = api.find(taskId);
            const records = activities(task, component.split('/')[0], user, displayId);
            if (!records) return;
            const id = key(task);
            const entry = saved.get(id);
            if (!entry || entry.user !== user) return;
            restoreDensity(task, records, entry);
            if (!entry.central) saved.delete(id);
        });
    }

    function release(user) {
        return api.lock(() => {
            if (api.user() !== user || api.isLocked()) throw error('unsupported_task');
            let incomplete = false;
            for (const [id, entry] of saved) {
                if (entry.user !== user) continue;
                const task = api.find(entry.taskId);
                try {
                    if (task && key(task) === id) {
                        const records = activities(task, entry.pkg, user);
                        if (!records) throw error('rollback_failed');
                        if (records[0].getDisplayId() === 0 && entry.central) {
                            restore(task, records, entry.central, true, entry.dpi);
                        } else restoreDensity(task, records, entry);
                    }
                    saved.delete(id);
                } catch (_) { incomplete = true; }
            }
            if (incomplete) throw error('rollback_failed');
            return {};
        });
    }

    return {
        Rect, framework, frame, apply, captureLaunch, release, releaseSecondary, screenOf,
        freeformAvailable: () => api.supportsFreeform() && !api.isLocked(),
        userOf: api.userOf,
        currentUser: api.user,
        id: api.id
    };
}
