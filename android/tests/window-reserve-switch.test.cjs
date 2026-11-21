const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

// When the experimental reservation may be applied, and the record that keeps it from being applied again after
// it may have ended system_server.
const module_ = vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../hooks/window-reserve-switch.js'), 'utf8')
    .replace(/^export /gm, '') + '\n({RESERVE_GUARD, readGuard, reserveSwitch})');
const BOOT = '5f1c8a9e-0b7d-4c1a-9f5e-2d3c4b5a6978';
const OTHER_BOOT = '11111111-2222-4333-8444-555555555555';
const want = (attempt = 1, user = 0) => ({attempt, user});
const guardOf = (state, attempt = 1, boot = BOOT, pid = 900) => JSON.stringify({schema: 1, attempt, boot, pid, state});

function fixture(options = {}) {
    const script = options.script ?? {};
    const calls = [];
    let active = false;
    const reserve = {
        inspect(width) { calls.push(['inspect', width]); return script.inspect?.() ?? {status: 'ready', width: 171}; },
        apply(width, reclaim) {
            calls.push(['apply', width, reclaim]);
            const result = script.apply?.() ?? {status: 'applied', width: 171};
            active = result.status === 'applied';
            return result;
        },
        release(reclaim) {
            calls.push(['release', reclaim]);
            const result = script.release?.() ?? {status: 'released'};
            if (result.status === 'released') active = false;
            return result;
        },
        poll() {
            calls.push(['poll']);
            const result = script.poll?.() ?? {status: 'verified', width: 171};
            if (['panel_lost', 'panel_moved', 'verify_failed', 'already_reserved'].includes(result.status)) active = false;
            return result;
        },
        active: () => active,
        width: () => (active ? 171 : 0)
    };
    const state = {guard: options.guard ?? '', writes: [], lines: [], now: 0, clockReads: 0, user: 0, writable: true,
        session: options.session ?? (() => ({boot: BOOT, pid: 900}))};
    const control = module_.reserveSwitch({
        reserve,
        session: () => state.session(),
        read: () => state.guard,
        write: document => {
            if (!state.writable) return false;
            state.guard = document;
            state.writes.push(JSON.parse(document).state);
            return true;
        },
        currentUser: () => state.user,
        panelWidth: () => 171,
        announce: line => state.lines.push(line),
        now: () => { state.clockReads++; return state.now; }
    });
    const step = (wanted, millis = 400) => { state.now += millis; control.sync(wanted); };
    return {control, reserve, calls, state, step, script, stored: () => module_.readGuard(state.guard)};
}

test('the record accepts only a complete, bounded document', () => {
    assert.deepEqual({...module_.readGuard(guardOf('armed'))}, {attempt: 1, boot: BOOT, pid: 900, state: 'armed'});
    assert.deepEqual({...module_.readGuard(JSON.stringify({schema: 1, attempt: 2, boot: BOOT, pid: 7, state: 'refused', code: 'verify_failed'}))},
        {attempt: 2, boot: BOOT, pid: 7, state: 'refused', code: 'verify_failed'});
    for (const broken of ['', 'not json', '[]', guardOf('running'), guardOf('armed', -1), guardOf('armed', 1, 'boot id'),
        guardOf('refused'), JSON.stringify({schema: 1, attempt: 1, boot: BOOT, pid: 900, state: 'refused', code: 'Not A Code'}),
        guardOf('armed', 1, BOOT, 0), JSON.stringify({schema: 2, attempt: 1, boot: BOOT, pid: 900, state: 'armed'}),
        guardOf('armed') + ' '.repeat(300)]) {
        assert.equal(module_.readGuard(broken), null, broken.slice(0, 40));
    }
    assert.equal(module_.readGuard(null), null);
});

test('turning the option on records the attempt, waits for the record to reach the disk and then reserves', () => {
    const f = fixture();
    f.step(want());
    assert.deepEqual(f.calls, [['inspect', 171]]);
    assert.equal(f.stored().state, 'armed');
    assert.equal(f.control.width(), 0);
    // Settings writes are persisted up to two seconds later; nothing is changed before that.
    for (let waited = 400; waited < 3000; waited += 400) f.step(want());
    assert.deepEqual(f.calls, [['inspect', 171]]);
    f.step(want());
    assert.deepEqual(f.calls[1], ['apply', 171, false]);
    assert.equal(f.control.width(), 171);
    assert.equal(f.stored().state, 'armed');
    f.step(want(), 1000);
    assert.equal(f.stored().state, 'verified');
    f.step(want(), 1000);
    f.step(want(), 1000);
    assert.deepEqual(f.state.writes, ['armed', 'verified']);
    assert.deepEqual(f.state.lines, ['OVC_WINDOW_INSETS status=armed width=171', 'OVC_WINDOW_INSETS status=applied width=171',
        'OVC_WINDOW_INSETS status=verified width=171']);
    // The same state answers a request from the app, which cannot rely on a log the supervisor truncates.
    assert.deepEqual({...f.control.status()}, {status: 'verified', width: 171});
    assert.deepEqual({...fixture().control.status()}, {status: 'inactive', width: 0});
});

test('an attempt that another system_server lifetime left unfinished stays off until the driver asks again', () => {
    for (const previous of [guardOf('armed', 1, BOOT, 412), guardOf('armed', 1, OTHER_BOOT, 900),
        guardOf('failed', 1, OTHER_BOOT, 412)]) {
        const f = fixture({guard: previous});
        for (let tick = 0; tick < 20; tick++) f.step(want());
        assert.deepEqual(f.calls, []);
        assert.deepEqual(f.state.writes, []);
        assert.deepEqual(f.state.lines, ['OVC_WINDOW_INSETS status=blocked']);
        // The app raises the attempt number when the driver turns the option on again.
        f.step(want(2));
        assert.deepEqual(f.calls, [['inspect', 171]]);
        assert.equal(f.stored().attempt, 2);
        assert.equal(f.state.lines.at(-1), 'OVC_WINDOW_INSETS status=armed width=171');
    }
});

test('a system_server replaced while the strip was reserved blocks this boot and the following ones', () => {
    const f = fixture({guard: guardOf('verified', 1, BOOT, 412)});
    f.step(want());
    assert.deepEqual(f.calls, []);
    assert.deepEqual(f.state.lines, ['OVC_WINDOW_INSETS status=blocked']);
    assert.deepEqual({...f.stored()}, {attempt: 1, boot: BOOT, pid: 900, state: 'failed'});

    const nextBoot = fixture({guard: f.state.guard, session: () => ({boot: OTHER_BOOT, pid: 388})});
    nextBoot.step(want());
    assert.deepEqual(nextBoot.calls, []);
    assert.deepEqual(nextBoot.state.lines, ['OVC_WINDOW_INSETS status=blocked']);
});

test('a strip confirmed in an earlier boot is reserved again after a restart of the head unit', () => {
    const f = fixture({guard: guardOf('verified', 1, OTHER_BOOT, 412)});
    f.step(want());
    assert.equal(f.stored().state, 'armed');
    assert.equal(f.stored().boot, BOOT);
    f.step(want(), 3000);
    assert.deepEqual(f.calls.at(-1), ['apply', 171, false]);
});

test('an agent reloaded inside the same system_server takes over what its predecessor left', () => {
    const f = fixture({guard: guardOf('verified')});
    f.step(want());
    f.step(want(), 3000);
    assert.deepEqual(f.calls.at(-1), ['apply', 171, true]);
    // The takeover is a one-time permission: a later attempt must not remove a registration it did not record.
    f.step(null);
    f.step(want());
    f.step(want(), 3000);
    assert.deepEqual(f.calls.at(-1), ['apply', 171, false]);
});

test('without the launcher panel nothing is recorded or changed, and the wait slows down', () => {
    const script = {inspect: () => ({status: 'no_panel'})};
    const f = fixture({script});
    f.step(want());
    f.step(want());
    assert.equal(f.calls.length, 1);
    f.step(want(), 5000);
    assert.equal(f.calls.length, 2);
    assert.deepEqual(f.state.writes, []);
    assert.deepEqual(f.state.lines, ['OVC_WINDOW_INSETS status=no_panel']);
    // Two minutes of looking every five seconds, then once a minute for as long as the option stays on.
    for (let probe = 0; probe < 40; probe++) f.step(want(), 5000);
    const settled = f.calls.length;
    for (let step = 0; step < 24; step++) f.step(want(), 5000);
    assert.equal(f.calls.length - settled, 2);
    assert.deepEqual(f.state.writes, []);
    script.inspect = undefined;
    f.step(want(), 60000);
    assert.equal(f.stored().state, 'armed');
});

test('a firmware the reservation was not written for is refused once and left alone', () => {
    const f = fixture({script: {inspect: () => ({status: 'oem_policy'})}});
    for (let tick = 0; tick < 40; tick++) f.step(want(), 5000);
    assert.deepEqual(f.calls, [['inspect', 171]]);
    assert.deepEqual(f.state.writes, []);
    assert.deepEqual(f.state.lines, ['OVC_WINDOW_INSETS status=oem_policy']);
    f.step(null);
    assert.deepEqual(f.state.lines, ['OVC_WINDOW_INSETS status=oem_policy', 'OVC_WINDOW_INSETS status=inactive']);
    assert.deepEqual(f.calls, [['inspect', 171]]);
});

test('an attempt that did not hold is undone and never repeated; the next lifetime answers from the record', () => {
    const undone = fixture({script: {apply: () => ({status: 'verify_failed'})}});
    undone.step(want());
    undone.step(want(), 3000);
    assert.deepEqual(undone.state.writes, ['armed', 'refused']);
    assert.equal(undone.stored().code, 'verify_failed');
    assert.equal(undone.state.lines.at(-1), 'OVC_WINDOW_INSETS status=verify_failed');
    for (let tick = 0; tick < 10; tick++) undone.step(want(), 5000);
    assert.equal(undone.calls.length, 2);

    // Applying and undoing restarts activities. After a restart of the head unit the same attempt must not do
    // that again: the agent answers with the recorded reason and leaves the framework alone.
    for (const session of [() => ({boot: OTHER_BOOT, pid: 388}), () => ({boot: BOOT, pid: 901}), () => ({boot: BOOT, pid: 900})]) {
        const later = fixture({guard: undone.state.guard, session});
        for (let tick = 0; tick < 10; tick++) later.step(want(), 5000);
        assert.deepEqual(later.calls, []);
        assert.deepEqual(later.state.writes, []);
        assert.deepEqual(later.state.lines, ['OVC_WINDOW_INSETS status=verify_failed']);
        // Turning the option on again is a new attempt.
        later.step(want(2));
        assert.deepEqual(later.calls, [['inspect', 171]]);
    }

    // The panel went away while the record was settling: nothing changed, so the wait starts over.
    const script = {apply: () => ({status: 'no_panel'})};
    const gone = fixture({script});
    gone.step(want());
    gone.step(want(), 3000);
    assert.deepEqual(gone.state.writes, ['armed', 'clear']);
    script.apply = undefined;
    gone.step(want(), 5000);
    gone.step(want(), 3000);
    assert.equal(gone.control.width(), 171);
});

test('an undo that did not complete is final for this process and blocks the following ones', () => {
    const stuck = fixture({script: {apply: () => ({status: 'rollback_failed'})}});
    stuck.step(want());
    stuck.step(want(), 3000);
    assert.deepEqual(stuck.state.writes, ['armed', 'failed']);
    assert.equal(stuck.state.lines.at(-1), 'OVC_WINDOW_INSETS status=rollback_failed');
    // Turning the option off does not make the framework state known again, and a new attempt cannot start on it.
    stuck.step(null);
    assert.equal(stuck.control.status().status, 'rollback_failed');
    assert.equal(stuck.control.stop(), false);
    const calls = stuck.calls.length;
    for (let tick = 0; tick < 10; tick++) stuck.step(want(2), 5000);
    assert.equal(stuck.calls.length, calls);
    assert.equal(stuck.control.status().status, 'rollback_failed');

    const next = fixture({guard: stuck.state.guard, session: () => ({boot: OTHER_BOOT, pid: 388})});
    next.step(want());
    assert.deepEqual(next.calls, []);
    assert.deepEqual(next.state.lines, ['OVC_WINDOW_INSETS status=blocked']);
});

test('a lost panel is waited for again, while a moved one ends the attempt for good', () => {
    const script = {};
    const f = fixture({script});
    f.step(want());
    f.step(want(), 3000);
    f.step(want(), 1000);
    script.poll = () => ({status: 'waiting_panel', width: 171});
    f.step(want(), 1000);
    script.poll = () => ({status: 'panel_lost'});
    f.step(want(), 1000);
    assert.equal(f.stored().state, 'clear');
    assert.equal(f.control.width(), 0);
    script.poll = undefined;
    f.step(want(), 5000);
    f.step(want(), 3000);
    f.step(want(), 1000);
    assert.deepEqual(f.state.writes, ['armed', 'verified', 'clear', 'armed', 'verified']);
    assert.deepEqual(f.state.lines.slice(2), ['OVC_WINDOW_INSETS status=verified width=171',
        'OVC_WINDOW_INSETS status=waiting_panel width=171', 'OVC_WINDOW_INSETS status=panel_lost',
        'OVC_WINDOW_INSETS status=armed width=171', 'OVC_WINDOW_INSETS status=applied width=171',
        'OVC_WINDOW_INSETS status=verified width=171']);

    script.poll = () => ({status: 'panel_moved'});
    f.step(want(), 1000);
    assert.deepEqual({...f.stored()}, {attempt: 1, boot: BOOT, pid: 900, state: 'refused', code: 'panel_moved'});
    assert.equal(f.state.lines.at(-1), 'OVC_WINDOW_INSETS status=panel_moved');
    const before = f.calls.length;
    for (let tick = 0; tick < 10; tick++) f.step(want(), 5000);
    assert.equal(f.calls.length, before);
});

test('turning the option off releases the strip, and an incomplete release is recorded and reported', () => {
    const f = fixture();
    f.step(want());
    f.step(want(), 3000);
    f.step(null);
    assert.deepEqual(f.calls.at(-1), ['release', false]);
    assert.equal(f.stored().state, 'clear');
    assert.equal(f.control.width(), 0);
    assert.equal(f.state.lines.at(-1), 'OVC_WINDOW_INSETS status=inactive');
    assert.equal(f.control.stop(), true);

    const stuck = fixture({script: {release: () => ({status: 'rollback_failed'})}});
    stuck.step(want());
    stuck.step(want(), 3000);
    assert.equal(stuck.control.stop(), false);
    assert.equal(stuck.stored().state, 'failed');
    assert.equal(stuck.state.lines.at(-1), 'OVC_WINDOW_INSETS status=rollback_failed');
    assert.equal(stuck.control.stop(), false);

    // Turned off while the record was still settling: nothing was reserved, so the record is cleared.
    const early = fixture();
    early.step(want());
    assert.equal(early.control.stop(), true);
    assert.deepEqual(early.state.writes, ['armed', 'clear']);
    assert.deepEqual(early.calls, [['inspect', 171]]);
});

test('without a usable record the strip is never reserved', () => {
    for (const session of [() => { throw Error('EACCES'); }, () => ({boot: 'unknown', pid: 900}), () => null]) {
        const f = fixture({session});
        for (let tick = 0; tick < 10; tick++) f.step(want(), 1000);
        assert.deepEqual(f.calls, []);
        assert.deepEqual(f.state.lines, ['OVC_WINDOW_INSETS status=guard_unavailable']);
    }
    const readOnly = fixture();
    readOnly.state.writable = false;
    for (let tick = 0; tick < 10; tick++) readOnly.step(want(), 1000);
    assert.deepEqual(readOnly.calls, [['inspect', 171]]);
    assert.deepEqual(readOnly.state.lines, ['OVC_WINDOW_INSETS status=guard_unavailable']);
});

test('with the option off, only a recorded leftover of this very process is removed, once', () => {
    const untouched = fixture();
    untouched.step(null);
    untouched.step(null);
    assert.deepEqual(untouched.calls, []);
    assert.deepEqual(untouched.state.writes, []);
    assert.deepEqual(untouched.state.lines, ['OVC_WINDOW_INSETS status=inactive']);
    // With the option off the agent does not even read the clock.
    assert.equal(untouched.state.clockReads, 0);

    const leftover = fixture({guard: guardOf('verified')});
    leftover.step(null);
    leftover.step(null);
    assert.deepEqual(leftover.calls, [['release', true]]);
    assert.deepEqual({...leftover.stored()}, {attempt: 1, boot: BOOT, pid: 900, state: 'clear'});

    const stuck = fixture({guard: guardOf('verified'), script: {release: () => ({status: 'rollback_failed'})}});
    stuck.step(null);
    assert.equal(stuck.stored().state, 'failed');
    assert.deepEqual(stuck.state.lines, ['OVC_WINDOW_INSETS status=rollback_failed']);
});

test('a block written by another lifetime survives a start with the option off', () => {
    // An attempt ended system_server, the next lifetime reported "blocked", and the window service was then turned
    // off and on. The agent that starts in between sees the option off; it must not rewrite the record, or the
    // same attempt would run again as soon as the option is back.
    for (const previous of [guardOf('armed', 4, BOOT, 412), guardOf('failed', 4, OTHER_BOOT, 412),
        guardOf('verified', 4, BOOT, 412), JSON.stringify({schema: 1, attempt: 4, boot: OTHER_BOOT, pid: 412,
            state: 'refused', code: 'verify_failed'})]) {
        const f = fixture({guard: previous});
        f.step(null);
        f.step(null);
        assert.deepEqual(f.calls, []);
        assert.deepEqual(f.state.writes, []);
        assert.equal(f.state.guard, previous);
        f.step(want(4));
        assert.deepEqual(f.calls, []);
        assert.notEqual(f.state.lines.at(-1), 'OVC_WINDOW_INSETS status=armed width=171');
    }
});

test('the strip follows the configured Android user and a new attempt starts over', () => {
    const f = fixture();
    f.state.user = 10;
    f.step(want());
    f.step(want(), 5000);
    assert.deepEqual(f.calls, []);
    assert.deepEqual(f.state.lines, ['OVC_WINDOW_INSETS status=other_user']);
    f.state.user = 0;
    f.step(want(), 5000);
    f.step(want(), 3000);
    assert.equal(f.control.width(), 171);
    f.state.user = 10;
    f.step(want(), 1000);
    assert.deepEqual(f.calls.at(-1), ['release', false]);
    assert.equal(f.control.width(), 0);
    assert.equal(f.state.lines.at(-1), 'OVC_WINDOW_INSETS status=other_user');

    const again = fixture();
    again.step(want());
    again.step(want(), 3000);
    again.step(want(2));
    assert.deepEqual(again.calls.slice(-2), [['release', false], ['inspect', 171]]);
    assert.equal(again.stored().attempt, 2);
    assert.equal(again.stored().state, 'armed');
});

test('the leftover of an earlier agent is taken over once, not on every later attempt', () => {
    const script = {apply: () => ({status: 'no_panel'})};
    const f = fixture({guard: guardOf('verified'), script});
    f.step(want());
    f.step(want(), 3000);
    assert.deepEqual(f.calls.at(-1), ['apply', 171, true]);
    script.apply = undefined;
    f.step(want(), 5000);
    f.step(want(), 3000);
    assert.deepEqual(f.calls.at(-1), ['apply', 171, false]);
});
