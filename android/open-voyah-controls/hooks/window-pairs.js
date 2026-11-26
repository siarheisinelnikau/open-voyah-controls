import { CONTROLS } from './window-profiles.js';

const PREFIX = CONTROLS + '.extra.PAIR_';
const ID = PREFIX + 'ID';
const SIDE = PREFIX + 'SIDE';
const WIDTH = PREFIX + 'LEFT_PERCENT';
const proportions = new Set([33, 50, 67]);

export function readPairLaunch(record, launch) {
    if (launch?.caller !== CONTROLS || launch.requestedDisplay > 0 || launch.display > 0) return null;
    const intent = record.intent.value;
    const id = String(intent.getStringExtra(ID));
    if (!/^[a-f0-9]{32}$/.test(id)) return null;
    const side = intent.getIntExtra(SIDE, -1);
    const leftPercent = intent.getIntExtra(WIDTH, -1);
    intent.removeExtra(ID);
    intent.removeExtra(SIDE);
    intent.removeExtra(WIDTH);
    return (side === 0 || side === 1) && proportions.has(leftPercent) ? {id, side, leftPercent} : null;
}

export function pairFrames(frame, leftPercent, leftMinimum, rightMinimum) {
    if (!proportions.has(leftPercent)) return null;
    const [left, top, right, bottom] = frame;
    const width = right - left;
    if (bottom - top < Math.max(1, leftMinimum[1], rightMinimum[1])) return null;
    const smallest = Math.max(1, leftMinimum[0]);
    const largest = width - Math.max(1, rightMinimum[0]);
    if (smallest > largest) return null;
    const divider = left + Math.min(largest, Math.max(smallest, Math.round(width * leftPercent / 100)));
    return [[left, top, divider, bottom], [divider, top, right, bottom]];
}

/** Collects only the task IDs produced by the two launcher starts. Missing or repeated starts never place a pair. */
export function pairStarts({schedule, apply, reply}) {
    const requests = new Map();
    const finished = new Set();
    function finish(id, status) {
        requests.delete(id);
        finished.add(id);
        if (finished.size > 32) finished.delete(finished.values().next().value);
        reply(id, status);
    }
    return (pair, user, component, taskId) => {
        const {id, side, leftPercent} = pair;
        if (finished.has(id)) return;
        let request = requests.get(id);
        if (!request) {
            if (requests.size >= 8) return finish(id, 'failed');
            request = {user, leftPercent, sides: []};
            requests.set(id, request);
            schedule(() => { if (requests.get(id) === request) finish(id, 'incomplete'); }, 3500);
        }
        if (request.user !== user || request.leftPercent !== leftPercent || request.sides[side] || taskId == null) {
            finish(id, 'incomplete');
            return;
        }
        request.sides[side] = {component, taskId};
        if (!request.sides[0] || !request.sides[1]) return;
        if (request.sides[0].taskId === request.sides[1].taskId ||
            request.sides[0].component.split('/')[0] === request.sides[1].component.split('/')[0]) {
            finish(id, 'unsupported_task');
            return;
        }
        schedule(() => {
            if (requests.get(id) !== request) return;
            let status;
            try { status = apply(request); } catch (error) { status = error?.code ?? 'failed'; }
            finish(id, status);
        }, 0);
    };
}
