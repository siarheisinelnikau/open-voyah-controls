// Decides when the experimental system reservation may be applied, and keeps a record that stops it from being
// applied again after it failed or may have ended system_server.
//
// The record lives in Settings.Global and names the attempt (a number the app raises each time the driver turns
// the option on), the kernel boot and the system_server process that wrote it:
//   armed     the reservation is about to be applied, or was applied and is not confirmed yet
//   verified  the layout was confirmed
//   failed    an undo did not complete
//   refused   the attempt was applied, did not hold and was undone; the record keeps the reason
//   clear     nothing of the reservation is left in the framework and nothing speaks against trying again
// For the same attempt, "refused" is final in every lifetime of system_server: applying and undoing changes the
// display configuration, which restarts activities, and must not repeat on every boot. A record from another
// lifetime is otherwise trusted only when it says "clear", or "verified" in an earlier boot. Anything else means
// the process ended, or was replaced, while the reservation was on: the option then reports "blocked". Only a new
// attempt number, which the driver gives by turning the option on again, starts over.

export const RESERVE_GUARD = 'org.openvoyah.controls.windowInsetsGuard';
// Settings writes reach the disk up to two seconds later. The reservation waits longer than that, so the record
// survives whatever the reservation does to the process.
const ARM_DELAY_MS = 3000;
const POLL_MS = 1000;
const PROBE_MS = 5000;
const SLOW_PROBE_MS = 60000;
const FAST_PROBES = 24;
const states = new Set(['armed', 'verified', 'failed', 'refused', 'clear']);
const reason = /^[a-z_]{1,32}$/;
// Nothing was changed and the cause can pass: the launcher has not added its panel yet.
const retried = new Set(['no_panel', 'busy']);

export function readGuard(raw) {
    if (typeof raw !== 'string' || raw === '' || raw.length > 256) return null;
    let value;
    try { value = JSON.parse(raw); } catch (_) { return null; }
    if (!value || value.schema !== 1 || !Number.isInteger(value.attempt) || value.attempt < 0 ||
        !validSession(value) || !states.has(value.state)) return null;
    // A refusal without a readable reason is not a record this agent wrote.
    if (value.state === 'refused' && !(typeof value.code === 'string' && reason.test(value.code))) return null;
    const guard = {attempt: value.attempt, boot: value.boot, pid: value.pid, state: value.state};
    if (value.state === 'refused') guard.code = value.code;
    return guard;
}

function validSession(value) {
    return typeof value?.boot === 'string' && /^[0-9a-f-]{16,64}$/i.test(value.boot) &&
        Number.isInteger(value.pid) && value.pid > 0;
}

/**
 * [reserve] is the framework side (window-reserve.js). [session] names this kernel boot and this process, [read]
 * and [write] access the record, [currentUser] and [panelWidth] read the present state, [announce] receives every
 * change of status, and [now] is a clock that never jumps: the waiting below must not shrink or stretch when the
 * head unit sets its wall clock after boot.
 */
export function reserveSwitch({reserve, session, read, write, currentUser, panelWidth, announce, now}) {
    let phase = 'off';      // off | probing | armed | active | halted
    let attempt = null, user = null, own = null;
    let dueAt = 0, probes = 0;
    let reclaim = false, verified = false, cleaned = false;
    // An undo that did not complete leaves the framework in an unknown state until the process ends.
    let stuck = false;
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

    function record(state, code) {
        if (own === null) return false;
        const document = {schema: 1, attempt, boot: own.boot, pid: own.pid, state};
        if (state === 'refused') document.code = reason.test(String(code)) ? code : 'failed';
        try {
            return write(JSON.stringify(document)) === true;
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

    function notUndone() {
        stuck = true;
        record('failed');
        halt('rollback_failed');
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
        if (stuck) return halt('rollback_failed');
        own = identify();
        if (own === null) return halt('guard_unavailable');
        const previous = guard();
        const sameProcess = previous !== null && previous.boot === own.boot && previous.pid === own.pid;
        // Only this process can still hold a registration that an earlier agent in it left behind.
        reclaim = sameProcess && (previous.state === 'armed' || previous.state === 'verified' || previous.state === 'failed');
        if (previous !== null && previous.attempt === attempt) {
            if (previous.state === 'refused') return halt(previous.code);
            if (!sameProcess) {
                if (previous.state === 'armed' || previous.state === 'failed') return halt('blocked');
                if (previous.state === 'verified' && previous.boot === own.boot) {
                    // system_server was replaced during this boot while the strip was reserved. Remember that,
                    // so the next boot does not try again on its own.
                    record('failed');
                    return halt('blocked');
                }
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
        // A refusal before anything was changed costs nothing, so it is simply found again by the next agent.
        if (!retried.has(found.status)) return halt(found.status);
        probeLater(time);
        say(found.status);
    }

    function apply(time) {
        const result = reserve.apply(panelWidth(), reclaim);
        // The leftover of an earlier agent is taken over once. A later attempt finds only what it made itself.
        if (result.status !== 'busy') reclaim = false;
        if (result.status === 'applied') {
            phase = 'active';
            dueAt = time + POLL_MS;
            return say('applied', result.width);
        }
        if (result.status === 'rollback_failed') return notUndone();
        if (retried.has(result.status)) {
            record('clear');
            probeLater(time);
            return say(result.status);
        }
        record('refused', result.status);
        halt(result.status);
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
                return notUndone();
            default: {
                // The reservation released itself, or is gone: panel_moved, verify_failed, already_reserved.
                const code = result.status === 'inactive' ? 'failed' : result.status;
                record('refused', code);
                return halt(code);
            }
        }
    }

    /** Returns whether nothing of the reservation is left in the framework. */
    function release() {
        if (!reserve.active()) return true;
        const released = reserve.release(false).status === 'released';
        if (released) record('clear');
        else {
            stuck = true;
            record('failed');
        }
        return released;
    }

    function stop() {
        let restored = true;
        if (phase === 'active') restored = release();
        else if (phase === 'armed') record('clear');
        phase = 'off';
        attempt = null;
        user = null;
        restored = restored && !stuck;
        say(restored ? 'inactive' : 'rollback_failed');
        return restored;
    }

    /**
     * Once per agent, while the option is off: remove what an agent that ended with it on left in this very
     * process. A record written by another lifetime is left as it is. It may be the block of an attempt that
     * ended system_server, and rewriting it would let that attempt run again.
     */
    function cleanup() {
        cleaned = true;
        const previous = guard();
        own = identify();
        const leftover = previous !== null && own !== null && previous.boot === own.boot && previous.pid === own.pid &&
            (previous.state === 'armed' || previous.state === 'verified' || previous.state === 'failed');
        if (!leftover) return say('inactive');
        attempt = previous.attempt;
        const released = reserve.release(true).status === 'released';
        if (released) record('clear');
        else {
            stuck = true;
            record('failed');
        }
        attempt = null;
        say(released ? 'inactive' : 'rollback_failed');
    }

    return {
        /** [want] is null while the option is off, otherwise {attempt, user}. Called from the settings timer. */
        sync(want) {
            if (want === null) {
                if (phase !== 'off') stop();
                else if (!cleaned) cleanup();
                return;
            }
            cleaned = true;
            const time = now();
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
            return phase === 'off' ? !stuck : stop();
        },
        /** The width Android keeps clear at the left edge, or 0 while the reservation is not in effect. */
        width: () => (phase === 'active' && reserve.active() ? reserve.width() : 0),
        /** The last announced status, for a request that asks instead of reading the log. */
        status: () => ({...current})
    };
}
