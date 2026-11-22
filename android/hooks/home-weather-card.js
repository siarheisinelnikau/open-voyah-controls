import { createReporter } from './report.js';

// Codes of the launcher's weather icons (`WeatherIconUtils.getMediumIcon`, from its packed switch): clear, cloudy,
// overcast, thunderstorm, hail, rain, snow, fog, dust. A non-numeric code shows the "unknown" icon and keeps the text.
const ICON_CODES = new Set(['0', '2', '3', '5', '6', '7', '14', '19', '21']);
const UNKNOWN = 'na';
// Air quality levels the card understands (`WeatherIconUtils.getAqiIcon`, `WeatherUtils.getAqiLevelText`).
const AQI_LEVELS = new Set(['优', '良', '轻度污染', '中度污染', '重度污染', '严重污染']);
const TEXT = /^[^\u0000-\u001f]{1,40}$/;
const TEMPERATURE = /^-?\d{1,3}$/;
/** Weather older than this is not shown; the card says there is no data instead. */
const MAX_AGE_MS = 3 * 60 * 60 * 1000;
const REPOST_MS = 5 * 60 * 1000;

/** The card's "no data" text when the setting is on, else null. A restore request always means off. */
export function weatherSetting(config) {
    if (!config || config.schema !== 1 || config.restoreIcons !== undefined) return null;
    const noData = config.weatherCard?.noData;
    return typeof noData === 'string' && TEXT.test(noData) ? {noData} : null;
}

/**
 * What the card shows for the weather Controls published: the reading while it is valid and fresh, else the
 * "no data" state. Never stale data.
 */
export function weatherModel(payload, setting, now) {
    const none = {code: UNKNOWN, text: setting.noData, temperature: '_ _', aqi: null, fresh: false};
    if (!payload || payload.schema !== 1) return none;
    const {code, text, temperature, aqi, fetchedAt} = payload;
    if (!Number.isFinite(fetchedAt) || fetchedAt > now + 60000 || now - fetchedAt > MAX_AGE_MS) return none;
    if (!ICON_CODES.has(code) || typeof text !== 'string' || !TEXT.test(text)
        || typeof temperature !== 'string' || !TEMPERATURE.test(temperature)) return none;
    return {code, text, temperature, aqi: AQI_LEVELS.has(aqi) ? aqi : null, fresh: true};
}

/**
 * Feeds the launcher's weather card (`HomeWeatherCardView`) with the weather Controls fetched, in the card's own
 * model, so it keeps its look: condition icon, condition text, temperature and, when known, the air quality.
 * The factory weather service works only in China; while the setting is on, every update it sends to the card
 * (data or error) is replaced by Controls' weather. Off, or before Controls detaches, the card goes back to its
 * factory empty state and the factory updates pass through again.
 */
export function installHomeWeatherCard(Java, {readConfig, readWeather, report = createReporter('OVC_HOME_WEATHER_ERROR')} = {}) {
    const hooks = [];
    const cards = [];
    let timer = null, disposed = false, model = null, shownKey = '', postedAt = 0;
    try {
        const Card = Java.use('com.qinggan.app.launcher.homecard.HomeWeatherCardView');
        const Bean = Java.use('com.qinggan.tsp.models.cpsp.WeatherLiveBean');
        const Data = Java.use('com.qinggan.tsp.models.cpsp.WeatherLiveBean$DataBean');
        const Live = Java.use('com.qinggan.tsp.models.cpsp.WeatherLiveBean$DataBean$LiveBean');
        const Air = Java.use('com.qinggan.tsp.models.cpsp.WeatherLiveBean$DataBean$WeatherAirQualityBean');
        const Reference = Java.use('java.lang.ref.WeakReference');
        const ActivityThread = Java.use('android.app.ActivityThread');
        const View = Java.use('android.view.View');
        const update = Card.updateWeather.overload('com.qinggan.tsp.models.cpsp.WeatherLiveBean');
        const redraw = Card.updateWeather.overload();
        const attached = Card.onAttachedToWindow.overload();

        function bean(current) {
            const live = Live.$new();
            live.setWeather(current.code);
            live.setWeather_CN(current.text);
            live.setWeather_EN(current.text);
            live.setTemperature(current.temperature);
            // The card always reads the air quality; an unknown level is hidden again below.
            const air = Air.$new();
            air.setAqi(current.aqi ?? '优');
            const data = Data.$new();
            data.setLive(live);
            data.setWeatherAirQuality(air);
            const result = Bean.$new();
            result.setStatusCode('0');
            result.setData(data);
            return result;
        }

        function airVisible(card, visible) {
            // Its own failure leaves the weather on the card; only the air quality level may stay as it was.
            try {
                const context = card.getContext();
                const resources = context.getResources();
                for (const name of ['aqi_view', 'aqi_value']) {
                    const id = resources.getIdentifier(name, 'id', context.getPackageName());
                    // findViewById is generic (<T extends View>); the bridge returns it as java.lang.Object.
                    const found = id === 0 ? null : card.findViewById(id);
                    if (found !== null) Java.cast(found, View).setVisibility(visible ? 0 : 8);
                }
            } catch (error) { report('air', error); }
        }

        function show(card) {
            update.call(card, bean(model));
            airVisible(card, model.aqi !== null);
        }

        function factory(card) {
            // The factory card's own empty state: unknown icon, "- -" and "_ _", no air quality.
            card.mWeatherLiveBean.value = null;
            redraw.call(card);
            airVisible(card, false);
        }

        function live() {
            for (let i = cards.length - 1; i >= 0; i--) {
                if (cards[i].get() !== null) continue;
                cards[i].$dispose();
                cards.splice(i, 1);
            }
            return cards.map(ref => Java.cast(ref.get(), Card));
        }

        function track(card) {
            if (cards.some(ref => card.equals(ref.get()))) return;
            cards.push(Java.retain(Reference.$new(card)));
            if (model !== null) show(card);
        }

        update.implementation = function (factoryBean) {
            if (model === null) return update.call(this, factoryBean);
            try {
                show(this);
                return;
            } catch (error) { report('update', error); }
            return update.call(this, factoryBean);
        };
        hooks.push(update);
        attached.implementation = function () {
            const result = attached.call(this);
            try { track(this); } catch (error) { report('track', error); }
            return result;
        };
        hooks.push(attached);

        function apply(now) {
            const app = ActivityThread.currentApplication();
            if (app === null) return;
            const next = weatherSetting(readConfig(app));
            const nextModel = next === null ? null : weatherModel(readWeather(app), next, now);
            const key = JSON.stringify(nextModel);
            if (key === shownKey && (nextModel === null || now - postedAt < REPOST_MS)) return;
            const wasOn = model !== null;
            model = nextModel;
            shownKey = key;
            postedAt = now;
            for (const card of live()) {
                try {
                    if (model !== null) show(card);
                    else if (wasOn) factory(card);
                } catch (error) { report(model !== null ? 'show' : 'restore', error); }
            }
        }

        timer = setInterval(() => Java.perform(() => {
            if (disposed) return;
            Java.scheduleOnMainThread(() => {
                try { if (!disposed) apply(Date.now()); }
                catch (error) { report('refresh', error); }
            });
        }), 1000);

        Java.choose('com.qinggan.app.launcher.homecard.HomeWeatherCardView', {
            onMatch(card) {
                const kept = Java.retain(card);
                Java.scheduleOnMainThread(() => {
                    try { track(kept); } catch (error) { report('track', error); }
                    finally { kept.$dispose(); }
                });
            },
            onComplete() {
                Java.scheduleOnMainThread(() => {
                    try {
                        if (disposed) return;
                        apply(Date.now());
                        console.log('OVC_HOME_WEATHER_READY');
                    } catch (error) { report('install', error); }
                });
            }
        });

        return {
            /** UI thread only. Part of the panel's restore handshake before Controls detaches. */
            restoreAll() {
                const wasOn = model !== null;
                model = null;
                shownKey = '';
                let restored = true;
                if (!wasOn) return true;
                for (const card of live()) {
                    try { factory(card); }
                    catch (error) { report('restore', error); restored = false; }
                }
                return restored;
            },
            dispose() {
                disposed = true;
                clearInterval(timer);
                for (const hook of hooks) {
                    try { hook.implementation = null; } catch (error) { report('rollback', error); }
                }
                Java.scheduleOnMainThread(() => {
                    if (model === null) return;
                    model = null;
                    for (const card of live()) {
                        try { factory(card); } catch (error) { report('rollback', error); }
                    }
                });
            }
        };
    } catch (error) {
        report('install', error);
        if (timer !== null) clearInterval(timer);
        for (const hook of hooks) {
            try { hook.implementation = null; } catch (resetError) { report('rollback', resetError); }
        }
        return null;
    }
}
