import { errorKind } from './report.js';

const APP = 'org.openvoyah.controls';
const ACTION = APP + '.action.SHOW_BOOT_BRAND';
const SETUP = APP + '.SetupActivity';
const STARTUP_WINDOW_MS = 30_000;

function visibleOnCentralDisplay(view) {
    return view?.isAttachedToWindow() && view.isShown() && view.getWindowVisibility() === 0
        && view.getDisplay()?.getDisplayId() === 0;
}

/**
 * Called on the UI thread once an owned, configured panel button is available. Every attachment ends with one
 * `OVC_STARTUP_BRAND` line: the launch was requested, failed, or was skipped for a named reason.
 */
export function createStartupBrand(Java) {
    const Clock = Java.use('android.os.SystemClock');
    const Global = Java.use('android.provider.Settings$Global');
    const Intent = Java.use('android.content.Intent');
    const Animator = Java.use('android.animation.ValueAnimator');
    const ActivityManager = Java.use('android.app.ActivityManager');
    const RunningTask = Java.use('android.app.ActivityManager$RunningTaskInfo');
    let deadline;
    let finished = false;
    let scanning = false;
    // The last condition that held the intro back; reported if the startup window closes.
    let blocker = 'no_panel_button';

    function skip(reason) {
        finished = true;
        console.log('OVC_STARTUP_BRAND outcome=skipped reason=' + reason);
    }

    /**
     * True while Controls' setup page has a task, even one behind home. The page is excluded from the recents, and
     * Android drops such a hidden task when a new task starts: the intro's task did that during a provisioning
     * probe (boot 207), and the installer waited in vain for the page's answer.
     */
    function setupOpen(launcher) {
        const tasks = Java.cast(launcher.getSystemService('activity'), ActivityManager).getRunningTasks(64);
        for (let i = 0; i < tasks.size(); i++) {
            const task = Java.cast(tasks.get(i), RunningTask);
            for (const component of [task.baseActivity.value, task.topActivity.value]) {
                if (component && String(component.getPackageName()) === APP &&
                    String(component.getClassName()) === SETUP) return true;
            }
        }
        return false;
    }

    function pending() {
        if (finished || scanning) return false;
        const now = Number(Clock.elapsedRealtime());
        deadline ??= now + STARTUP_WINDOW_MS;
        if (now < deadline) return true;
        skip(blocker);
        return false;
    }

    function showIfReady(launcher, button) {
        if (!pending()) return false;
        if (!visibleOnCentralDisplay(button)) {
            blocker = 'panel_hidden';
            return false;
        }
        try {
            const boot = Global.getInt(launcher.getContentResolver(), 'boot_count', -1);
            const prefs = launcher.getSharedPreferences(APP + '.startup_brand', 0);
            const early = boot < 0 ? 'boot_count'
                : prefs.getInt('last_boot', -1) === boot ? 'requested_this_boot'
                : !Animator.areAnimatorsEnabled() ? 'animators_disabled' : null;
            if (early) {
                skip(early);
                return true;
            }
            const home = Intent.$new('android.intent.action.MAIN');
            home.addCategory('android.intent.category.HOME');
            const info = launcher.getPackageManager().resolveActivity(home, 65536)?.activityInfo.value;
            if (!info || String(info.packageName.value) !== String(launcher.getPackageName())) {
                skip('other_home');
                return true;
            }
            if (setupOpen(launcher)) {
                blocker = 'setup_active';
                return false;
            }
            const homeClass = String(info.targetActivity.value ?? info.name.value);
            blocker = 'home_missing';
            scanning = true;
            Java.choose(homeClass, {
                onMatch(activity) {
                    if (finished) return 'stop';
                    if (Number(Clock.elapsedRealtime()) >= deadline) return;
                    if (!visibleOnCentralDisplay(button)) {
                        blocker = 'panel_hidden';
                        return;
                    }
                    if (activity.isFinishing() || !activity.hasWindowFocus() ||
                        !visibleOnCentralDisplay(activity.getWindow()?.getDecorView())) {
                        blocker = 'home_unfocused';
                        return;
                    }
                    finished = true;
                    try {
                        // Persist before launching so repeated attachment cannot interrupt the user again.
                        if (!prefs.edit().putInt('last_boot', boot).commit()) {
                            throw Object.assign(Error('Could not save boot marker'), {code:'marker_not_saved'});
                        }
                        const intent = Intent.$new(ACTION);
                        intent.setClassName(APP, APP + '.LaunchBrandActivity');
                        intent.addFlags(0x10000000);
                        activity.startActivity(intent);
                        console.log('OVC_STARTUP_BRAND outcome=requested');
                    } catch (error) {
                        console.log('OVC_STARTUP_BRAND outcome=failed kind=' + errorKind(error));
                    }
                    return 'stop';
                },
                onComplete() { scanning = false; }
            });
            return true;
        } catch (error) {
            finished = true;
            console.log('OVC_STARTUP_BRAND outcome=unavailable kind=' + errorKind(error));
            return true;
        }
    }

    return {pending, showIfReady};
}
