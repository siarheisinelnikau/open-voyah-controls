// Settings.Global key through which Controls learns whether the central display is lowered: "1" lowered, "0" not,
// absent while no agent reports it.
const SCREEN_LOWERED = 'org.openvoyah.controls.screenLowered';

/**
 * Publishes the motorised display's lowered state for Controls. The launcher's central bar is told about every
 * switch (`NavigationBar.doScreenLift`, type 1 = lowered) and at start reads `ScreenLiftUtils.getScreenLiftState()`;
 * both feed one Settings.Global value, written only when it changes. Disposal removes the value, so a detached agent
 * never leaves Controls in the lowered layout.
 */
export function installScreenLift(Java, Nav, report) {
    const Global = Java.use('android.provider.Settings$Global');
    const ActivityThread = Java.use('android.app.ActivityThread');
    const lift = Nav.doScreenLift.overload('int');
    let last = null, resolver = null;

    function publish(context, lowered) {
        const value = lowered ? '1' : '0';
        if (value === last) return;
        resolver = context.getContentResolver();
        if (Global.putString(resolver, SCREEN_LOWERED, value)) last = value;
    }

    lift.implementation = function (type) {
        const result = lift.call(this, type);
        try { if (this.mScreenId.value === 0) publish(this.mContext.value, type === 1); }
        catch (error) { report('screen_lift', error); }
        return result;
    };
    try {
        const app = ActivityThread.currentApplication();
        const state = Java.use('com.qinggan.utils.ScreenLiftUtils').getScreenLiftState();
        if (app !== null) publish(app, state === 1);
    } catch (error) { report('screen_lift', error); }

    return {
        dispose() {
            lift.implementation = null;
            try { if (resolver !== null) Global.putString(resolver, SCREEN_LOWERED, null); }
            catch (error) { report('screen_lift', error); }
            last = null;
        }
    };
}
