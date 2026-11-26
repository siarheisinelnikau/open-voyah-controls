const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const js = fs.readFileSync(path.join(__dirname, '../hooks/window-pairs.js'), 'utf8')
    .replace(/^import .*$/gm, '').replace(/^export /gm, '');
const api = vm.runInNewContext("const CONTROLS = 'org.openvoyah.controls';\n" + js +
    '\n({pairFrames, pairStarts, readPairLaunch})');
const plain = value => JSON.parse(JSON.stringify(value));

test('pair geometry uses the already measured panel edge and keeps the status bar clear', () => {
    const frame = [175, 70, 1815, 720];
    assert.deepEqual(plain(api.pairFrames(frame, 50, [300, 100], [300, 100])),
        [[175, 70, 995, 720], [995, 70, 1815, 720]]);
    assert.deepEqual(plain(api.pairFrames(frame, 33, [800, 300], [300, 100])),
        [[175, 70, 975, 720], [975, 70, 1815, 720]]);
    assert.deepEqual(plain(api.pairFrames(frame, 67, [300, 300], [800, 100])),
        [[175, 70, 1015, 720], [1015, 70, 1815, 720]]);
    assert.equal(api.pairFrames(frame, 50, [900, 100], [900, 100]), null);
    assert.equal(api.pairFrames(frame, 50, [300, 700], [300, 100]), null);
    assert.equal(api.pairFrames(frame, 12, [300, 100], [300, 100]), null);
});

const id = 'a'.repeat(32);
const left = 'org.example.map/org.example.map.Main';
const right = 'org.example.radio/org.example.radio.Main';
const mark = (side, values = {}) => ({id, side, leftPercent:67, ...values});

function fixture() {
    const waiting = [];
    const applied = [];
    const results = [];
    const start = api.pairStarts({
        schedule: (work, delay) => waiting.push({work, delay}),
        apply: request => { applied.push(plain(request)); return 'applied'; },
        reply: (token, outcome) => results.push([token, outcome]),
    });
    const runTimers = delay => waiting.filter(item => item.delay === delay).forEach(item => item.work());
    return {start, applied, runTimers, results};
}

test('only both tasks of the same request start a pair, with at-most-once placement and receipt', () => {
    const f = fixture();
    f.start(mark(1), 0, right, 9);
    f.runTimers(0);
    assert.deepEqual(f.applied, []);
    f.start(mark(0), 0, left, 4);
    f.runTimers(0);
    f.runTimers(3500);
    f.start(mark(0), 0, left, 4);
    f.runTimers(0);
    assert.deepEqual(f.applied, [{user:0, leftPercent:67, sides:[{component:left,taskId:4},{component:right,taskId:9}]}]);
    assert.deepEqual(f.results, [[id, 'applied']]);
});

test('a partial, duplicate or mismatched start never places either task', () => {
    for (const [second, expected] of [
        [f => f.runTimers(3500), 'incomplete'],
        [f => f.start(mark(0), 0, left, 4), 'incomplete'],
        [f => f.start(mark(1), 10, right, 9), 'incomplete'],
        [f => f.start(mark(1, {leftPercent:33}), 0, right, 9), 'incomplete'],
        [f => f.start(mark(1), 0, right, null), 'incomplete'],
        [f => f.start(mark(1), 0, right, 4), 'unsupported_task'],
        [f => f.start(mark(1), 0, left, 9), 'unsupported_task'],
    ]) {
        const f = fixture();
        f.start(mark(0), 0, left, 4);
        second(f);
        f.runTimers(0);
        assert.deepEqual(f.applied, []);
        assert.deepEqual(f.results, [[id, expected]]);
    }
});

test('markers are consumed only on central launcher intents from Controls', () => {
    const extras = new Map([
        ['org.openvoyah.controls.extra.PAIR_ID', id],
        ['org.openvoyah.controls.extra.PAIR_SIDE', 1],
        ['org.openvoyah.controls.extra.PAIR_LEFT_PERCENT', 67],
    ]);
    const record = {intent:{value:{
        getStringExtra: key => extras.get(key) ?? null,
        getIntExtra: (key, fallback) => extras.get(key) ?? fallback,
        removeExtra: key => extras.delete(key),
    }}};
    const launch = {caller:'org.openvoyah.controls', display:0, requestedDisplay:0};
    assert.equal(api.readPairLaunch(record, {...launch, caller:'org.example.app'}), null);
    assert.equal(api.readPairLaunch(record, {...launch, requestedDisplay:1}), null);
    assert.deepEqual(plain(api.readPairLaunch(record, launch)), mark(1));
    assert.equal(extras.size, 0);
    assert.equal(api.readPairLaunch(record, launch), null);
});
