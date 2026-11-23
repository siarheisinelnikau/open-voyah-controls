// A two- or three-finger horizontal swipe that moves an app's window to another screen. Pure logic: the window
// agent feeds it the pointer events of a display and acts on what it returns.

export const MOTION = {down: 0, up: 1, move: 2, cancel: 3, pointerDown: 5, pointerUp: 6};

/**
 * Pixels, milliseconds and pixels per millisecond. All fingers have to land within [armMs] of the first one, so a
 * finger that joins a one-finger scroll later never starts a move. The fingers then travel [slopPx] together and
 * mostly sideways before anything follows them; a mostly vertical start (a map's two-finger tilt) is left alone.
 * A move needs [commitShare] of the display width, or a flick of at least [flickShare] at [flickSpeed].
 */
export const SWIPE = {armMs: 400, slopPx: 40, axisRatio: 1.6, fingerShare: 0.35, commitShare: 0.22,
    flickShare: 0.06, flickSpeed: 0.9, speedWindowMs: 120};

function centroid(points) {
    let x = 0, y = 0;
    for (const point of points) { x += point.x; y += point.y; }
    return [x / points.length, y / points.length];
}

/**
 * Follows the pointer events of one display. [feed] takes {action, count, time, points: [{id, x, y}]} and returns
 * one of {type: 'arm', x, y} (the fingers landed; x and y are their centre), {type: 'drag', dx}, {type: 'commit',
 * direction: 'left' | 'right', dx}, {type: 'cancel'} or null. Every 'arm' is followed by exactly one 'commit' or
 * 'cancel'. [wantsPoints] tells the caller when an event's coordinates are needed at all, so most touches cost two
 * reads of the event.
 */
export function swipeRecognizer(fingers, width, limits = SWIPE) {
    if ((fingers !== 2 && fingers !== 3) || !(width > 0)) throw Error('Invalid swipe');
    let state = 'idle';
    let downAt = 0, start = null, origin = null, samples = [], dx = 0;

    const tracking = () => state === 'armed' || state === 'dragging';
    const stop = next => {
        const was = tracking();
        state = next;
        start = origin = null;
        samples = [];
        return was ? {type: 'cancel'} : null;
    };
    /** Centre deltas of [points], or null when a finger is not one of those that armed the swipe. */
    const deltas = points => {
        if (points.length !== fingers || points.some(point => !start.has(point.id))) return null;
        const [x, y] = centroid(points);
        return {x, y, dx: x - origin[0], dy: y - origin[1],
            each: points.map(point => point.x - start.get(point.id)[0])};
    };
    const decide = (time, x) => {
        samples.push([time, x]);
        const first = samples.find(([at]) => time - at <= limits.speedWindowMs) ?? samples[samples.length - 1];
        const elapsed = time - first[0];
        const speed = elapsed > 0 ? (x - first[1]) / elapsed : 0;
        const distance = Math.abs(dx);
        const flick = distance >= limits.flickShare * width && Math.sign(speed) === Math.sign(dx) &&
            Math.abs(speed) >= limits.flickSpeed;
        return distance >= limits.commitShare * width || flick;
    };

    return {
        fingers,
        wantsPoints(action, count) {
            if (action === MOTION.pointerDown) return state === 'waiting' && count === fingers;
            if (action === MOTION.move || action === MOTION.pointerUp || action === MOTION.up) return tracking();
            return false;
        },
        feed({action, count, time, points = []}) {
            switch (action) {
            case MOTION.down: {
                const ended = stop('waiting');
                downAt = time;
                return ended;
            }
            case MOTION.pointerDown:
                if (state === 'waiting' && count === fingers) {
                    if (time - downAt > limits.armMs || points.length !== fingers) return stop('refused');
                    state = 'armed';
                    start = new Map(points.map(point => [point.id, [point.x, point.y]]));
                    origin = centroid(points);
                    samples = [[time, origin[0]]];
                    dx = 0;
                    return {type: 'arm', x: origin[0], y: origin[1]};
                }
                // A finger beyond the configured count is a different gesture.
                return state === 'waiting' && count < fingers ? null : stop('refused');
            case MOTION.move: {
                if (!tracking()) return null;
                const moved = deltas(points);
                if (!moved) return stop('refused');
                if (state === 'armed') {
                    const across = Math.abs(moved.dx), along = Math.abs(moved.dy);
                    if (along >= limits.slopPx && along * limits.axisRatio > across) return stop('refused');
                    if (across < limits.slopPx || across < limits.axisRatio * along) return null;
                    // Fingers moving apart (a pinch) do not all travel the same way.
                    const together = moved.each.every(each => Math.sign(each) === Math.sign(moved.dx) &&
                        Math.abs(each) >= across * limits.fingerShare);
                    if (!together) return stop('refused');
                    state = 'dragging';
                }
                dx = moved.dx;
                samples.push([time, moved.x]);
                while (samples.length > 2 && time - samples[1][0] > limits.speedWindowMs) samples.shift();
                return {type: 'drag', dx};
            }
            case MOTION.pointerUp:
            case MOTION.up: {
                if (state !== 'dragging') return stop(action === MOTION.up ? 'idle' : 'refused');
                const moved = deltas(points);
                if (moved) dx = moved.dx;
                const commit = decide(time, moved ? moved.x : origin[0] + dx);
                const result = {type: 'commit', direction: dx < 0 ? 'left' : 'right', dx};
                stop(action === MOTION.up ? 'idle' : 'refused');
                return commit ? result : {type: 'cancel'};
            }
            case MOTION.cancel:
                return stop('idle');
            default:
                return null;
            }
        },
    };
}

/**
 * Where a finished swipe sends a window. The central display (0) sends an app left to the instrument cluster and
 * right to the passenger display; a right swipe that starts over no movable app brings the cluster's window back,
 * since the cluster has no touch of its own. The passenger display sends its app left, back to the centre.
 * Returns {action: 'move', to} | {action: 'recall'} | null.
 */
export function swipeDestination({direction, display, app, cluster, passenger}) {
    if (display === 0) {
        if (!app) return direction === 'right' && cluster !== null ? {action: 'recall'} : null;
        const to = direction === 'left' ? cluster : passenger;
        return to === null || to === 0 ? null : {action: 'move', to};
    }
    if (display === passenger && app && direction === 'left') return {action: 'move', to: 0};
    return null;
}

/** How far the window follows the fingers: the full distance up to a third of the display, then damped. */
export function followOffset(dx, width) {
    const free = width / 3;
    const distance = Math.abs(dx);
    const shown = distance <= free ? distance : free + (distance - free) / 3;
    return Math.round(Math.sign(dx) * Math.min(shown, width / 2));
}
