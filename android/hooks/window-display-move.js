import { MOTION, followOffset, swipeDestination, swipeRecognizer } from './window-gesture.js';

/** The instrument cluster shows this virtual display in its navigation view; the cluster app creates it. */
export const CLUSTER_NAVI_DISPLAY = 'Cluster-Navi-Display';
export const CLUSTER_PACKAGE = 'com.qinggan.cluster';
const SYSTEM_UID = 1000;

/** Windows that never leave the central display, beyond the packages the profile policy already protects. */
const staying = new Set(['com.qinggan.systemui', 'com.android.systemui', 'com.android.permissioncontroller',
    'com.android.packageinstaller', 'com.qinggan.instrumentcard', 'com.qinggan.app.hiboard']);

/**
 * Android 11 (R) members for moving a task between displays. Throws when one is missing, so the agent never
 * announces the move on a firmware that lacks it. Each member was checked against the supported head unit's
 * services.jar.
 */
export function android11DisplayMoves(Java) {
    const am = Java.cast(Java.use('android.app.ActivityTaskManager').getService(),
        Java.use('com.android.server.wm.ActivityTaskManagerService'));
    const root = am.mRootWindowContainer.value;
    const Stack = Java.use('com.android.server.wm.ActivityStack');
    const Content = Java.use('com.android.server.wm.DisplayContent');
    const Display = Java.use('android.view.Display');
    const Transaction = Java.use('android.view.SurfaceControl$Transaction');
    // The pointer listener every display already has. Watching its events consumes none of them: the app under
    // the fingers keeps receiving the whole gesture, as with any input monitor.
    const listener = Java.use('com.android.server.wm.TaskTapPointerEventListener').onPointerEvent
        .overload('android.view.MotionEvent');
    const move = root.moveStackToDisplay.overload('int', 'int', 'boolean');
    const touched = Content.getTouchableWinAtPointLocked.overload('float', 'float');
    const getX = Java.use('android.view.MotionEvent').getX.overload('int');
    const getY = Java.use('android.view.MotionEvent').getY.overload('int');
    const privateFlag = Display.FLAG_PRIVATE.value;
    const virtualType = Display.TYPE_VIRTUAL.value;
    const standardType = Java.use('android.app.WindowConfiguration').ACTIVITY_TYPE_STANDARD.value;
    const content = id => root.getDisplayContent(id);

    return {
        listener,
        /** Calls [callback](displayId, motion) for each pointer event of every display until [unlisten]. */
        listen(callback) {
            listener.implementation = function (motion) {
                try { callback(this.mDisplayContent.value.getDisplayId(), motion); } catch (_) { }
                return listener.call(this, motion);
            };
        },
        unlisten() { listener.implementation = null; },
        /** The logical width of [displayId], or 0 when it is not attached. */
        width: displayId => content(displayId)?.getDisplayInfo().logicalWidth.value ?? 0,
        event(motion, withPoints) {
            const action = motion.getActionMasked();
            const count = motion.getPointerCount();
            const sample = {action, count, time: Number(motion.getEventTime()), points: []};
            if (withPoints) {
                for (let i = 0; i < count; i++) {
                    sample.points.push({id: motion.getPointerId(i), x: getX.call(motion, i), y: getY.call(motion, i)});
                }
            }
            return sample;
        },
        lock(fn) {
            let result;
            Java.synchronized(am.mGlobalLock.value, () => { result = fn(); });
            return result;
        },
        user: () => am.getCurrentUserId(),
        /** The task whose window takes a touch at x, y, or null. Needs the global lock. */
        taskAt(displayId, x, y) {
            const display = content(displayId);
            const window = display === null ? null : touched.call(display, x, y);
            return window === null ? null : window.getTask();
        },
        describe(task) {
            const stack = Java.cast(task.getStack(), Stack);
            const component = task.realActivity.value;
            const top = task.getTopNonFinishingActivity();
            return {
                taskId: task.mTaskId.value,
                display: task.getDisplayId(),
                user: task.mUserId.value,
                component: component === null ? null : String(component.flattenToString()),
                topPackage: top === null ? null : String(top.packageName.value),
                standard: task.getActivityType() === standardType,
                leaf: task.isLeafTask(),
                alwaysOnTop: stack.isAlwaysOnTop(),
                mode: task.getWindowingMode(),
            };
        },
        find: id => root.anyTaskForId(id),
        stackOf: task => Java.cast(task.getStack(), Stack),
        /** Moves the task's root task to the top of [displayId]. Needs the global lock. */
        moveTask(task, displayId) {
            const stack = Java.cast(task.getStack(), Stack);
            move.call(root, stack.getRootTaskId(), displayId, true);
            return task.getDisplayId() === displayId;
        },
        /** The cluster's navigation display: owned by the cluster app running as the system user, never by an app. */
        clusterDisplay() {
            for (let i = 0, count = root.getChildCount(); i < count; i++) {
                const display = Java.cast(root.getChildAt(i), Content).getDisplay();
                if (display !== null && String(display.getName()) === CLUSTER_NAVI_DISPLAY &&
                    String(display.getOwnerPackageName()) === CLUSTER_PACKAGE && display.getOwnerUid() === SYSTEM_UID) {
                    return display.getDisplayId();
                }
            }
            return null;
        },
        /** Whether [displayId] is an attached public screen (the passenger display is an HDMI panel). */
        publicDisplay(displayId) {
            const display = content(displayId)?.getDisplay() ?? null;
            return display !== null && (display.getFlags() & privateFlag) === 0 && display.getType() !== virtualType;
        },
        /** Lets the window follow the fingers: shifts and fades the task's root surface, nothing else. */
        follow(stack, dx, alpha) {
            const surface = stack.getSurfaceControl();
            if (surface === null) return;
            const origin = stack.mLastSurfacePosition.value;
            const transaction = Transaction.$new();
            try {
                transaction.setPosition(surface, origin.x.value + dx, origin.y.value);
                transaction.setAlpha(surface, alpha);
                transaction.apply();
            } finally {
                transaction.close();
            }
        },
    };
}

/** Whether a task described by [info] may leave its display. [componentKey] rejects the protected packages. */
export function movableTask(info, user, componentKey) {
    if (!info || info.user !== user || !info.standard || !info.leaf || info.alwaysOnTop || info.display < 0) return false;
    const key = info.component === null ? null : componentKey(info.component);
    if (!key) return false;
    const pkg = key.split('/')[0];
    return !staying.has(pkg) && (info.topPackage === null || info.topPackage === pkg);
}

/**
 * The swipe that moves app windows between the central, cluster and passenger displays. [api] is
 * android11DisplayMoves (or a test double). [prepare](task, info) gives a task its native placement back before it
 * leaves the central display; [placed](component, user, taskId, displayId) applies the window policy once it has
 * arrived. [schedule] runs work outside the input thread. Nothing is watched until [sync] receives settings.
 */
export function displayMover({api, componentKey, prepare, placed, schedule, report, announce = () => {}}) {
    let settings = null;
    let watching = false;
    const gestures = new Map();
    const recognizers = new Map();
    /** taskId -> {to, component, user}: tasks this agent moved off the central display. */
    const moved = new Map();

    /** displayId -> logical width, measured when the settings arrive; only these displays are watched. */
    let widths = new Map();
    const recognizer = displayId => {
        let entry = recognizers.get(displayId);
        if (entry === undefined) {
            entry = swipeRecognizer(settings.fingers, widths.get(displayId));
            recognizers.set(displayId, entry);
        }
        return entry;
    };

    function settle(gesture) {
        if (!gesture?.target?.follows || !gesture.shifted) return;
        gesture.shifted = false;
        try { api.follow(gesture.target.stack, 0, 1); } catch (error) { report('move_feedback', error); }
    }

    /** Runs on the input thread for every pointer event of a watched display. Never throws. */
    function onEvent(displayId, motion) {
        try {
            if (settings === null || !widths.has(displayId)) return;
            const swipe = recognizer(displayId);
            const action = motion.getActionMasked();
            const sample = api.event(motion, swipe.wantsPoints(action, motion.getPointerCount()));
            const outcome = swipe.feed(sample);
            if (outcome === null) return;
            if (outcome.type === 'arm') {
                const gesture = {display: displayId, x: outcome.x, y: outcome.y, target: undefined, shifted: false};
                gestures.set(displayId, gesture);
                schedule(() => resolve(gesture));
            } else if (outcome.type === 'drag') {
                const gesture = gestures.get(displayId);
                const target = gesture?.target;
                const to = outcome.dx < 0 ? target?.left : target?.right;
                if (!target?.follows || to == null) { settle(gesture); return; }
                gesture.shifted = true;
                api.follow(target.stack, followOffset(outcome.dx, widths.get(displayId)), 0.85);
            } else {
                const gesture = gestures.get(displayId);
                gestures.delete(displayId);
                settle(gesture);
                if (outcome.type === 'commit' && gesture) schedule(() => finish(gesture, outcome.direction));
            }
        } catch (error) { report('move_input', error); }
    }

    /** Finds what the fingers landed on and where it could go. Runs once per armed swipe. */
    function resolve(gesture) {
        try {
            if (settings === null) return;
            const current = settings;
            gesture.target = api.lock(() => {
                const cluster = api.clusterDisplay();
                const passenger = api.publicDisplay(current.passenger) ? current.passenger : null;
                const task = api.taskAt(gesture.display, gesture.x, gesture.y);
                const info = task === null ? null : api.describe(task);
                const app = movableTask(info, api.user(), componentKey) && info.display === gesture.display;
                const where = direction => swipeDestination({direction, display: gesture.display, app, cluster, passenger});
                const left = where('left'), right = where('right');
                return {app, info, cluster, passenger, follows: app, stack: app ? api.stackOf(task) : null,
                    left: left?.action === 'move' ? left.to : null, right: right?.action === 'move' ? right.to : null,
                    recall: right?.action === 'recall'};
            });
        } catch (error) {
            gesture.target = null;
            report('move_target', error);
        }
    }

    function finish(gesture, direction) {
        if (settings === null) return;
        if (gesture.target === undefined) resolve(gesture);
        const target = gesture.target;
        if (!target) return;
        if (direction === 'right' && target.recall) {
            recallFrom(target.cluster, 'swipe');
            return;
        }
        const to = direction === 'left' ? target.left : target.right;
        if (to == null) return;
        moveTo(target.info, to, 'swipe');
    }

    /** Moves the task described by [info] to [to]. A task already on the cluster first returns to the centre. */
    function moveTo(info, to, reason) {
        let outcome;
        try {
            outcome = api.lock(() => {
                const task = api.find(info.taskId);
                const now = task === null ? null : api.describe(task);
                if (!now || now.display !== info.display || !movableTask(now, api.user(), componentKey)) return 'changed';
                if (to !== 0) {
                    for (const [taskId, entry] of moved) {
                        if (entry.to === to && taskId !== info.taskId) returnTask(taskId, entry);
                    }
                }
                if (info.display === 0) prepare(task, now);
                if (!api.moveTask(task, to)) return 'refused';
                if (to === 0) moved.delete(info.taskId);
                else moved.set(info.taskId, {to, component: now.component, user: now.user});
                return 'moved';
            });
        } catch (error) {
            outcome = 'failed';
            report('move', error);
        }
        announce('OVC_WINDOW_MOVE from=' + info.display + ' to=' + to + ' reason=' + reason + ' outcome=' + outcome);
        if (outcome === 'moved') placed(info.component, info.user, info.taskId, to);
        return outcome;
    }

    /** Needs the global lock. Brings a moved task back to the central display if it is still where it was put. */
    function returnTask(taskId, entry) {
        moved.delete(taskId);
        const task = api.find(taskId);
        if (task === null || api.describe(task).display !== entry.to) return false;
        if (!api.moveTask(task, 0)) throw Object.assign(Error('Task stayed on its display'), {code: 'rollback_failed'});
        schedule(() => placed(entry.component, entry.user, taskId, 0));
        return true;
    }

    function recallFrom(display, reason) {
        let count = 0;
        try {
            api.lock(() => {
                for (const [taskId, entry] of [...moved]) {
                    if (display === undefined || entry.to === display) count += returnTask(taskId, entry) ? 1 : 0;
                }
            });
        } catch (error) {
            report('move_recall', error);
            announce('OVC_WINDOW_MOVE to=0 reason=' + reason + ' outcome=failed');
            return false;
        }
        if (count) announce('OVC_WINDOW_MOVE to=0 reason=' + reason + ' outcome=returned count=' + count);
        return true;
    }

    return {
        onEvent,
        /** [next]: null (off) or {fingers: 2 | 3, passenger: displayId}. */
        sync(next) {
            const measured = next === null ? new Map() : api.lock(() => {
                const found = new Map();
                for (const displayId of [0, next.passenger]) {
                    const width = displayId === 0 || api.publicDisplay(displayId) ? api.width(displayId) : 0;
                    if (width > 0) found.set(displayId, width);
                }
                return found;
            });
            const same = JSON.stringify(next) === JSON.stringify(settings) &&
                JSON.stringify([...measured]) === JSON.stringify([...widths]);
            if (same) return;
            for (const gesture of gestures.values()) settle(gesture);
            gestures.clear();
            recognizers.clear();
            settings = next;
            widths = measured;
            if (next !== null && !watching) {
                api.listen(onEvent);
                watching = true;
            } else if (next === null && watching) {
                api.unlisten();
                watching = false;
                recallFrom(undefined, 'off');
            }
        },
        get watching() { return watching; },
        /** Every moved window back to the central display; true when none was left behind. */
        recall: () => recallFrom(undefined, 'request'),
        release() {
            settings = null;
            widths = new Map();
            if (watching) api.unlisten();
            watching = false;
            for (const gesture of gestures.values()) settle(gesture);
            gestures.clear();
            return recallFrom(undefined, 'release');
        },
        moved: () => moved.size,
    };
}
