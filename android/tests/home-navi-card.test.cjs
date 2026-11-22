const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = ['report.js', 'home-navi-card.js'].map(file => fs.readFileSync(path.join(__dirname, '../hooks', file), 'utf8')
    .replace(/^import .*;$/gm, '').replace(/^export /gm, '')).join('\n');

function load(extra = {}) {
    const logs = [];
    const context = {console: {log: line => logs.push(line)}, setInterval: () => 0, clearInterval() {}, ...extra};
    const api = vm.runInNewContext(source + '\n({cardPackage, pickNotification, guidanceLines, createHomeCardController,' +
        ' installHomeNavigationCard, createReporter, CARD_MARK, pickWidget})', context);
    return {api, logs};
}

const NAVI = 'ru.yandex.yandexnavi';

test('only a valid package outside a restore request replaces the factory card', () => {
    const {cardPackage} = load().api;
    assert.equal(cardPackage({schema: 1, homeCard: {package: NAVI}}), NAVI);
    // Independent of the panel assignments switch.
    assert.equal(cardPackage({schema: 1, enabled: false, homeCard: {package: NAVI}}), NAVI);
    assert.equal(cardPackage({schema: 1, enabled: false, restoreIcons: 'a'.repeat(32), homeCard: {package: NAVI}}), null);
    assert.equal(cardPackage({schema: 2, homeCard: {package: NAVI}}), null);
    for (const name of ['', 'navi', 'ru.yandex.yandexnavi;rm', 'ru..x', '1ru.x', 'ru.' + 'x'.repeat(130), 7, null]) {
        assert.equal(cardPackage({schema: 1, homeCard: {package: name}}), null, String(name));
    }
    assert.equal(cardPackage({schema: 1}), null);
    assert.equal(cardPackage(null), null);
});

test('the newest ongoing navigator notification wins and group summaries never show', () => {
    const {pickNotification} = load().api;
    const item = (id, ongoing, postTime, summary = false) => ({id, ongoing, postTime, summary});
    assert.equal(pickNotification([]), null);
    assert.equal(pickNotification([item('a', false, 5), item('b', true, 1), item('c', true, 3)]).id, 'c');
    assert.equal(pickNotification([item('a', false, 5), item('b', false, 9)]).id, 'b');
    assert.equal(pickNotification([item('s', true, 9, true), item('a', false, 1)]).id, 'a');
    assert.equal(pickNotification([item('s', true, 9, true)]), null);
});

test('guidance lines are trimmed, bounded and absent without text', () => {
    const {guidanceLines} = load().api;
    assert.deepEqual({...guidanceLines({title: ' 300 m\n', text: 'Tverskaya  street', sub: null})},
        {title: '300 m', text: 'Tverskaya street', sub: ''});
    const long = guidanceLines({title: 'x'.repeat(200)});
    assert.equal(long.title.length, 80);
    assert.ok(long.title.endsWith('…'));
    assert.equal(guidanceLines({title: ' ', text: '', sub: null}), null);
    assert.equal(guidanceLines(null), null);
});

test('the tallest navigator widget nearest the card ratio is chosen', () => {
    const {pickWidget} = load().api;
    // Yandex Navigator's traffic widgets on the unit, in dp; the card is 576 x 652.
    const sizes = [[180, 40], [110, 110], [180, 110], [320, 40], [320, 110]].map(([minWidth, minHeight]) => ({minWidth, minHeight}));
    assert.deepEqual({...pickWidget(sizes, 576 / 652)}, {minWidth: 110, minHeight: 110});
    assert.deepEqual({...pickWidget(sizes, 3)}, {minWidth: 320, minHeight: 110});
    assert.equal(pickWidget([], 1), null);
    assert.equal(pickWidget([{minWidth: 0, minHeight: 10}], 1), null);
});

function fakeEnv(options = {}) {
    const calls = [];
    const state = {launchable: new Set([NAVI]), orphans: new Map(), failAdd: false, failRemove: false, dead: new Set()};
    const env = {
        same: (handle, card) => handle.card === card,
        keep: card => ({card}),
        alive: handle => !state.dead.has(handle.card),
        forget: entry => calls.push(['forget', entry.card.card]),
        launchable: name => state.launchable.has(name),
        removeOrphans(handle) {
            const count = state.orphans.get(handle.card) ?? 0;
            state.orphans.delete(handle.card);
            calls.push(['orphans', handle.card, count]);
            return count;
        },
        addOverlay(handle, name) {
            if (state.failAdd) throw new Error('java.lang.IllegalStateException: add');
            calls.push(['add', handle.card, name]);
            return {name, card: handle.card};
        },
        render: (overlay, model) => calls.push(['render', overlay.card, model?.lines?.title ?? null]),
        removeOverlay(handle) {
            if (state.failRemove) throw new Error('java.lang.IllegalStateException: remove');
            calls.push(['remove', handle.card]);
        },
        pauseMap: handle => calls.push(['pause', handle.card]),
        resumeMap: handle => calls.push(['resume', handle.card]),
        open: (handle, name) => calls.push(['open', handle.card, name]),
        ...options
    };
    const {api, logs} = load();
    const controller = api.createHomeCardController(env, api.createReporter('OVC_HOME_CARD_ERROR'));
    return {controller, calls, state, logs};
}

test('a configured launchable navigator covers every card, pauses Baidu and opens on a tap', () => {
    const {controller, calls} = fakeEnv();
    controller.track('card1');
    assert.deepEqual(calls.splice(0), [['orphans', 'card1', 0]]);
    controller.configure(NAVI);
    assert.deepEqual(calls.splice(0), [['add', 'card1', NAVI], ['render', 'card1', null], ['pause', 'card1']]);
    controller.track('card2');
    controller.track('card2');
    assert.deepEqual(calls.splice(0), [['orphans', 'card2', 0], ['add', 'card2', NAVI], ['render', 'card2', null], ['pause', 'card2']]);
    assert.equal(controller.covers('card1'), true);
    assert.equal(controller.covers('other'), false);
    assert.equal(controller.open('card2'), true);
    assert.equal(controller.open('other'), false);
    assert.deepEqual(calls.splice(0), [['open', 'card2', NAVI]]);

    controller.show({name: NAVI, lines: {title: '300 m'}});
    assert.deepEqual(calls.splice(0), [['render', 'card1', '300 m'], ['render', 'card2', '300 m']]);
    // A card created later shows the current guidance at once.
    controller.track('card3');
    assert.deepEqual(calls.splice(0), [['orphans', 'card3', 0], ['add', 'card3', NAVI], ['render', 'card3', '300 m'], ['pause', 'card3']]);
});

test('turning the setting off restores and resumes the factory cards', () => {
    const {controller, calls} = fakeEnv();
    controller.track('card1');
    controller.configure(NAVI);
    calls.length = 0;
    controller.configure(NAVI);
    assert.deepEqual(calls, []);
    controller.configure(null);
    assert.deepEqual(calls.splice(0), [['remove', 'card1'], ['resume', 'card1']]);
    assert.equal(controller.covers('card1'), false);
    assert.equal(controller.open('card1'), false);
    assert.equal(controller.covering(), false);
});

test('a missing navigator keeps the factory card', () => {
    const {controller, calls, state} = fakeEnv();
    state.launchable.clear();
    controller.track('card1');
    controller.configure(NAVI);
    assert.deepEqual(calls.splice(0), [['orphans', 'card1', 0]]);
    assert.equal(controller.target(), null);
    state.launchable.add(NAVI);
    controller.configure(NAVI);
    assert.equal(controller.covers('card1'), true);
});

test('the restore handshake reports a card it could not restore and stays honest', () => {
    const {controller, calls, state, logs} = fakeEnv();
    controller.track('card1');
    controller.configure(NAVI);
    state.failRemove = true;
    assert.equal(controller.restoreAll(), false);
    assert.equal(controller.covers('card1'), true);
    assert.deepEqual(logs, ['OVC_HOME_CARD_ERROR stage=restore kind=IllegalStateException']);
    state.failRemove = false;
    calls.length = 0;
    assert.equal(controller.restoreAll(), true);
    assert.deepEqual(calls, [['remove', 'card1'], ['resume', 'card1']]);
    assert.equal(controller.restoreAll(), true);
});

test('a failed overlay leaves the factory card and is reported once', () => {
    const {controller, calls, state, logs} = fakeEnv();
    state.failAdd = true;
    controller.track('card1');
    controller.configure(NAVI);
    controller.configure(NAVI);
    assert.equal(controller.covers('card1'), false);
    assert.deepEqual(calls.filter(call => call[0] === 'pause'), []);
    assert.deepEqual(logs, ['OVC_HOME_CARD_ERROR stage=apply kind=IllegalStateException']);
});

test('an overlay left by an earlier agent is removed and the factory map resumed', () => {
    const {controller, calls, state} = fakeEnv();
    state.orphans.set('card1', 1);
    controller.track('card1');
    assert.deepEqual(calls.splice(0), [['orphans', 'card1', 1], ['resume', 'card1']]);
    controller.configure(NAVI);
    state.orphans.set('card2', 1);
    controller.track('card2');
    // Covered again right away, so Baidu is not restarted in between.
    assert.deepEqual(calls.splice(0).filter(call => call[1] === 'card2'),
        [['orphans', 'card2', 1], ['add', 'card2', NAVI], ['render', 'card2', null], ['pause', 'card2']]);
});

test('collected cards are forgotten on the next configuration', () => {
    const {controller, calls, state} = fakeEnv();
    controller.track('card1');
    controller.configure(NAVI);
    state.dead.add('card1');
    calls.length = 0;
    controller.configure(null);
    assert.deepEqual(calls, [['forget', 'card1']]);
    assert.equal(controller.covering(), false);
});

// Adapter: the factory classes and views as plain objects.
function adapterHarness(options = {}) {
    const timers = [];
    const hooks = {};
    const events = [];
    const config = {value: options.config ?? {schema: 1, homeCard: {package: NAVI}}};
    const notifications = {list: options.notifications ?? []};
    function hook(name) {
        hooks[name] = {implementation: null, call(self, ...args) { events.push(['original', name]); return name === 'touch' ? 'factory' : undefined; }};
        return hooks[name];
    }
    function view(kind, extra = {}) {
        return {
            kind, children: [], tag: null, visibility: 0, text: null,
            setTag(tag) { this.tag = tag; }, getTag() { return this.tag; },
            setBackground(value) { this.background = value; }, setClipToOutline() {}, setClickable(value) { this.clickable = value; },
            setOrientation() {}, setGravity() {}, setPadding() {}, setScaleType() {},
            setTextSize() {}, setTextColor() {}, setMaxLines() {}, setEllipsize() {}, setTypeface() {},
            setText: {overload: () => ({call(target, value) { target.text = value; }})},
            setVisibility(value) { this.visibility = value; }, setContentDescription(value) { this.description = value; },
            setImageDrawable(value) { this.drawable = value; },
            addView(child) { this.children.push(child); child.parent = this; },
            removeAllViews() { this.children = []; },
            getChildCount() { return this.children.length; }, getChildAt(i) { return this.children[i]; },
            removeViewAt(i) { this.children.splice(i, 1); }, indexOfChild(child) { return this.children.indexOf(child); },
            getContext: () => appContext, equals(other) { return other === this; }, $dispose() {},
            getParent() { return this.parent ?? null; },
            ...extra
        };
    }
    const packages = {
        getLaunchIntentForPackage: name => name === NAVI && !options.noNavigator
            ? {getComponent: () => ({flattenToString: () => NAVI + '/.core.NavigatorActivity'})} : null,
        getApplicationIcon: {overload: () => ({call: () => 'app-icon'})},
        getApplicationInfo: {overload: () => ({call: () => 'info'})},
        getApplicationLabel: () => 'Yandex Navigator'
    };
    const appContext = {
        getPackageManager: () => packages, getPackageName: () => 'com.qinggan.app.launcher',
        getResources: () => ({getIdentifier: () => 0, getDimension: () => 0, getDisplayMetrics: () => ({density: {value: 1}}),
            getConfiguration: () => 'config'}),
        createConfigurationContext(config) { assert.equal(config, 'config'); return plainContext; }
    };
    // A context without the activity's view factories, for the widget's RemoteViews.
    const plainContext = {plain: true};
    const card = view('card', {isCard: true, isShown: () => true, getWidth: () => 576, getHeight: () => 652,
        onResume() { events.push(['card_resume']); }});
    const widget = {providers: options.widgets ?? [], bound: new Map(), next: 7, refuse: options.refuseBind === true,
        listening: false, deleted: [], hostDeleted: 0, sizes: [], contexts: []};
    const widgetHost = {
        getAppWidgetIds: () => [...widget.bound.keys()],
        allocateAppWidgetId: () => widget.next++,
        deleteAppWidgetId(id) { widget.deleted.push(id); widget.bound.delete(id); },
        startListening() { widget.listening = true; }, stopListening() { widget.listening = false; },
        deleteHost() { widget.hostDeleted++; widget.bound.clear(); },
        createView(context, id, info) {
            widget.contexts.push(context);
            return view('widget', {id, info, updateAppWidgetSize(options, ...size) { widget.sizes.push(size); }});
        },
        $dispose() {}
    };
    const largeBdNavi = view('frame');
    const mapLayout = view('map');
    card.addView(largeBdNavi);
    largeBdNavi.addView(mapLayout);
    const launched = [];
    const classes = {
        'com.qinggan.app.launcher.homecard.HomeNaviCardView': {
            class: {isInstance: value => value?.isCard === true},
            onResume: {overload: () => hook('onResume')}, onAttachedToWindow: {overload: () => hook('onAttachedToWindow')},
            doScreenLift: {overload: () => hook('doScreenLift')}
        },
        'com.baidu.widgetmap.MapWidgetLayout': {dispatchTouchEvent: {overload: () => hook('touch')}},
        'com.baidu.widgetmap.MapWidgetManager': {getInstance: () => ({onPause() { events.push(['baidu_pause']); }})},
        'android.view.View': {class: {isInstance: value => typeof value?.getParent === 'function'}},
        'android.view.ViewGroup': {},
        'android.widget.FrameLayout': {$new: () => view('frame')},
        'android.widget.FrameLayout$LayoutParams': {$new: (w, h) => ({w, h})},
        'android.widget.LinearLayout': {$new: () => view('column')},
        'android.widget.LinearLayout$LayoutParams': {$new: (w, h) => ({w, h, bottomMargin: {value: 0}})},
        'android.widget.ImageView': {$new: () => view('image')},
        'android.widget.ImageView$ScaleType': {FIT_CENTER: {value: 'fit'}},
        'android.widget.TextView': {$new: () => view('text')},
        'android.text.TextUtils$TruncateAt': {END: {value: 'end'}},
        'android.graphics.Typeface': {DEFAULT_BOLD: {value: 'bold'}},
        'android.graphics.drawable.GradientDrawable': {$new: () => ({setColor() {}, setCornerRadius() {}})},
        'java.lang.String': {$new: value => value},
        'java.lang.ref.WeakReference': {$new: referent => ({get: () => referent, $dispose() {}})},
        'android.app.ActivityThread': {currentApplication: () => appContext},
        'android.os.ServiceManager': {getService: () => 'binder'},
        'android.app.INotificationManager$Stub': {asInterface: () => ({
            getActiveNotificationsWithAttribution(caller) {
                assert.equal(caller, 'com.qinggan.app.launcher');
                if (options.notificationsDenied) throw new Error('java.lang.SecurityException: denied');
                return notifications.list;
            }
        })},
        'android.app.INotificationManager': {},
        'android.appwidget.AppWidgetHost': {$new(app, id) { widget.hostId = id; return widgetHost; }},
        'android.appwidget.AppWidgetProviderInfo': {},
        'android.os.Bundle': {$new: () => ({})},
        'android.appwidget.AppWidgetManager': {getInstance: () => ({
            getInstalledProvidersForPackage(name) {
                const list = name === NAVI ? widget.providers : [];
                return {size: () => list.length, get: i => list[i]};
            },
            getAppWidgetInfo: id => widget.bound.get(id) ?? null,
            bindAppWidgetIdIfAllowed: {overload: () => ({call(manager, id, provider) {
                if (widget.refuse) return false;
                widget.bound.set(id, widget.providers.find(info => info.provider.value === provider));
                return true;
            }})}
        })}
    };
    const Java = {
        use(name) {
            if (options.missing === name) throw new Error('java.lang.ClassNotFoundException: ' + name);
            assert.ok(classes[name], name);
            return classes[name];
        },
        cast: value => value, retain: value => value, perform: fn => fn(), scheduleOnMainThread: fn => fn(),
        choose(name, callbacks) { callbacks.onMatch(card); callbacks.onComplete(); }
    };
    const {api, logs} = load({setInterval: (fn, ms) => { timers.push({fn, ms}); return timers.length; }});
    const result = api.installHomeNavigationCard(Java, {
        readConfig: () => config.value,
        launch: (context, component) => { launched.push(component); return true; },
        onNavigation: (context, name) => events.push(['navigation', name])
    });
    const overlay = () => card.children.find(child => child.tag === api.CARD_MARK) ?? null;
    const touch = (action, x, y) => hooks.touch.implementation.call(mapLayout, {
        getActionMasked: () => action, getRawX: () => x, getRawY: () => y
    });
    mapLayout.playSoundEffect = () => events.push(['sound']);
    return {api, logs, result, card, overlay, hooks, events, launched, config, timers, touch, notifications, mapLayout, widget};
}

test('the adapter covers the live card, announces readiness and pauses Baidu', () => {
    const h = adapterHarness();
    assert.ok(h.result);
    assert.deepEqual(h.logs, ['OVC_HOME_CARD_READY']);
    const overlay = h.overlay();
    assert.ok(overlay);
    assert.equal(overlay.clickable, false);
    assert.equal(overlay.description, 'Yandex Navigator');
    const [column] = overlay.children;
    const [icon, title, text] = column.children;
    assert.equal(icon.drawable, 'app-icon');
    assert.equal(title.text, 'Yandex Navigator');
    assert.equal(text.visibility, 8);
    assert.ok(h.events.some(event => event[0] === 'baidu_pause'));
});

test('a tap on the covered card opens the navigator and Baidu sees no gesture', () => {
    const h = adapterHarness();
    h.events.length = 0;
    assert.equal(h.touch(0, 100, 100), true);
    assert.equal(h.touch(2, 104, 103), true);
    assert.equal(h.touch(1, 105, 104), true);
    assert.deepEqual(h.launched, [NAVI + '/.core.NavigatorActivity']);
    assert.deepEqual(h.events, [['sound'], ['navigation', NAVI]]);
    // A drag is not a tap.
    h.touch(0, 100, 100);
    h.touch(1, 140, 100);
    assert.equal(h.launched.length, 1);
});

test('without the setting the factory touch path and map run unchanged', () => {
    const h = adapterHarness({config: {schema: 1, enabled: true}});
    assert.equal(h.overlay(), null);
    assert.equal(h.touch(1, 0, 0), 'factory');
    assert.deepEqual(h.events.filter(event => event[0] === 'baidu_pause'), []);
});

test('the card resume path pauses Baidu again while covered', () => {
    const h = adapterHarness();
    h.events.length = 0;
    h.hooks.onResume.implementation.call(h.card);
    assert.deepEqual(h.events, [['original', 'onResume'], ['baidu_pause']]);
});

test('turning the setting off restores the factory card on the next check', () => {
    const h = adapterHarness();
    h.config.value = {schema: 1, enabled: false};
    h.events.length = 0;
    h.timers.find(timer => timer.ms === 1000).fn();
    assert.equal(h.overlay(), null);
    assert.equal(h.card.children.length, 1);
    assert.deepEqual(h.events, [['card_resume']]);
});

test('the navigator notification fills the card and its removal brings the tile back', () => {
    const notification = (title, text, flags = 2, postTime = 1) => ({
        getPackageName: () => NAVI, getPostTime: () => postTime,
        getNotification: () => ({flags: {value: flags}, contentView: {value: null}, getLargeIcon: () => ({loadDrawable: () => 'arrow'}),
            extras: {value: {getCharSequence: key => ({'android.title': title, 'android.text': text})[key] ?? null}}})
    });
    const h = adapterHarness({notifications: [notification('300 m', 'Tverskaya street')]});
    const poll = h.timers.find(timer => timer.ms === 2000).fn;
    poll();
    const [icon, title, text, sub] = h.overlay().children[0].children;
    assert.equal(title.text, '300 m');
    assert.equal(text.text, 'Tverskaya street');
    assert.equal(sub.visibility, 8);
    assert.equal(icon.drawable, 'arrow');
    h.notifications.list = [];
    poll();
    assert.equal(title.text, 'Yandex Navigator');
    assert.equal(icon.drawable, 'app-icon');
});

test('denied notification access keeps the tile and is reported once', () => {
    const h = adapterHarness({notificationsDenied: true});
    const poll = h.timers.find(timer => timer.ms === 2000).fn;
    poll();
    poll();
    assert.deepEqual(h.logs.filter(line => line.startsWith('OVC_HOME_CARD_ERROR')),
        ['OVC_HOME_CARD_ERROR stage=notifications kind=SecurityException']);
    assert.equal(h.overlay().children[0].children[1].text, 'Yandex Navigator');
});

test('restoreAll is the detach handshake and dispose unhooks everything', () => {
    const h = adapterHarness();
    assert.equal(h.result.restoreAll(), true);
    assert.equal(h.overlay(), null);
    h.result.dispose();
    for (const name of ['touch', 'onResume', 'onAttachedToWindow', 'doScreenLift']) assert.equal(h.hooks[name].implementation, null);
});

test('a launcher without the factory card reports it and installs nothing', () => {
    const h = adapterHarness({missing: 'com.baidu.widgetmap.MapWidgetLayout'});
    assert.equal(h.result, null);
    assert.deepEqual(h.logs, ['OVC_HOME_CARD_ERROR stage=install kind=ClassNotFoundException']);
    assert.equal(h.hooks.onResume?.implementation ?? null, null);
    assert.equal(h.overlay(), null);
});

const provider = (name, minWidth, minHeight) => ({minWidth: {value: minWidth}, minHeight: {value: minHeight},
    provider: {value: {name, equals(other) { return other?.name === name; }}}});
const TRAFFIC = [provider('2x1', 180, 40), provider('2x2', 110, 110), provider('5x2', 320, 110)];

test('the navigator traffic widget fills the card when the navigator has one', () => {
    const h = adapterHarness({widgets: TRAFFIC});
    const overlay = h.overlay();
    const [column, hosted] = overlay.children;
    assert.equal(column.visibility, 8);
    assert.equal(hosted.kind, 'widget');
    assert.equal(hosted.info.provider.value.name, '2x2');
    assert.deepEqual(h.widget.sizes, [[576, 652, 576, 652]]);
    assert.equal(h.widget.contexts.length, 1);
    assert.equal(h.widget.contexts[0].plain, true);
    assert.equal(h.widget.listening, true);
    assert.deepEqual(h.logs, ['OVC_HOME_CARD_READY']);
    // Its own taps are the widget's; elsewhere the card still opens the navigator.
    h.touch(0, 10, 10);
    h.touch(1, 10, 10);
    assert.equal(h.launched.length, 1);
    // No notification polling while the widget shows.
    let polled = false;
    h.notifications.list = {get length() { polled = true; return 0; }};
    h.timers.find(timer => timer.ms === 2000).fn();
    assert.equal(polled, false);
});

test('turning the setting off releases the widget host', () => {
    const h = adapterHarness({widgets: TRAFFIC});
    h.config.value = {schema: 1};
    h.timers.find(timer => timer.ms === 1000).fn();
    assert.equal(h.overlay(), null);
    assert.equal(h.widget.listening, false);
    assert.equal(h.widget.hostDeleted, 1);
});

test('a widget bound earlier by this host is reused, others are deleted', () => {
    const h = adapterHarness({widgets: TRAFFIC});
    assert.equal(h.result.restoreAll(), true);
    // Reattach: one stale widget of another provider and none of the chosen one.
    h.widget.bound.set(3, TRAFFIC[2]);
    h.widget.bound.set(4, TRAFFIC[1]);
    h.timers.find(timer => timer.ms === 1000).fn();
    const hosted = h.overlay().children[1];
    assert.equal(hosted.id, 4);
    assert.deepEqual(h.widget.deleted, [3]);
});

test('a refused widget binding falls back to the icon card and is reported', () => {
    const h = adapterHarness({widgets: TRAFFIC, refuseBind: true});
    const [column] = h.overlay().children;
    assert.equal(column.visibility, 0);
    assert.equal(h.overlay().children.length, 1);
    assert.deepEqual(h.logs, ['OVC_HOME_CARD_ERROR stage=widget kind=widget_refused', 'OVC_HOME_CARD_READY']);
});
