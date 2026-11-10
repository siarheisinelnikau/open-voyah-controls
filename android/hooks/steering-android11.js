import { createActionLauncher } from './launcher-buttons.js';

const EVENT = 'com.qinggan.system.QGKeyEvent';
const CONFIG = 'org.openvoyah.controls.panel';

function requireReturn(method, type) {
    if (method.returnType.className !== type) throw Error('Unsupported OEM callback signature');
    return method;
}

export function installSteering(Java, retries = 10) {
    Java.perform(() => {
        const installed = [];
        try {
            const sdk = Java.use('android.os.Build$VERSION').SDK_INT.value;
            if (sdk !== 30) {
                console.log('OVC_STEERING_UNSUPPORTED Android SDK ' + sdk);
                return;
            }
            const app = Java.use('android.app.ActivityThread').currentApplication();
            if (app === null) {
                if (retries > 0) setTimeout(() => installSteering(Java, retries - 1), 2000);
                else console.log('OVC_STEERING_UNSUPPORTED launcher context unavailable');
                return;
            }
            const resolver = app.getContentResolver();
            const Global = Java.use('android.provider.Settings$Global');
            const Event = Java.use(EVENT);
            const Callback = Java.use('com.qinggan.system.KeyManager$5');
            requireReturn(Event.getKeyCode.overload(), 'int');
            requireReturn(Event.getAction.overload(), 'int');
            requireReturn(Event.isCanceled.overload(), 'boolean');
            requireReturn(Event.getDownTime.overload(), 'long');
            const voice = Event.KEYCODE_IVOKA.value;
            const custom = Event.KEYCODE_SWC_USER_DEFINE.value;
            const camera = Event.KEYCODE_SWC_AVM.value;
            const short = Event.KEYACTION_SHORT.value;
            const long = Event.KEYACTION_LONG.value;
            if (![voice, custom, camera, short, long].every(value => Number.isInteger(value) && value > 0)
                || new Set([voice, custom, camera]).size !== 3 || short === long) {
                throw Error('Unsupported OEM key constants');
            }
            const callbacks = [
                requireReturn(Callback.onEvent.overload(EVENT, 'java.lang.String', 'int'), 'boolean'),
                requireReturn(Callback.onKeyEvent.overload(EVENT), 'boolean')
            ];
            const launch = createActionLauncher(Java);
            const keys = new Map([[voice, 'voice'], [custom, 'custom']]);
            const gestures = new Map([[short, 'Short'], [long, 'Long']]);
            const handled = new Map();
            for (const callback of callbacks) {
                installed.push(callback);
                callback.implementation = function (...args) {
                    try {
                        const event = args[0];
                        const key = event && keys.get(event.getKeyCode());
                        const gesture = key && gestures.get(event.getAction());
                        if (gesture && !event.isCanceled()) {
                            // Both OEM paths need the same down time before replacing a physical gesture.
                            const stamp = Number(event.getDownTime());
                            if (Number.isSafeInteger(stamp) && stamp > 0) {
                                const previous = handled.get(key);
                                if (previous?.stamp === stamp) {
                                    if (previous.consumed || previous.pending) return true;
                                } else if (!previous || stamp > previous.stamp) {
                                    const attempt = {stamp, consumed: false, pending: false};
                                    handled.set(key, attempt);
                                    const raw = Global.getString(resolver, CONFIG);
                                    const config = raw && JSON.parse(String(raw));
                                    const action = config?.schema === 1 && config.steeringEnabled === true
                                        && config.steering?.[key + gesture];
                                    if (action) {
                                        attempt.pending = true;
                                        try { attempt.consumed = launch(app, 0, action) === true; }
                                        finally { attempt.pending = false; }
                                        if (attempt.consumed) return true;
                                    }
                                }
                            }
                        }
                    } catch (error) { }
                    return callback.call(this, ...args);
                };
            }
            console.log('OVC_STEERING_READY Android 11 voice and custom keys');
        } catch (error) {
            for (const callback of installed) {
                try { callback.implementation = null; } catch (restoreError) {
                    console.log('OVC_STEERING_ERROR callback restore failed ' + restoreError);
                }
            }
            console.log('OVC_STEERING_UNSUPPORTED ' + error);
        }
    });
}
