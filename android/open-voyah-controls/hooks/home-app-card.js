import { createActionPreflight } from './action-preflight.js';

const COMPONENT = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+\/[A-Za-z0-9_.$]+$/;
const LAUNCH_TIMEOUT_MS = 7000;
export const HOME_APP_PAIR_REQUEST = 'org.openvoyah.controls.homeAppPairRequest';

export function homeAppComponent(config) {
    if (config?.schema !== 1 || config.restoreIcons !== undefined) return null;
    const component = config.homeCard?.component;
    if (typeof component !== 'string' || component.length > 256 || !COMPONENT.test(component)) return null;
    const pkg = component.split('/')[0];
    return pkg === 'org.openvoyah.controls' || pkg === 'com.qinggan.app.launcher' ? null : component;
}

export function homeAppDpi(config) {
    const dpi = config?.homeCard?.dpi;
    return Number.isInteger(dpi) && dpi >= 100 && dpi <= 320 && dpi % 10 === 0 ? dpi : 160;
}

export function homeAppPairRequest(raw, now) {
    if (raw === null) return null;
    try {
        const request = JSON.parse(String(raw));
        if (request?.schema !== 1 || typeof request.id !== 'string' || !/^[a-f0-9]{32}$/.test(request.id) ||
            !Number.isSafeInteger(request.expires) || request.expires < now || request.expires > now + 6000) return null;
        const component = homeAppComponent({schema: 1, homeCard: {component: request.component}});
        return component === null ? null : {id: request.id, component};
    } catch (_) { return null; }
}

export function canHostHomeApp(taskDisplays) {
    return taskDisplays.length === 0 || (taskDisplays.length === 1 && taskDisplays[0] === 0);
}

// Android owns the app window and routes input through ActivityView's surface.
export function createHomeAppCard(Java, report) {
    if (Java.use('android.os.Build$VERSION').SDK_INT.value !== 30) return null;
    const ActivityView = Java.use('android.app.ActivityView');
    const ActivityManager = Java.use('android.app.ActivityManager');
    const RunningTask = Java.use('android.app.ActivityManager$RunningTaskInfo');
    const ComponentName = Java.use('android.content.ComponentName');
    const Intent = Java.use('android.content.Intent');
    const Clock = Java.use('android.os.SystemClock');
    const SurfaceView = Java.use('android.view.SurfaceView');
    const taskDisplay = RunningTask.class.getField('displayId');
    const launchable = createActionPreflight(Java, intent => intent);
    let active = null;

    function intentFor(context, target) {
        const component = ComponentName.unflattenFromString(target);
        if (component === null) return null;
        const intent = Intent.makeMainActivity(component);
        return launchable(context, intent) ? intent : null;
    }

    function displays(context, pkg) {
        const manager = Java.cast(context.getSystemService(ActivityManager.class), ActivityManager);
        const tasks = manager.getRunningTasks(64);
        const result = [];
        for (let i = 0; i < tasks.size(); i++) {
            const task = Java.cast(tasks.get(i), RunningTask);
            const matches = component => component !== null && String(component.getPackageName()) === pkg;
            if (task.numActivities.value <= 0) continue;
            if (matches(task.baseActivity.value)) result.push(taskDisplay.getInt(task));
            else if (matches(task.topActivity.value)) result.push(-1);
        }
        return result;
    }

    return {
        canLaunch(context, target) { return intentFor(context, target) !== null; },
        create(card, target, radius) {
            const display = card.getDisplay();
            if (active !== null || !card.isAttachedToWindow() || !card.isShown() ||
                !card.hasWindowFocus() || card.getWindowVisibility() !== 0 || display === null || display.getDisplayId() !== 0 ||
                card.getWidth() <= 0 || card.getHeight() <= 0) return null;
            const context = card.getContext();
            const pkg = target.split('/')[0];
            // Once Home takes focus, Android can reuse a single task from this same screen.
            if (!canHostHomeApp(displays(context, pkg))) return null;
            const view = ActivityView.$new(context);
            try {
                const surface = view.getChildAt(0);
                if (surface === null || !SurfaceView.class.isInstance(surface)) {
                    throw new Error('The ActivityView has no SurfaceView');
                }
                Java.cast(surface, SurfaceView).setZOrderOnTop(true);
                view.setCornerRadius(radius);
                view.setAlpha(0);
                const host = {view: Java.retain(view), target, pkg, deadline: Number(Clock.elapsedRealtime()) + LAUNCH_TIMEOUT_MS,
                    display: -1, ready: false, failed: false};
                active = host;
                return host;
            } catch (error) {
                view.release();
                throw error;
            }
        },
        update(card, host, dpi) {
            if (!card.isAttachedToWindow() || host.view.getParent() === null) return false;
            try {
                const {view, pkg} = host;
                const context = card.getContext();
                const taskDisplays = displays(context, pkg);
                const virtual = view.getVirtualDisplay();
                if (virtual !== null && host.dpi !== dpi && card.getWidth() > 0 && card.getHeight() > 0) {
                    virtual.resize(card.getWidth(), card.getHeight(), dpi);
                    host.dpi = dpi;
                }
                if (host.ready) return taskDisplays.includes(host.display);
                if (Number(Clock.elapsedRealtime()) >= host.deadline) {
                    host.failed = true;
                    console.log('OVC_HOME_APP outcome=timeout');
                    return false;
                }
                if (host.display < 0) {
                    if (!card.isShown() || !card.hasWindowFocus() || card.getWindowVisibility() !== 0) return false;
                    if (virtual === null) return true;
                    const display = virtual.getDisplay().getDisplayId();
                    const intent = intentFor(context, host.target);
                    const manager = Java.cast(context.getSystemService(ActivityManager.class), ActivityManager);
                    if (!canHostHomeApp(taskDisplays)) return false;
                    if (display <= 0 || intent === null || !manager.isActivityStartAllowedOnDisplay(context, display, intent)) {
                        host.failed = true;
                        console.log('OVC_HOME_APP outcome=denied');
                        return false;
                    }
                    host.display = display;
                    try { view.startActivity.overload('android.content.Intent').call(view, intent); }
                    catch (error) {
                        // The OEM can report an error after moving the task. Confirm where it landed before release.
                        report('app_launch', error);
                    }
                } else if (taskDisplays.includes(host.display)) {
                    view.setAlpha(1);
                    host.ready = true;
                    console.log('OVC_HOME_APP outcome=ready');
                }
                return true;
            } catch (error) {
                host.failed = true;
                report('app_host', error);
                return false;
            }
        },
        release(view) {
            if (!ActivityView.class.isInstance(view)) return;
            Java.cast(view, ActivityView).release();
            if (active?.view.equals(view)) active = null;
        }
    };
}
