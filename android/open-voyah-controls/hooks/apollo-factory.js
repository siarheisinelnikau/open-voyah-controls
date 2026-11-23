// What the factory Driving assistance page shows in Guest is decided by answers the factory settings process
// gives itself. Controls can supply them while its own switch is on, and take them away again when it is off.
// Nothing here changes the vehicle except the one part the driver enables separately, and an accepted request is
// never reported as a verified state change.
import { createReporter } from './report.js';

/** Settings.Global document Controls writes: {"schema":1,"subscription":true,"controller":false,"equipment":false}. */
export const APOLLO_CONFIG_KEY = 'org.openvoyah.controls.apolloFactory';
/** Settings.Global document the agent writes back, so Controls can show what the installed software offered. */
export const APOLLO_STATE_KEY = 'org.openvoyah.controls.apolloFactoryState';
/** The subscription answer the stock factory page reads from the Baidu state provider. */
export const SUBSCRIPTION_ANSWER = '{"expireStatus":"0","isMqtt":false,"remainDays":"30","subscriptionStatus":"1"}';
export const APOLLO_CLASSES = Object.freeze({
    subscription: 'com.qinggan.app.vehiclesetting.fragments.driveassistance.adas.BaiduProviderUtil',
    manager: 'com.qinggan.app.vehiclesetting.fragments.driveassistance.adas.DriveAssistanceAdasStatusManager',
    canTool: 'com.qinggan.app.basevehiclesetting.canbustools.CanBusTool$3',
    equipment: 'com.qinggan.app.vehiclesetting.fragments.driveassistance.utils.Utils',
    region: 'com.qinggan.utils.AppCommonUtils',
});
/** The factory process sends its assistance state again when the vehicle reaches one of these readings. */
export const APOLLO_TRIGGERS = Object.freeze({ HUM_VCU_READY: 1, BMS_STATE: 3 });
const PARTS = ['subscription', 'controller', 'equipment'];

/**
 * The published document, or null when every part is off or the document is not the one Controls writes.
 * An unreadable value never leaves a substitution running on a guess.
 */
export function apolloConfig(raw) {
    if (typeof raw !== 'string' || raw.length === 0 || raw.length > 512) return null;
    let document;
    try { document = JSON.parse(raw); } catch (_) { return null; }
    if (!document || document.schema !== 1) return null;
    const wanted = {};
    for (const part of PARTS) if (document[part] === true) wanted[part] = true;
    return Object.keys(wanted).length === 0 ? null : wanted;
}

/** The state document the agent publishes. Only names and a count leave the process. */
export function apolloState(applied, missing, sent) {
    const state = { schema: 1 };
    for (const part of PARTS) {
        // A part the installed software only offered in part is reported as unavailable, never as applied.
        state[part] = missing.has(part) ? 'unavailable' : applied.includes(part) ? 'applied' : 'off';
    }
    return JSON.stringify({ ...state, sent });
}

/** Whether one factory state reading asks the factory software to send its assistance state again. */
export function apolloTrigger(name, value) {
    return Object.prototype.hasOwnProperty.call(APOLLO_TRIGGERS, name) && Number(value) === APOLLO_TRIGGERS[name];
}

export function installApolloFactory(Java, report = createReporter('OVC_APOLLO_ERROR')) {
    Java.perform(() => {
        let hooks = [], applied = [], missing = new Set(), sent = 0;
        const restore = () => {
            while (hooks.length) {
                const hook = hooks.pop();
                try { hook.implementation = null; } catch (error) { report('restore', error); }
            }
            applied = [];
            missing = new Set();
        };
        const context = () => Java.use('android.app.ActivityThread').currentApplication().getApplicationContext();
        /** The named factory method, tried under each candidate signature; null when the software does not offer it. */
        const method = (className, name, signatures) => {
            for (const signature of signatures) {
                try {
                    const target = Java.use(className)[name];
                    return signature === null ? target : target.overload(...signature);
                } catch (error) { report('resolve', error, { method: name }); }
            }
            return null;
        };
        const sendAssistanceState = () => {
            // The factory singleton builds and initializes on first use; that is the request itself, not a side
            // effect to hide. A failure is reported once and never retried in a loop.
            const instance = method(APOLLO_CLASSES.manager, 'instance', [['android.content.Context']]);
            if (instance === null) return false;
            try {
                const manager = instance.call(null, context());
                manager.sendAdasSubStatusToADCU(true);
                manager.asyncQueryAdasSubData();
                sent++;
                return true;
            } catch (error) {
                report('send', error);
                return false;
            }
        };
        const apply = wanted => {
            if (wanted.subscription) {
                const query = method(APOLLO_CLASSES.subscription, 'doQuerySubscribeInfo',
                    [['android.content.Context'], null]);
                if (query === null) missing.add('subscription');
                else {
                    query.implementation = function (configuration) { return SUBSCRIPTION_ANSWER; };
                    hooks.push(query);
                    applied.push('subscription');
                }
            }
            if (wanted.equipment) {
                // The factory page picks an equipment branch and a region from these predicates. Only what the page
                // shows changes; the readings behind them stay as the vehicle reported them.
                let replaced = 0;
                for (const [className, name, answer] of [
                    [APOLLO_CLASSES.equipment, 'is97Y', false], [APOLLO_CLASSES.equipment, 'is97C', true],
                    [APOLLO_CLASSES.region, 'isOverSeaVehicle', false],
                ]) {
                    const predicate = method(className, name, [[]]);
                    if (predicate === null) { missing.add('equipment'); continue; }
                    predicate.implementation = function () { return answer; };
                    hooks.push(predicate);
                    replaced++;
                }
                if (replaced > 0) applied.push('equipment');
            }
            if (wanted.controller) {
                const changed = method(APOLLO_CLASSES.canTool, 'onVehicleStateChanged',
                    [['java.lang.String', 'java.lang.String'], ['java.lang.String', 'int']]);
                if (changed === null) missing.add('controller');
                else {
                    changed.implementation = function (name, value) {
                        if (apolloTrigger(String(name), value)) sendAssistanceState();
                        return changed.call(this, name, value);
                    };
                    hooks.push(changed);
                }
                // The first request is what the driver asked for; the factory process repeats it on its own after.
                if (!sendAssistanceState()) missing.add('controller');
                else applied.push('controller');
            }
        };
        const Global = Java.use('android.provider.Settings$Global');
        const resolver = Java.use('android.app.ActivityThread').currentApplication().getContentResolver();
        const publish = written => {
            try { Global.putString(resolver, APOLLO_STATE_KEY, apolloState(applied, missing, sent)); }
            catch (error) { if (written) report('state', error); }
        };
        let previous = null;
        const follow = () => {
            let raw;
            try { raw = String(Global.getString(resolver, APOLLO_CONFIG_KEY) || ''); }
            catch (error) {
                // Stay off while the provider is unavailable, and read the value again once it is back.
                previous = null;
                restore();
                report('settings', error);
                return;
            }
            if (raw === previous) return;
            previous = raw;
            restore();
            const wanted = apolloConfig(raw);
            if (wanted !== null) apply(wanted);
            publish(wanted !== null);
        };
        follow();
        setInterval(() => Java.perform(follow), 1000);
        console.log('OVC_APOLLO_READY ' + String(Java.use('android.os.Build').PRODUCT.value));
    });
}
