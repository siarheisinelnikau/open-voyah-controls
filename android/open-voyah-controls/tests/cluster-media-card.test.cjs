const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = ['report.js', 'media-slots.js', 'driver-navigation.js', 'driver-media-layout.js', 'cluster-media-card.js'].map(file => fs.readFileSync(path.join(__dirname, '../hooks', file), 'utf8')
    .replace(/^import .*;$/gm, '').replace(/^export /gm, '')).join('\n');

function load(extra = {}) {
    const logs = [];
    const context = {console: {log: line => logs.push(line)}, setInterval: () => 1, clearInterval() {}, JSON, Date, ...extra};
    const api = vm.runInNewContext(source + (extra.fakeDriverLayer ? '\ncreateDriverMediaLayer = () => fakeDriverLayer;' : '') +
        '\n({clusterCardSetting, trackProgress, installClusterMediaCard, MEDIA_SLOTS, driverMediaGeometry, driverMediaText, createDriverMediaLayer, driverNavigationGeometry, driverNavigationToken, driverRouteRemaining})', context);
    return {api, logs};
}

const MUSIC = {package: 'app.voyahmusic.player', service: 'app.voyahmusic.player.playback.MusicBrowserService'};
const RADIO = {package: 'app.voyahradio', service: 'app.voyahradio.playback.RadioBrowserService'};
const ON = {schema: 1, clusterCard: {sources: [MUSIC, RADIO]}};
const plain = value => JSON.parse(JSON.stringify(value));

test('the compact navigation frame is centered and fits the minimum admitted media viewport with large text', () => {
    const {driverNavigationGeometry, driverRouteRemaining, driverNavigationToken} = load().api;
    assert.deepEqual(plain(driverNavigationGeometry({width: 574, height: 464}, 1, 1)),
        {width: 288, height: 112, left: 143, top: 176, horizontal: 18, vertical: 10, radius: 16, stroke: 1});
    assert.equal(driverNavigationGeometry({width: 480, height: 240}, 1.5, 1.5).width, 432);
    assert.equal(driverNavigationGeometry({width: 480, height: 240}, 1.5, 1.51), null);
    assert.equal(driverNavigationGeometry({width: 288, height: 160}, 1, 1), null);
    assert.equal(driverNavigationGeometry({width: 340, height: 120}, 1, 1), null);
    assert.equal(driverRouteRemaining(1_000, 5_999), 1);
    assert.equal(driverRouteRemaining(1_000, 6_000), 0);
    assert.equal(driverRouteRemaining(1_000, 999), 0);
    assert.equal(driverRouteRemaining('1000', 1_000), 0);
    assert.equal(driverNavigationToken({schema: 1, driverNavigation: true}), null);
    assert.equal(driverNavigationToken({schema: 1, driverNavigation: 'a'.repeat(32), restoreIcons: null}), null);
});

test('the setting lists valid, distinct apps and is off without them or during a restore request', () => {
    const {clusterCardSetting} = load().api;
    assert.deepEqual(plain(clusterCardSetting(ON)), {sources: [MUSIC, RADIO]});
    assert.equal(clusterCardSetting({...ON, restoreIcons: 'a'.repeat(32)}), null);
    assert.equal(clusterCardSetting({schema: 1}), null);
    assert.equal(clusterCardSetting({schema: 1, clusterCard: {}}), null);
    assert.equal(clusterCardSetting({schema: 1, clusterCard: {sources: []}}), null);
    assert.equal(clusterCardSetting({schema: 2, clusterCard: ON.clusterCard}), null);
    assert.equal(clusterCardSetting(null), null);
    // The home screen card's setting does not turn this card on.
    assert.equal(clusterCardSetting({schema: 1, mediaCard: {sources: [MUSIC]}}), null);
    assert.deepEqual(plain(clusterCardSetting({schema: 1, clusterCard: {...ON.clusterCard, driverLayout: true}})),
        {sources: [MUSIC, RADIO], driverLayout: true});
    assert.deepEqual(plain(clusterCardSetting({schema: 1, clusterCard: {...ON.clusterCard, driverLayout: 'true'}})),
        {sources: [MUSIC, RADIO]});
    const bad = [{package: 'app', service: 'app.X'}, {package: 'app.x;rm', service: 'app.x.S'}, {package: 'app.x', service: ''}, null];
    assert.equal(clusterCardSetting({schema: 1, clusterCard: {sources: bad}}), null);
    const third = {package: 'org.example.player', service: 'org.example.player.Browser'};
    assert.deepEqual(plain(clusterCardSetting({schema: 1, clusterCard: {sources: [MUSIC, MUSIC, RADIO, third]}})).sources, [MUSIC, RADIO]);
});

test('driver geometry stays inside the visible media view on the exact driver surface', () => {
    const {driverMediaGeometry, driverMediaText} = load().api;
    const view = {display: 'Cluster-Media-Display', owner: 'com.qinggan.instrumentcard',
        attached: true, shown: true, width: 574, height: 464, density: 1};
    const layer = driverMediaGeometry(view);
    assert.equal(layer.width, 574);
    assert.equal(layer.height, 464);
    assert.ok(layer.art + layer.inset < layer.left);
    assert.equal(layer.left + layer.textWidth + layer.inset, layer.width);
    for (const refused of [{display: 'Built-in Screen'}, {display: 'Cluster-Navi-Display'}, {owner: 'com.qinggan.cluster'},
        {attached: false}, {shown: false}, {width: 100}, {height: 40}, {density: NaN}, {width: 1920}]) {
        assert.equal(driverMediaGeometry({...view, ...refused}), null);
    }
    assert.equal(driverMediaText('  Track\nName \u202e More\0 '), 'Track Name More');
    assert.equal(driverMediaText('x'.repeat(400)).length, 240);
});

test('driver view attaches without taking input, expires and only removes its own layer', () => {
    const {createDriverMediaLayer} = load().api;
    const calls = [];
    function nativeView() {
        let parent = null;
        const view = {
            width: 0, height: 0, alpha: 1, children: [],
            setParent(value) { parent = value; }, getParent() { return parent; },
            setTag(tag) { this.tag = tag; }, setAlpha(alpha) { this.alpha = alpha; }, getAlpha() { return this.alpha; },
            setClickable(clickable) { this.clickable = clickable; }, setFocusable(focusable) { this.focusable = focusable; },
            addView(child) { this.children.push(child); }, setText(text) { this.text = text; },
            layout(x, y, width, height) { this.width = width; this.height = height; },
            setLayoutParams(params) { this.params = params; },
            animate() {
                const self = this;
                return {cancel() {}, alpha(value) { this.end = value; return this; },
                    setStartDelay(delay) { this.delay = delay; return this; },
                    setDuration() { return this; }, start() { calls.push([self, this.end, this.delay]); }};
            }
        };
        return new Proxy(view, {get: (obj, key) => obj[key] ?? (() => {})});
    }
    const layerGroup = {children: [], findViewWithTag(tag) { return this.children.find(view => view.tag === tag) ?? null; },
        equals(other) { return this === other; }};
    const overlay = {getOverlayView: () => layerGroup, add(view) { view.setParent(layerGroup); layerGroup.children.push(view); },
        remove(view) { layerGroup.children.splice(layerGroup.children.indexOf(view), 1); view.setParent(null); }};
    const factory = nativeView();
    factory.setTag('stock');
    overlay.add(factory);
    let visibleWidth = 574;
    const host = {getDisplay: () => ({getName: () => 'Cluster-Media-Display'}), getOverlay: () => overlay,
        getContext: () => ({getPackageName: () => 'com.qinggan.instrumentcard'}),
        getResources: () => ({getDisplayMetrics: () => ({density: {value: 1}}), getConfiguration: () => ({fontScale: {value: 1}})}),
        getLocalVisibleRect(rect) {
            Object.assign(rect, {left: {value: 0}, top: {value: 0}, right: {value: visibleWidth}, bottom: {value: 464}});
            return true;
        },
        isAttachedToWindow: () => true, isShown: () => true, getWidth: () => 574, getHeight: () => 464,
        equals(other) { return this === other; }, $dispose() {}};
    const java = {
        cast: value => value, retain: value => value,
        use(name) {
            if (name === 'android.os.Build$VERSION') return {SDK_INT: {value: 30}};
            if (name === 'android.view.ViewGroup') return {class: {isInstance: () => true}};
            if (name === 'android.view.ViewOverlay') return {getOverlayView: {overload: () => ({call: view => view.getOverlayView()})}};
            if (name === 'android.widget.FrameLayout$LayoutParams') return {$new: (width, height) => ({width, height, leftMargin: {}, topMargin: {}})};
            if (name === 'android.widget.ImageView$ScaleType') return {CENTER_CROP: {value: 'center'}};
            if (name === 'android.text.TextUtils$TruncateAt') return {END: {value: 'end'}};
            if (name === 'android.content.res.ColorStateList') return {valueOf: color => color};
            if (name === 'java.lang.String') return {$new: value => value};
            return {$new: nativeView};
        }
    };
    const layer = createDriverMediaLayer(java);
    const model = {source: 'Music', title: 'A track', artist: 'An artist', artworkRevision: 0, progress: null};
    assert.equal(layer.update(host, model), true);
    const drawn = layerGroup.children[1];
    assert.deepEqual([drawn.width, drawn.height, drawn.focusable, drawn.clickable, drawn.alpha], [574, 464, false, false, 1]);
    assert.equal(drawn.children[1].children[1].text, 'A track');
    assert.deepEqual(calls.at(-1), [drawn, 0, 3500]);
    layer.update(host, {...model, title: 'Another track'});
    assert.equal(layerGroup.children.length, 2);
    const route = {at: 1_000, heading: 'Next maneuver', title: 'River Road', detail: '250 m'};
    assert.equal(layer.updateNavigation(host, route, 2_000), true);
    const navigation = layerGroup.children[1];
    assert.notEqual(navigation, drawn);
    assert.equal(drawn.alpha, 0);
    const content = navigation.children[0];
    assert.deepEqual([content.params.width, content.params.height, content.params.leftMargin.value,
        content.params.topMargin.value], [288, 112, 143, 176]);
    assert.deepEqual(content.children.map(child => child.text), ['Next maneuver', 'River Road', '250 m']);
    assert.deepEqual(calls.at(-1), [navigation, 0, 4000]);
    const renewals = calls.length;
    layer.updateNavigation(host, route, 3_000);
    assert.equal(calls.length, renewals, 'an unchanged observation cannot extend the drawing lease');
    assert.equal(layer.updateNavigation(host, route, 6_000), false);
    visibleWidth = 573;
    assert.equal(layer.updateNavigation(host, {...route, at: 6_000}, 6_100), false);
    assert.equal(layer.update(host, model), true, 'the existing player can return');
    assert.equal(navigation.alpha, 0);
    layer.clear(host);
    assert.deepEqual(layerGroup.children, [factory]);
    assert.equal(drawn.alpha, 0);
});

test('a track with a duration moves on while it plays; a station has no progress', () => {
    const {trackProgress} = load().api;
    const track = {duration: 200000, position: 50000, updated: 1000, speed: 1, playing: true};
    assert.deepEqual(plain(trackProgress(track, 3000)), {position: 52000, duration: 200000});
    assert.deepEqual(plain(trackProgress({...track, playing: false}, 3000)), {position: 50000, duration: 200000});
    assert.deepEqual(plain(trackProgress({...track, speed: 2}, 3000)), {position: 54000, duration: 200000});
    // Never past the end, never before the start.
    assert.deepEqual(plain(trackProgress(track, 1_000_000)), {position: 200000, duration: 200000});
    assert.deepEqual(plain(trackProgress({...track, position: -5}, 1000)), {position: 0, duration: 200000});
    assert.equal(trackProgress({...track, duration: -1}, 3000), null);
    assert.equal(trackProgress({...track, duration: 0}, 3000), null);
    assert.equal(trackProgress({...track, duration: NaN}, 3000), null);
});

// A Java-like enum constant: identity equality and writable fields.
function constant(name, fields) {
    const object = {name, equals(other) { return other === this; }, toString: () => name};
    for (const [field, value] of Object.entries(fields)) object[field] = {value};
    return object;
}
function javaSet(items = []) {
    const values = new Set(items);
    return {add: x => values.add(x), remove: x => values.delete(x), contains: x => values.has(x), values};
}
function javaMap() {
    const values = new Map();
    return {get: key => values.get(key) ?? null, put: (key, value) => values.set(key, value),
        remove(key) { const value = values.get(key) ?? null; values.delete(key); return value; }, values};
}
function javaList(items = []) {
    return {items: [...items], contains(x) { return this.items.includes(x); }};
}
function imageView() {
    return {drawable: null, bitmap: null, getDrawable() { return this.drawable; },
        setImageDrawable(d) { this.drawable = d; this.bitmap = null; },
        setImageBitmap(b) { this.bitmap = b; this.drawable = null; }};
}

function harness(options = {}) {
    const timers = [];
    const calls = [];
    const state = {config: options.config ?? ON, installed: new Set(options.installed ?? [MUSIC.package, RADIO.package]),
        locale: 'ru-RU', memory: options.memory ?? null, clock: 10_000, framework: options.framework ?? true};
    const hooks = {};
    const hook = (name, original = () => undefined) => (hooks[name] = {implementation: null,
        call(self, ...args) { calls.push([name, ...args]); return original(self, ...args); }});
    // The factory MediaEnum values the agent touches, as in the instrument card 1.0.
    const factory = {
        NO: {mediaId: -1, serviceName: '', streamType: -1, pageName: 'no', servicePageName: 'no', clientId: 'no'},
        BT_MUSIC: {mediaId: 0, serviceName: 'bt', streamType: 4, pageName: '', servicePageName: 'com.qinggan.media', clientId: ''},
        SYSTEM_BT_MUSIC: {mediaId: 11, streamType: -2, pageName: '', serviceName: 'com.qinggan.media.btmusic.BTMediaBrowserService',
            servicePageName: 'com.android.bluetooth', clientId: 'com.qinggan.media.btmusic.BTMediaBrowserService'},
        USB_MUSIC: {mediaId: 1, serviceName: 'usb', streamType: 3, pageName: 'u', servicePageName: 'com.qinggan.media', clientId: 'u'},
        LOCAL_RADIO: {mediaId: 6, serviceName: 'r', streamType: 21, pageName: 'r', servicePageName: 'com.qinggan.media', clientId: 'r'},
        XMLA_MUSIC: {mediaId: 5, serviceName: 'x', streamType: 3, pageName: 'x', servicePageName: 'com.qinggan.media', clientId: 'x'},
        RADIO_YUNTING: {mediaId: 14, serviceName: 'y', streamType: 3, pageName: 'y', servicePageName: 'com.qinggan.media', clientId: 'y'},
        WECAR_FLOW: {mediaId: 15, serviceName: 'w', streamType: 3, pageName: 'w', servicePageName: 'com.qinggan.media', clientId: 'w'},
        DAB: {mediaId: 15, streamType: 3, pageName: 'com.ex.dabplayer.pad', serviceName: 'com.ex.dabplayer.pad.service.DabMediaService',
            servicePageName: 'com.qinggan.media', clientId: 'com.ex.dabplayer.pad'},
        LOCAL_VIDEO: {mediaId: 8, serviceName: '', streamType: 3, pageName: 'v', servicePageName: 'com.qinggan.media', clientId: 'v'}
    };
    // Frida reads a static field without initializing its class: the enum's constants are null until the card's
    // framework has used the enum.
    const constants = {};
    const media = {};
    for (const [name, fields] of Object.entries(factory)) {
        constants[name] = constant(name, fields);
        media[name] = {get value() { return state.framework ? constants[name] : null; }};
    }
    const E = name => constants[name];
    // AsgardMediaConfigImpl on this car; every new PanelView installs a fresh one.
    const newSettings = () => ({mediaEnums: {value: javaSet(['USB_MUSIC', 'LOCAL_RADIO', 'XMLA_MUSIC', 'BT_MUSIC', 'RADIO_YUNTING', 'WECAR_FLOW'].map(E))},
        controlEnums: {value: javaSet(['BT_MUSIC', 'USB_MUSIC', 'LOCAL_RADIO', 'XMLA_MUSIC', 'RADIO_YUNTING', 'WECAR_FLOW'].map(E))},
        getSupportMedias() { return this.mediaEnums.value; }, getControlMedias() { return this.controlEnums.value; }});
    state.settings = newSettings();
    const controls = [];
    const manager = {controlMap: {value: javaMap()}, mediaInfoMap: {value: javaMap()}, playMap: {value: javaMap()},
        playStateMap: {value: javaMap()}, linkedList: {value: {items: [], remove(x) { this.items = this.items.filter(i => i !== x); }}},
        curMediaEnum: {value: E(options.current ?? 'BT_MUSIC')},
        addMediaControl(control) { this.controlMap.value.put(control.value, control); },
        // The most recent source of the list, as MediaManager.getMyMediaEnum.
        getMyMediaEnum(list) { return [...this.linkedList.value.items].reverse().find(x => list.items.includes(x)) ?? E('NO'); },
        onMediaTypeChange(value, tag) { calls.push(['onMediaTypeChange', value.name]); this.curMediaEnum.value = value; this.linkedList.value.items.push(value); }};
    // The audio policy's latest media entry: Bluetooth music unless a test says otherwise.
    const policy = {mLatestMedia: {value: 0}, latest: options.latest === undefined ? {getStreamType: () => 4, getPackageName: () => '',
        getClientId: () => 'bt'} : options.latest, getLatestMediaInfo() { return this.latest; },
        notifyFocusChanged() { calls.push(['notifyFocusChanged']); }};
    const prefs = {getString: (key, fallback) => (key === 'key_media_type' && state.memory !== null ? state.memory : fallback),
        edit: () => ({putString(key, value) { calls.push(['memory', key, value]); return {apply() { state.memory = value; }}; }})};
    const icon = pkg => ({getConstantState: () => ({newDrawable: () => ({icon: pkg})})});
    let packageChecks = 0;
    const pm = {
        getServiceInfo(component) {
            packageChecks++;
            if (!state.installed.has(component.pkg)) throw new Error('android.content.pm.PackageManager$NameNotFoundException: ' + component.pkg);
            return {};
        },
        getApplicationInfo: (pkg) => ({pkg}),
        getApplicationLabel: info => (info.pkg === MUSIC.package ? 'Voyah Music' : 'Voyah Radio') + ' ' + state.locale,
        getApplicationIcon: pkg => icon(pkg)
    };
    const application = {getPackageManager: () => pm, getApplicationContext: () => application,
        getSharedPreferences(name) { assert.equal(name, 'media_memory_name'); return prefs; },
        getResources: () => ({getConfiguration: () => ({getLocales: () => ({toLanguageTags: () => state.locale})})})};
    // Each app's session as the card's connection sees it.
    const sessions = {
        [MUSIC.package]: {duration: 180000, position: 30000, updated: 10_000, speed: 1, state: 3, art: null},
        [RADIO.package]: {duration: -1, position: 0, updated: 10_000, speed: 1, state: 3, art: {bitmap: 'station'}}
    };
    let metadataReads = 0;
    const controller = pkg => ({
        getMetadata: () => (metadataReads++, {getLong: key => (key === 'android.media.metadata.DURATION' ? sessions[pkg].duration : 0),
            getString: key => (key === 'android.media.metadata.TITLE' ? 'Media title' : null),
            getBitmap: key => (key === 'android.media.metadata.ART' ? sessions[pkg].art : null)}),
        getPlaybackState: () => ({getPosition: () => sessions[pkg].position, getLastPositionUpdateTime: () => sessions[pkg].updated,
            getPlaybackSpeed: () => sessions[pkg].speed, getState: () => sessions[pkg].state})
    });
    // The music view the cluster shows; its own source is the card helper's current one.
    const view = {cur: E('BT_MUSIC'), isMusic: true, list: javaList(['BT_MUSIC', 'USB_MUSIC', 'LOCAL_RADIO', 'WECAR_FLOW', 'XMLA_MUSIC', 'RADIO_YUNTING'].map(E)),
        mTvTitle: {value: {text: '音乐', getText() { return this.text; }}}, mIvType: {value: imageView()}, mIvCover: {value: imageView()},
        handler: {value: {removed: [], removeMessages(what) { this.removed.push(what); }}}, ring: null, progress: null,
        getCurMediaEnum() { return this.cur; }, updateProgressView(shown) { this.ring = shown; },
        updateProgress(position, duration, value) { this.progress = [position, duration, value.name]; }};
    const panel = {children: [view], getChildCount() { return this.children.length; }, getChildAt(i) { return this.children[i]; }};
    const screen = {panelMusicView: {value: panel}};
    const classes = {
        'android.os.Build$VERSION': {SDK_INT: {value: 28}},
        'com.qinggan.media.helper.MediaEnum': media,
        'com.qinggan.media.helper.MediaConfig': {getInstance: () => ({getMediaConfigInter: () => state.settings})},
        'com.qinggan.media.helper.impl.BaseConfigImpl': {},
        'com.qinggan.app.mediaCentre.MediaManager': {getInstance() {
            assert.ok(state.framework, 'MediaManager used before the card set up its framework');
            return manager;
        }},
        'com.qinggan.app.mediaCentre.manager.MediaControlManager': {$new(ctx, value) {
            const pkg = value.servicePageName.value;
            // MediaBrowserHelper.connectService connects only a source the current configuration supports.
            const supported = state.settings.getSupportMedias().contains(value);
            const browser = {connected: supported && (options.connected ?? true), stopped: false, isConnected() { return this.connected; },
                getMediaController: () => controller(pkg),
                onStop() {
                    // The stop reports the last state to MediaManager at once, which makes a current slot current again.
                    calls.push(['stop', value.name, manager.curMediaEnum.value.name]);
                    if (manager.curMediaEnum.value === value) manager.linkedList.value.items.push(value);
                    this.stopped = true;
                    this.connected = false;
                }};
            const control = {value, listeners: [], browser, addQinMediaListener(l) { this.listeners.push(l); },
                getMediaBrowserHelper: () => browser, equals(other) { return other === this; }, $dispose() {}};
            controls.push(control);
            return control;
        }},
        'com.qinggan.media.helper.AudioPolicyHelper': {
            isMediaFocus: {overload: (...types) => (types.length === 2 ? hook('focusWith', () => 'oem') : hook('focusLatest', () => 'oem'))},
            getInstance() {
                assert.ok(state.framework, 'AudioPolicyHelper used before the card set up its framework');
                return policy;
            }
        },
        'com.qinggan.app.mediaCentre.view.BaseMediaView': {getMediaEnums: {overload: () => hook('sources', self => self.list)}},
        'com.qinggan.cardview.panel.media.MusicBaseView': {
            updateView: {overload: () => hook('update')},
            updateUrlImage: {overload: () => hook('coverUrl')},
            class: {isInstance: child => child.isMusic === true}
        },
        'com.qinggan.instrumentcard.ScreenActivity': {screenActivity: {value: options.screen === false ? null : screen}},
        'com.qinggan.app.service.utils.ContextUtils': {context: {get value() { return state.framework ? application : null; }}},
        'java.util.ArrayList': {$new: {overload: () => ({call: (cls, from) => javaList(from.items ?? [...from.values])})},
            add: {overload: () => ({call(list, x) { list.items.push(x); }})},
            remove: {overload: () => ({call(list, x) { list.items = list.items.filter(i => i !== x); }})}},
        'java.util.LinkedList': {remove: {overload: () => ({call(list, x) { list.remove(x); }})}},
        'android.app.ActivityThread': {currentApplication: () => application},
        'android.content.ComponentName': {$new: (pkg, cls) => ({pkg, cls})},
        'android.widget.TextView': {setText: {overload: () => ({call(target, text) { target.text = text; }})}},
        'java.lang.String': {$new: value => value},
        'android.os.SystemClock': {elapsedRealtime: () => state.clock}
    };
    const Java = {
        use(name) {
            if (options.missing === name) throw new Error('java.lang.ClassNotFoundException: ' + name);
            assert.ok(classes[name], name);
            return classes[name];
        },
        cast: value => value, retain: value => value, perform: fn => fn(), scheduleOnMainThread: fn => fn()
    };
    let stale = null;
    if (options.leftover) {
        // As an agent that vanished without restoring left the card: a borrowed slot and its connection.
        const dab = E('DAB');
        Object.assign(dab, {servicePageName: {value: RADIO.package}, serviceName: {value: RADIO.service}, mediaId: {value: 9002},
            clientId: {value: RADIO.package + '.'}});
        stale = {value: dab, browser: {stopped: false, onStop() { this.stopped = true; }}, getMediaBrowserHelper() { return this.browser; }};
        manager.controlMap.value.put(dab, stale);
        state.settings.mediaEnums.value.add(dab);
        manager.curMediaEnum.value = dab;
        manager.linkedList.value.items.push(E('USB_MUSIC'), dab);
        policy.mLatestMedia.value = 9002;
    }
    let now = 1_700_000_000_000;
    const {api, logs} = load({setInterval: (fn, ms) => { timers.push({fn, ms}); return timers.length; },
        Date: {now: () => now}, fakeDriverLayer: options.fakeDriverLayer});
    const result = api.installClusterMediaCard(Java, {readConfig: () => state.config, readRoute: options.readRoute});
    const tick = (ms = 1000) => { now += ms; state.clock += ms; timers[0].fn(); };
    const settle = () => tick();
    return {api, result, logs, calls, hooks, state, manager, policy, controls, media: E, view, panel, sessions, tick, settle,
        factory, stale, newSettings, packageChecks: () => packageChecks, metadataReads: () => metadataReads};
}

/** As the factory card does when a source becomes current: its helper switches, then the view updates. */
function show(h, name) {
    h.view.cur = h.media(name);
    h.hooks.update.implementation.call(h.view, h.media(name), {});
}

test('live navigation temporarily takes the visual slot and returns it to the currently focused source', () => {
    const token = 'a'.repeat(32);
    const modes = [];
    const reads = [];
    let route = {at: 10_000, heading: 'Next maneuver', title: 'River Road', detail: '250 m'};
    let h;
    const hLayer = {
        navigationFits: view => view !== null,
        updateNavigation(view, model, now) {
            if (view === null || now - model.at >= 5000) return false;
            modes.push('navigation');
            return true;
        },
        update(view, model) { modes.push(model?.source ?? 'stock'); },
        clear() { modes.push('stock'); }
    };
    h = harness({config: {...ON, driverNavigation: token, clusterCard: {...ON.clusterCard, driverLayout: true}},
        fakeDriverLayer: hLayer, readRoute(context, requested) { reads.push(requested); return route; }});
    const focus = pkg => ({getStreamType: () => 3, getPackageName: () => pkg, getClientId: () => 'x'});
    h.policy.latest = focus(MUSIC.package);
    show(h, 'SYSTEM_BT_MUSIC');
    h.tick();
    assert.equal(modes.at(-1), 'Voyah Music ru-RU');
    assert.deepEqual(reads, []);
    h.tick();
    assert.equal(modes.at(-1), 'navigation');
    const currentSource = h.view.getCurMediaEnum;
    h.view.getCurMediaEnum = () => { throw new Error('Media unavailable'); };
    h.tick(100);
    assert.equal(modes.at(-1), 'navigation');
    h.view.getCurMediaEnum = currentSource;
    h.policy.latest = focus(RADIO.package);
    show(h, 'DAB');
    h.tick();
    assert.equal(modes.at(-1), 'navigation');
    assert.equal(h.hooks.focusLatest.implementation.call({}, h.media('DAB')), true);
    h.tick(2000);
    assert.equal(modes.at(-1), 'Voyah Radio ru-RU');
    route = {...route, at: h.state.clock};
    h.tick();
    assert.equal(modes.at(-1), 'navigation');
    h.state.config = {schema: 1, driverNavigation: token};
    h.tick();
    assert.equal(modes.at(-1), 'navigation', 'without companion media slots');
    h.panel.children = [];
    h.tick();
    assert.equal(modes.at(-1), 'stock');
    h.panel.children = [h.view];
    h.tick();
    route = {...route, at: h.state.clock};
    h.tick();
    assert.equal(modes.at(-1), 'navigation');
    h.state.config = {schema: 1, restoreIcons: 'b'.repeat(32)};
    h.tick();
    assert.equal(modes.at(-1), 'stock');
    assert.ok(h.logs.includes('OVC_CLUSTER_CARD_RESTORED ' + 'b'.repeat(32)));
    assert.ok(reads.every(value => value === token));
});

test('each app borrows a slot, joins the framework configuration and is connected as a factory source', () => {
    const h = harness();
    assert.deepEqual(h.logs, ['OVC_CLUSTER_CARD_READY']);
    const music = h.media('SYSTEM_BT_MUSIC'), radio = h.media('DAB');
    assert.deepEqual([music.mediaId.value, music.serviceName.value, music.servicePageName.value, music.clientId.value, music.streamType.value],
        [9001, MUSIC.service, MUSIC.package, MUSIC.package + '.', 3]);
    assert.deepEqual([radio.mediaId.value, radio.serviceName.value, radio.servicePageName.value, radio.streamType.value],
        [9002, RADIO.service, RADIO.package, 3]);
    for (const value of [music, radio]) {
        assert.ok(h.state.settings.mediaEnums.value.contains(value));
        assert.ok(h.state.settings.controlEnums.value.contains(value));
    }
    assert.deepEqual(h.controls.map(control => control.value.name), ['SYSTEM_BT_MUSIC', 'DAB']);
    assert.ok(h.controls.every(control => control.listeners[0] === h.manager));
    // Each slot was in the configuration before its control was created, so the control connected.
    assert.ok(h.controls.every(control => control.browser.connected));
    assert.equal(h.manager.controlMap.value.get(radio), h.controls[1]);
    // The factory focus path runs again, so an app that already plays takes the card at once.
    assert.ok(h.calls.some(call => call[0] === 'notifyFocusChanged'));
    // An unchanged setting changes nothing, and asks the package manager only now and then.
    const checks = h.packageChecks();
    h.calls.length = 0;
    h.settle();
    assert.deepEqual(h.calls, []);
    assert.equal(h.packageChecks(), checks);
    h.tick(15000);
    assert.equal(h.packageChecks(), checks + 2);
    h.tick();
    assert.equal(h.packageChecks(), checks + 2);
});

test('the card views accept the apps as sources; their factory list stays as it was', () => {
    const h = harness();
    const listed = h.hooks.sources.implementation.call(h.view);
    assert.deepEqual(listed.items.map(value => value.name),
        ['BT_MUSIC', 'USB_MUSIC', 'LOCAL_RADIO', 'WECAR_FLOW', 'XMLA_MUSIC', 'RADIO_YUNTING', 'SYSTEM_BT_MUSIC', 'DAB']);
    assert.equal(h.view.list.items.length, 6);
    h.state.config = {schema: 1};
    h.settle();
    assert.equal(h.hooks.sources.implementation.call(h.view), h.view.list);
});

test('the latest media focus of an app selects its slot; other sources keep the factory check', () => {
    const h = harness();
    const info = (pkg, stream = 3) => ({getStreamType: () => stream, getPackageName: () => pkg, getClientId: () => 'x'});
    assert.equal(h.hooks.focusWith.implementation.call({}, h.media('DAB'), info(RADIO.package)), true);
    assert.equal(h.hooks.focusWith.implementation.call({}, h.media('DAB'), info(MUSIC.package)), false);
    assert.equal(h.hooks.focusWith.implementation.call({}, h.media('DAB'), info(RADIO.package, 4)), false);
    assert.equal(h.hooks.focusWith.implementation.call({}, h.media('SYSTEM_BT_MUSIC'), null), false);
    assert.equal(h.hooks.focusWith.implementation.call({}, h.media('USB_MUSIC'), info(RADIO.package)), 'oem');
    h.policy.latest = info(MUSIC.package);
    assert.equal(h.hooks.focusLatest.implementation.call({}, h.media('SYSTEM_BT_MUSIC')), true);
    assert.equal(h.hooks.focusLatest.implementation.call({}, h.media('DAB')), false);
    assert.equal(h.hooks.focusLatest.implementation.call({}, h.media('BT_MUSIC')), 'oem');
});

test('a view showing Voyah Radio gets its name, icon, station picture and no progress ring', () => {
    const h = harness();
    show(h, 'DAB');
    assert.equal(h.view.mTvTitle.value.text, 'Voyah Radio ru-RU');
    assert.deepEqual({...h.view.mIvType.value.drawable}, {icon: RADIO.package});
    assert.deepEqual(h.view.mIvCover.value.bitmap, {bitmap: 'station'});
    // The factory default disc must not replace the picture 3 s later.
    assert.deepEqual(h.view.handler.value.removed, [1]);
    assert.equal(h.view.ring, false);
    // A station without a picture shows the app icon.
    h.sessions[RADIO.package].art = null;
    show(h, 'DAB');
    assert.deepEqual({...h.view.mIvCover.value.drawable}, {icon: RADIO.package});
    // A language change gives the app's name in the new language.
    h.state.locale = 'en-US';
    show(h, 'DAB');
    assert.equal(h.view.mTvTitle.value.text, 'Voyah Radio en-US');
});

test('a view showing Voyah Music gets the app icon as cover and a moving progress ring', () => {
    const h = harness();
    show(h, 'SYSTEM_BT_MUSIC');
    assert.equal(h.view.mTvTitle.value.text, 'Voyah Music ru-RU');
    assert.deepEqual({...h.view.mIvCover.value.drawable}, {icon: MUSIC.package});
    assert.deepEqual(h.view.progress, [30000, 180000, 'SYSTEM_BT_MUSIC']);
    h.tick();
    assert.deepEqual(h.view.progress, [31000, 180000, 'SYSTEM_BT_MUSIC']);
    // Paused, the ring stays where the track stopped.
    h.sessions[MUSIC.package] = {...h.sessions[MUSIC.package], state: 2, position: 40000, updated: h.state.clock};
    h.tick(5000);
    assert.deepEqual(h.view.progress, [40000, 180000, 'SYSTEM_BT_MUSIC']);
    // The ring reads only the playback state; the metadata with its pictures is read when the view updates.
    const reads = h.metadataReads();
    h.tick();
    h.tick();
    assert.equal(h.metadataReads(), reads);
});

test('the app name comes back after the card resets its title for a language change', () => {
    const h = harness();
    show(h, 'DAB');
    // MusicBaseView.onConfigurationChanged writes the factory word again 100 ms after a language change.
    h.view.mTvTitle.value.text = '音乐';
    h.tick();
    assert.equal(h.view.mTvTitle.value.text, 'Voyah Radio ru-RU');
});

test('views of factory sources stay as the card drew them', () => {
    const h = harness();
    show(h, 'BT_MUSIC');
    assert.equal(h.view.mTvTitle.value.text, '音乐');
    assert.equal(h.view.mIvCover.value.drawable, null);
    assert.deepEqual(h.view.handler.value.removed, []);
    h.view.progress = null;
    h.tick();
    assert.equal(h.view.progress, null);
    // The factory card loads a factory source's cover itself, but never an app's media URI.
    h.calls.length = 0;
    h.hooks.coverUrl.implementation.call(h.view, h.media('DAB'), 'http://stream.example/live.mp3');
    assert.deepEqual(h.calls, []);
    h.hooks.coverUrl.implementation.call(h.view, h.media('USB_MUSIC'), '/storage/usb/cover.jpg');
    assert.deepEqual(h.calls, [['coverUrl', h.media('USB_MUSIC'), '/storage/usb/cover.jpg']]);
});

test('without a connection to the app the view keeps its name and icon', () => {
    const h = harness({connected: false});
    show(h, 'SYSTEM_BT_MUSIC');
    assert.equal(h.view.mTvTitle.value.text, 'Voyah Music ru-RU');
    assert.deepEqual({...h.view.mIvCover.value.drawable}, {icon: MUSIC.package});
    assert.equal(h.view.ring, false);
});

test('a new PanelView gets a fresh framework configuration; the apps join it again', () => {
    const h = harness();
    h.state.settings = h.newSettings();
    h.settle();
    assert.ok(h.state.settings.mediaEnums.value.contains(h.media('DAB')));
    assert.ok(h.state.settings.controlEnums.value.contains(h.media('SYSTEM_BT_MUSIC')));
    // Nothing is connected twice.
    assert.equal(h.controls.length, 2);
});

test('off puts the factory slots back and returns the card to the factory source heard last', () => {
    const h = harness();
    show(h, 'DAB');
    h.manager.curMediaEnum.value = h.media('DAB');
    h.manager.linkedList.value.items.push(h.media('USB_MUSIC'), h.media('DAB'));
    h.policy.mLatestMedia.value = 9002;
    h.state.memory = 'DAB';
    h.state.config = {schema: 1};
    h.calls.length = 0;
    h.settle();
    for (const name of ['SYSTEM_BT_MUSIC', 'DAB']) {
        const value = h.media(name);
        for (const [field, factory] of Object.entries(h.factory[name])) assert.equal(value[field].value, factory, name + '.' + field);
        assert.equal(h.state.settings.mediaEnums.value.contains(value), false);
        assert.equal(h.state.settings.controlEnums.value.contains(value), false);
        assert.equal(h.manager.controlMap.value.get(value), null);
    }
    assert.ok(h.controls.every(control => control.browser.stopped));
    // The card left the slot before its connection stopped, so the stop did not make it current again.
    assert.ok(h.calls.filter(call => call[0] === 'stop').every(call => call[2] === 'NO'));
    assert.ok(!h.manager.linkedList.value.items.some(value => value === h.media('DAB') || value === h.media('SYSTEM_BT_MUSIC')));
    // The card shows USB music again, the policy helper reports the next source, and nothing remembers a slot.
    assert.deepEqual(h.calls.filter(call => call[0] === 'onMediaTypeChange'), [['onMediaTypeChange', 'USB_MUSIC']]);
    assert.equal(h.policy.mLatestMedia.value, -1);
    assert.ok(h.calls.some(call => call[0] === 'notifyFocusChanged'));
    assert.equal(h.state.memory, 'BT_MUSIC');
    // The hooks are inert now.
    assert.equal(h.hooks.focusWith.implementation.call({}, h.media('DAB'), null), 'oem');
    h.calls.length = 0;
    h.hooks.coverUrl.implementation.call(h.view, h.media('DAB'), 'u');
    assert.equal(h.calls.length, 1);
});

test('without a factory source heard, the card falls back to Bluetooth music', () => {
    const h = harness();
    h.manager.curMediaEnum.value = h.media('SYSTEM_BT_MUSIC');
    h.calls.length = 0;
    assert.equal(h.result.restoreAll(), true);
    assert.deepEqual(h.calls.filter(call => call[0] === 'onMediaTypeChange'), [['onMediaTypeChange', 'BT_MUSIC']]);
    // A card that shows a factory source is not moved.
    const other = harness();
    other.calls.length = 0;
    assert.equal(other.result.restoreAll(), true);
    assert.deepEqual(other.calls.filter(call => call[0] === 'onMediaTypeChange'), []);
});

test('a view still showing an app moves to a factory source even when MediaManager follows another source', () => {
    const h = harness();
    show(h, 'DAB');
    // USB video took the focus: MediaManager follows it, the music view ignores it and keeps the app.
    h.manager.curMediaEnum.value = h.media('LOCAL_VIDEO');
    h.manager.linkedList.value.items.push(h.media('USB_MUSIC'));
    h.calls.length = 0;
    h.state.config = {schema: 1};
    h.settle();
    assert.deepEqual(h.calls.filter(call => call[0] === 'onMediaTypeChange'), [['onMediaTypeChange', 'USB_MUSIC']]);
});

test('the restore token puts the factory card back like off and is answered once', () => {
    const h = harness();
    const token = 'b'.repeat(32);
    h.state.config = {...ON, restoreIcons: token};
    h.settle();
    assert.equal(h.media('DAB').servicePageName.value, 'com.qinggan.media');
    assert.equal(h.manager.controlMap.value.get(h.media('DAB')), null);
    assert.deepEqual(h.logs, ['OVC_CLUSTER_CARD_READY', 'OVC_CLUSTER_CARD_RESTORED ' + token]);
    h.settle();
    assert.equal(h.logs.length, 2);
    // Anything but a token Controls writes is not repeated in the log.
    const other = harness();
    other.state.config = {...ON, restoreIcons: 'x y'};
    other.settle();
    assert.deepEqual(other.logs, ['OVC_CLUSTER_CARD_READY']);
});

test('a restore that failed is not answered until it succeeds', () => {
    const h = harness();
    const token = 'c'.repeat(32);
    let fail = 1;
    const control = h.controls[0];
    const stop = control.browser.onStop;
    control.browser.onStop = function () {
        if (fail-- > 0) throw new Error('java.lang.IllegalStateException: binder');
        return stop.call(this);
    };
    h.state.config = {...ON, restoreIcons: token};
    h.settle();
    assert.ok(!h.logs.includes('OVC_CLUSTER_CARD_RESTORED ' + token));
    h.settle();
    assert.ok(h.logs.includes('OVC_CLUSTER_CARD_RESTORED ' + token));
});

test('without a latest media entry the focus path is not run again', () => {
    // AudioPolicyHelper.notifyFocusChanged would clear the card to its empty view without an entry.
    const h = harness({latest: null});
    assert.deepEqual(h.calls.filter(call => call[0] === 'notifyFocusChanged'), []);
    assert.deepEqual(h.controls.map(control => control.value.name), ['SYSTEM_BT_MUSIC', 'DAB']);
});

test('before the card sets up its media framework nothing touches it; the apps come once it is there', () => {
    const h = harness({framework: false});
    // No error lines: the enum is not even initialized yet, and nothing can have borrowed a slot.
    assert.deepEqual(h.logs, ['OVC_CLUSTER_CARD_READY']);
    assert.deepEqual(h.controls, []);
    h.settle();
    assert.deepEqual(h.controls, []);
    // A restore token is answered at once: there is nothing to restore.
    h.state.config = {...ON, restoreIcons: 'd'.repeat(32)};
    h.settle();
    assert.deepEqual(h.logs, ['OVC_CLUSTER_CARD_READY', 'OVC_CLUSTER_CARD_RESTORED ' + 'd'.repeat(32)]);
    h.state.config = ON;
    h.state.framework = true;
    h.settle();
    assert.deepEqual(h.controls.map(control => control.value.name), ['SYSTEM_BT_MUSIC', 'DAB']);
});

test('slots a vanished agent borrowed go back before the new agent borrows them', () => {
    const off = harness({config: {schema: 1}, leftover: true});
    assert.deepEqual(off.logs, ['OVC_CLUSTER_CARD_READY']);
    for (const [field, factory] of Object.entries(off.factory.DAB)) assert.equal(off.media('DAB')[field].value, factory, field);
    assert.equal(off.stale.browser.stopped, true);
    assert.equal(off.manager.controlMap.value.get(off.media('DAB')), null);
    assert.equal(off.state.settings.mediaEnums.value.contains(off.media('DAB')), false);
    assert.deepEqual(off.calls.filter(call => call[0] === 'onMediaTypeChange'), [['onMediaTypeChange', 'USB_MUSIC']]);
    assert.equal(off.policy.mLatestMedia.value, -1);
    const on = harness({leftover: true});
    assert.equal(on.media('DAB').servicePageName.value, RADIO.package);
    assert.equal(on.manager.controlMap.value.get(on.media('DAB')), on.controls[1]);
});

test('an app that is not installed is not offered; it appears once installed', () => {
    const h = harness({installed: [RADIO.package]});
    assert.deepEqual(h.controls.map(control => control.value.servicePageName.value), [RADIO.package]);
    assert.equal(h.media('SYSTEM_BT_MUSIC').servicePageName.value, RADIO.package);
    h.state.installed.add(MUSIC.package);
    h.tick(15000);
    assert.equal(h.media('SYSTEM_BT_MUSIC').servicePageName.value, MUSIC.package);
    assert.equal(h.media('DAB').servicePageName.value, RADIO.package);
});

test('without a card on the cluster the progress tick does nothing', () => {
    const h = harness({screen: false});
    h.settle();
    assert.deepEqual(h.logs, ['OVC_CLUSTER_CARD_READY']);
});

test('a card without the expected classes reports it and installs nothing', () => {
    const h = harness({missing: 'com.qinggan.cardview.panel.media.MusicBaseView'});
    assert.equal(h.result, null);
    assert.deepEqual(h.logs, ['OVC_CLUSTER_CARD_ERROR stage=install kind=ClassNotFoundException']);
});

test('dispose puts the factory card back, then unhooks', () => {
    const h = harness();
    h.result.dispose();
    for (const name of ['sources', 'update', 'coverUrl', 'focusWith', 'focusLatest']) {
        assert.equal(h.hooks[name].implementation, null, name);
    }
    assert.equal(h.media('DAB').servicePageName.value, 'com.qinggan.media');
    assert.ok(h.controls.every(control => control.browser.stopped));
});
