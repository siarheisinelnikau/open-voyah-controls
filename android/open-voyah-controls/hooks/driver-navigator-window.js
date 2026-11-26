import { driverMediaGeometry } from './driver-media-layout.js';
import { createActionPreflight } from './action-preflight.js';

export const DRIVER_NAVIGATOR_REQUEST = 'org.openvoyah.controls.driverNavigatorRequest';
const NAVIGATOR = 'ru.yandex.yandexnavi';
const DISPLAY_OWNER = 'com.qinggan.cluster';
const SYSTEM_UID = 1000;
const FIRST_FRAME_MS = 6500;
const COMMAND_TTL_MS = 4000;
const COVER_TAG = 'org.openvoyah.controls.driver-navigator-window';
const HANDLES_TAG = 0x7f0f0760;
const EXACTLY = 0x40000000;
// Android 11 DisplayManager flags. Use a private, own-content-only display and destroy its task on release.
const DISPLAY_FLAGS = (1 << 3) | (1 << 8) | (1 << 10);

export function driverNavigatorRequest(raw, now) {
    if (raw === null) return null;
    try {
        const request = JSON.parse(String(raw));
        if (request?.schema !== 1 || typeof request.id !== 'string' || !/^[a-f0-9]{32}$/.test(request.id) ||
            !['open', 'close'].includes(request.operation) || !Number.isSafeInteger(request.expires) ||
            request.expires < now || request.expires > now + COMMAND_TTL_MS) return null;
        return request;
    } catch (_) { return null; }
}

/** One explicit launch at a time. A suspended, failed or retired session can only be opened again by the user. */
export function driverNavigatorSession(platform, reply, report) {
    let active = null;
    let seen = null;
    let ready = false;

    function finish(status) {
        if (active === null) return true;
        active.closing = true;
        active.finalStatus ??= status;
        try { platform.close(active.window); }
        catch (error) {
            report('navigator_close', error);
            return false;
        }
        const {request, finalStatus} = active;
        active = null;
        if (request !== null) reply(request, finalStatus);
        return true;
    }

    return {
        get mounted() { return active !== null; },
        get showing() { return active !== null && active.request === null && !active.closing; },
        reset(raw, now) {
            // An agent that attaches again must not replay a launch left in Settings by its predecessor.
            seen = driverNavigatorRequest(raw, now)?.id ?? null;
            ready = true;
        },
        close: () => finish('cancelled'),
        sync(raw, host, permitted, now) {
            if (!ready) return;
            const request = driverNavigatorRequest(raw, now);
            const command = request !== null && request.id !== seen ? request : null;
            if (command !== null) seen = command.id;
            if (command?.operation === 'close') {
                reply(command.id, finish('cancelled') ? 'restored' : 'failed');
                return;
            }
            if (!permitted) {
                finish('cancelled');
                if (command !== null) reply(command.id, 'unavailable');
                return;
            }
            if (active !== null) {
                try {
                    const status = active.closing ? 'cancelled' : platform.poll(active.window, host);
                    if (status === 'shown' && active.request !== null) {
                        reply(active.request, 'shown');
                        active.request = null;
                    } else if (status !== 'shown' && status !== 'waiting') {
                        finish(status);
                    } else if (active.request !== null && now >= active.deadline) {
                        finish('no_frame');
                    }
                } catch (error) {
                    report('navigator_poll', error);
                    finish('failed');
                }
            }
            if (command === null) return;
            if (active !== null) {
                reply(command.id, active.request === null && !active.closing ? 'shown' : 'busy');
                return;
            }
            try {
                const window = platform.mount(host);
                if (typeof window === 'string') reply(command.id, window);
                else active = {window, request: command.id, deadline: now + FIRST_FRAME_MS, closing: false};
            } catch (error) {
                report('navigator_mount', error);
                reply(command.id, 'failed');
            }
        },
    };
}

export function createDriverNavigatorWindow(Java) {
    if (Java.use('android.os.Build$VERSION').SDK_INT.value !== 30) return null;
    const Process = Java.use('android.os.Process');
    const Clock = Java.use('android.os.SystemClock');
    const Rect = Java.use('android.graphics.Rect');
    const Point = Java.use('android.graphics.Point');
    const Options = Java.use('android.app.ActivityOptions');
    const TextureView = Java.use('android.view.TextureView');
    const Overlay = Java.use('android.view.ViewOverlay').getOverlayView.overload();
    const Surface = Java.use('android.view.Surface');
    const VD = Java.use('android.hardware.display.VirtualDisplay');
    const DisplayManager = Java.use('android.hardware.display.DisplayManager');
    const Activities = Java.use('android.app.ActivityManager');
    const RunningTask = Java.use('android.app.ActivityManager$RunningTaskInfo');
    const taskDisplay = RunningTask.class.getField('displayId');
    const Power = Java.use('android.os.PowerManager');
    const JString = Java.use('java.lang.String');
    const ArrayList = Java.use('java.util.ArrayList');
    const tag = JString.$new(COVER_TAG);
    const uid = Process.myUid();
    const launchable = createActionPreflight(Java, intent => intent);
    const wakeClock = () => Number(Clock.elapsedRealtime()) - Number(Clock.uptimeMillis());

    function viewport(view) {
        if (view === null || !view.isHardwareAccelerated() || view.getWindowVisibility() !== 0) return null;
        const display = view.getDisplay();
        if (display === null || !display.isValid() || display.getState() !== 2 || display.getType() !== 5 ||
            String(display.getOwnerPackageName()) !== DISPLAY_OWNER) return null;
        const context = view.getContext();
        const density = context.getResources().getDisplayMetrics().density.value;
        const geometry = driverMediaGeometry({display: String(display.getName()), owner: String(context.getPackageName()),
            attached: view.isAttachedToWindow(), shown: view.isShown(), width: view.getWidth(), height: view.getHeight(), density});
        const power = context.getSystemService(Power.class);
        if (geometry === null || power === null || !Java.cast(power, Power).isInteractive()) return null;
        const {width, height} = geometry;
        const visible = Rect.$new();
        if (!view.getLocalVisibleRect(visible) || visible.width() !== width || visible.height() !== height) return null;
        const size = Point.$new();
        display.getRealSize(size);
        const pos = Java.array('int', [0, 0]);
        view.getLocationOnScreen(pos);
        const [x, y] = [pos[0], pos[1]];
        if (size.x.value !== 1920 || size.y.value !== 720 || x < 0 || y < 0 || x + width > size.x.value / 2 ||
            y + height > size.y.value) return null;
        const key = JSON.stringify([display.getDisplayId(), x, y, width, height, density]);
        return {context, width, height, key};
    }

    function navigatorDisplays(manager) {
        const tasks = manager.getRunningTasks(100);
        const displays = [];
        for (let i = 0; i < tasks.size(); i++) {
            const task = Java.cast(tasks.get(i), RunningTask);
            const matches = component => component !== null && String(component.getPackageName()) === NAVIGATOR;
            if (task.numActivities.value > 0 && (matches(task.baseActivity.value) || matches(task.topActivity.value))) {
                displays.push(taskDisplay.getInt(task));
            }
        }
        return displays;
    }

    function clearCover(overlay, cover) {
        cover.animate().cancel();
        cover.setAlpha(0);
        const saved = cover.getTag(HANDLES_TAG);
        if (saved !== null) {
            const handles = Java.cast(saved, ArrayList);
            // Keep the handles on the cover if release fails so the next cleanup can retry.
            if (handles.size() === 2) Java.cast(handles.get(1), VD).release();
            if (handles.size() > 0) Java.cast(handles.get(0), Surface).release();
            cover.setTag(HANDLES_TAG, null);
        }
        overlay.remove(cover);
    }

    function sweep(host) {
        if (host === null) return;
        const overlay = host.getOverlay();
        const previous = Overlay.call(overlay).findViewWithTag(tag);
        if (previous !== null) clearCover(overlay, Java.cast(previous, TextureView));
    }

    function mount(host) {
        const place = viewport(host);
        if (place === null) return 'no_slot';
        const {context, width, height, key} = place;
        const pm = context.getPackageManager();
        if (!pm.hasSystemFeature('android.software.activities_on_secondary_displays') ||
            (uid !== SYSTEM_UID && (context.checkSelfPermission('android.permission.ADD_TRUSTED_DISPLAY') !== 0 ||
                context.checkSelfPermission('android.permission.REAL_GET_TASKS') !== 0))) return 'denied';
        const launch = pm.getLaunchIntentForPackage(NAVIGATOR);
        if (launch === null || launch.getComponent() === null ||
            String(launch.getComponent().getPackageName()) !== NAVIGATOR) return 'not_installed';
        if (!launchable(context, launch)) return 'denied';
        const manager = Java.cast(context.getSystemService(Activities.class), Activities);
        if (navigatorDisplays(manager).length !== 0) return 'in_use';
        sweep(host);
        const overlay = host.getOverlay();
        const cover = TextureView.$new(context);
        cover.setTag(tag);
        cover.setOpaque(false);
        cover.setClickable(false);
        cover.setFocusable(false);
        cover.setImportantForAccessibility(4);
        // Leave the OEM card readable while TextureView acquires a hardware layer and a producer.
        cover.setAlpha(0.01);
        try {
            overlay.add(cover);
            cover.measure(EXACTLY | width, EXACTLY | height);
            cover.layout(0, 0, width, height);
            return {host: Java.retain(host), overlay, cover, context, launch, manager, width, height, key,
                wake: wakeClock(), display: null, presented: false};
        } catch (error) {
            clearCover(overlay, cover);
            throw error;
        }
    }

    function pulse(cover, alpha) {
        const animator = cover.animate();
        animator.cancel();
        if (cover.getAlpha() !== alpha) cover.setAlpha(alpha);
        animator.alpha(0).setStartDelay(2800).setDuration(120).start();
    }

    function poll(window, host) {
        if (host === null || !window.host.equals(host) || viewport(host)?.key !== window.key ||
            wakeClock() - window.wake > 300) return 'cancelled';
        const {cover, context, launch, manager, width, height} = window;
        const texture = cover.getSurfaceTexture();
        if (texture === null) return window.display === null ? 'waiting' : 'cancelled';
        if (window.display === null) {
            texture.setDefaultBufferSize(width, height);
            const handles = ArrayList.$new();
            cover.setTag(HANDLES_TAG, handles);
            const surface = Surface.$new(texture);
            handles.add(surface);
            const displays = Java.cast(context.getSystemService(DisplayManager.class), DisplayManager);
            const child = displays.createVirtualDisplay('OVC-Driver-Navigator', width, height, 160, surface, DISPLAY_FLAGS);
            if (child === null) return 'denied';
            handles.add(child);
            const display = child.getDisplay();
            if (!display.isValid() || !launchable(context, launch) ||
                !manager.isActivityStartAllowedOnDisplay(context, display.getDisplayId(), launch)) {
                return 'denied';
            }
            if (navigatorDisplays(manager).length !== 0) return 'in_use';
            const options = Options.makeBasic().setLaunchDisplayId(display.getDisplayId());
            launch.addFlags(0x10000000); // FLAG_ACTIVITY_NEW_TASK
            context.startActivity(launch, options.toBundle());
            window.display = display;
            pulse(cover, 0.01);
            return 'waiting';
        }
        if (!window.display.isValid()) return 'cancelled';
        const positions = navigatorDisplays(manager);
        const taskOnDisplay = positions.includes(window.display.getDisplayId());
        if (!taskOnDisplay || Number(texture.getTimestamp()) <= 0) {
            if (window.presented || (positions.length !== 0 && !taskOnDisplay)) return 'no_frame';
            pulse(cover, 0.01);
            return 'waiting';
        }
        window.presented = true;
        pulse(cover, 1);
        return 'shown';
    }

    return {sweep, mount, poll, close(window) {
        clearCover(window.overlay, window.cover);
        window.host.$dispose();
    }};
}
