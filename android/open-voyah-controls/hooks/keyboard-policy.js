import { createReporter, errorKind } from './report.js';

/**
 * Lets the owner choose the keyboard of the central display.
 *
 * The input method service of this firmware ignores Android's `default_input_method` setting: every time it
 * re-reads its settings it selects a fixed keyboard by the system language (the OEM keyboard for English or Chinese,
 * the AOSP keyboard otherwise), so `ime set` or a settings write is undone within milliseconds. This policy replaces
 * that fixed choice with the keyboard Controls stored in [CHOICE_KEY], but only while that keyboard is installed,
 * enabled in Android's list and not one of the OEM keyboards. In every other case, and whenever the agent is not
 * running, the firmware chooses as it always did, so the factory keyboard is the fallback.
 *
 * The passenger display has its own input method service (`input_method_ext`) bound to a separate OEM package.
 * It is left alone: one keyboard package cannot serve both services at once.
 */
export const CHOICE_KEY = 'open_voyah_controls_input_method';
/** Why the agent dropped the owner's choice; Controls shows it and clears it with the next choice. */
export const FALLBACK_KEY = 'open_voyah_controls_input_method_fallback';
export const READY = 'OVC_KEYBOARD_READY protocol=1';
/** OEM keyboards stay with the firmware: the passenger service binds one of them. */
export const FACTORY_PACKAGES = ['com.qinggan.app.qgime', 'com.qinggan.app.qgime.second'];
const SERVICES = ['com.android.server.inputmethod.InputMethodManagerService', 'com.android.server.InputMethodManagerService'];
const ID = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+\/\.?[A-Za-z][A-Za-z0-9_$]*(?:\.[A-Za-z][A-Za-z0-9_$]*)*$/;

/** The keyboard IDs in Android's `enabled_input_methods` value: `id;subtype;subtype:id:...`. */
export function enabledIds(text) {
    const ids = new Set();
    for (const entry of String(text ?? '').split(':')) {
        const id = entry.split(';')[0];
        if (id) ids.add(id);
    }
    return ids;
}

export function packageOf(id) {
    return String(id).split('/')[0];
}

/**
 * The keyboard to select instead of the firmware's fixed one, or null to let the firmware choose. [known] tells
 * whether Android's service lists [choice] as installed; a removed or disabled package is not listed.
 */
export function keyboardOverride({ choice, known, enabled, tripped }) {
    if (typeof choice !== 'string' || !ID.test(choice)) return null;
    if (FACTORY_PACKAGES.includes(packageOf(choice))) return null;
    if (!known || !enabledIds(enabled).has(choice) || tripped === choice) return null;
    return choice;
}

/**
 * Notices a keyboard that keeps crashing: [limit] disconnects of the chosen package within [windowMs]. Android
 * restarts a crashed keyboard on its own, so without this a broken keyboard would leave no working one.
 */
export function crashBreaker({ limit = 3, windowMs = 60000 } = {}) {
    let times = [];
    let watched = null;
    return {
        /** Returns true when this disconnect is the one that trips the breaker. */
        record(id, now) {
            if (id !== watched) { watched = id; times = []; }
            times = times.filter(time => now - time < windowMs);
            times.push(now);
            if (times.length < limit) return false;
            times = [];
            return true;
        },
    };
}

/** Finds the input method service class of this Android version and checks the members the policy uses. */
function serviceType(Java) {
    for (const name of SERVICES) {
        let type;
        try { type = Java.use(name); } catch (_) { continue; }
        const update = type.updateInputMethodsFromSettingsLocked.overload('boolean');
        const select = type.setInputMethodLocked.overload('java.lang.String', 'int');
        const disconnected = type.onServiceDisconnected.overload('android.content.ComponentName');
        return { type, update, select, disconnected };
    }
    throw Object.assign(Error('no_service'), { code: 'no_service' });
}

export function installKeyboardPolicy(Java, { report = createReporter('OVC_KEYBOARD_ERROR'), log = line => console.log(line),
    now = () => Date.now() } = {}) {
    Java.perform(() => {
        const hooks = [];
        try {
            const { type, update, select, disconnected } = serviceType(Java);
            const Secure = Java.use('android.provider.Settings$Secure');
            const context = Java.use('android.app.ActivityThread').currentActivityThread().getSystemContext();
            const resolver = Java.retain(context.getContentResolver());
            const breaker = crashBreaker();
            let tripped = null;
            let active = null;

            const read = (key, user) => {
                const value = Secure.getStringForUser(resolver, key, user);
                return value === null ? '' : String(value);
            };
            const write = (key, value, user) => Secure.putStringForUser(resolver, key, value, user);
            // Android re-reads its keyboard settings only when the selected keyboard changes, so an absent value
            // becomes empty and any other value is removed. The firmware or this policy then selects anew.
            const reselect = user => write('default_input_method',
                Secure.getStringForUser(resolver, 'default_input_method', user) === null ? '' : null, user);
            /** The override for [service], read under its own lock while Android re-reads keyboard settings. */
            const choose = service => {
                const settings = service.mSettings.value;
                const user = settings.getCurrentUserId();
                const choice = read(CHOICE_KEY, user);
                if (choice !== tripped) tripped = null;
                const known = choice !== '' && service.mMethodMap.value.get(choice) !== null;
                return { user, id: keyboardOverride({ choice, known, enabled: settings.getEnabledInputMethodsStr(), tripped }) };
            };

            update.implementation = function (enabledMayChange) {
                let id = null;
                try {
                    id = choose(this).id;
                    if (id !== null) {
                        const settings = this.mSettings.value;
                        select.call(this, id, settings.getSelectedInputMethodSubtypeId(id));
                        this.mSwitchingController.value.resetCircularListLocked(this.mContext.value);
                        if (active !== id) log('OVC_KEYBOARD_APPLIED package=' + packageOf(id));
                        active = id;
                        return;
                    }
                } catch (error) {
                    report('update', error, { cause: errorKind(error) });
                }
                if (active !== null) log('OVC_KEYBOARD_FACTORY');
                active = null;
                update.call(this, enabledMayChange);
            };
            hooks.push(update);

            disconnected.implementation = function (name) {
                disconnected.call(this, name);
                try {
                    if (active === null || name === null || String(name.getPackageName()) !== packageOf(active)) return;
                    if (!breaker.record(active, now())) return;
                    const user = this.mSettings.value.getCurrentUserId();
                    tripped = active;
                    log('OVC_KEYBOARD_FALLBACK reason=crashed package=' + packageOf(active));
                    write(FALLBACK_KEY, 'crashed ' + active, user);
                    write(CHOICE_KEY, '', user);
                    reselect(user);
                } catch (error) {
                    report('crash_fallback', error, { cause: errorKind(error) });
                }
            };
            hooks.push(disconnected);

            // A choice made before this boot applies now; one whose keyboard is gone is dropped with its reason.
            const service = Java.cast(Java.use('android.os.ServiceManager').getService('input_method'), type);
            const user = service.mSettings.value.getCurrentUserId();
            const choice = read(CHOICE_KEY, user);
            if (choice !== '') {
                const { id } = choose(service);
                if (id === null) {
                    log('OVC_KEYBOARD_FALLBACK reason=unavailable package=' + (ID.test(choice) ? packageOf(choice) : 'invalid'));
                    write(FALLBACK_KEY, 'unavailable ' + choice, user);
                    write(CHOICE_KEY, '', user);
                } else if (String(service.mCurMethodId.value) !== id) {
                    reselect(user);
                }
            }
            log(READY);
        } catch (error) {
            // Nothing stays half-installed: the firmware keeps choosing the keyboard as before.
            for (const hook of hooks) hook.implementation = null;
            report('install', error, error?.cause === undefined ? {} : { cause: errorKind(error.cause) });
        }
    });
}
