const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = ['report.js', 'home-weather-card.js'].map(file => fs.readFileSync(path.join(__dirname, '../hooks', file), 'utf8')
    .replace(/^import .*;$/gm, '').replace(/^export /gm, '')).join('\n');

function load(extra = {}) {
    const logs = [];
    const context = {console: {log: line => logs.push(line)}, setInterval: () => 1, clearInterval() {}, Date, ...extra};
    const api = vm.runInNewContext(source + '\n({weatherSetting, weatherModel, installHomeWeatherCard, MAX_AGE_MS})', context);
    return {api, logs};
}

const ON = {schema: 1, weatherCard: {noData: 'Нет данных'}};
const NOW = 1_700_000_000_000;
const reading = (fields = {}) => ({schema: 1, code: '2', text: 'Облачно', temperature: '-3', aqi: '良', fetchedAt: NOW - 60000, ...fields});

test('the weather setting needs its no-data text and is off during a restore request', () => {
    const {weatherSetting} = load().api;
    assert.deepEqual({...weatherSetting(ON)}, {noData: 'Нет данных'});
    assert.equal(weatherSetting({...ON, restoreIcons: 'a'.repeat(32)}), null);
    assert.equal(weatherSetting({schema: 1}), null);
    assert.equal(weatherSetting({schema: 1, weatherCard: {noData: ''}}), null);
    assert.equal(weatherSetting({schema: 1, weatherCard: {noData: 'x'.repeat(41)}}), null);
    assert.equal(weatherSetting(null), null);
});

test('only a fresh, valid reading is shown; anything else is no data, never stale weather', () => {
    const {weatherModel, MAX_AGE_MS} = load().api;
    const setting = {noData: 'Нет данных'};
    const none = {code: 'na', text: 'Нет данных', temperature: '_ _', aqi: null, fresh: false};
    assert.deepEqual({...weatherModel(reading(), setting, NOW)},
        {code: '2', text: 'Облачно', temperature: '-3', aqi: '良', fresh: true});
    assert.equal(weatherModel(reading({aqi: 'bad'}), setting, NOW).aqi, null);
    assert.equal(weatherModel(reading({aqi: null}), setting, NOW).fresh, true);
    for (const payload of [null, {}, reading({schema: 2}), reading({fetchedAt: NOW - MAX_AGE_MS - 1}),
        reading({fetchedAt: NOW + 120000}), reading({fetchedAt: 'x'}), reading({code: '4'}), reading({code: 2}),
        reading({text: ''}), reading({text: 'a\nb'}), reading({temperature: '12.5'}), reading({temperature: '1000'})]) {
        assert.deepEqual({...weatherModel(payload, setting, NOW)}, none, JSON.stringify(payload));
    }
});

function harness(options = {}) {
    const timers = [];
    const calls = [];
    const state = {config: options.config ?? ON, weather: options.weather ?? reading({fetchedAt: Date.now() - 60000})};
    const hooks = {};
    const hook = name => (hooks[name] = {implementation: null, call(self, ...args) { calls.push([name, ...args]); return undefined; }});
    const visibility = {};
    const ids = {aqi_view: 11, aqi_value: 12, weather_date: 13, weather_date_formatter: 21};
    // The factory layout: the air quality 134 px from the top, the long weekday in the date.
    const layout = {11: {topMargin: {value: 134}}, 12: {topMargin: {value: 134}}};
    const dateFormat = {f12: 'EEEE, MMM d', f24: 'EEEE, MMM d'};
    const relayouts = [];
    const card = {
        mWeatherLiveBean: {value: 'factory'},
        equals(other) { return other === this; }, $dispose() {},
        getContext: () => ({getPackageName: () => 'com.qinggan.app.launcher',
            getResources: () => ({getIdentifier: name => ids[name] ?? 0,
                getString: id => (id === 21 ? 'EEEE, MMM d' : assert.fail('string ' + id)),
                getConfiguration: () => ({getLocales: () => ({get: () => 'ru_RU'})})})}),
        // As the bridge returns it: a java.lang.Object without View methods until it is cast.
        findViewById: id => ({object: true, id})
    };
    const bean = kind => () => {
        const object = {kind};
        return new Proxy(object, {get(target, key) {
            if (key in target) return target[key];
            if (typeof key === 'string' && key.startsWith('set')) return value => { target[key.slice(3)] = value; };
            return undefined;
        }});
    };
    const classes = {
        'com.qinggan.app.launcher.homecard.HomeWeatherCardView': {
            updateWeather: {overload: (...types) => types.length ? (hooks.update ?? hook('update')) : (hooks.redraw ?? hook('redraw'))},
            onAttachedToWindow: {overload: () => hook('attached')},
            onLocaleChanged: {overload: () => hook('locale')}
        },
        'android.view.ViewGroup$MarginLayoutParams': {isMargin: true},
        'android.widget.TextClock': {isClock: true},
        'android.text.format.DateFormat': {getBestDateTimePattern: (locale, skeleton) =>
            (locale === 'ru_RU' && skeleton === 'EEEdMMM' ? 'ccc, d MMM' : assert.fail(locale + ' ' + skeleton))},
        'java.lang.String': {$new: value => value},
        'com.qinggan.tsp.models.cpsp.WeatherLiveBean': {$new: bean('bean')},
        'com.qinggan.tsp.models.cpsp.WeatherLiveBean$DataBean': {$new: bean('data')},
        'com.qinggan.tsp.models.cpsp.WeatherLiveBean$DataBean$LiveBean': {$new: bean('live')},
        'com.qinggan.tsp.models.cpsp.WeatherLiveBean$DataBean$WeatherAirQualityBean': {$new: bean('air')},
        'java.lang.ref.WeakReference': {$new: referent => ({get: () => referent, $dispose() {}})},
        'android.view.View': {isView: true},
        'android.app.ActivityThread': {currentApplication: () => ({})}
    };
    const Java = {
        use(name) {
            if (options.missing === name) throw new Error('java.lang.ClassNotFoundException: ' + name);
            assert.ok(classes[name], name);
            return classes[name];
        },
        cast(value, type) {
            if (type?.isView && value?.object) return {id: value.id,
                setVisibility(visible) { visibility[value.id] = visible; },
                getLayoutParams: () => layout[value.id],
                setLayoutParams(params) { relayouts.push(value.id); layout[value.id] = params; }};
            if (type?.isMargin) return value;
            if (type?.isClock) return {setFormat12Hour(text) { dateFormat.f12 = text; }, setFormat24Hour(text) { dateFormat.f24 = text; }};
            return value;
        },
        retain: value => value, perform: fn => fn(), scheduleOnMainThread: fn => fn(),
        choose(name, callbacks) { callbacks.onMatch(card); callbacks.onComplete(); }
    };
    const {api, logs} = load({setInterval: (fn, ms) => { timers.push({fn, ms}); return timers.length; }});
    const result = api.installHomeWeatherCard(Java, {readConfig: () => state.config, readWeather: () => state.weather});
    const shown = () => {
        const last = calls.filter(call => call[0] === 'update').at(-1)?.[1];
        if (!last) return null;
        const live = last.Data.Live;
        return {status: last.StatusCode, code: live.Weather, text: live.Weather_EN, cn: live.Weather_CN,
            temperature: live.Temperature, aqi: last.Data.WeatherAirQuality.Aqi};
    };
    const tick = () => timers[0].fn();
    const fitted = () => ({aqiTop: [layout[11].topMargin.value, layout[12].topMargin.value], date: {...dateFormat}});
    return {result, logs, calls, card, hooks, state, shown, tick, visibility, fitted, relayouts};
}

test('the card shows Controls weather in its own model as soon as the agent attaches', () => {
    const h = harness();
    assert.deepEqual(h.logs, ['OVC_HOME_WEATHER_READY']);
    assert.deepEqual(h.shown(), {status: '0', code: '2', text: 'Облачно', cn: 'Облачно', temperature: '-3', aqi: '良'});
    assert.deepEqual(h.visibility, {11: 0, 12: 0});
});

test('updates from the factory weather service are replaced while the setting is on', () => {
    const h = harness({weather: reading({fetchedAt: Date.now(), aqi: null})});
    h.calls.length = 0;
    h.hooks.update.implementation.call(h.card, null);
    assert.equal(h.calls.length, 1);
    assert.equal(h.shown().code, '2');
    // An unknown air quality still gives the card a valid level, and the level stays hidden.
    assert.equal(h.shown().aqi, '优');
    assert.deepEqual(h.visibility, {11: 8, 12: 8});
});

test('stale weather turns into no data, and off brings back the factory empty state', () => {
    const h = harness();
    h.state.weather = reading({fetchedAt: Date.now() - 4 * 60 * 60 * 1000});
    h.tick();
    assert.deepEqual(h.shown(), {status: '0', code: 'na', text: 'Нет данных', cn: 'Нет данных', temperature: '_ _', aqi: '优'});
    h.calls.length = 0;
    h.state.config = {schema: 1};
    h.tick();
    assert.equal(h.card.mWeatherLiveBean.value, null);
    assert.deepEqual(h.calls, [['redraw']]);
    // Factory updates pass through again.
    h.hooks.update.implementation.call(h.card, 'oem');
    assert.deepEqual(h.calls.at(-1), ['update', 'oem']);
});

test('without the setting the card is never touched', () => {
    const h = harness({config: {schema: 1, enabled: true}});
    assert.deepEqual(h.calls, []);
    h.hooks.update.implementation.call(h.card, 'oem');
    assert.deepEqual(h.calls, [['update', 'oem']]);
    assert.equal(h.result.restoreAll(), true);
    assert.deepEqual(h.calls, [['update', 'oem']]);
});

test('the restore handshake puts the factory state back and dispose unhooks', () => {
    const h = harness();
    h.calls.length = 0;
    assert.equal(h.result.restoreAll(), true);
    assert.deepEqual(h.calls, [['redraw']]);
    h.result.dispose();
    assert.equal(h.hooks.update.implementation, null);
    assert.equal(h.hooks.attached.implementation, null);
});

test('a launcher without the weather card reports it and installs nothing', () => {
    const h = harness({missing: 'com.qinggan.app.launcher.homecard.HomeWeatherCardView'});
    assert.equal(h.result, null);
    assert.deepEqual(h.logs, ['OVC_HOME_WEATHER_ERROR stage=install kind=ClassNotFoundException']);
});

test('a failure around the air quality never undoes the weather or calls the factory update again', () => {
    const h = harness();
    h.card.getContext = () => { throw new TypeError('context'); };
    h.calls.length = 0;
    h.logs.length = 0;
    h.hooks.update.implementation.call(h.card, 'oem');
    assert.deepEqual(h.calls.map(call => call[0]), ['update']);
    assert.equal(h.shown().code, '2');
    assert.deepEqual(h.logs, ['OVC_HOME_WEATHER_ERROR stage=air kind=TypeError', 'OVC_HOME_WEATHER_ERROR stage=fit kind=TypeError']);
});

test('while Controls feeds the card, the air quality moves under the clock and the date takes the short weekday', () => {
    const h = harness();
    assert.deepEqual(h.fitted(), {aqiTop: [160, 160], date: {f12: 'ccc, d MMM', f24: 'ccc, d MMM'}});
    // A re-send does not lay the card out again.
    h.relayouts.length = 0;
    h.hooks.update.implementation.call(h.card, 'oem');
    assert.deepEqual(h.relayouts, []);
});

test('a language change keeps the short date while the setting is on', () => {
    const h = harness();
    h.hooks.locale.implementation.call(h.card);
    assert.equal(h.calls.at(-1)[0], 'locale');
    assert.deepEqual(h.fitted().date, {f12: 'ccc, d MMM', f24: 'ccc, d MMM'});
});

test('off and the restore handshake put the factory layout and date pattern back', () => {
    for (const leave of [h => { h.state.config = {schema: 1}; h.tick(); }, h => h.result.restoreAll()]) {
        const h = harness();
        leave(h);
        assert.deepEqual(h.fitted(), {aqiTop: [134, 134], date: {f12: 'EEEE, MMM d', f24: 'EEEE, MMM d'}});
    }
});

test('without the setting the card layout is never touched', () => {
    const h = harness({config: {schema: 1, enabled: true}});
    h.hooks.locale.implementation.call(h.card);
    assert.deepEqual(h.fitted(), {aqiTop: [134, 134], date: {f12: 'EEEE, MMM d', f24: 'EEEE, MMM d'}});
    assert.deepEqual(h.relayouts, []);
});

test('a failure while fitting the layout keeps the weather and is reported', () => {
    const h = harness();
    h.card.findViewById = () => { throw new TypeError('find'); };
    h.calls.length = 0;
    h.logs.length = 0;
    h.hooks.update.implementation.call(h.card, 'oem');
    assert.deepEqual(h.calls.map(call => call[0]), ['update']);
    assert.equal(h.shown().code, '2');
    assert.deepEqual(h.logs, ['OVC_HOME_WEATHER_ERROR stage=air kind=TypeError', 'OVC_HOME_WEATHER_ERROR stage=fit kind=TypeError']);
});
