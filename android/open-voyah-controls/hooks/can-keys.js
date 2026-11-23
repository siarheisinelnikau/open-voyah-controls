/** Routes only OEM terminal key events. Edge-only protocols keep their native path. */
export function createCanKeyRouter({keys, status, readConfig, launch, now}) {
    const presses = new Map();
    const held = new Map();
    const lifetime = 15000;
    const releasedLifetime = 500;

    return (code, event) => {
        const time = now();
        if (!Number.isSafeInteger(time) || time < 0) return false;
        for (const [key, started] of held) {
            if (time < started || time - started > lifetime) held.delete(key);
        }
        for (const [key, press] of presses) {
            if (time < press.started || time - press.started > lifetime
                || press.released !== null && time - press.released > releasedLifetime) {
                presses.delete(key);
                held.delete(key);
            }
        }
        if (event === status.down) {
            if (held.has(code)) return false;
            held.set(code, time);
            // A chord belongs to the vehicle, including a second, unmapped key.
            if (held.size > 1) for (const press of presses.values()) press.canceled = true;
            if (keys.has(code)) presses.set(code, {
                started: time, released: null, config: readConfig(), canceled: held.size > 1,
                attempted: false, consumed: false, pending: false
            });
            return false;
        }
        const press = presses.get(code);
        if (event === status.up) {
            held.delete(code);
            if (press && press.released === null) press.released = time;
            return false;
        }
        const gesture = event === status.short ? 'Short' : event === status.long ? 'Long' : null;
        if (!gesture || !press) return false;
        if (press.attempted) return press.consumed || press.pending;
        if (press.canceled) return false;
        // Record even a failed/unassigned terminal event; a repeat must not launch it later.
        press.attempted = true;
        press.pending = true;
        try {
            const raw = readConfig();
            if (!raw || raw !== press.config) return false;
            const config = JSON.parse(String(raw));
            if (config?.schema !== 1 || config.steeringSource !== 'can-terminal'
                || config.steeringEnabled !== true) return false;
            const action = config.steering?.[keys.get(code) + gesture];
            if (!action) return false;
            const current = now();
            if (press.canceled || !Number.isSafeInteger(current) || current < press.started || current - press.started > lifetime
                || press.released !== null && (current < press.released || current - press.released > releasedLifetime)) return false;
            press.consumed = launch(action) === true;
        }
        finally { press.pending = false; }
        return press.consumed;
    };
}
