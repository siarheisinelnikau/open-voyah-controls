const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = ['report.js', 'home-media-card.js'].map(file => fs.readFileSync(path.join(__dirname, '../hooks', file), 'utf8')
    .replace(/^import .*;$/gm, '').replace(/^export /gm, '')).join('\n');

function load(extra = {}) {
    const logs = [];
    const context = {console: {log: line => logs.push(line)}, setInterval: () => 1, clearInterval() {}, JSON, ...extra};
    const api = vm.runInNewContext(source + '\n({mediaSources, assignSlots, focusOwner, installHomeMediaCard, MEDIA_SLOTS})', context);
    return {api, logs};
}

const MUSIC = {package: 'app.voyahmusic.player', service: 'app.voyahmusic.player.playback.MusicBrowserService'};
const RADIO = {package: 'app.voyahradio', service: 'app.voyahradio.playback.RadioBrowserService'};
const ON = {schema: 1, mediaCard: {sources: [MUSIC, RADIO]}};
const plain = value => JSON.parse(JSON.stringify(value));

test('the setting lists valid, distinct sources and is off during a restore request', () => {
    const {mediaSources} = load().api;
    assert.deepEqual(plain(mediaSources(ON)), [MUSIC, RADIO]);
    assert.equal(mediaSources({...ON, restoreIcons: 'a'.repeat(32)}), null);
    assert.equal(mediaSources({schema: 1}), null);
    assert.equal(mediaSources({schema: 2, mediaCard: ON.mediaCard}), null);
    assert.equal(mediaSources(null), null);
    const bad = [{package: 'app', service: 'app.X'}, {package: 'app.x;rm', service: 'app.x.S'}, {package: 'app.x', service: ''},
        {package: 'app.x', service: 'S'}, {package: 'a.' + 'b'.repeat(130), service: 'a.b.S'}, null, 'app.x'];
    assert.equal(mediaSources({schema: 1, mediaCard: {sources: bad}}), null);
    // A repeated package keeps its first entry, and there are no more sources than borrowed slots.
    const third = {package: 'org.example.player', service: 'org.example.player.Browser'};
    assert.deepEqual(plain(mediaSources({schema: 1, mediaCard: {sources: [MUSIC, {...MUSIC, service: 'app.voyahmusic.Other'}, RADIO, third]}})),
        [MUSIC, RADIO]);
});

test('sources pair with borrowed slots and free tab entries in order', () => {
    const {assignSlots} = load().api;
    const pairs = assignSlots([MUSIC, RADIO], ['LOCAL_RADIO', 'KW_MUSIC']);
    assert.deepEqual(plain(pairs.map(pair => [pair.source.package, pair.slot.name, pair.tab])),
        [['app.voyahmusic.player', 'SYSTEM_BT_MUSIC', 'LOCAL_RADIO'], ['app.voyahradio', 'DAB', 'KW_MUSIC']]);
    // A car that supports Kuwo and FM radio leaves one free entry, so only the first app gets a tab.
    assert.equal(assignSlots([MUSIC, RADIO], ['WE_CAR']).length, 1);
    assert.equal(assignSlots([MUSIC], []).length, 0);
});

test('an app owns the latest media focus by its package or its focus listener class', () => {
    const {focusOwner} = load().api;
    // As the OEM audio policy logged it for Voyah Radio on the car.
    const radio = {streamType: 3, packageName: 'app.voyahradio',
        clientId: 'android.media.AudioManager@750796eapp.voyahradio.playback.RadioService$$ExternalSyntheticLambda5@b57680f'};
    assert.equal(focusOwner(RADIO, radio), true);
    assert.equal(focusOwner(RADIO, {...radio, packageName: ''}), true);
    // A Media3 player asks for focus with a listener of the library; the package still names the app.
    assert.equal(focusOwner(MUSIC, {streamType: 3, packageName: 'app.voyahmusic.player',
        clientId: 'android.media.AudioManager@1androidx.media3.exoplayer.AudioFocusManager$AudioFocusListener@2'}), true);
    assert.equal(focusOwner(MUSIC, radio), false);
    assert.equal(focusOwner(RADIO, {...radio, streamType: 4}), false);
    assert.equal(focusOwner(RADIO, null), false);
    // A package that only starts with the app's name is another app.
    assert.equal(focusOwner(RADIO, {streamType: 3, packageName: 'app.voyahradiox', clientId: 'x@app.voyahradiox.Player$1@3'}), false);
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
    return {items: [...items], size() { return this.items.length; }, get(i) { return this.items[i]; }};
}

function harness(options = {}) {
    const timers = [];
    const calls = [];
    const state = {config: options.config ?? ON, installed: new Set(options.installed ?? [MUSIC.package, RADIO.package]),
        locale: 'ru-RU'};
    const hooks = {};
    const hook = (name, original = () => undefined) => (hooks[name] = {implementation: null,
        call(self, ...args) { calls.push([name, ...args]); return original(self, ...args); }});
    // The factory MediaEnum values the agent touches, as in the 1.0.6 launcher.
    const factory = {
        NO: {mediaId: -1, serviceName: '', streamType: -1, pageName: 'no', servicePageName: 'no', clientId: 'no'},
        BT_MUSIC: {mediaId: 0, serviceName: 'bt', streamType: 4, pageName: '', servicePageName: 'com.qinggan.media', clientId: ''},
        SYSTEM_BT_MUSIC: {mediaId: 11, streamType: -2, pageName: '', serviceName: 'com.qinggan.media.btmusic.BTMediaBrowserService',
            servicePageName: 'com.android.bluetooth', clientId: 'com.qinggan.media.btmusic.BTMediaBrowserService'},
        USB_MUSIC: {mediaId: 1, serviceName: 'usb', streamType: 3, pageName: 'u', servicePageName: 'com.qinggan.media', clientId: 'u'},
        KW_MUSIC: {mediaId: 4, serviceName: 'kw', streamType: 3, pageName: 'k', servicePageName: 'com.qinggan.media', clientId: 'k'},
        LOCAL_RADIO: {mediaId: 6, serviceName: 'r', streamType: 21, pageName: 'r', servicePageName: 'com.qinggan.media', clientId: 'r'},
        XMLA_MUSIC: {mediaId: 5, serviceName: 'x', streamType: 3, pageName: 'x', servicePageName: 'com.qinggan.media', clientId: 'x'},
        RADIO_YUNTING: {mediaId: 14, serviceName: 'y', streamType: 3, pageName: 'y', servicePageName: 'com.qinggan.media', clientId: 'y'},
        WECAR_FLOW: {mediaId: 15, serviceName: 'w', streamType: 3, pageName: 'w', servicePageName: 'com.qinggan.media', clientId: 'w'},
        DAB: {mediaId: 15, streamType: 3, pageName: 'com.ex.dabplayer.pad', serviceName: 'com.ex.dabplayer.pad.service.DabMediaService',
            servicePageName: 'com.qinggan.media', clientId: 'com.ex.dabplayer.pad'}
    };
    const media = {};
    for (const [name, fields] of Object.entries(factory)) media[name] = {value: constant(name, fields)};
    const E = name => media[name].value;
    const tabs = {};
    for (const [name, value] of [['NO', 'NO'], ['WE_CAR', 'WECAR_FLOW'], ['ONLINE_RADIO', 'RADIO_YUNTING'],
        ['LOCAL_RADIO', 'LOCAL_RADIO'], ['XMLY', 'XMLA_MUSIC'], ['KW_MUSIC', 'KW_MUSIC'], ['BT_MUSIC', 'BT_MUSIC'], ['USB_MUSIC', 'USB_MUSIC']]) {
        tabs[name] = {value: constant('tab:' + name, {mediaEnum: E(value)})};
        tabs[name].value.getMediaEnum = function () { return this.mediaEnum.value; };
    }
    const T = name => tabs[name].value;
    // This car: WeCar Flow instead of Kuwo, no FM antenna.
    const supported = options.supported ?? ['BT_MUSIC', 'XMLA_MUSIC', 'USB_MUSIC', 'WECAR_FLOW', 'RADIO_YUNTING'];
    const factoryTabs = options.factoryTabs ?? ['WE_CAR', 'BT_MUSIC', 'USB_MUSIC', 'XMLY', 'ONLINE_RADIO'];
    const settings = {mediaEnums: {value: javaSet(['USB_MUSIC', 'LOCAL_RADIO', 'XMLA_MUSIC', 'BT_MUSIC', 'RADIO_YUNTING', 'WECAR_FLOW'].map(E))},
        controlEnums: {value: javaSet(['BT_MUSIC', 'USB_MUSIC', 'LOCAL_RADIO', 'XMLA_MUSIC', 'RADIO_YUNTING', 'WECAR_FLOW'].map(E))},
        getSupportMedias() { return this.mediaEnums.value; }, getControlMedias() { return this.controlEnums.value; }};
    const controls = [];
    const manager = {controlMap: {value: javaMap()}, mediaInfoMap: {value: javaMap()}, playMap: {value: javaMap()},
        playStateMap: {value: javaMap()}, linkedList: {value: {items: [], remove(x) { this.items = this.items.filter(i => i !== x); }}},
        curMediaEnum: {value: E('NO')},
        addMediaControl(control) { this.controlMap.value.put(control.value, control); }};
    const listener = {onNoMediaEnum() { calls.push(['onNoMediaEnum']); }};
    const helper = {supportSet: {value: javaSet(supported.map(E))}, cur: E('NO'), listener: {value: listener}, imageUrl: {value: ''},
        getCurMediaEnum() { return this.cur; }, setCurMediaEnum(value) { this.cur = value; },
        updateMediaEnum(value) { calls.push(['updateMediaEnum', value.name]); this.cur = value; },
        clearMediaInfo() { calls.push(['clearMediaInfo']); }};
    const view = name => ({name, text: null, drawable: null, visibility: 0,
        setImageDrawable(drawable) { this.drawable = drawable; }, setVisibility(v) { this.visibility = v; }});
    const tabView = {adapter: {data: {value: javaList()}}, filled: [],
        fillData(list) { this.adapter.data.value = javaList(list.items); this.filled.push(list.items.map(t => t.name)); }};
    tabView.mediaTabAdapter = {value: tabView.adapter};
    const binding = {mediaTab: {value: tabView}, tvMediaName: {value: view('title')}, mediaIcon: {value: view('icon')},
        flRcy: {value: view('rcy')}};
    const context = {name: 'card-context', getPackageManager: () => pm};
    const card = {binding: {value: binding}, mediaInfoHelper: {value: helper}, equals(other) { return other === this; },
        getContext: () => context, $dispose() {},
        initMedia() { return hooks.initMedia.implementation ? hooks.initMedia.implementation.call(card) : factoryInit(card); }};
    function factoryInit(target) {
        target.binding.value.mediaTab.value.fillData({items: factoryTabs.map(T)});
    }
    const icon = pkg => ({getConstantState: () => ({newDrawable: () => ({icon: pkg})})});
    const pm = {
        getServiceInfo(component) {
            if (!state.installed.has(component.pkg)) throw new Error('android.content.pm.PackageManager$NameNotFoundException: ' + component.pkg);
            return {};
        },
        getApplicationInfo: (pkg) => ({pkg}),
        getApplicationLabel: info => (info.pkg === MUSIC.package ? 'Voyah Music' : 'Voyah Radio') + ' ' + state.locale,
        getApplicationIcon: pkg => icon(pkg),
        getLaunchIntentForPackage: pkg => ({getComponent: () => ({flattenToString: () => pkg + '/.Main'})})
    };
    const application = {getPackageManager: () => pm, getApplicationContext: () => application,
        getResources: () => ({getConfiguration: () => ({getLocales: () => ({toLanguageTags: () => state.locale})})})};
    const latest = {info: null};
    const classes = {
        'com.qinggan.media.helper.MediaEnum': media,
        'com.qinggan.media.helper.MediaConfig': {getInstance: () => ({getMediaConfigInter: () => settings})},
        'com.qinggan.media.helper.impl.BaseConfigImpl': {},
        'com.qinggan.app.mediaCentre.MediaManager': {getInstance: () => manager},
        'com.qinggan.app.mediaCentre.manager.MediaControlManager': {$new(ctx, value) {
            const browser = {connected: options.connected ?? true, stopped: false, isConnected() { return this.connected; },
                onStop() { this.stopped = true; }, reConnect() { calls.push(['reConnect']); }};
            const control = {value, listeners: [], browser, addQinMediaListener(l) { this.listeners.push(l); },
                getMediaBrowserHelper: () => browser};
            controls.push(control);
            return control;
        }},
        'com.qinggan.app.mediaCentre.inter.QinMediaListener': {},
        'com.qinggan.media.helper.AudioPolicyHelper': {
            isMediaFocus: {overload: (...types) => (types.length === 2 ? hook('focusWith', () => 'oem') : hook('focusLatest', () => 'oem'))},
            getInstance: () => ({getLatestMediaInfo: () => latest.info})
        },
        'com.pateo.voyah.mediaCard.home.h97cV2.BigMediaView97cV2': {
            initMedia: {overload: () => hook('initMedia', self => factoryInit(self))},
            updateTitleUI: {overload: () => hook('titleUi')},
            openMediaPage: {overload: () => hook('openPage')},
            onItemClick: {overload: () => hook('itemClick')}
        },
        'com.pateo.voyah.mediaCard.home.h97c.MediaInfoHelper97C': {updateCoverUrl: {overload: () => hook('cover')}},
        'com.pateo.voyah.mediaCard.home.view.mediaTab97c.MediaTabAdapter97c$MediaTabHolder': {bindView: {overload: () => hook('bindTab')}},
        'com.pateo.voyah.mediaCard.home.inter.MediaBeanInter': {},
        'com.pateo.voyah.mediaCard.home.enums.MediaResEnum97C': tabs,
        'java.util.ArrayList': {$new: (list) => ({items: list ? [...list.items] : []}),
            add: {overload: () => ({call(list, x) { list.items.push(x); }})}},
        'java.util.LinkedList': {remove: {overload: () => ({call(list, x) { list.remove(x); }})}},
        'java.lang.ref.WeakReference': {$new: referent => ({get: () => referent, $dispose() {}})},
        'android.app.ActivityThread': {currentApplication: () => application},
        'android.content.ComponentName': {$new: (pkg, cls) => ({pkg, cls})},
        'android.widget.TextView': {setText: {overload: () => ({call(target, text) { target.text = text; }})}},
        'java.lang.String': {$new: value => value}
    };
    const Java = {
        use(name) {
            if (options.missing === name) throw new Error('java.lang.ClassNotFoundException: ' + name);
            assert.ok(classes[name], name);
            return classes[name];
        },
        cast: value => value, retain: value => value, perform: fn => fn(), scheduleOnMainThread: fn => fn(),
        choose(name, callbacks) { if (options.cards !== 0) callbacks.onMatch(card); callbacks.onComplete(); }
    };
    // The card as the launcher built it before the agent attached.
    factoryInit(card);
    let stale = null;
    if (options.leftover) {
        // As an agent that vanished without restoring left the launcher: a borrowed slot, entry, control and tab.
        const dab = E('DAB');
        Object.assign(dab, {servicePageName: {value: RADIO.package}, serviceName: {value: RADIO.service}, mediaId: {value: 9002},
            clientId: {value: RADIO.package + '.'}});
        T('KW_MUSIC').mediaEnum.value = dab;
        stale = {value: dab, browser: {stopped: false, onStop() { this.stopped = true; }}, getMediaBrowserHelper() { return this.browser; }};
        manager.controlMap.value.put(dab, stale);
        settings.mediaEnums.value.add(dab);
        helper.supportSet.value.add(dab);
        tabView.adapter.data.value.items.push(T('KW_MUSIC'));
    }
    const launched = [];
    const {api, logs} = load({setInterval: (fn, ms) => { timers.push({fn, ms}); return timers.length; }});
    const result = api.installHomeMediaCard(Java, {readConfig: () => state.config,
        launch: (ctx, component) => { launched.push([ctx.name, component]); return true; }});
    const tick = () => timers[0].fn();
    const shownTabs = () => tabView.adapter.data.value.items.map(t => t.name);
    return {api, result, logs, calls, hooks, state, card, helper, manager, settings, controls, media: E, tab: T, binding,
        tick, shownTabs, launched, latest, tabView, factory, stale};
}

test('the card offers both apps after its factory sources, each through a borrowed slot and free tab entry', () => {
    const h = harness();
    assert.deepEqual(h.logs, ['OVC_HOME_MEDIA_READY']);
    assert.deepEqual(h.shownTabs(), ['tab:WE_CAR', 'tab:BT_MUSIC', 'tab:USB_MUSIC', 'tab:XMLY', 'tab:ONLINE_RADIO',
        'tab:LOCAL_RADIO', 'tab:KW_MUSIC']);
    const music = h.media('SYSTEM_BT_MUSIC'), radio = h.media('DAB');
    assert.deepEqual([music.mediaId.value, music.serviceName.value, music.servicePageName.value, music.clientId.value, music.streamType.value],
        [9001, MUSIC.service, MUSIC.package, MUSIC.package + '.', 3]);
    assert.deepEqual([radio.mediaId.value, radio.serviceName.value, radio.servicePageName.value, radio.streamType.value],
        [9002, RADIO.service, RADIO.package, 3]);
    assert.equal(h.tab('LOCAL_RADIO').mediaEnum.value, music);
    assert.equal(h.tab('KW_MUSIC').mediaEnum.value, radio);
    for (const value of [music, radio]) {
        assert.ok(h.settings.mediaEnums.value.contains(value));
        assert.ok(h.settings.controlEnums.value.contains(value));
        assert.ok(h.helper.supportSet.value.contains(value));
    }
    // Each app is connected as MediaManager connects the factory sources.
    assert.deepEqual(h.controls.map(control => control.value.name), ['SYSTEM_BT_MUSIC', 'DAB']);
    assert.ok(h.controls.every(control => control.listeners[0] === h.manager));
    assert.equal(h.manager.controlMap.value.get(radio), h.controls[1]);
    // Polling the unchanged setting changes nothing.
    h.tabView.filled.length = 0;
    h.tick();
    assert.deepEqual(h.tabView.filled, []);
});

test('tabs and the title show the app name and icon, and the cover shows the app icon', () => {
    const h = harness();
    const tvName = {}, ivIcon = {setImageDrawable(d) { this.drawable = d; }};
    const holder = {mediaBean: {value: h.tab('KW_MUSIC')}, tvName: {value: tvName}, ivIcon: {value: ivIcon}};
    h.hooks.bindTab.implementation.call(holder, 6);
    assert.equal(tvName.text, 'Voyah Radio ru-RU');
    assert.deepEqual({...ivIcon.drawable}, {icon: RADIO.package});
    h.hooks.titleUi.implementation.call(h.card, h.tab('LOCAL_RADIO'));
    assert.equal(h.binding.tvMediaName.value.text, 'Voyah Music ru-RU');
    // A language change gives the app's name in the new language.
    h.state.locale = 'en-US';
    h.hooks.titleUi.implementation.call(h.card, h.tab('LOCAL_RADIO'));
    assert.equal(h.binding.tvMediaName.value.text, 'Voyah Music en-US');
    // Factory tabs stay as the launcher drew them.
    const factoryHolder = {mediaBean: {value: h.tab('BT_MUSIC')}, tvName: {value: {}}, ivIcon: {value: ivIcon}};
    h.hooks.bindTab.implementation.call(factoryHolder, 1);
    assert.equal(factoryHolder.tvName.value.text, undefined);
    const cover = {setImageDrawable(d) { this.drawable = d; }};
    h.calls.length = 0;
    h.hooks.cover.implementation.call(h.helper, cover, 'url', h.media('DAB'));
    assert.deepEqual({...cover.drawable}, {icon: RADIO.package});
    assert.deepEqual(h.calls, []);
    h.hooks.cover.implementation.call(h.helper, cover, 'url', h.media('USB_MUSIC'));
    assert.equal(h.calls.at(-1)[0], 'cover');
});

test('the latest media focus of an app selects its slot; other sources keep the factory check', () => {
    const h = harness();
    const info = (pkg, stream = 3) => ({getStreamType: () => stream, getPackageName: () => pkg, getClientId: () => 'x'});
    assert.equal(h.hooks.focusWith.implementation.call({}, h.media('DAB'), info(RADIO.package)), true);
    assert.equal(h.hooks.focusWith.implementation.call({}, h.media('DAB'), info(MUSIC.package)), false);
    assert.equal(h.hooks.focusWith.implementation.call({}, h.media('SYSTEM_BT_MUSIC'), null), false);
    assert.equal(h.hooks.focusWith.implementation.call({}, h.media('USB_MUSIC'), info(RADIO.package)), 'oem');
    h.latest.info = info(MUSIC.package);
    assert.equal(h.hooks.focusLatest.implementation.call({}, h.media('SYSTEM_BT_MUSIC')), true);
    assert.equal(h.hooks.focusLatest.implementation.call({}, h.media('DAB')), false);
    assert.equal(h.hooks.focusLatest.implementation.call({}, h.media('BT_MUSIC')), 'oem');
});

test('a tap on the card opens the playing app, and a tab of an app not connected opens the app', () => {
    const h = harness({connected: false});
    h.helper.cur = h.media('DAB');
    h.calls.length = 0;
    h.hooks.openPage.implementation.call(h.card);
    assert.deepEqual(h.launched, [['card-context', RADIO.package + '/.Main']]);
    assert.deepEqual(h.calls, []);
    h.helper.cur = h.media('USB_MUSIC');
    h.hooks.openPage.implementation.call(h.card);
    assert.deepEqual(h.calls.map(call => call[0]), ['openPage']);
    h.calls.length = 0;
    h.hooks.itemClick.implementation.call(h.card, h.tab('LOCAL_RADIO'));
    assert.deepEqual(h.calls, [['reConnect']]);
    assert.equal(h.binding.flRcy.value.visibility, 8);
    assert.deepEqual(h.launched.at(-1), ['card-context', MUSIC.package + '/.Main']);
    // A factory tab plays as the launcher does.
    h.hooks.itemClick.implementation.call(h.card, h.tab('XMLY'));
    assert.deepEqual(h.calls.at(-1), ['itemClick', h.tab('XMLY')]);
});

test('a connected app tab plays through the launcher itself', () => {
    const h = harness();
    h.calls.length = 0;
    h.hooks.itemClick.implementation.call(h.card, h.tab('KW_MUSIC'));
    assert.deepEqual(h.calls, [['itemClick', h.tab('KW_MUSIC')]]);
    assert.deepEqual(h.launched, []);
});

test('off puts the factory slots, entries, sources and tabs back', () => {
    const h = harness();
    h.helper.cur = h.media('SYSTEM_BT_MUSIC');
    h.manager.curMediaEnum.value = h.media('SYSTEM_BT_MUSIC');
    h.state.config = {schema: 1};
    h.calls.length = 0;
    h.tick();
    for (const name of ['SYSTEM_BT_MUSIC', 'DAB']) {
        const value = h.media(name);
        for (const [field, factory] of Object.entries(h.factory[name])) assert.equal(value[field].value, factory, name + '.' + field);
        assert.equal(h.settings.mediaEnums.value.contains(value), false);
        assert.equal(h.settings.controlEnums.value.contains(value), false);
        assert.equal(h.helper.supportSet.value.contains(value), false);
        assert.equal(h.manager.controlMap.value.get(value), null);
    }
    assert.equal(h.tab('LOCAL_RADIO').mediaEnum.value, h.media('LOCAL_RADIO'));
    assert.equal(h.tab('KW_MUSIC').mediaEnum.value, h.media('KW_MUSIC'));
    assert.ok(h.controls.every(control => control.browser.stopped));
    assert.deepEqual(h.shownTabs(), ['tab:WE_CAR', 'tab:BT_MUSIC', 'tab:USB_MUSIC', 'tab:XMLY', 'tab:ONLINE_RADIO']);
    // The card showed an app: it goes back to the last factory source, as after a start without media.
    assert.equal(h.manager.curMediaEnum.value, h.media('NO'));
    assert.deepEqual(h.calls.filter(call => call[0] !== 'initMedia'), [['clearMediaInfo'], ['onNoMediaEnum']]);
    // The hooks are inert now.
    assert.equal(h.hooks.focusWith.implementation.call({}, h.media('DAB'), null), 'oem');
});

test('the restore handshake restores; without the setting nothing is touched', () => {
    const h = harness();
    assert.equal(h.result.restoreAll(), true);
    assert.equal(h.media('DAB').servicePageName.value, 'com.qinggan.media');
    assert.deepEqual(h.shownTabs(), ['tab:WE_CAR', 'tab:BT_MUSIC', 'tab:USB_MUSIC', 'tab:XMLY', 'tab:ONLINE_RADIO']);
    const off = harness({config: {schema: 1, enabled: true}});
    assert.deepEqual(off.controls, []);
    assert.equal(off.shownTabs().length, 5);
    assert.equal(off.result.restoreAll(), true);
});

test('an app that is not installed gets no tab; it appears once installed', () => {
    const h = harness({installed: [RADIO.package]});
    assert.deepEqual(h.shownTabs().slice(5), ['tab:LOCAL_RADIO']);
    assert.equal(h.tab('LOCAL_RADIO').mediaEnum.value, h.media('SYSTEM_BT_MUSIC'));
    assert.equal(h.media('SYSTEM_BT_MUSIC').servicePageName.value, RADIO.package);
    h.state.installed.add(MUSIC.package);
    h.tick();
    assert.deepEqual(h.shownTabs().slice(5), ['tab:LOCAL_RADIO', 'tab:KW_MUSIC']);
    assert.equal(h.media('SYSTEM_BT_MUSIC').servicePageName.value, MUSIC.package);
});

test('entries of sources the card supports are never borrowed', () => {
    // A car with Kuwo and an FM antenna: only the WeCar Flow entry is free.
    const h = harness({supported: ['BT_MUSIC', 'XMLA_MUSIC', 'USB_MUSIC', 'KW_MUSIC', 'LOCAL_RADIO', 'RADIO_YUNTING'],
        factoryTabs: ['KW_MUSIC', 'BT_MUSIC', 'USB_MUSIC', 'XMLY', 'ONLINE_RADIO']});
    assert.deepEqual(h.shownTabs(), ['tab:KW_MUSIC', 'tab:BT_MUSIC', 'tab:USB_MUSIC', 'tab:XMLY', 'tab:ONLINE_RADIO', 'tab:WE_CAR']);
    assert.equal(h.tab('KW_MUSIC').mediaEnum.value, h.media('KW_MUSIC'));
    assert.deepEqual(h.controls.map(control => control.value.name), ['SYSTEM_BT_MUSIC']);
});

test('a card built later gets the tabs, and takes an app that already plays', () => {
    const h = harness();
    h.manager.curMediaEnum.value = h.media('DAB');
    const helper = {...h.helper, supportSet: {value: javaSet()}, cur: h.media('NO')};
    const tabView = {adapter: {data: {value: javaList()}}, fillData(list) { this.adapter.data.value = javaList(list.items); }};
    tabView.mediaTabAdapter = {value: tabView.adapter};
    const card = {...h.card, binding: {value: {...h.binding, mediaTab: {value: tabView}}}, mediaInfoHelper: {value: helper},
        equals(other) { return other === this; }};
    h.calls.length = 0;
    h.hooks.initMedia.implementation.call(card);
    assert.deepEqual(tabView.adapter.data.value.items.slice(5).map(t => t.name), ['tab:LOCAL_RADIO', 'tab:KW_MUSIC']);
    assert.ok(helper.supportSet.value.contains(h.media('DAB')));
    assert.deepEqual(h.calls.filter(call => call[0] === 'updateMediaEnum'), [['updateMediaEnum', 'DAB']]);
});

test('slots a vanished agent borrowed go back before the new agent borrows them', () => {
    const off = harness({config: {schema: 1}, leftover: true});
    assert.deepEqual(off.logs, ['OVC_HOME_MEDIA_READY']);
    for (const [field, factory] of Object.entries(off.factory.DAB)) assert.equal(off.media('DAB')[field].value, factory, field);
    assert.equal(off.tab('KW_MUSIC').mediaEnum.value, off.media('KW_MUSIC'));
    assert.equal(off.stale.browser.stopped, true);
    assert.equal(off.manager.controlMap.value.get(off.media('DAB')), null);
    assert.equal(off.helper.supportSet.value.contains(off.media('DAB')), false);
    assert.equal(off.settings.mediaEnums.value.contains(off.media('DAB')), false);
    assert.deepEqual(off.shownTabs(), ['tab:WE_CAR', 'tab:BT_MUSIC', 'tab:USB_MUSIC', 'tab:XMLY', 'tab:ONLINE_RADIO']);
    // With the setting on, the new agent borrows the slots afresh: one tab per app, not a copy of the stale one.
    const on = harness({leftover: true});
    assert.deepEqual(on.shownTabs().slice(5), ['tab:LOCAL_RADIO', 'tab:KW_MUSIC']);
    assert.equal(on.media('DAB').servicePageName.value, RADIO.package);
    assert.equal(on.manager.controlMap.value.get(on.media('DAB')), on.controls[1]);
});

test('without a card yet, the apps are added once one appears', () => {
    const h = harness({cards: 0});
    assert.deepEqual(h.controls, []);
    h.hooks.initMedia.implementation.call(h.card);
    assert.deepEqual(h.controls, []);
    h.tick();
    assert.deepEqual(h.controls.map(control => control.value.name), ['SYSTEM_BT_MUSIC', 'DAB']);
    assert.deepEqual(h.shownTabs().slice(5), ['tab:LOCAL_RADIO', 'tab:KW_MUSIC']);
});

test('a launcher without the media card reports it and installs nothing', () => {
    const h = harness({missing: 'com.pateo.voyah.mediaCard.home.h97cV2.BigMediaView97cV2'});
    assert.equal(h.result, null);
    assert.deepEqual(h.logs, ['OVC_HOME_MEDIA_ERROR stage=install kind=ClassNotFoundException']);
});

test('dispose unhooks and puts the factory card back', () => {
    const h = harness();
    h.result.dispose();
    for (const name of ['initMedia', 'titleUi', 'openPage', 'itemClick', 'bindTab', 'cover', 'focusWith', 'focusLatest']) {
        assert.equal(h.hooks[name].implementation, null, name);
    }
    assert.equal(h.media('DAB').servicePageName.value, 'com.qinggan.media');
    assert.deepEqual(h.shownTabs().length, 5);
});
