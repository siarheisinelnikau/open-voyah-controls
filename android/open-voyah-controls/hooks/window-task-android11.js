import { launcherPanelEdge } from './window-panel-frame.js';

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
    const WindowState = Java.use('com.android.server.wm.WindowState');
    const reparent = Task.reparent.overload('com.android.server.wm.ActivityStack', 'boolean', 'int', 'boolean', 'boolean', 'java.lang.String');
    const resize = Task.resize.overload('android.graphics.Rect', 'int', 'boolean');
    let removeTask;
    try {
        const method = am.removeTask.overload('int');
        if (method.returnType.className === 'boolean') removeTask = id => method.call(am, id);
    } catch (_) { }
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
    const setBounds = () => member('bounds', () => Java.use('android.app.WindowConfiguration').setBounds
        .overload('android.graphics.Rect'));
    /**
     * Fixes the resource sizes and app bounds of [next] at [dpi] (0: the display's) to what Android 11 computes for a
     * freeform task of [pinned] bounds on [displayId]. Android computes only the values that are not set.
     */
    const pin = (next, dpi, pinned, displayId) => {
        for (const field of SIZES) next[field].value = 0;
        const parent = display(displayId).getConfiguration();
        const density = Math.fround(Math.fround(dpi || parent.densityDpi.value) * Math.fround(0.00625));
        const [left, top, right, bottom] = pinned;
        const width = right - left, height = bottom - top;
        // A freeform task inside its parent is limited to the parent's sizes; the smallest width is not.
        next.screenWidthDp.value = Math.min(dp(width, density), parent.screenWidthDp.value);
        next.screenHeightDp.value = Math.min(dp(height, density), parent.screenHeightDp.value);
        next.smallestScreenWidthDp.value = dp(Math.min(width, height), density);
        setAppBounds().call(next.windowConfiguration.value, Rect.$new(left, top, right, bottom));
        return next;
    };
    /** Task.computeConfigResourceOverrides in Java float arithmetic: px / (dpi * 0.00625f), truncated. */
    const dp = (px, density) => Math.trunc(Math.fround(Math.fround(px) / density));
    const appBoundsOf = config => {
        const rect = config.windowConfiguration.value.getAppBounds();
        return rect === null || rect.isEmpty() ? null : [rect.left.value, rect.top.value, rect.right.value, rect.bottom.value];
    };

    return {
        am,
        removeTask,
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
        setEmbeddedBounds(config, bounds) {
            const window = config.windowConfiguration.value;
            setBounds().call(window, Rect.$new(...bounds));
            window.setWindowingMode(0);
        },
        /**
         * The requested override of [task] at [dpi] (0: the display's).
         *
         * Without [pinned] bounds the resource sizes and app bounds are left for Android to compute. With them they
         * are fixed exactly as Android 11 computes them for a freeform task of those bounds. A multi-window task
         * would otherwise get them like a fullscreen one: the smallest width of the display, a height less the
         * display's insets (including a navigation bar that is reserved but never shown) and app bounds cut to the
         * display's app area. Android computes only the values that are not set. [displayId] is the display the
         * task is on, whose sizes limit a pinned task's.
         */
        densityConfig(task, dpi, pinned = null, displayId = 0) {
            const next = framework.Configuration.$new(getOverride(task));
            next.densityDpi.value = dpi;
            for (const field of SIZES) next[field].value = 0;
            if (pinned === null) {
                if (appBoundsOf(next) !== null) setAppBounds().call(next.windowConfiguration.value, null);
                return next;
            }
            return pin(next, dpi, pinned, displayId);
        },
        /** Whether this release can give an activity bounds of its own (the members exist). */
        activityPlacement() {
            try { setBounds(); setAppBounds(); return true; } catch (_) { return false; }
        },
        /** Whether [record] runs in size compatibility mode: Android then sizes it from its own display copy. */
        sizeCompat: record => record.mCompatDisplayInsets.value !== null,
        /** Whether [record]'s app comes with the firmware (a system app, updated or not). */
        preinstalled: record => (record.info.value.applicationInfo.value.flags.value & 1) !== 0,
        /** The bounds [record] has of its own inside its task, or null while it fills the task. */
        activityBounds(record) {
            const rect = record.getRequestedOverrideBounds();
            return rect === null || rect.isEmpty() ? null
                : [rect.left.value, rect.top.value, rect.right.value, rect.bottom.value];
        },
        /**
         * Gives [record] [bounds] of its own inside its fullscreen task (null: none again), with the resource sizes a freeform task of
         * those bounds gets. Android 11 resolves such an activity's configuration from its own bounds
         * (`ActivityRecord.resolveFullscreenConfiguration`), lays its windows out in them (`WindowState.computeFrameLw`)
         * and letterboxes the rest of the task, the same path it uses for an app's maximum aspect ratio. The task
         * and the other activities in it keep their size. Needs the global lock.
         */
        placeActivity(record, bounds) {
            const next = framework.Configuration.$new(record.getRequestedOverrideConfiguration());
            if (bounds === null) {
                // Back to filling its task, with the sizes Android computes.
                for (const field of SIZES) next[field].value = 0;
                setAppBounds().call(next.windowConfiguration.value, null);
                setBounds().call(next.windowConfiguration.value, null);
            } else {
                pin(next, 0, bounds, record.getDisplayId());
                setBounds().call(next.windowConfiguration.value, Rect.$new(...bounds));
            }
            record.onRequestedOverrideConfigurationChanged(next);
        },
        /** Whether the overrides agree on density, resource sizes and app bounds. */
        resourcesMatch(current, wanted) {
            if (current.densityDpi.value !== wanted.densityDpi.value) return false;
            if (SIZES.some(field => current[field].value !== wanted[field].value)) return false;
            const a = appBoundsOf(current), b = appBoundsOf(wanted);
            return a === null ? b === null : b !== null && a.every((value, index) => value === b[index]);
        },
        /**
         * The bottom edge of the system windows docked at the top of [displayId] over its full width, or 0. On
         * the central screen measure the known `StatusBar1` only. The passenger display's clock bar
         * (`StatusBar2` of the OEM system UI) also uses type 2118, which Android may omit from its status inset.
         * Needs the global lock.
         */
        topBar(displayId) {
            const content = display(displayId);
            if (content === null) return 0;
            const info = content.getDisplayInfo();
            const width = info.logicalWidth.value, height = info.logicalHeight.value;
            const windows = am.mWindowManager.value.mWindowMap.value.values().toArray();
            let bottom = 0;
            for (let i = 0; i < windows.length; i++) {
                const window = Java.cast(windows[i], WindowState);
                if (window.getDisplayId() !== displayId || window.mOwnerUid.value !== 1000 ||
                    window.mActivityRecord.value !== null || !window.isVisibleLw()) continue;
                const type = window.mAttrs.value.type.value;
                if (type < 2000 || type > 2999) continue;
                if (displayId === 0 && (type !== 2118 || String(window.getOwningPackage()) !== 'com.qinggan.systemui' ||
                    String(window.mAttrs.value.getTitle()) !== 'StatusBar1')) continue;
                const frame = window.getFrameLw();
                if (frame.top.value === 0 && frame.left.value <= 0 && frame.right.value >= width &&
                    frame.bottom.value > 0 && frame.bottom.value <= height / 4) {
                    bottom = Math.max(bottom, frame.bottom.value);
                }
            }
            return bottom;
        },
        /** Reads the actual launcher window on demand under the global lock. */
        launcherPanelEdge(frame) {
            const windows = am.mWindowManager.value.mWindowMap.value.values().toArray();
            const candidates = [];
            for (let i = 0; i < windows.length; i++) {
                const window = Java.cast(windows[i], WindowState);
                const attrs = window.mAttrs.value;
                if (attrs.type.value !== 2110 || window.getDisplayId() !== 0 ||
                    String(window.getOwningPackage()) !== 'com.qinggan.app.launcher') continue;
                const rect = window.getFrameLw();
                candidates.push({pkg:'com.qinggan.app.launcher', displayId:0, type:2110,
                    title:String(attrs.getTitle()), visible:window.isVisibleLw(), alive:!window.mRemoved.value,
                    frame:[rect.left.value, rect.top.value, rect.right.value, rect.bottom.value]});
            }
            return launcherPanelEdge(candidates, frame);
        },
        requestedBounds: task => task.getRequestedOverrideBounds(),
        isLeaf: task => task.isLeafTask(),
        isLocked: () => am.isInLockTaskMode(),
        supportsFreeform: () => am.mSupportsFreeformWindowManagement.value
    };
}
