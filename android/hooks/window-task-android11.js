export function android11Tasks(Java, framework) {
    const am = Java.cast(Java.use('android.app.ActivityTaskManager').getService(),
        Java.use('com.android.server.wm.ActivityTaskManagerService'));
    const root = am.mRootWindowContainer.value;
    const display = (id = 0) => root.getDisplayContent(id);
    const lookup = root.anyTaskForId.overload('int', 'int');
    const attached = Java.use('com.android.server.wm.RootWindowContainer').MATCH_TASK_IN_STACKS_ONLY.value;
    const Task = Java.use('com.android.server.wm.Task');
    const activity = Java.use('com.android.server.wm.ActivityRecord');
    const Stack = Java.use('com.android.server.wm.ActivityStack');
    const Rect = Java.use('android.graphics.Rect');
    const reparent = Task.reparent.overload('com.android.server.wm.ActivityStack', 'boolean', 'int', 'boolean', 'boolean', 'java.lang.String');
    const resize = Task.resize.overload('android.graphics.Rect', 'int', 'boolean');
    const getOverride = task => task.getRequestedOverrideConfiguration();
    const SIZES = ['screenWidthDp', 'screenHeightDp', 'smallestScreenWidthDp', 'screenLayout'];
    // Members used only by the corrections below, looked up once on first use. A firmware without one of them keeps
    // Android's own behaviour for that correction (null: looked up and missing).
    const members = new Map();
    const member = (name, find) => {
        if (!members.has(name)) {
            try { members.set(name, find()); } catch (_) { members.set(name, null); }
        }
        const found = members.get(name);
        if (found === null) throw Error('Missing framework member: ' + name);
        return found;
    };
    const navigationInsets = () => member('insets', () => Java.use('com.android.server.wm.DisplayPolicy')
        .getNonDecorInsetsLw.overload('int', 'int', 'int', 'android.view.DisplayCutout', 'android.graphics.Rect'));
    const navigationSource = () => member('source', () => Java.use('android.view.InsetsState').ITYPE_NAVIGATION_BAR.value);
    const ensureVisible = () => member('visible', () => root.ensureActivitiesVisible
        .overload('com.android.server.wm.ActivityRecord', 'int', 'boolean'));
    const setAppBounds = () => member('appBounds', () => Java.use('android.app.WindowConfiguration').setAppBounds
        .overload('android.graphics.Rect'));
    /** Task.computeConfigResourceOverrides in Java float arithmetic: px / (dpi * 0.00625f), truncated. */
    const dp = (px, density) => Math.trunc(Math.fround(Math.fround(px) / density));
    const appBoundsOf = config => {
        const rect = config.windowConfiguration.value.getAppBounds();
        return rect === null || rect.isEmpty() ? null : [rect.left.value, rect.top.value, rect.right.value, rect.bottom.value];
    };

    return {
        am,
        lock(fn) {
            let result;
            Java.synchronized(am.mGlobalLock.value, () => { result = fn(); });
            return result;
        },
        windowManager: am.mWindowManager.value,
        /**
         * Writes into [out] the insets of a navigation bar that display 0 reserves without a window providing it,
         * and returns true; false when the display has no such bar. A bar counts as present when the policy has a
         * navigation bar window or any window provides the navigation insets source. Without a cutout, the
         * non-decor insets are the navigation bar's alone.
         */
        unusedNavigationBar(out) {
            let unused = false;
            Java.synchronized(am.mGlobalLock.value, () => {
                const content = display();
                const policy = content?.getDisplayPolicy();
                if (!policy || !policy.hasNavigationBar() || policy.getNavigationBar() !== null) return;
                const source = content.getInsetsStateController().peekSourceProvider(navigationSource());
                if (source !== null && source.hasWindow()) return;
                const info = content.getDisplayInfo();
                navigationInsets().call(policy, info.rotation.value, info.logicalWidth.value, info.logicalHeight.value,
                    null, out);
                unused = true;
            });
            return unused;
        },
        activity,
        display,
        androidDisplay: id => display(id)?.getDisplay() ?? null,
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
        /**
         * Multi-window keeps the bounds it is given (no fitting into the display as for freeform) and draws no
         * caption bar. A registered task organizer for that mode would take the task over, so none may exist.
         */
        multiWindowAvailable() {
            // A placed multi-window task needs its resource sizes fixed (densityConfig); without that, stay freeform.
            setAppBounds();
            return am.mSupportsMultiWindow.value &&
                am.mTaskOrganizerController.value.getTaskOrganizer(framework.multiWindow) === null;
        },
        /**
         * Runs Android's visibility pass when home is the focused task of display 0, and says whether it did. Needs
         * the global lock. A freeform or multi-window task does not cover the tasks below it, so home stays
         * resumed behind it. A later start of home then finds it resumed and returns before that pass. The task
         * that was in front keeps being drawn, and above home, because home tasks are layered below application
         * tasks. The focused task, not the top one: a picture-in-picture or other always-on-top task stays above
         * home but never takes focus from it.
         */
        revealHome() {
            const focused = display().getFocusedStack();
            if (focused === null || !focused.isActivityTypeHome()) return false;
            ensureVisible().call(root, null, 0, false);
            return true;
        },
        place(task, bounds, placed = framework.freeform) {
            const mode = bounds === null ? framework.fullscreen : placed;
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
        /**
         * The requested override of [task] at [dpi] (0: the display's).
         *
         * Without [pinned] bounds the resource sizes and app bounds are left for Android to compute. With them they
         * are fixed exactly as Android 11 computes them for a freeform task of those bounds. A multi-window task
         * would otherwise get them like a fullscreen one: the smallest width of the display, a height less the
         * display's insets (including a navigation bar that is reserved but never shown) and app bounds cut to the
         * display's app area. Android computes only the values that are not set.
         */
        densityConfig(task, dpi, pinned = null) {
            const next = framework.Configuration.$new(getOverride(task));
            next.densityDpi.value = dpi;
            for (const field of SIZES) next[field].value = 0;
            const windowConfiguration = next.windowConfiguration.value;
            if (pinned === null) {
                if (appBoundsOf(next) !== null) setAppBounds().call(windowConfiguration, null);
                return next;
            }
            const parent = display().getConfiguration();
            const density = Math.fround(Math.fround(dpi || parent.densityDpi.value) * Math.fround(0.00625));
            const [left, top, right, bottom] = pinned;
            const width = right - left, height = bottom - top;
            // A freeform task inside its parent is limited to the parent's sizes; the smallest width is not.
            next.screenWidthDp.value = Math.min(dp(width, density), parent.screenWidthDp.value);
            next.screenHeightDp.value = Math.min(dp(height, density), parent.screenHeightDp.value);
            next.smallestScreenWidthDp.value = dp(Math.min(width, height), density);
            setAppBounds().call(windowConfiguration, Rect.$new(left, top, right, bottom));
            return next;
        },
        /** Whether the overrides agree on density, resource sizes and app bounds. */
        resourcesMatch(current, wanted) {
            if (current.densityDpi.value !== wanted.densityDpi.value) return false;
            if (SIZES.some(field => current[field].value !== wanted[field].value)) return false;
            const a = appBoundsOf(current), b = appBoundsOf(wanted);
            return a === null ? b === null : b !== null && a.every((value, index) => value === b[index]);
        },
        requestedBounds: task => task.getRequestedOverrideBounds(),
        isLeaf: task => task.isLeafTask(),
        isLocked: () => am.isInLockTaskMode(),
        supportsFreeform: () => am.mSupportsFreeformWindowManagement.value
    };
}
