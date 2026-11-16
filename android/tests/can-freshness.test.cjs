const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const context = vm.createContext({});
for (const file of ['can-edges.js', 'can-keys.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../hooks', file), 'utf8').replace(/^export /gm, ''), context);
}

function harness(mode, options = {}) {
    let time = 100, route, timerId = 0;
    const timers = new Map(), native = [], launched = [];
    const config = {schema:1, steeringSource:mode, steeringEnabled:true, steeringHoldMs:650, steeringDebounceMs:100,
        steering:{voiceShort:{kind:'tap'}, voiceLong:{kind:'hold'}}};
    const h = {
        config, options, timers, native, launched, phase:'down', preflights:0, reads:0,
        advance(ms) { time += ms; },
        emit(code, event) {
            const owned = route(code, event);
            if (!owned) native.push([code, event]);
            return owned;
        },
        fireTimers() {
            for (const [id, timer] of new Map(timers)) if (timer.at <= time) { timers.delete(id); timer.run(); }
        }
    };
    const inputs = {
        keys:new Map([[16, 'voice']]), status:{down:1, up:0, short:3, long:4}, now:() => time,
        readConfig() { h.reads++; options.read?.(h); return JSON.stringify(config); },
        canLaunch(action) { h.preflights++; options.preflight?.(h, action); return true; },
        launch(action) { launched.push(action.kind); options.launch?.(h); return true; },
        schedule(run, delay) { timers.set(++timerId, {run, at:time + delay}); return timerId; },
        cancel:id => timers.delete(id), report() {}
    };
    route = mode === 'can-edges' ? context.createCanEdgeRouter(inputs) : context.createCanKeyRouter(inputs);
    return h;
}

test('edge preflight expiry before capture preserves the whole native gesture', () => {
    const h = harness('can-edges', {preflight(current) {
        if (current.phase === 'down' && current.preflights === 2) current.advance(15_001);
    }});
    assert.equal(h.emit(16, 1), false);
    assert.equal(h.emit(16, 0), false);
    assert.deepEqual(h.native, [[16, 1], [16, 0]]);
    assert.deepEqual(h.launched, []);
    assert.equal(h.timers.size, 0);
});

test('a settings change during edge preflight preserves the whole native gesture', () => {
    const h = harness('can-edges', {preflight(current) {
        if (current.phase === 'down' && current.preflights === 2) current.config.steeringEnabled = false;
    }});
    h.emit(16, 1); h.emit(16, 0);
    assert.deepEqual(h.native, [[16, 1], [16, 0]]);
    assert.deepEqual(h.launched, []);
    assert.equal(h.timers.size, 0);
});

test('a chord arriving during edge preflight is native before either replacement edge is owned', () => {
    const h = harness('can-edges', {preflight(current) {
        if (current.phase === 'down' && current.preflights === 2) current.emit(99, 1);
    }});
    assert.equal(h.emit(16, 1), false);
    h.emit(16, 0); h.emit(99, 0);
    assert.deepEqual(h.native, [[99, 1], [16, 1], [16, 0], [99, 0]]);
    assert.deepEqual(h.launched, []);
});

test('an edge preflight completing exactly at the lifetime boundary can still capture', () => {
    const h = harness('can-edges', {preflight(current) {
        if (current.phase === 'down' && current.preflights === 2) current.advance(15_000);
    }});
    assert.equal(h.emit(16, 1), true);
    h.phase = 'dispatch'; h.emit(16, 0); h.emit(16, 0);
    assert.deepEqual(h.native, []);
    assert.deepEqual(h.launched, ['hold']);
});

test('late target preflight expiry cancels tap, released hold and timed hold without leaking owned edges', () => {
    for (const gesture of ['tap', 'released-hold', 'timed-hold']) {
        const h = harness('can-edges', {preflight(current) {
            if (current.phase === 'dispatch') current.advance(15_001);
        }});
        h.emit(16, 1); h.phase = 'dispatch'; h.advance(gesture === 'tap' ? 100 : 650);
        if (gesture === 'timed-hold') h.fireTimers();
        assert.equal(h.emit(16, 0), true);
        h.fireTimers();
        assert.deepEqual(h.launched, []);
        assert.deepEqual(h.native, []);
        assert.equal(h.timers.size, 0);
    }
});

test('a settings change during final edge preflight cannot launch a stale assignment or retry it', () => {
    for (const timed of [false, true]) {
        const h = harness('can-edges', {preflight(current) {
            if (current.phase === 'dispatch') current.config.steeringEnabled = false;
        }});
        h.emit(16, 1); h.phase = 'dispatch'; h.advance(timed ? 650 : 100);
        if (timed) h.fireTimers();
        h.emit(16, 0); h.emit(16, 0);
        assert.deepEqual(h.launched, []);
        assert.deepEqual(h.native, []);
        h.options.preflight = null; h.config.steeringEnabled = true; h.advance(101);
        h.emit(16, 1); h.advance(100); h.emit(16, 0); h.emit(16, 0);
        assert.deepEqual(h.launched, ['tap']);
    }
});

test('a final edge preflight at the exact lifetime boundary launches once', () => {
    const h = harness('can-edges', {preflight(current) {
        if (current.phase === 'dispatch') current.advance(1);
    }});
    h.emit(16, 1); h.phase = 'dispatch'; h.advance(14_999);
    h.emit(16, 0); h.emit(16, 0); h.fireTimers();
    assert.deepEqual(h.launched, ['hold']);
    assert.deepEqual(h.native, []);
});

test('a terminal settings read cannot extend a press beyond its lifetime', () => {
    const h = harness('can-terminal', {read(current) {
        if (current.phase === 'dispatch') current.advance(15_001);
    }});
    h.emit(16, 1); h.phase = 'dispatch'; h.emit(16, 3); h.emit(16, 3); h.emit(16, 0);
    assert.deepEqual(h.launched, []);
    assert.deepEqual(h.native, [[16, 1], [16, 3], [16, 3], [16, 0]]);
});

test('a terminal settings read completing at the exact lifetime boundary can dispatch once', () => {
    const h = harness('can-terminal', {read(current) {
        if (current.phase === 'dispatch') current.advance(15_000);
    }});
    h.emit(16, 1); h.phase = 'dispatch'; h.emit(16, 3); h.emit(16, 3); h.emit(16, 0);
    assert.deepEqual(h.launched, ['tap']);
    assert.deepEqual(h.native, [[16, 1], [16, 0]]);
});

test('fresh terminal time also preserves the existing post-release lifetime boundary', () => {
    for (const delay of [100, 101]) {
        const h = harness('can-terminal', {read(current) {
            if (current.phase === 'dispatch') current.advance(delay);
        }});
        h.emit(16, 1); h.advance(100); h.emit(16, 0); h.advance(400); h.phase = 'dispatch'; h.emit(16, 3);
        assert.deepEqual(h.launched, delay === 100 ? ['tap'] : []);
        assert.deepEqual(h.native, delay === 100 ? [[16, 1], [16, 0]] : [[16, 1], [16, 0], [16, 3]]);
    }
});

test('a reentrant terminal during settings validation cannot add a native or duplicate action', () => {
    let repeated = false;
    const h = harness('can-terminal', {read(current) {
        if (current.phase === 'dispatch' && !repeated) { repeated = true; current.emit(16, 3); }
    }});
    h.emit(16, 1); h.phase = 'dispatch'; h.emit(16, 3); h.emit(16, 0);
    assert.deepEqual(h.launched, ['tap']);
    assert.deepEqual(h.native, [[16, 1], [16, 0]]);
});

test('terminal configuration changes during validation retain native fallback without later retry', () => {
    const h = harness('can-terminal', {read(current) {
        if (current.phase === 'dispatch') current.config.steeringEnabled = false;
    }});
    h.emit(16, 1); h.phase = 'dispatch'; h.emit(16, 3);
    h.config.steeringEnabled = true; h.options.read = null; h.emit(16, 3); h.emit(16, 0);
    assert.deepEqual(h.launched, []);
    assert.deepEqual(h.native, [[16, 1], [16, 3], [16, 3], [16, 0]]);
});
