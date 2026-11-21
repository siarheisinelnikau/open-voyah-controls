/** Own a complete edge gesture or leave it native from its first down. */
export function createCanEdgeRouter({keys, status, readConfig, canLaunch, launch, now, schedule, cancel, report}) {
    const held = new Map();
    const finished = new Map();
    const lifetime = 15000;

    function stop(press) {
        press.canceled = true;
        if (press.timer !== null) cancel(press.timer);
        press.timer = null;
    }
    function currentTime(press) {
        try {
            if (readConfig() !== press.raw) return null;
            const time = now();
            return Number.isSafeInteger(time) && time >= press.started && time - press.started <= lifetime ? time : null;
        } catch (_) { return null; }
    }
    function fire(press, gesture) {
        if (press.canceled || press.attempted) { stop(press); return; }
        const time = currentTime(press);
        if (time === null) { stop(press); return; }
        if (gesture === 'long' && time - press.started < press.holdMs) return;
        press.attempted = true;
        try {
            if (!canLaunch(press[gesture])) { report('launch-unavailable'); return; }
            const current = currentTime(press);
            if (press.canceled || current === null) { stop(press); return; }
            if (launch(press[gesture]) !== true) report('launch-unavailable');
        } catch (error) { report('launch-unavailable', error); }
    }
    function candidate(code, time) {
        // An unmapped key stays native without a Settings read.
        const key = keys.get(code);
        if (!key) return null;
        try {
            const raw = readConfig();
            const config = raw && JSON.parse(String(raw));
            if (config?.schema !== 1 || config.steeringSource !== 'can-edges'
                || config.steeringEnabled !== true || held.size > 0
                || !Number.isInteger(config.steeringHoldMs) || config.steeringHoldMs < 250 || config.steeringHoldMs > 2000
                || !Number.isInteger(config.steeringDebounceMs) || config.steeringDebounceMs < 0 || config.steeringDebounceMs > 500) return null;
            const short = config.steering?.[key + 'Short'], long = config.steering?.[key + 'Long'];
            // A missing half cannot fall back after its down has already been consumed.
            if (!short || !long || !canLaunch(short) || !canLaunch(long)) return null;
            if (currentTime({raw, started:time}) === null || held.size > 0) return null;
            const previous = finished.get(code);
            return {owned:true, started:time, raw, short, long, holdMs:config.steeringHoldMs,
                canceled:!!previous && time - previous.time <= config.steeringDebounceMs,
                attempted:false, timer:null};
        } catch (_) { return null; }
    }

    return (code, event) => {
        const time = now();
        const clockValid = Number.isSafeInteger(time) && time >= 0;
        for (const [key, press] of held) {
            if (!clockValid || time < press.started || time - press.started > lifetime) {
                if (press.owned) stop(press); // Keep ownership until up, even if a release was lost.
                else held.delete(key);
            }
        }
        for (const [key, release] of finished) {
            if (!clockValid || time < release.time || time - release.time > 500) finished.delete(key);
        }
        const existing = held.get(code);
        if (event === status.down) {
            if (existing) return existing.owned;
            for (const press of held.values()) if (press.owned) stop(press);
            if (!clockValid) return false;
            const press = candidate(code, time) || {owned:false, started:time};
            held.set(code, press);
            finished.delete(code);
            if (press.owned && !press.canceled) {
                try {
                    press.timer = schedule(() => {
                        press.timer = null;
                        if (held.get(code) === press) fire(press, 'long');
                    }, press.holdMs);
                } catch (_) {
                    held.set(code, {owned:false, started:time});
                    return false;
                }
            }
            return press.owned;
        }
        if (event === status.up) {
            if (!existing) return finished.has(code);
            // Retire before launch so reentrant delivery cannot dispatch the same press again.
            held.delete(code);
            if (!existing.owned) return false;
            finished.set(code, {time});
            if (existing.timer !== null) cancel(existing.timer);
            existing.timer = null;
            if (clockValid) fire(existing, time - existing.started >= existing.holdMs ? 'long' : 'short');
            return true;
        }
        if (existing?.owned) {
            // A terminal/mixed protocol must never add a native action to an owned gesture.
            stop(existing);
            return true;
        }
        return finished.has(code);
    };
}
