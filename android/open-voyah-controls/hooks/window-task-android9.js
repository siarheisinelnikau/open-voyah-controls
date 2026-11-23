export function android9Tasks(Java, framework) {
    const am = Java.cast(Java.use('android.app.ActivityManager').getService(),
        Java.use('com.android.server.am.ActivityManagerService'));
    const supervisor = am.mStackSupervisor.value;
    const display = (id = 0) => supervisor.getActivityDisplay(id);
    const lookup = supervisor.anyTaskForIdLocked.overload('int', 'int');
    const attached = Java.use('com.android.server.am.ActivityStackSupervisor').MATCH_TASK_IN_STACKS_ONLY.value;
    const activity = Java.use('com.android.server.am.ActivityRecord');
    const Stack = Java.use('com.android.server.am.ActivityStack');
    const Task = Java.use('com.android.server.am.TaskRecord');
    const reparent = Task.reparent.overload('com.android.server.am.ActivityStack', 'boolean', 'int', 'boolean', 'boolean', 'java.lang.String');
    const resize = Task.resize.overload('android.graphics.Rect', 'int', 'boolean', 'boolean');

    const getOverride = task => task.getOverrideConfiguration();
    const empty = rect => rect === null || rect.isEmpty();
    let policyApi;
    // Looked up once on first use (null: missing). A firmware without these exact members keeps its stable insets.
    const navigationPolicy = () => {
        if (policyApi === undefined) {
            try {
                const Policy = Java.use('com.android.server.policy.PhoneWindowManager');
                policyApi = {Policy, insets: Policy.getNonDecorInsetsLw.overload('int', 'int', 'int',
                    'android.view.DisplayCutout', 'android.graphics.Rect')};
            } catch (_) { policyApi = null; }
        }
        if (policyApi === null) throw Error('Navigation bar policy unavailable');
        return policyApi;
    };
    return {
        am,
        lock(fn) {
            let result;
            Java.synchronized(am, () => { result = fn(); });
            return result;
        },
        windowManager: am.mWindowManager.value,
        /**
         * Writes into [out] the insets of a navigation bar that display 0 reserves without a window providing it,
         * and returns true; false when the display has no such bar. Screen decor windows count as bars too: an
         * automotive system UI of this release reserves its bars that way. Without a cutout, the non-decor insets
         * are the navigation bar's alone. Takes the window manager's lock after the activity manager's, as Android
         * does.
         */
        unusedNavigationBar(out) {
            const wm = am.mWindowManager.value;
            const {Policy, insets} = navigationPolicy();
            let unused = false;
            Java.synchronized(wm.mWindowMap.value, () => {
                const policy = Java.cast(wm.mPolicy.value, Policy);
                if (!policy.mHasNavigationBar.value || policy.mNavigationBar.value !== null ||
                    !policy.mScreenDecorWindows.value.isEmpty()) return;
                const content = wm.mRoot.value.getDisplayContent(0);
                if (content === null) return;
                const info = content.getDisplayInfo();
                insets.call(policy, info.rotation.value, info.logicalWidth.value, info.logicalHeight.value, null, out);
                unused = true;
            });
            return unused;
        },
        activity,
        display,
        androidDisplay: id => display(id)?.mDisplay.value ?? null,
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
        // Android 9 has no multi-window mode with bounds of its own; placed tasks stay freeform.
        multiWindowAvailable: () => false,
        // Android 9 resumes only the focused stack, so starting home always takes the full resume path, which
        // includes the visibility pass.
        revealHome: () => false,
        place(task, bounds, placed = framework.freeform) {
            const mode = bounds === null ? framework.fullscreen : placed;
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
        // Android 9 computes the sizes of a placed (freeform) task in densityConfig itself; only density decides.
        resourcesMatch: (current, wanted) => current.densityDpi.value === wanted.densityDpi.value,
        requestedBounds: task => task.getOverrideBounds(),
        isLeaf: () => true,
        isLocked: () => am.isInLockTaskMode(),
        supportsFreeform: () => am.mSupportsFreeformWindowManagement.value
    };
}
