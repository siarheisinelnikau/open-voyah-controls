import { createActionLauncher } from './launcher-buttons.js';
import { initializedType } from './java-types.js';
import { createReporter, errorKind } from './report.js';

const EVENT = 'com.qinggan.system.QGKeyEvent';
const CONFIG = 'org.openvoyah.controls.panel';
const CAN_ROUTES = ['can-terminal', 'can-edges'];
// The KeyManager service delivers one event to both callbacks back to back. Two real presses of one key
// with the same gesture are further apart than this, counted from the end of the previous attempt.
const DUPLICATE_MS = 100;

function unsupported(code) {
    return Object.assign(Error(code), {code});
}

function requireReturn(method, type) {
    if (method.returnType.className !== type) throw unsupported('callback_signature');
    return method;
}

export function installSteering(Java, retries = 10, report = createReporter('OVC_STEERING_ERROR')) {
    Java.perform(() => {
        const installed = [];
        try {
            const sdk = Java.use('android.os.Build$VERSION').SDK_INT.value;
            if (![28, 30].includes(sdk)) {
                console.log('OVC_STEERING_UNSUPPORTED unsupported_sdk sdk=' + (Number.isInteger(sdk) ? sdk : 'invalid'));
                return;
            }
            const app = Java.use('android.app.ActivityThread').currentApplication();
            if (app === null) {
                if (retries > 0) setTimeout(() => installSteering(Java, retries - 1, report), 2000);
                else console.log('OVC_STEERING_UNSUPPORTED no_launcher_context');
                return;
            }
            const resolver = app.getContentResolver();
            const Global = Java.use('android.provider.Settings$Global');
            const readConfig = () => {
                const raw = Global.getString(resolver, CONFIG);
                return raw ? JSON.parse(String(raw)) : null;
            };
            let initial = null;
            // A broken document is not an unsupported firmware: install, and stay native while it cannot be read.
            try { initial = readConfig(); }
            catch (error) { report('config', error); }
            if (CAN_ROUTES.includes(initial?.steeringSource)) {
                console.log('OVC_STEERING_READY protocol=2 delegated=can');
                return;
            }
            const Event = initializedType(Java, EVENT);
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
                throw unsupported('key_constants');
            }
            const callbacks = [
                requireReturn(Callback.onEvent.overload(EVENT, 'java.lang.String', 'int'), 'boolean'),
                requireReturn(Callback.onKeyEvent.overload(EVENT), 'boolean')
            ];
            const launch = createActionLauncher(Java);
            const SystemClock = Java.use('android.os.SystemClock');
            const uptime = () => Number(SystemClock.uptimeMillis());
            const keys = new Map([[voice, 'voice'], [custom, 'custom'], [camera, 'camera']]);
            const gestures = new Map([[short, 'Short'], [long, 'Long']]);
            const handled = new Map();

            function assigned(key, gesture) {
                try {
                    const config = readConfig();
                    return config?.schema === 1 && !CAN_ROUTES.includes(config.steeringSource)
                        && config.steeringEnabled === true && config.steering?.[key + gesture] || null;
                } catch (error) {
                    report('config', error, {key, gesture});
                    return null;
                }
            }

            /**
             * The answer of an earlier attempt when this callback carries the same event, false for an older event,
             * and undefined for a new one.
             */
            function earlier(previous, stamp, exact, gesture) {
                if (!previous || previous.exact !== exact) return undefined;
                if (exact) {
                    if (previous.stamp === stamp) return previous.consumed || previous.pending;
                    return stamp < previous.stamp ? false : undefined;
                }
                if (previous.gesture !== gesture) return undefined;
                if (previous.pending) return true;
                return stamp >= previous.stamp - DUPLICATE_MS && stamp <= previous.settled + DUPLICATE_MS
                    ? previous.consumed : undefined;
            }

            /** True when an assigned action owns this event, so the OEM callback must not run. */
            function consumed(event) {
                const key = event && keys.get(event.getKeyCode());
                const gesture = key && gestures.get(event.getAction());
                if (!gesture || event.isCanceled()) return false;
                // Firmware that keeps the down time lets both OEM paths name the same physical gesture. The
                // reviewed H97 parcel carries only code, action, flags, data and display, so every event reaches
                // the launcher with a zero down time; its arrival time then tells a repeat from a new press.
                const down = Number(event.getDownTime());
                const exact = down !== 0;
                const stamp = exact ? down : uptime();
                if (!Number.isSafeInteger(stamp) || stamp <= 0) return false;
                const previous = earlier(handled.get(key), stamp, exact, gesture);
                if (previous !== undefined) return previous;
                // The Settings read is a JNI call that lets the other callback for this event run. It must not
                // find an attempt that looks like a native decision while this one may still launch.
                const action = assigned(key, gesture);
                const current = earlier(handled.get(key), stamp, exact, gesture);
                if (current !== undefined) return current;
                const attempt = {stamp, exact, gesture, settled: stamp, consumed: false, pending: action !== null};
                handled.set(key, attempt);
                if (action === null) return false;
                try { attempt.consumed = launch(app, 0, action) === true; }
                catch (error) { report('launch', error, {key, gesture}); }
                finally {
                    attempt.pending = false;
                    // A repeat arrives only after this callback returns, so its window starts here.
                    if (!exact) try { attempt.settled = Math.max(stamp, uptime()); } catch (_) {}
                }
                return attempt.consumed;
            }

            for (const callback of callbacks) {
                installed.push(callback);
                callback.implementation = function (...args) {
                    try { if (consumed(args[0])) return true; }
                    catch (error) { report('event', error); }
                    return callback.call(this, ...args);
                };
            }
            console.log('OVC_STEERING_READY protocol=2 sdk=' + sdk + ' voice custom camera');
        } catch (error) {
            for (const callback of installed) {
                try { callback.implementation = null; } catch (restoreError) { report('restore', restoreError); }
            }
            console.log('OVC_STEERING_UNSUPPORTED ' + errorKind(error));
        }
    });
}
