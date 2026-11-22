const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const source = (...files) => files.map(file => fs.readFileSync(path.join(__dirname, '../hooks', file), 'utf8')
    .replace(/^import [\s\S]*?;$/gm, '').replace(/^export /gm, '')).join('\n');
const gesture = vm.runInNewContext(source('window-gesture.js') +
    '\n({MOTION, SWIPE, swipeRecognizer, swipeDestination, followOffset})');
const move = vm.runInNewContext(source('window-profiles.js', 'window-gesture.js', 'window-display-move.js') +
    '\n({displayMover, movableTask, componentKey, readWindowConfig, readWindowRequest})');
const {MOTION, swipeRecognizer, swipeDestination, followOffset} = gesture;

/** Fingers [ids] at x positions [xs] (y 300 + 40 per finger), at [time]. */
const points = (xs, y = 300) => xs.map((x, id) => ({id, x, y: y + id * 40}));

/** Lands [fingers] fingers at x = [x] 20 ms apart and returns the recognizer's last answer. */
function land(swipe, fingers, x = 900, start = 0) {
    let result = swipe.feed({action: MOTION.down, count: 1, time: start, points: points([x])});
    for (let count = 2; count <= fingers; count++) {
        result = swipe.feed({action: MOTION.pointerDown, count, time: start + (count - 1) * 20,
            points: points(Array(count).fill(x))});
    }
    return result;
}
const moveTo = (swipe, fingers, x, time, y = 300) =>
    swipe.feed({action: MOTION.move, count: fingers, time, points: points(Array(fingers).fill(x), y)});
const lift = (swipe, fingers, x, time) =>
    swipe.feed({action: MOTION.pointerUp, count: fingers, time, points: points(Array(fingers).fill(x))});

test('a slow three-finger drag past the distance moves, a short one cancels', () => {
    const swipe = swipeRecognizer(3, 1920);
    assert.deepEqual({...land(swipe, 3)}, {type: 'arm', x: 900, y: 340});
    assert.equal(moveTo(swipe, 3, 880, 100), null, 'inside the slop nothing follows yet');
    assert.deepEqual({...moveTo(swipe, 3, 700, 400)}, {type: 'drag', dx: -200});
    assert.deepEqual({...moveTo(swipe, 3, 450, 1000)}, {type: 'drag', dx: -450});
    assert.deepEqual({...lift(swipe, 3, 450, 1200)}, {type: 'commit', direction: 'left', dx: -450});
    // The other fingers lifting afterwards end nothing more.
    assert.equal(swipe.feed({action: MOTION.up, count: 1, time: 1250}), null);

    land(swipe, 3, 900, 5000);
    moveTo(swipe, 3, 1100, 5400);
    assert.deepEqual({...lift(swipe, 3, 1150, 6000)}, {type: 'cancel'}, '250 px slowly is not enough');
});

test('a quick flick moves with a shorter distance, in the direction it was thrown', () => {
    const swipe = swipeRecognizer(2, 1920);
    land(swipe, 2);
    moveTo(swipe, 2, 960, 20);
    moveTo(swipe, 2, 1040, 50);
    assert.deepEqual({...lift(swipe, 2, 1080, 70)}, {type: 'commit', direction: 'right', dx: 180});

    // Thrown back at the end: the release speed points the other way, so it is not a flick to the right.
    land(swipe, 2, 900, 1000);
    moveTo(swipe, 2, 1100, 1300);
    moveTo(swipe, 2, 1060, 1340);
    assert.deepEqual({...lift(swipe, 2, 1020, 1360)}, {type: 'cancel'});
});

test('the configured finger count is exact and the fingers have to land together', () => {
    const two = swipeRecognizer(2, 1920);
    land(two, 3);
    assert.equal(moveTo(two, 3, 500, 300), null, 'a third finger turns a two-finger swipe off');

    const three = swipeRecognizer(3, 1920);
    land(three, 2);
    assert.equal(moveTo(three, 2, 500, 300), null, 'two fingers do not start a three-finger swipe');

    // A finger joining a one-finger scroll later is a different gesture.
    three.feed({action: MOTION.down, count: 1, time: 0, points: points([900])});
    three.feed({action: MOTION.pointerDown, count: 2, time: 300, points: points([900, 900])});
    assert.equal(three.feed({action: MOTION.pointerDown, count: 3, time: 500, points: points([900, 900, 900])}), null);
    assert.equal(moveTo(three, 3, 400, 700), null);

    // An extra finger during a swipe cancels it.
    land(three, 3, 900, 2000);
    moveTo(three, 3, 700, 2200);
    assert.deepEqual({...three.feed({action: MOTION.pointerDown, count: 4, time: 2300, points: points([700, 700, 700, 700])})},
        {type: 'cancel'});
    assert.throws(() => swipeRecognizer(4, 1920));
});

test('vertical drags and pinches stay with the app under the fingers', () => {
    const swipe = swipeRecognizer(2, 1920);
    land(swipe, 2);
    assert.deepEqual({...moveTo(swipe, 2, 905, 200, 400)}, {type: 'cancel'}, 'a two-finger tilt of a map');
    assert.equal(moveTo(swipe, 2, 400, 400), null);

    land(swipe, 2, 900, 1000);
    // One finger travels left, the other right: a pinch.
    const pinch = swipe.feed({action: MOTION.move, count: 2, time: 1200,
        points: [{id: 0, x: 600, y: 300}, {id: 1, x: 1000, y: 340}]});
    assert.deepEqual({...pinch}, {type: 'cancel'});

    // A system cancel (for example a dialog taking the touch) ends a swipe without moving anything.
    land(swipe, 2, 900, 3000);
    moveTo(swipe, 2, 600, 3200);
    assert.deepEqual({...swipe.feed({action: MOTION.cancel, count: 2, time: 3300})}, {type: 'cancel'});
});

test('the coordinates of most events are never read', () => {
    const swipe = swipeRecognizer(3, 1920);
    assert.equal(swipe.wantsPoints(MOTION.move, 1), false);
    assert.equal(swipe.wantsPoints(MOTION.down, 1), false);
    swipe.feed({action: MOTION.down, count: 1, time: 0});
    assert.equal(swipe.wantsPoints(MOTION.pointerDown, 2), false);
    assert.equal(swipe.wantsPoints(MOTION.pointerDown, 3), true);
    land(swipe, 3);
    assert.equal(swipe.wantsPoints(MOTION.move, 3), true);
});

test('left goes to the cluster, right to the passenger, and the reverse brings windows back', () => {
    const at = (direction, display, app) => swipeDestination({direction, display, app, cluster: 6, passenger: 1});
    assert.deepEqual({...at('left', 0, true)}, {action: 'move', to: 6});
    assert.deepEqual({...at('right', 0, true)}, {action: 'move', to: 1});
    assert.deepEqual({...at('right', 0, false)}, {action: 'recall'});
    assert.equal(at('left', 0, false), null);
    assert.deepEqual({...at('left', 1, true)}, {action: 'move', to: 0});
    assert.equal(at('right', 1, true), null);
    assert.equal(at('left', 1, false), null);
    // Without a cluster display there is nothing to send left or to bring back.
    assert.equal(swipeDestination({direction: 'left', display: 0, app: true, cluster: null, passenger: 1}), null);
    assert.equal(swipeDestination({direction: 'right', display: 0, app: false, cluster: null, passenger: 1}), null);
    assert.equal(swipeDestination({direction: 'right', display: 0, app: true, cluster: 6, passenger: null}), null);
});

test('the window follows the fingers one to one, then damped, never beyond half the display', () => {
    assert.equal(followOffset(-300, 1920), -300);
    assert.equal(followOffset(640, 1920), 640);
    assert.equal(followOffset(940, 1920), 740);
    assert.equal(followOffset(-5000, 1920), -960);
});

test('only standard apps of the current user leave their display', () => {
    const app = {user: 0, standard: true, leaf: true, alwaysOnTop: false, display: 0,
        component: 'ru.yandex.yandexnavi/ru.yandex.yandexnavi.core.NavigatorActivity', topPackage: 'ru.yandex.yandexnavi'};
    const movable = info => move.movableTask(info, 0, move.componentKey);
    assert.equal(movable(app), true);
    for (const [field, value] of [['user', 10], ['standard', false], ['leaf', false], ['alwaysOnTop', true],
        ['display', -1], ['component', null], ['topPackage', 'com.other.app']]) {
        assert.equal(movable({...app, [field]: value}), false, field);
    }
    for (const component of ['org.openvoyah.controls/.MainActivity', 'com.qinggan.app.launcher/.activity.MainActivity',
        'com.qinggan.systemui/.Bar', 'com.qinggan.cluster/.display.ClusterActivity', 'com.android.settings/.Settings']) {
        assert.equal(movable({...app, component, topPackage: null}), false, component);
    }
});

/** A head unit with a central display (0), the passenger HDMI display (1) and the cluster's navigation display (6). */
function unit({clusterPresent = true, passengerPublic = true} = {}) {
    const tasks = new Map();
    const log = [], follows = [], scheduled = [], reports = [];
    let listening = null, unlistens = 0, lockDepth = 0, maxDepth = 0;
    const add = (taskId, extra = {}) => tasks.set(taskId, {taskId, display: 0, user: 0, standard: true, leaf: true,
        alwaysOnTop: false, mode: 6, component: 'org.example.nav/org.example.nav.Main', topPackage: 'org.example.nav',
        bounds: [145, 0, 1920, 720], ...extra});
    const api = {
        listen: callback => { listening = callback; },
        unlisten: () => { listening = null; unlistens++; },
        width: displayId => displayId === 6 ? 0 : 1920,
        event: (motion, withPoints) => ({...motion, points: withPoints ? motion.points : []}),
        lock: fn => {
            lockDepth++;
            maxDepth = Math.max(maxDepth, lockDepth);
            try { return fn(); } finally { lockDepth--; }
        },
        user: () => 0,
        taskAt: (display, x) => [...tasks.values()].find(task => task.display === display &&
            x >= task.bounds[0] && x < task.bounds[2]) ?? null,
        describe: task => ({...task}),
        find: id => tasks.get(id) ?? null,
        stackOf: task => ({stack: task.taskId}),
        moveTask: (task, to) => {
            log.push('move ' + task.taskId + '->' + to);
            if (car.refuse) return false;
            task.display = to;
            task.mode = 1;
            return true;
        },
        clusterDisplay: () => clusterPresent ? 6 : null,
        publicDisplay: id => id === 1 && passengerPublic,
        follow: (stack, dx, alpha) => follows.push([stack.stack, dx, alpha]),
        startHome: (display, user) => { log.push('home ' + display + ' user ' + user); return true; },
        frontLauncher: display => { log.push('launcher ' + display); return true; },
        openControlsApps: user => { log.push('apps user ' + user); },
    };
    const placed = [];
    const car = {refuse: false};
    const mover = move.displayMover({
        api, componentKey: move.componentKey,
        prepare: task => { log.push('prepare ' + task.taskId); },
        placed: (component, user, taskId, displayId) => placed.push([component, user, taskId, displayId]),
        schedule: work => scheduled.push(work),
        report: (phase, error) => reports.push(phase + ':' + (error?.code ?? error?.message)),
        announce: line => log.push(line),
    });
    const run = () => { while (scheduled.length) scheduled.shift()(); };
    /** A three-finger swipe on [display] from x0 to x1, delivered through the hooked listener. */
    const swipe = (display, x0, x1, {fingers = 3, settle = true, beforeEnd = () => {}} = {}) => {
        const send = (action, count, time, x) => listening(display, {getActionMasked: () => action,
            getPointerCount: () => count, action, count, time, points: points(Array(count).fill(x))});
        send(MOTION.down, 1, 0, x0);
        for (let count = 2; count <= fingers; count++) send(MOTION.pointerDown, count, count * 10, x0);
        if (settle) run();
        const step = (x1 - x0) / 4;
        for (let i = 1; i <= 4; i++) send(MOTION.move, fingers, 100 + i * 150, x0 + step * i);
        beforeEnd();
        send(MOTION.pointerUp, fingers, 800, x1);
        run();
    };
    return Object.assign(car, {api, mover, tasks, add, log, follows, placed, reports, swipe, run,
        listening: () => listening !== null, unlistens: () => unlistens, maxDepth: () => maxDepth});
}

test('nothing is watched until the swipe is turned on, and turning it off stops watching', () => {
    const car = unit();
    assert.equal(car.listening(), false);
    car.mover.sync(null);
    assert.equal(car.listening(), false);
    car.mover.sync({fingers: 3, passenger: 1});
    assert.equal(car.listening(), true);
    car.mover.sync({fingers: 3, passenger: 1});
    assert.equal(car.unlistens(), 0, 'the same settings change nothing');
    car.mover.sync(null);
    assert.equal(car.listening(), false);
    assert.equal(car.unlistens(), 1);
});

test('a left swipe sends the app under the fingers to the cluster and the window follows meanwhile', () => {
    const car = unit();
    car.add(45);
    car.mover.sync({fingers: 3, passenger: 1});
    car.swipe(0, 1200, 500);
    assert.deepEqual(car.log, ['prepare 45', 'move 45->6', 'OVC_WINDOW_MOVE from=0 to=6 reason=swipe outcome=moved',
        'home 0 user 0']);
    assert.deepEqual(car.placed, [['org.example.nav/org.example.nav.Main', 0, 45, 6]]);
    assert.ok(car.follows.some(([, dx, alpha]) => dx < 0 && alpha < 1), 'the window followed the fingers');
    assert.deepEqual(car.follows.at(-1), [45, 0, 1], 'and was put back before it moved');
    assert.equal(car.mover.moved(), 1);
    assert.equal(car.maxDepth(), 1);
});

test('a right swipe sends it to the passenger display, and a left swipe there brings it back', () => {
    const car = unit();
    car.add(45);
    car.mover.sync({fingers: 3, passenger: 1});
    car.swipe(0, 600, 1400);
    assert.equal(car.tasks.get(45).display, 1);
    car.swipe(1, 1400, 400);
    assert.equal(car.tasks.get(45).display, 0);
    assert.deepEqual(car.placed.map(entry => entry[3]), [1, 0], 'the window policy runs on each arrival');
    assert.deepEqual(car.log.filter(line => line.startsWith('prepare')), ['prepare 45'], 'only the centre prepares');
    assert.equal(car.mover.moved(), 0);
});

test('a right swipe over the home screen brings the cluster window back', () => {
    const car = unit();
    car.add(45);
    car.mover.sync({fingers: 3, passenger: 1});
    car.swipe(0, 1200, 400);
    assert.equal(car.tasks.get(45).display, 6);
    // Nothing movable is under the fingers now: the home screen fills the central display.
    car.swipe(0, 300, 1300);
    assert.equal(car.tasks.get(45).display, 0);
    assert.equal(car.log.at(-1), 'OVC_WINDOW_MOVE to=0 reason=swipe outcome=returned count=1');
    assert.deepEqual(car.placed.at(-1), ['org.example.nav/org.example.nav.Main', 0, 45, 0]);
});

test('a second window sent to the cluster takes the place of the first, which returns to the centre', () => {
    const car = unit();
    car.add(45);
    car.mover.sync({fingers: 3, passenger: 1});
    car.swipe(0, 1200, 400);
    car.add(46, {component: 'org.example.music/org.example.music.Player', topPackage: 'org.example.music'});
    car.swipe(0, 1200, 400);
    assert.equal(car.tasks.get(46).display, 6);
    assert.equal(car.tasks.get(45).display, 0);
    assert.equal(car.mover.moved(), 1);
});

test('protected windows, short swipes and the wrong finger count move nothing', () => {
    const car = unit();
    car.add(45, {component: 'com.qinggan.app.launcher/.activity.MainActivity', topPackage: 'com.qinggan.app.launcher'});
    car.mover.sync({fingers: 3, passenger: 1});
    car.swipe(0, 1200, 400);
    assert.equal(car.tasks.get(45).display, 0);
    assert.equal(car.follows.length, 0, 'a protected window does not follow the fingers either');

    const other = unit();
    other.add(45);
    other.mover.sync({fingers: 3, passenger: 1});
    other.swipe(0, 1200, 1000);
    other.swipe(0, 1200, 400, {fingers: 2});
    assert.equal(other.tasks.get(45).display, 0);
    assert.deepEqual(other.follows.at(-1), [45, 0, 1], 'a cancelled drag puts the window back');
});

test('a window that changed before the swipe ended stays where it is', () => {
    const car = unit();
    car.add(45);
    car.mover.sync({fingers: 3, passenger: 1});
    car.swipe(0, 1200, 400, {settle: false});
    // Without the swipe settling first, the target is found when it ends: still there, so it moves.
    assert.equal(car.tasks.get(45).display, 6);

    // Found when the fingers landed, then moved elsewhere (by the launcher, say) before they lifted.
    const changed = unit();
    changed.add(45);
    changed.mover.sync({fingers: 3, passenger: 1});
    const original = changed.tasks.get(45);
    changed.swipe(0, 1200, 400, {beforeEnd: () => { original.display = 1; }});
    assert.equal(original.display, 1);
    assert.equal(changed.log.at(-1), 'OVC_WINDOW_MOVE from=0 to=6 reason=swipe outcome=changed');
    assert.equal(changed.placed.length, 0);
});

test('a window Android refuses to move is placed beside the panel again', () => {
    const car = unit();
    car.add(45);
    car.mover.sync({fingers: 3, passenger: 1});
    car.refuse = true;
    car.swipe(0, 1200, 400);
    assert.equal(car.tasks.get(45).display, 0);
    assert.equal(car.log.at(-1), 'OVC_WINDOW_MOVE from=0 to=6 reason=swipe outcome=refused');
    assert.deepEqual(car.placed, [['org.example.nav/org.example.nav.Main', 0, 45, 0]]);
    assert.equal(car.mover.moved(), 0);
});

test('without a cluster or a public passenger display those directions do nothing', () => {
    const car = unit({clusterPresent: false, passengerPublic: false});
    car.add(45);
    car.mover.sync({fingers: 3, passenger: 1});
    car.swipe(0, 1200, 400);
    car.swipe(0, 600, 1400);
    assert.equal(car.tasks.get(45).display, 0);
    assert.equal(car.follows.length, 0);
});

test('turning the swipe off, a recall and a release bring every moved window back', () => {
    for (const end of ['off', 'recall', 'release']) {
        const car = unit();
        car.add(45);
        car.add(46, {component: 'org.example.music/org.example.music.Player', topPackage: 'org.example.music'});
        car.mover.sync({fingers: 3, passenger: 1});
        car.swipe(0, 1200, 400);
        car.tasks.get(46).bounds = [145, 0, 1920, 720];
        car.swipe(0, 600, 1400);
        assert.equal(car.mover.moved(), 2);
        if (end === 'off') car.mover.sync(null);
        else assert.equal(car.mover[end](), true);
        car.run();
        assert.equal(car.tasks.get(45).display, 0, end);
        assert.equal(car.tasks.get(46).display, 0, end);
        assert.equal(car.mover.moved(), 0);
        assert.equal(car.listening(), end === 'recall');
    }
});

test('a window moved back by Android itself is not moved again on recall', () => {
    const car = unit();
    car.add(45);
    car.mover.sync({fingers: 3, passenger: 1});
    car.swipe(0, 1200, 400);
    car.tasks.get(45).display = 0;
    car.log.length = 0;
    assert.equal(car.mover.recall(), true);
    assert.deepEqual(car.log, []);
});

test('the window document carries the swipe setting, and the app may ask for the windows back', () => {
    const base = {schema: 1, user: 0, enabled: true, apps: []};
    const read = extra => move.readWindowConfig(JSON.stringify({...base, ...extra}));
    assert.equal(read({}).displayMove, null);
    assert.equal(read({displayMove: null}).displayMove, null);
    assert.deepEqual({...read({displayMove: {fingers: 2}}).displayMove}, {fingers: 2, reveal: 'home'});
    assert.deepEqual({...read({displayMove: {fingers: 3, reveal: 'apps'}}).displayMove}, {fingers: 3, reveal: 'apps'});
    for (const invalid of [{fingers: 4}, {fingers: '3'}, {}, [], true, {fingers: 3, extra: 1},
        {fingers: 3, reveal: 'grid'}]) {
        assert.equal(read({displayMove: invalid}), null, JSON.stringify(invalid));
    }
    const request = operation => move.readWindowRequest(JSON.stringify({schema: 1, id: 'a'.repeat(32), user: 0,
        expires: 5000, operation}), 1000);
    assert.deepEqual({...request('recall')}, {id: 'a'.repeat(32), user: 0, operation: 'recall'});
    assert.equal(request('recall-all'), null);
});

test('the central display shows home or the Controls app grid once a window left it, the passenger its launcher', () => {
    const car = unit();
    car.add(45);
    car.mover.sync({fingers: 3, passenger: 1, reveal: 'home'});
    car.swipe(0, 600, 1400);
    assert.equal(car.log.at(-1), 'home 0 user 0');
    car.swipe(1, 1400, 400);
    assert.equal(car.log.at(-1), 'launcher 1', 'the passenger display shows its own home again');

    const apps = unit();
    apps.add(45);
    apps.mover.sync({fingers: 3, passenger: 1, reveal: 'apps'});
    apps.swipe(0, 1200, 400);
    assert.equal(apps.log.at(-1), 'apps user 0');
    assert.equal(apps.log.filter(line => line.startsWith('home')).length, 0);
});

test('nothing is shown over a window that came back from the cluster in exchange or by a recall', () => {
    const car = unit();
    car.add(45);
    car.mover.sync({fingers: 3, passenger: 1, reveal: 'home'});
    car.swipe(0, 1200, 400);
    car.add(46, {component: 'org.example.music/org.example.music.Player', topPackage: 'org.example.music'});
    car.log.length = 0;
    car.swipe(0, 1200, 400);
    assert.equal(car.tasks.get(45).display, 0);
    assert.equal(car.log.some(line => line.startsWith('home')), false);
    // The window that came back stays clear of the fingers, so this right swipe recalls the cluster's window.
    car.tasks.get(45).bounds = [1500, 0, 1920, 720];
    car.log.length = 0;
    car.swipe(0, 300, 1300);
    assert.equal(car.tasks.get(46).display, 0);
    assert.equal(car.log.some(line => line.startsWith('home')), false);
});

test('a failure to show home is reported and leaves the moved window where it went', () => {
    const car = unit();
    car.add(45);
    car.mover.sync({fingers: 3, passenger: 1, reveal: 'home'});
    car.api.startHome = () => { throw Object.assign(Error('no home'), {code: 'no_home'}); };
    car.swipe(0, 600, 1400);
    assert.equal(car.tasks.get(45).display, 1);
    assert.deepEqual(car.reports, ['move_reveal:no_home']);
});
