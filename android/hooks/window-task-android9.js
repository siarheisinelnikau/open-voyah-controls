export function android9Tasks(Java, framework) {
    const am = Java.cast(Java.use('android.app.ActivityManager').getService(),
        Java.use('com.android.server.am.ActivityManagerService'));
    const supervisor = am.mStackSupervisor.value;
    const display = () => supervisor.getActivityDisplay(0);
    const lookup = supervisor.anyTaskForIdLocked.overload('int', 'int');
    const attached = Java.use('com.android.server.am.ActivityStackSupervisor').MATCH_TASK_IN_STACKS_ONLY.value;
    const activity = Java.use('com.android.server.am.ActivityRecord');
    const Stack = Java.use('com.android.server.am.ActivityStack');
    const Task = Java.use('com.android.server.am.TaskRecord');
    const reparent = Task.reparent.overload('com.android.server.am.ActivityStack', 'boolean', 'int', 'boolean', 'boolean', 'java.lang.String');
    const resize = Task.resize.overload('android.graphics.Rect', 'int', 'boolean', 'boolean');

    const getOverride = task => task.getOverrideConfiguration();
    const empty = rect => rect === null || rect.isEmpty();
    return {
        am,
        lock(fn) {
            let result;
            Java.synchronized(am, () => { result = fn(); });
            return result;
        },
        windowManager: am.mWindowManager.value,
        activity,
        display,
        user: () => am.getCurrentUser().id.value,
        userOf: record => record.userId.value,
        find: id => lookup.call(supervisor, id, attached),
        id: task => task.taskId.value,
        runningId: info => info.id.value,
        getOverride,
        minimum: task => {
            const defaultSize = supervisor.mDefaultMinSizeOfResizeableTask.value;
            return [task.mMinWidth.value > 0 ? task.mMinWidth.value : defaultSize,
                task.mMinHeight.value > 0 ? task.mMinHeight.value : defaultSize];
        },
        place(task, bounds) {
            const mode = bounds === null ? framework.fullscreen : framework.freeform;
            if (task.getWindowingMode() !== mode) {
                const target = Java.cast(display().getOrCreateStack(mode, task.getActivityType(), false), Stack);
                reparent.call(task, target, true, Task.REPARENT_KEEP_STACK_AT_FRONT.value, false, false, 'OpenVoyahControls');
            }
            if (task.getWindowingMode() === mode) resize.call(task, bounds, framework.resizeSystem, false, false);
        },
        setOverride(task, config) {
            task.onOverrideConfigurationChanged(config);
        },
        densityConfig(task, dpi) {
            const next = framework.Configuration.$new(getOverride(task));
            const parent = Java.cast(task.getStack(), Stack).getConfiguration();
            const bounds = task.getOverrideBounds();
            const bounded = !empty(bounds);
            if (bounded) {
                // Recompute from physical bounds before scaling, so repeated DPI changes do not accumulate rounding.
                const normal = framework.Configuration.$new();
                task.computeOverrideConfiguration(normal, bounds, null, false, false);
                for (const field of ['screenWidthDp', 'screenHeightDp', 'smallestScreenWidthDp', 'screenLayout', 'orientation']) {
                    next[field].value = normal[field].value;
                }
            }
            next.densityDpi.value = dpi;
            if (dpi !== 0) {
                const factor = parent.densityDpi.value / dpi;
                const baseline = bounded ? next : parent;
                const width = baseline.screenWidthDp.value || parent.screenWidthDp.value;
                const height = baseline.screenHeightDp.value || parent.screenHeightDp.value;
                const smallest = baseline.smallestScreenWidthDp.value || Math.min(width, height);
                next.screenWidthDp.value = Math.max(1, Math.round(width * factor));
                next.screenHeightDp.value = Math.max(1, Math.round(height * factor));
                next.smallestScreenWidthDp.value = Math.max(1, Math.round(smallest * factor));
                next.screenLayout.value = framework.Configuration.reduceScreenLayout(
                    framework.layoutBase,
                    Math.max(next.screenWidthDp.value, next.screenHeightDp.value),
                    Math.min(next.screenWidthDp.value, next.screenHeightDp.value));
            } else if (!bounded) {
                for (const field of ['screenWidthDp', 'screenHeightDp', 'smallestScreenWidthDp', 'screenLayout']) next[field].value = 0;
            }
            return next;
        },
        requestedBounds: task => task.getOverrideBounds(),
        isLeaf: () => true,
        isLocked: () => am.isInLockTaskMode(),
        supportsFreeform: () => am.mSupportsFreeformWindowManagement.value
    };
}
