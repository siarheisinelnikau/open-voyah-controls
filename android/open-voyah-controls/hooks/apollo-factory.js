// What the factory Driving assistance page shows in Guest is decided by answers the factory settings process
// gives itself. Controls can supply them while its own switch is on, and take them away again when it is off.
// Nothing here changes the vehicle except one separately requested send, and an accepted request is never
// reported as a verified state change. Controls verifies a fresh Park and zero speed before asking for that send;
// this agent only honours the one request Controls carries, and never repeats one on its own.
import { createReporter } from './report.js';

/** Settings.Global document Controls writes: {"schema":1,"request":"<32 hex>","send":true|false,...}. */
export const APOLLO_CONFIG_KEY = 'org.openvoyah.controls.apolloFactory';
/** Settings.Global document the agent writes back, so Controls can show what the installed software offered. */
export const APOLLO_STATE_KEY = 'org.openvoyah.controls.apolloFactoryState';
/** The subscription answer the stock factory page reads from the Baidu state provider. */
export const SUBSCRIPTION_ANSWER = '{"expireStatus":"0","isMqtt":false,"remainDays":"30","subscriptionStatus":"1"}';
export const APOLLO_CLASSES = Object.freeze({
    subscription: 'com.qinggan.app.vehiclesetting.fragments.driveassistance.adas.BaiduProviderUtil',
    manager: 'com.qinggan.app.vehiclesetting.fragments.driveassistance.adas.DriveAssistanceAdasStatusManager',
    equipment: 'com.qinggan.app.vehiclesetting.fragments.driveassistance.utils.Utils',
    region: 'com.qinggan.utils.AppCommonUtils',
});
const PARTS = ['subscription', 'controller', 'equipment'];
const REQUEST = /^[0-9a-f]{32}$/;

/**
 * The published document, or null when every part is off or the document is not the one Controls writes.
 * An unreadable value never leaves a substitution running on a guess. `send` is a one-shot permit tied to
 * `request`; Controls writes it only after its own Park and zero-speed check, and never for a neighbouring switch.
 */
export function apolloConfig(raw) {
    if (typeof raw !== 'string' || raw.length === 0 || raw.length > 512) return null;
    let document;
    try { document = JSON.parse(raw); } catch (_) { return null; }
    if (!document || document.schema !== 1) return null;
    if (typeof document.request !== 'string' || !REQUEST.test(document.request)) return null;
    const wanted = {};
    for (const part of PARTS) if (document[part] === true) wanted[part] = true;
    if (Object.keys(wanted).length === 0) return null;
    wanted.request = document.request;
    // A send is never inferred from a controller switch: it needs the switch, an unexpired permit and a deadline.
    wanted.send = document.send === true && wanted.controller === true
        && Number.isSafeInteger(document.expires) && document.expires > 0;
    if (wanted.send) wanted.expires = document.expires;
    return wanted;
}

/**
 * The state document the agent publishes. Only names, the matching request and a request count leave the process.
 * `requested` means the factory call was accepted; it says nothing about what the controller did with it.
 */
export function apolloState(applied, controllerRequested, missing, request, sent) {
    const state = { schema: 1 };
    if (request !== null) state.request = request;
    state.sent = sent;
    for (const part of PARTS) {
        state[part] = missing.has(part) ? 'unavailable'
            : part === 'controller' && controllerRequested ? 'requested'
            : applied.includes(part) ? 'applied' : 'off';
    }
    return JSON.stringify(state);
}

export function installApolloFactory(Java, report = createReporter('OVC_APOLLO_ERROR')) {
    Java.perform(() => {
        let hooks = [], applied = [], missing = new Set(), sent = 0;
        let request = null, sentFor = null, controllerRequested = false;
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
        /** The factory singleton builds and initializes on first use; an accepted call is a request, not telemetry. */
        const requestAssistanceState = () => {
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
                // shows changes; the readings behind them stay as the vehicle reported them. A part is installed
                // whole or not at all: a half-installed branch would hide behind an "unavailable" report.
                const specs = [
                    [APOLLO_CLASSES.equipment, 'is97Y', false], [APOLLO_CLASSES.equipment, 'is97C', true],
                    [APOLLO_CLASSES.region, 'isOverSeaVehicle', false],
                ];
                const predicates = specs.map(([className, name]) => method(className, name, [[]]));
                if (predicates.some(predicate => predicate === null)) missing.add('equipment');
                else {
                    predicates.forEach((predicate, index) => {
                        const answer = specs[index][2];
                        predicate.implementation = function () { return answer; };
                        hooks.push(predicate);
                    });
                    applied.push('equipment');
                }
            }
            if (wanted.controller && wanted.send && now() <= wanted.expires && wanted.request !== sentFor) {
                if (requestAssistanceState()) {
                    sentFor = wanted.request;
                    controllerRequested = true;
                } else missing.add('controller');
            }
        };
        const Global = Java.use('android.provider.Settings$Global');
        const clock = Java.use('android.os.SystemClock');
        const now = () => Number(clock.elapsedRealtime());
        const resolver = Java.use('android.app.ActivityThread').currentApplication().getContentResolver();
        const publish = written => {
            try { Global.putString(resolver, APOLLO_STATE_KEY, apolloState(applied, controllerRequested, missing, request, sent)); }
            catch (error) { if (written) report('state', error); }
        };
        // A restarted factory app must not replay a request it already accepted. The previous document is the only
        // record that survives the process; an unreadable one starts with no request and no send.
        try {
            const prior = JSON.parse(String(Global.getString(resolver, APOLLO_STATE_KEY) || ''));
            if (prior && typeof prior.request === 'string' && REQUEST.test(prior.request)) {
                request = prior.request;
                if (Number.isSafeInteger(prior.sent) && prior.sent >= 0) sent = prior.sent;
                if (prior.controller === 'requested') { sentFor = prior.request; controllerRequested = true; }
            }
        } catch (_) { /* No prior document: nothing to carry over. */ }
        let previous = null;
        const follow = () => {
            let raw;
            try { raw = String(Global.getString(resolver, APOLLO_CONFIG_KEY) || ''); }
            catch (error) {
                // Stay off while the provider is unavailable, and read the value again once it is back.
                previous = null;
                request = null;
                controllerRequested = false;
                restore();
                report('settings', error);
                return;
            }
            if (raw === previous) return;
            previous = raw;
            restore();
            const wanted = apolloConfig(raw);
            if (wanted === null) {
                request = null;
                controllerRequested = false;
            } else {
                request = wanted.request;
                if (!wanted.controller) controllerRequested = false;
                apply(wanted);
            }
            publish(wanted !== null);
        };
        follow();
        setInterval(() => Java.perform(follow), 1000);
        console.log('OVC_APOLLO_READY ' + String(Java.use('android.os.Build').PRODUCT.value));
    });
}
