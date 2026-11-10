import { android9Tasks } from './window-task-android9.js';
import { android11Tasks } from './window-task-android11.js';
import { rectValues, sameBounds, windowBounds } from './window-profiles.js';

function error(code) {
    const failure = Error(code);
    failure.code = code;
    return failure;
}

export function systemTasks(Java, sdk) {
    const Rect = Java.use('android.graphics.Rect');
    const Configuration = Java.use('android.content.res.Configuration');
    const WindowConfiguration = Java.use('android.app.WindowConfiguration');
    const ResizeMode = Java.use(sdk === 28 ? 'android.app.ActivityManager' : 'android.app.ActivityTaskManager');
    const System = Java.use('java.lang.System');
    const TaskInfo = Java.use('android.app.ActivityManager$RunningTaskInfo');
    const framework = {
        Configuration,
        layoutBase: Configuration.SCREENLAYOUT_LONG_YES.value | Configuration.SCREENLAYOUT_SIZE_XLARGE.value,
        freeform: WindowConfiguration.WINDOWING_MODE_FREEFORM.value,
        fullscreen: WindowConfiguration.WINDOWING_MODE_FULLSCREEN.value,
        resizeSystem: ResizeMode.RESIZE_MODE_SYSTEM.value
    };
    const api = (sdk === 28 ? android9Tasks : android11Tasks)(Java, framework);
    const saved = new Map();
    const toRect = bounds => bounds === null ? null : Rect.$new(...bounds);
    const key = task => api.id(task) + ':' + System.identityHashCode(task);
    const knownMode = mode => mode === framework.fullscreen || mode === framework.freeform;

    function frame() {
        const display = api.display();
        if (!display) throw error('no_display');
        const bounds = rectValues(display.getConfiguration().windowConfiguration.value.getBounds());
        const insets = Rect.$new();
        api.windowManager.getStableInsets(0, insets);
        const [left, top, right, bottom] = rectValues(insets);
        return {bounds, stable: [bounds[0] + left, bounds[1] + top, bounds[2] - right, bounds[3] - bottom]};
    }

    function activities(task, pkg, user) {
        if (!task || !api.isLeaf(task) || !task.isActivityTypeStandardOrUndefined()) return null;
        const records = [];
        for (let i = 0, count = task.getChildCount(); i < count; i++) {
            const record = Java.cast(task.getChildAt(i), api.activity);
            if (record.finishing.value) continue;
            if (String(record.packageName.value) !== pkg || api.userOf(record) !== user || record.getDisplayId() !== 0) return null;
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
            if (activities(task, pkg, user)) yield task;
        }
    }

    function matchingTask(pkg, user, exactId) {
        if (exactId === undefined) return runningTasks(pkg, user).next().value ?? null;
        const task = api.find(exactId);
        return activities(task, pkg, user) ? task : null;
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

    function restore(task, records, prior) {
        if (!knownMode(task.getWindowingMode())) return;
        const current = geometry(task);
        if (task.getWindowingMode() !== prior.mode || (prior.bounds !== null && !sameBounds(current, prior.bounds))) {
            api.place(task, toRect(prior.bounds));
        }
        api.setOverride(task, prior.override);
        notify(records);
        if (task.getWindowingMode() !== prior.mode ||
            (prior.bounds !== null && !sameBounds(geometry(task), prior.bounds))) throw error('rollback_failed');
    }

    function apply(profile, user, measuredPanel, taskId, originals = null) {
        const pkg = profile.component.split('/')[0];
        return api.lock(() => {
            if (api.user() !== user || api.isLocked()) throw error('unsupported_task');
            const task = matchingTask(pkg, user, taskId);
            if (!task) throw error('no_task');
            const records = activities(task, pkg, user);
            if (!records || !knownMode(task.getWindowingMode())) throw error('unsupported_task');
            const before = snapshot(task);
            const taskKey = key(task);
            const dpi = profile.dpi;
            const changeDensity = before.override.densityDpi.value !== dpi;
            const canPlace = api.supportsFreeform() && task.isResizeable();
            if (!canPlace && dpi === 0 && !changeDensity) throw error('unsupported_window');
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
                if (!saved.has(taskKey) && (changeBounds || changeDensity || (originals && canPlace))) {
                    const prior = originals?.get(taskKey) ?? (originals && canPlace && before.mode === framework.freeform
                        ? {mode: framework.fullscreen, bounds:null, override: Configuration.$new()} : before);
                    if (saved.size >= 24) {
                        for (const [id, entry] of saved) {
                            const existing = api.find(entry.taskId);
                            if (!existing || key(existing) !== id) saved.delete(id);
                        }
                    }
                    saved.set(taskKey, {taskId:api.id(task), pkg, user, prior});
                }
                return {width:actual[2] - actual[0], dpi:actualDpi, left:actual[0],
                    status:canPlace ? 'applied' : 'density_only'};
            } catch (failure) {
                try { restore(task, records, before); } catch (_) { throw error('rollback_failed'); }
                throw failure;
            }
        });
    }

    function release(user) {
        return api.lock(() => {
            if (api.user() !== user || api.isLocked()) throw error('unsupported_task');
            let incomplete = false;
            for (const [id, entry] of saved) {
                if (entry.user !== user) continue;
                const task = api.find(entry.taskId);
                const records = activities(task, entry.pkg, user);
                try {
                    if (records && key(task) === id) restore(task, records, entry.prior);
                } catch (_) { incomplete = true; }
                saved.delete(id);
            }
            if (incomplete) throw error('rollback_failed');
            return {};
        });
    }

    return {
        Rect, framework, frame, apply, captureLaunch, release,
        freeformAvailable: () => api.supportsFreeform() && !api.isLocked(),
        userOf: api.userOf,
        currentUser: api.user,
        id: api.id
    };
}
