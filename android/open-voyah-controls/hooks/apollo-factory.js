// What the factory Driving assistance page shows in Guest is decided by answers the factory settings process
// gives itself. Controls can supply them while its own switch is on, and take them away again when it is off.
// Nothing here changes the vehicle except one separately requested send, and an accepted request is never
// reported as a verified state change. Controls verifies a fresh Park and zero speed before writing the permit;
// the agent stores a durable claim before calling the factory software and never repeats one, even after a restart.
import { createReporter } from './report.js';

/** Settings.Global document Controls writes: {"schema":1,"request":"<32 hex>","send":true,"boot":...,...}. */
export const APOLLO_CONFIG_KEY = 'org.openvoyah.controls.apolloFactory';
/** Settings.Global document the agent writes back, so Controls can show what the installed software offered. */
export const APOLLO_STATE_KEY = 'org.openvoyah.controls.apolloFactoryState';
/** The request the agent claimed before calling the factory manager; survives the process and the reboot. */
export const APOLLO_CLAIM_KEY = 'org.openvoyah.controls.apolloFactoryClaim';
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
const BOOT = /^[a-f0-9-]{8,64}$/;

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
    // A send needs the controller switch, the current kernel boot, a wall-clock issue time and a deadline.
    // Elapsed real time restarts on boot and cannot bound a permit read by a boot agent.
    wanted.send = document.send === true && wanted.controller === true
        && typeof document.boot === 'string' && BOOT.test(document.boot)
        && Number.isSafeInteger(document.issued) && Number.isSafeInteger(document.expires)
        && document.issued > 0 && document.expires > document.issued;
    if (wanted.send) { wanted.boot = document.boot; wanted.issued = document.issued; wanted.expires = document.expires; }
    return wanted;
}

/**
 * The state document the agent publishes. Only names, the matching request and a request count leave the process.
 * `requested` means the factory call was accepted; `uncertain` that the process ended before its result was
 * recorded; `partial` that a failed hook removal left a substitution running. None confirms the controller state.
 */
export function apolloState(applied, requestedFor, uncertainFor, missing, partial, request, sent) {
    const state = { schema: 1 };
    if (request !== null) state.request = request;
    state.sent = sent;
    for (const part of PARTS) {
        state[part] = partial.has(part) ? 'partial'
            : missing.has(part) ? 'unavailable'
            : request !== null && part === 'controller' && requestedFor === request ? 'requested'
            : request !== null && part === 'controller' && uncertainFor === request ? 'uncertain'
            : applied.includes(part) ? 'applied' : 'off';
    }
    return JSON.stringify(state);
}

export function installApolloFactory(Java, report = createReporter('OVC_APOLLO_ERROR')) {
    Java.perform(() => {
        let hooks = [], stuck = [], applied = [], missing = new Set(), partial = new Set(), sent = 0;
        let request = null, claimedFor = null, sentFor = null, uncertainFor = null, recordsReadable = true;
        /**
         * Only forget a hook once its original implementation is back. A failed removal stays on record so the
         * next tick can try again and the published state can say the substitution is still running.
         */
        const detach = () => {
            const remaining = [];
            for (const entry of hooks) {
                try { entry.target.implementation = null; }
                catch (error) { report('restore', error, { part: entry.part }); remaining.push(entry); }
            }
            hooks = [];
            stuck.push(...remaining);
            partial = new Set(stuck.map(entry => entry.part));
            return remaining.length === 0;
        };
        const restore = () => {
            detach();
            applied = [];
            missing = new Set();
        };
        /** Install the whole part or none of it; a throw between two hooks keeps every hook it already set. */
        const install = (entries, part) => {
            const installed = [];
            try {
                for (const [target, implementation] of entries) {
                    target.implementation = implementation;
                    installed.push(target);
                    // Retire an older removal record only after this member really carries the new hook. Removing
                    // it first would lose a live substitution if this assignment or a later one throws.
                    stuck = stuck.filter(entry => entry.target !== target || entry.part !== part);
                    partial = new Set(stuck.map(entry => entry.part));
                }
            } catch (error) {
                report('install', error, { part });
                let kept = false;
                for (const target of installed) {
                    try { target.implementation = null; }
                    catch (restoreError) {
                        report('restore', restoreError, { part });
                        stuck.push({ target, part });
                        kept = true;
                    }
                }
                if (kept) partial.add(part);
                else missing.add(part);
                return false;
            }
            for (const target of installed) hooks.push({ target, part });
            applied.push(part);
            return true;
        };
        /** Retry a removal that failed earlier, so a transient error does not leave a hidden substitution. */
        const heal = () => {
            if (stuck.length === 0) return;
            const remaining = [];
            for (const entry of stuck) {
                try { entry.target.implementation = null; }
                catch (error) { report('restore', error, { part: entry.part }); remaining.push(entry); }
            }
            stuck = remaining;
            partial = new Set(stuck.map(entry => entry.part));
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
        /** Names this kernel boot without depending on a clock that a reboot can move backwards. */
        const bootId = () => {
            let reader;
            try {
                reader = Java.use('java.io.BufferedReader').$new(
                    Java.use('java.io.FileReader').$new('/proc/sys/kernel/random/boot_id'));
                return String(reader.readLine()).trim();
            } catch (error) {
                report('boot', error);
                return null;
            } finally {
                if (reader !== null) { try { reader.close(); } catch (_) { /* Nothing left to undo. */ } }
            }
        };
        /**
         * The factory singleton builds and initializes on first use. 'failed' means the call threw after it began,
         * so the process may have sent something even though no result was recorded.
         */
        const requestAssistanceState = () => {
            const instance = method(APOLLO_CLASSES.manager, 'instance', [['android.content.Context']]);
            if (instance === null) return 'missing';
            try {
                const manager = instance.call(null, context());
                manager.sendAdasSubStatusToADCU(true);
                manager.asyncQueryAdasSubData();
                sent++;
                return 'ok';
            } catch (error) {
                report('send', error);
                return 'failed';
            }
        };
        const Global = Java.use('android.provider.Settings$Global');
        const resolver = Java.use('android.app.ActivityThread').currentApplication().getContentResolver();
        const apply = wanted => {
            if (wanted.subscription) {
                const query = method(APOLLO_CLASSES.subscription, 'doQuerySubscribeInfo',
                    [['android.content.Context'], null]);
                if (query === null) missing.add('subscription');
                else install([[query, function (configuration) { return SUBSCRIPTION_ANSWER; }]], 'subscription');
            }
            if (wanted.equipment) {
                // The factory page picks an equipment branch and a region from these predicates. Only what the page
                // shows changes; the readings behind them stay as the vehicle reported them.
                const specs = [
                    [APOLLO_CLASSES.equipment, 'is97Y', false], [APOLLO_CLASSES.equipment, 'is97C', true],
                    [APOLLO_CLASSES.region, 'isOverSeaVehicle', false],
                ];
                const predicates = specs.map(([className, name]) => method(className, name, [[]]));
                if (predicates.some(predicate => predicate === null)) missing.add('equipment');
                else install(predicates.map((predicate, index) =>
                    [predicate, function () { return specs[index][2]; }]), 'equipment');
            }
            const wall = Date.now();
            if (wanted.controller && wanted.send && wanted.request !== claimedFor
                && bootId() === wanted.boot && wall >= wanted.issued && wall <= wanted.expires) {
                // An unreadable earlier record cannot prove this is not a replay of a send whose result was lost.
                if (!recordsReadable) { uncertainFor = wanted.request; return; }
                // Settings.Global.putString can fail by returning false without throwing. No durable record means
                // no send: a restart could otherwise replay a request whose call was never acknowledged.
                let claimed = false;
                try { claimed = Global.putString(resolver, APOLLO_CLAIM_KEY, wanted.request) === true; }
                catch (error) { report('claim', error); }
                if (!claimed) { missing.add('controller'); return; }
                claimedFor = wanted.request;
                const outcome = requestAssistanceState();
                if (outcome === 'ok') { sentFor = wanted.request; uncertainFor = null; }
                else if (outcome === 'failed') uncertainFor = wanted.request;
                else {
                    // No call reached the factory manager, so this request can be tried again later.
                    try { Global.putString(resolver, APOLLO_CLAIM_KEY, ''); } catch (error) { report('claim', error); }
                    claimedFor = null;
                    missing.add('controller');
                }
            }
        };
        const publish = written => {
            try {
                Global.putString(resolver, APOLLO_STATE_KEY,
                    apolloState(applied, sentFor, uncertainFor, missing, partial, request, sent));
            } catch (error) { if (written) report('state', error); }
        };
        // The state document and the claim survive the process. A claimed request whose outcome was never recorded
        // is uncertain, never silently sent a second time. A failed read is not an empty record: until both reads
        // succeed, no new send may go out, because the previous one cannot be ruled out.
        const readRecords = seed => {
            let stateRaw, claimRaw;
            try { stateRaw = String(Global.getString(resolver, APOLLO_STATE_KEY) || ''); }
            catch (error) { recordsReadable = false; report('state_read', error); return; }
            try { claimRaw = String(Global.getString(resolver, APOLLO_CLAIM_KEY) || ''); }
            catch (error) { recordsReadable = false; report('claim_read', error); return; }
            recordsReadable = true;
            if (seed) {
                let prior = null;
                try { prior = JSON.parse(stateRaw); } catch (_) { /* An unreadable document seeds nothing. */ }
                if (prior && typeof prior.request === 'string' && REQUEST.test(prior.request)) {
                    request = prior.request;
                    if (Number.isSafeInteger(prior.sent) && prior.sent >= 0) sent = prior.sent;
                    if (prior.controller === 'requested') { sentFor = prior.request; claimedFor = prior.request; }
                    if (prior.controller === 'uncertain') { uncertainFor = prior.request; claimedFor = prior.request; }
                }
            }
            if ((seed || claimedFor === null) && REQUEST.test(claimRaw)) {
                claimedFor = claimRaw;
                if (sentFor !== claimRaw && uncertainFor !== claimRaw) uncertainFor = claimRaw;
            }
        };
        readRecords(true);
        let previous = null;
        const follow = () => {
            let raw;
            try { raw = String(Global.getString(resolver, APOLLO_CONFIG_KEY) || ''); }
            catch (error) {
                // Stay off while the provider is unavailable, and read the value again once it is back.
                previous = null;
                request = null;
                restore();
                report('settings', error);
                return;
            }
            if (raw === previous) {
                if (stuck.length) { heal(); publish(true); }
                return;
            }
            previous = raw;
            readRecords(false);
            restore();
            heal();
            const wanted = apolloConfig(raw);
            if (wanted === null) {
                // No document means every part is off, but a claim from a previous request stays on record.
                request = null;
            } else {
                request = wanted.request;
                apply(wanted);
            }
            publish(wanted !== null || stuck.length > 0);
        };
        follow();
        setInterval(() => Java.perform(follow), 1000);
        console.log('OVC_APOLLO_READY ' + String(Java.use('android.os.Build').PRODUCT.value));
    });
}
