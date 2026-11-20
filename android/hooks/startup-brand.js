const APP = 'org.openvoyah.controls';
const ACTION = APP + '.action.SHOW_BOOT_BRAND';
const STARTUP_WINDOW_MS = 30_000;

function visibleOnCentralDisplay(view) {
    return view?.isAttachedToWindow() && view.isShown() && view.getWindowVisibility() === 0
        && view.getDisplay()?.getDisplayId() === 0;
}

/** Called on the UI thread once an owned, configured panel button is available. */
export function createStartupBrand(Java) {
    const Clock = Java.use('android.os.SystemClock');
    const Global = Java.use('android.provider.Settings$Global');
    const Intent = Java.use('android.content.Intent');
    const Animator = Java.use('android.animation.ValueAnimator');
    let deadline;
    let finished = false;
    let scanning = false;

    function pending() {
        if (finished || scanning) return false;
        const now = Number(Clock.elapsedRealtime());
        deadline ??= now + STARTUP_WINDOW_MS;
        if (now < deadline) return true;
        finished = true;
        return false;
    }

    function showIfReady(launcher, button) {
        if (!pending() || !visibleOnCentralDisplay(button)) return false;
        try {
            const boot = Global.getInt(launcher.getContentResolver(), 'boot_count', -1);
            const prefs = launcher.getSharedPreferences(APP + '.startup_brand', 0);
            if (boot < 0 || prefs.getInt('last_boot', -1) === boot || !Animator.areAnimatorsEnabled()) {
                finished = true;
                return true;
            }
            const home = Intent.$new('android.intent.action.MAIN');
            home.addCategory('android.intent.category.HOME');
            const info = launcher.getPackageManager().resolveActivity(home, 65536)?.activityInfo.value;
            if (!info || String(info.packageName.value) !== String(launcher.getPackageName())) {
                finished = true;
                return true;
            }
            const homeClass = String(info.targetActivity.value ?? info.name.value);
            scanning = true;
            Java.choose(homeClass, {
                onMatch(activity) {
                    if (finished) return 'stop';
                    if (Number(Clock.elapsedRealtime()) >= deadline || !visibleOnCentralDisplay(button) ||
                        activity.isFinishing() || !activity.hasWindowFocus() ||
                        !visibleOnCentralDisplay(activity.getWindow()?.getDecorView())) return;
                    finished = true;
                    try {
                        // Persist before launching so repeated attachment cannot interrupt the user again.
                        if (!prefs.edit().putInt('last_boot', boot).commit()) throw Error('Could not save boot marker');
                        const intent = Intent.$new(ACTION);
                        intent.setClassName(APP, APP + '.LaunchBrandActivity');
                        intent.addFlags(0x10000000);
                        activity.startActivity(intent);
                        console.log('OVC_STARTUP_BRAND outcome=requested');
                    } catch (error) {
                        console.log('OVC_STARTUP_BRAND outcome=failed ' + error);
                    }
                    return 'stop';
                },
                onComplete() { scanning = false; }
            });
            return true;
        } catch (error) {
            finished = true;
            console.log('OVC_STARTUP_BRAND outcome=unavailable ' + error);
            return true;
        }
    }

    return {pending, showIfReady};
}
