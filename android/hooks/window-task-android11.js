export function android11Tasks(Java, framework) {
    const am = Java.cast(Java.use('android.app.ActivityTaskManager').getService(),
        Java.use('com.android.server.wm.ActivityTaskManagerService'));
    const root = am.mRootWindowContainer.value;
    const display = () => root.getDisplayContent(0);
    const lookup = root.anyTaskForId.overload('int', 'int');
    const attached = Java.use('com.android.server.wm.RootWindowContainer').MATCH_TASK_IN_STACKS_ONLY.value;
    const Task = Java.use('com.android.server.wm.Task');
    const activity = Java.use('com.android.server.wm.ActivityRecord');
    const Stack = Java.use('com.android.server.wm.ActivityStack');
    const reparent = Task.reparent.overload('com.android.server.wm.ActivityStack', 'boolean', 'int', 'boolean', 'boolean', 'java.lang.String');
    const resize = Task.resize.overload('android.graphics.Rect', 'int', 'boolean');
    const getOverride = task => task.getRequestedOverrideConfiguration();

    return {
        am,
        lock(fn) {
            let result;
            Java.synchronized(am.mGlobalLock.value, () => { result = fn(); });
            return result;
        },
        windowManager: am.mWindowManager.value,
        activity,
        display,
        user: () => am.getCurrentUserId(),
        userOf: record => record.mUserId.value,
        find: id => lookup.call(root, id, attached),
        id: task => task.mTaskId.value,
        runningId: info => info.taskId.value,
        getOverride,
        minimum: task => {
            const defaultSize = Math.round(root.mDefaultMinSizeOfResizeableTaskDp.value * display().getConfiguration().densityDpi.value / 160);
            return [task.mMinWidth.value > 0 ? task.mMinWidth.value : defaultSize,
                task.mMinHeight.value > 0 ? task.mMinHeight.value : defaultSize];
        },
        place(task, bounds) {
            const mode = bounds === null ? framework.fullscreen : framework.freeform;
            if (task.getWindowingMode() !== mode) {
                const stack = Java.cast(task.getStack(), Stack);
                if (stack.equals(task) && task.isLeafTask()) {
                    stack.setWindowingMode(mode);
                } else {
                    const target = task.getDisplayArea().getOrCreateStack(mode, task.getActivityType(), false);
                    reparent.call(task, target, true, Task.REPARENT_KEEP_STACK_AT_FRONT.value, false, false, 'OpenVoyahControls');
                }
            }
            if (task.getWindowingMode() === mode) resize.call(task, bounds, framework.resizeSystem, false);
        },
        setOverride(task, config) {
            task.onRequestedOverrideConfigurationChanged(config);
        },
        densityConfig(task, dpi) {
            const next = framework.Configuration.$new(getOverride(task));
            next.densityDpi.value = dpi;
            for (const field of ['screenWidthDp', 'screenHeightDp', 'smallestScreenWidthDp', 'screenLayout']) next[field].value = 0;
            return next;
        },
        requestedBounds: task => task.getRequestedOverrideBounds(),
        isLeaf: task => task.isLeafTask(),
        isLocked: () => am.isInLockTaskMode(),
        supportsFreeform: () => am.mSupportsFreeformWindowManagement.value
    };
}
