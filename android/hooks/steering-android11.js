import { createActionLauncher } from './launcher-buttons.js';

export function installSteering(Java) {
    Java.perform(() => {
        try {
            if (Java.use('android.os.Build$VERSION').SDK_INT.value !== 30) return;
            const app = Java.use('android.app.ActivityThread').currentApplication();
            if (!app) return setTimeout(() => installSteering(Java), 2000);
            const resolver = app.getContentResolver();
            const Global = Java.use('android.provider.Settings$Global');
            const Event = Java.use('com.qinggan.system.QGKeyEvent');
            const Callback = Java.use('com.qinggan.system.KeyManager$5');
            const launch = createActionLauncher(Java);
            const keys = new Map([
                [Event.KEYCODE_IVOKA.value, 'voice'],
                [Event.KEYCODE_SWC_USER_DEFINE.value, 'custom'],
                [Event.KEYCODE_SWC_AVM.value, 'camera']
            ]);
            const gestures = new Map([[Event.KEYACTION_SHORT.value, 'Short'], [Event.KEYACTION_LONG.value, 'Long']]);
            const callbacks = [
                Callback.onEvent.overload('com.qinggan.system.QGKeyEvent', 'java.lang.String', 'int'),
                Callback.onKeyEvent.overload('com.qinggan.system.QGKeyEvent')
            ];
            const handled = new Map();
            for (const callback of callbacks) {
                callback.implementation = function (...args) {
                    try {
                        const event = args[0];
                        const key = event && keys.get(event.getKeyCode());
                        const gesture = key && gestures.get(event.getAction());
                        if (gesture && !event.isCanceled()) {
                            const raw = Global.getString(resolver, 'org.openvoyah.controls.panel');
                            const config = raw && JSON.parse(String(raw));
                            if (config?.schema === 1 && config.steeringEnabled === true) {
                                const stamp = Number(event.getDownTime()) || Number(event.getEventTime());
                                const previous = handled.get(key);
                                const action = config.steering?.[key + gesture];
                                if (stamp > 0 && previous?.stamp === stamp) {
                                    if (previous.consumed) return true;
                                } else if (stamp > 0 && action) {
                                    const attempt = {stamp, consumed:false};
                                    handled.set(key, attempt);
                                    attempt.consumed = launch(app, 0, action);
                                    if (attempt.consumed) return true;
                                }
                            }
                        }
                    } catch (error) { }
                    return callback.call(this, ...args);
                };
            }
            console.log('OVC_STEERING_READY');
        } catch (error) { console.log('OVC_STEERING_ERROR ' + error); }
    });
}
