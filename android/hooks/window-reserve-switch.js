// Decides when the experimental system reservation may be applied, and keeps a record that stops it from being
// applied again after it may have ended system_server.
//
// The record lives in Settings.Global and names the attempt (a number the app raises each time the driver turns
// the option on), the kernel boot and the system_server process that wrote it:
//   armed     the reservation is about to be applied, or was applied and is not confirmed yet
//   verified  the layout was confirmed
//   failed    an undo did not complete
//   clear     nothing of the reservation is left in the framework
// A record of the same attempt from another system_server lifetime is trusted only when it says "clear", or
// "verified" in an earlier boot. Anything else means the process ended, or was replaced, while the reservation
// was on: the option then reports "blocked" and stays off until the driver turns it on again.

export const RESERVE_GUARD = 'org.openvoyah.controls.windowInsetsGuard';
// Settings writes reach the disk up to two seconds later. The reservation waits longer than that, so the record
// survives whatever the reservation does to the process.
const ARM_DELAY_MS = 3000;
const POLL_MS = 1000;
const PROBE_MS = 5000;
const SLOW_PROBE_MS = 60000;
const FAST_PROBES = 24;
const states = new Set(['armed', 'verified', 'failed', 'clear']);
// Nothing was changed and the cause can pass: the launcher has not added its panel yet.
const retried = new Set(['no_panel', 'busy']);

export function readGuard(raw) {
    if (typeof raw !== 'string' || raw === '' || raw.length > 256) return null;
    let value;
    try { value = JSON.parse(raw); } catch (_) { return null; }
    if (!value || value.schema !== 1 || !Number.isInteger(value.attempt) || value.attempt < 0 ||
        !validSession(value) || !states.has(value.state)) return null;
    return {attempt: value.attempt, boot: value.boot, pid: value.pid, state: value.state};
}

function validSession(value) {
    return typeof value?.boot === 'string' && /^[0-9a-f-]{16,64}$/i.test(value.boot) &&
        Number.isInteger(value.pid) && value.pid > 0;
}

/**
 * [reserve] is the framework side (window-reserve.js). [session] names this kernel boot and this process,
 * [read] and [write] access the record, [currentUser] and [panelWidth] read the present state, [releaseTasks]
 * returns tasks this agent placed beside the panel to Android, and [announce] receives every change of status.
 */
export function reserveSwitch({reserve, session, read, write, currentUser, panelWidth, releaseTasks, announce, now}) {
    let phase = 'off';      // off | probing | armed | active | halted
    let attempt = null, user = null, own = null;
    let dueAt = 0, probes = 0;
    let reclaim = false, verified = false, cleaned = false;
    let announced = '';
    let current = {status: 'inactive', width: 0};

    function say(status, width = 0) {
        current = {status, width};
        const line = 'OVC_WINDOW_INSETS status=' + status + (width > 0 ? ' width=' + width : '');
        if (line === announced) return;
        announced = line;
        announce(line);
    }

    function guard() {
        try { return readGuard(String(read() ?? '')); } catch (_) { return null; }
    }

    function record(state) {
        if (own === null) return false;
        try {
            return write(JSON.stringify({schema: 1, attempt, boot: own.boot, pid: own.pid, state})) === true;
        } catch (_) {
            return false;
        }
    }

    function identify() {
        try {
            const identity = session();
            return validSession(identity) ? {boot: identity.boot, pid: identity.pid} : null;
        } catch (_) {
            return null;
        }
    }

    function halt(status) {
        phase = 'halted';
        say(status);
    }

    function probeLater(time) {
        phase = 'probing';
        probes++;
        dueAt = time + (probes <= FAST_PROBES ? PROBE_MS : SLOW_PROBE_MS);
    }

    function begin(want, time) {
        attempt = want.attempt;
        user = want.user;
        verified = false;
        probes = 0;
        own = identify();
        if (own === null) return halt('guard_unavailable');
        const previous = guard();
        const sameProcess = previous !== null && previous.boot === own.boot && previous.pid === own.pid;
        // Only this process can still hold a registration that an earlier agent in it left behind.
        reclaim = sameProcess && previous.state !== 'clear';
        if (previous !== null && previous.attempt === attempt && !sameProcess) {
            if (previous.state === 'armed' || previous.state === 'failed') return halt('blocked');
            if (previous.state === 'verified' && previous.boot === own.boot) {
                // system_server was replaced during this boot while the strip was reserved. Remember that, so
                // the next boot does not try again on its own.
                record('failed');
                return halt('blocked');
            }
        }
        phase = 'probing';
        dueAt = time;
    }

    function probe(time) {
        if (currentUser() !== user) {
            probeLater(time);
            return say('other_user');
        }
        const found = reserve.inspect(panelWidth());
        if (found.status === 'ready') {
            if (!record('armed')) return halt('guard_unavailable');
            phase = 'armed';
            dueAt = time + ARM_DELAY_MS;
            return say('armed', found.width);
        }
        if (!retried.has(found.status)) return halt(found.status);
        probeLater(time);
        say(found.status);
    }

    function apply(time) {
        const result = reserve.apply(panelWidth(), reclaim);
        if (result.status === 'applied') {
            reclaim = false;
            phase = 'active';
            dueAt = time + POLL_MS;
            // Tasks that this agent had moved beside the panel return to Android, which now keeps the strip clear.
            // One that cannot be returned stays where it is, still beside the panel.
            try { releaseTasks(user); } catch (_) { }
            return say('applied', result.width);
        }
        if (result.status === 'rollback_failed') {
            record('failed');
            return halt('rollback_failed');
        }
        record('clear');
        if (!retried.has(result.status)) return halt(result.status);
        probeLater(time);
        say(result.status);
    }

    function poll(time) {
        dueAt = time + POLL_MS;
        if (currentUser() !== user) {
            if (!release()) return halt('rollback_failed');
            probeLater(time);
            return say('other_user');
        }
        const result = reserve.poll();
        switch (result.status) {
            case 'verified':
                if (!verified) verified = record('verified');
                return say('verified', result.width);
            case 'applied': case 'verifying': case 'reregistered': case 'readopted':
                return say('applied', result.width);
            case 'waiting_panel':
                return say('waiting_panel', result.width);
            case 'busy':
                return;
            case 'panel_lost':
                // The launcher may come back later; the strip is reserved again once its panel does.
                record('clear');
                verified = false;
                probeLater(time);
                return say('panel_lost');
            case 'rollback_failed':
                record('failed');
                return halt('rollback_failed');
            default:
                // The reservation released itself, or is gone: panel_moved, verify_failed, already_reserved.
                record('clear');
                return halt(result.status === 'inactive' ? 'failed' : result.status);
        }
    }

    /** Returns whether nothing of the reservation is left in the framework. */
    function release() {
        if (!reserve.active()) return true;
        const result = reserve.release(false);
        record(result.status === 'released' ? 'clear' : 'failed');
        return result.status === 'released';
    }

    function stop() {
        let restored = true;
        if (phase === 'active') restored = release();
        else if (phase === 'armed') record('clear');
        phase = 'off';
        attempt = null;
        user = null;
        say(restored ? 'inactive' : 'rollback_failed');
        return restored;
    }

    /** Once per agent, while the option is off: remove what an agent that ended with it on left in this process. */
    function cleanup() {
        cleaned = true;
        const previous = guard();
        if (previous === null || previous.state === 'clear') return say('inactive');
        own = identify();
        let restored = true;
        if (own !== null && previous.boot === own.boot && previous.pid === own.pid) {
            restored = reserve.release(true).status === 'released';
        }
        if (own !== null) {
            attempt = previous.attempt;
            record(restored ? 'clear' : 'failed');
            attempt = null;
        }
        say(restored ? 'inactive' : 'rollback_failed');
    }

    return {
        /** [want] is null while the option is off, otherwise {attempt, user}. Called from the settings timer. */
        sync(want) {
            const time = now();
            if (want === null) {
                if (phase !== 'off') stop();
                else if (!cleaned) cleanup();
                return;
            }
            cleaned = true;
            if (phase === 'off' || want.attempt !== attempt || want.user !== user) {
                if (phase !== 'off') stop();
                begin(want, time);
            }
            if (time < dueAt) return;
            if (phase === 'probing') probe(time);
            else if (phase === 'armed') apply(time);
            else if (phase === 'active') poll(time);
        },
        /** Ends the reservation at once. Returns whether nothing of it is left in the framework. */
        stop() {
            return phase === 'off' ? true : stop();
        },
        /** The width Android keeps clear at the left edge, or 0 while the reservation is not in effect. */
        width: () => (phase === 'active' && reserve.active() ? reserve.width() : 0),
        /** The last announced status, for a request that asks instead of reading the log. */
        status: () => ({...current})
    };
}
