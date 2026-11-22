import { installHomeNavigationCard } from './home-navi-card.js';
import { installHomeWeatherCard } from './home-weather-card.js';
import { installPanelState } from './panel-state.js';
import { createReporter } from './report.js';

const PREFIX = 'org.openvoyah.controls';
const LEFT_PANEL = PREFIX + '.extra.LEFT_PANEL_PX';
const PANELS = [
    [
        ['mScreenUpItemView1', 'screen_up_icon_1', 'navbar01'],
        ['mScreenUpItemView2', 'screen_up_icon_2', 'navbar02'],
        ['mScreenUpHomeView', 'screen_up_home_icon', 'mainHome'],
        ['mScreenUpAllAppView', 'screen_up_all_app_icon', 'mainApps']
    ],
    [
        ['mScreenUpHomeView', 'screen_up_home_icon', 'navbar11'],
        ['mScreenUpItemView1', 'screen_up_icon_1', 'navbar12'],
        ['mScreenUpItemView2', 'screen_up_icon_2', 'navbar13'],
        ['mScreenUpItemView3', 'screen_up_icon_3', 'navbar14'],
        ['mScreenUpItemView4', 'screen_up_icon_4', 'navbar15']
    ]
];

function panelView(nav, field) {
    // Optional positions differ between launcher builds; keep other assignments usable.
    try { return nav[field]?.value ?? null; }
    catch (_) { return null; }
}

export function createActionIntent(Java) {
    const Intent = Java.use('android.content.Intent');
    const Component = Java.use('android.content.ComponentName');
    return action => {
        let intent;
        if (action.kind === 'LAUNCH_APP') {
            if (!/^[A-Za-z0-9_.$]+\/[A-Za-z0-9_.$]+$/.test(action.component || '')) return null;
            const component = Component.unflattenFromString(action.component);
            if (component === null) return null;
            intent = Intent.$new('android.intent.action.MAIN');
            intent.addCategory('android.intent.category.LAUNCHER');
            intent.setComponent(component);
            if (Number.isInteger(action.leftPanelPx) && action.leftPanelPx > 0) {
                intent.putExtra.overload('java.lang.String', 'int').call(intent, LEFT_PANEL, action.leftPanelPx);
            }
        } else if (action.kind === 'START_VOICE') {
            intent = Intent.$new();
            intent.setComponent(Component.$new(PREFIX, PREFIX + '.QuickVoiceActivity'));
            intent.addFlags(0x20000000);
        } else if (action.kind === 'SYSTEM_SETTINGS') {
            intent = Intent.$new('android.settings.SETTINGS');
        } else if (action.kind === 'CONTROLS_SETTINGS' || action.kind === 'CONTROLS_APPS') {
            intent = Intent.$new(PREFIX + (action.kind === 'CONTROLS_APPS' ? '.action.OPEN_APPS' : '.action.OPEN_SETTINGS'));
            intent.setComponent(Component.$new(PREFIX, PREFIX + '.MainActivity'));
            intent.addFlags(0x04000000 | 0x20000000);
        } else {
            return null;
        }
        intent.addFlags(0x10000000 | 0x00200000);
        return intent;
    };
}

export function createActionLauncher(Java, buildIntent = createActionIntent(Java)) {
    const Options = Java.use('android.app.ActivityOptions');
    return (context, screen, action) => {
        const intent = buildIntent(action);
        if (intent === null) return false;
        const options = Options.makeBasic();
        options.setLaunchDisplayId(screen);
        context.startActivity.overload('android.content.Intent', 'android.os.Bundle').call(context, intent, options.toBundle());
        return true;
    };
}

/** Installs the panel adapter for the Qinggan launcher. */
export function install(Java, onNavigation = () => {}, startup, report = createReporter('OVC_PANEL_ERROR')) {
Java.perform(function () {
    let clickHook, initHook, selectionHook;
    const holdHooks = [];
    let panelState, homeCard = null, weatherCard = null;
    try {
    const Nav = Java.use('com.qinggan.mainlauncher.navigation.NavigationBar');
    const Global = Java.use('android.provider.Settings$Global');
    const buildIntent = createActionIntent(Java);
    const launch = createActionLauncher(Java, buildIntent);
    const Log = Java.use('android.util.Log');
    const CONFIG = PREFIX + '.panel';
    const WEATHER = PREFIX + '.homeWeather';
    const onClick = Nav.onClick.overload('android.view.View');
    const View = Java.use('android.view.View');
    const initViews = Nav.initScreenUpViews.overload();
    clickHook=onClick; initHook=initViews;
    holdHooks.push(View.performLongClick.overload(), View.performLongClick.overload('float', 'float'));
    let stockClickView = null;

    function readConfig(context) {
        const raw = Global.getString(context.getContentResolver(), CONFIG);
        try { return raw === null ? null : JSON.parse(raw.toString()); }
        catch (error) { return null; }
    }
    function readWeather(context) {
        const raw = Global.getString(context.getContentResolver(), WEATHER);
        try { return raw === null ? null : JSON.parse(raw.toString()); }
        catch (error) { return null; }
    }
    function actionFor(config, slot) {
        if (!config || config.schema !== 1 || config.enabled !== true || !config.actions) return null;
        const action = config.actions[slot];
        if (!action) return null;
        if (action.kind === 'CONTROLS_SETTINGS' || action.kind === 'CONTROLS_APPS' || action.kind === 'SYSTEM_SETTINGS' || action.kind === 'START_VOICE') return action;
        if (action.kind === 'LAUNCH_APP' && /^[A-Za-z0-9_.$]+\/[A-Za-z0-9_.$]+$/.test(action.component || '')) return {...action, slot};
        return null;
    }
    function slotFor(nav, view) {
        if (view === null) return null;
        const slots = PANELS[nav.mScreenId.value];
        if (!slots) return null;
        const name = view.getResources().getResourceEntryName(view.getId()).toString();
        return slots.find(([field, resource]) => resource === name && view.equals(panelView(nav, field)))?.[2] ?? null;
    }
    function clickOwner(view) {
        const info = Java.cast(view, View).mListenerInfo.value;
        const listener = info === null ? null : info.mOnClickListener.value;
        return listener !== null && Nav.class.isInstance(listener) ? Java.cast(listener, Nav) : null;
    }
    function ownsView(nav, view, slot) {
        return slotFor(nav, view) === slot && nav.equals(clickOwner(view));
    }

    onClick.implementation = function (view) {
        if (stockClickView !== null && view?.equals(stockClickView)) return onClick.call(this, view);
        let slot, action;
        try {
            slot = slotFor(this, view);
            const candidate = slot && actionFor(readConfig(this.mContext.value), slot);
            if (candidate && panelState?.active(this, view, candidate)) action = candidate;
            if (action?.kind === 'LAUNCH_APP' && this.mScreenId.value === 0) {
                // The OEM window uses the same width even while its left-edge animation is in progress.
                try {
                    const width = this.mLp.value.width.value;
                    if (Number.isInteger(width) && width > 0 && width <= 600) action.leftPanelPx = width;
                } catch (_) { }
            }
            if (action && launch(this.mContext.value, this.mScreenId.value, action)) {
                if (action.slot === 'navbar01') {
                    try { onNavigation(this.mContext.value, action.component.split('/')[0]); } catch (error) { }
                }
                return;
            }
        } catch (error) { report('click', error, slot ? {slot} : {}); }
        if (action) {
            try { panelState.failed(this, slot); }
            catch (error) { report('restore', error, {slot}); }
        }
        // Call OEM code exactly once if unmapped, disabled, or the replacement failed.
        return onClick.call(this, view);
    };

    try {
        // The inspected LauncherModel sends these foreground updates to its own display's bar.
        const selected = Nav.updateSelectedApp.overload('java.lang.String', 'java.lang.String');
        selectionHook = selected;
        selected.implementation = function (pkg, activity) {
            const selectionRestored = panelState?.restoreSelection(this);
            const result = selected.call(this, pkg, activity);
            if (!selectionRestored || pkg === null || activity === null || !String(pkg) || !String(activity)) return result;
            try {
                const packageName = String(pkg);
                const fullName = name => name.startsWith('.') ? packageName + name : name;
                const active = fullName(String(activity));
                const config = readConfig(this.mContext.value);
                const ids = [];
                for (const [field, , slot] of PANELS[this.mScreenId.value] ?? []) {
                    const action = actionFor(config, slot);
                    if (action?.kind !== 'LAUNCH_APP') continue;
                    const [targetPackage, targetActivity] = action.component.split('/');
                    if (packageName !== targetPackage || active !== fullName(targetActivity)) continue;
                    const view = panelView(this, field);
                    if (view !== null && panelState?.active(this, view, action) && view.getId() > 0) ids.push(view.getId());
                }
                if (ids.length) {
                    const group = this.mScreenUpRadioGroup.value;
                    if (group !== null && !ids.includes(group.getCheckedRadioButtonId())) panelState.select(this, group, ids[0]);
                }
            } catch (error) { report('selection', error); }
            return result;
        };
    } catch (error) {
        if (selectionHook) selectionHook.implementation = null;
        selectionHook = null;
        Log.i('OpenVoyahControls', 'OEM panel selection callback unavailable');
    }

    for (const hold of holdHooks) {
        hold.implementation = function (...coordinates) {
            if (stockClickView !== null && this.equals(stockClickView)) return true;
            let owner = null, slot = null;
            try {
                const nav = clickOwner(this);
                slot = nav && slotFor(nav, this);
                const action = slot && actionFor(readConfig(nav.mContext.value), slot);
                if (action && panelState?.active(nav, this, action)) owner = nav;
            } catch (error) { report('hold', error, slot ? {slot} : {}); }
            if (owner !== null) {
                // A virtual call from a different hook can re-enter the click implementation.
                const previous = stockClickView;
                stockClickView = this;
                try { onClick.call(owner, this); }
                catch (error) { report('hold', error, {slot}); }
                finally { stockClickView = previous; }
                // Android suppresses the release click after a handled long press.
                return true;
            }
            return hold.call(this, ...coordinates);
        };
    }
    initViews.implementation = function () {
        panelState?.restore(this);
        const result = initViews.call(this);
        panelState?.track(this);
        return result;
    };
    // Its own failure leaves the factory navigation card and does not affect the panel.
    homeCard = installHomeNavigationCard(Java, {readConfig, onNavigation,
        launch: (context, component) => launch(context, 0, {kind: 'LAUNCH_APP', component})});
    weatherCard = installHomeWeatherCard(Java, {readConfig, readWeather});
    panelState = installPanelState(Java, Nav, PANELS, readConfig, actionFor, buildIntent, ownsView, {startup,
        // Controls detaches only after this line, so the factory navigation card must be back as well.
        restored(token) {
            let restored = false;
            try { restored = homeCard === null || homeCard.restoreAll(); }
            catch (error) { report('home_card', error); }
            try { if (weatherCard !== null && !weatherCard.restoreAll()) restored = false; }
            catch (error) { restored = false; report('home_weather', error); }
            if (restored) console.log('OVC_PANEL_ICONS_RESTORED ' + token);
        }});
    // Injection normally occurs after launcher has already constructed the bars.
    Java.choose('com.qinggan.mainlauncher.navigation.NavigationBar', {
        onMatch(nav) {
            const kept = Java.retain(nav);
            Java.scheduleOnMainThread(() => {
                try {
                    panelState.track(kept);
                } finally { kept.$dispose(); }
            });
        },
        onComplete() {
            Java.scheduleOnMainThread(() => {
                if (panelState.finishScan()) console.log('OVC_READY NavigationBar click+hold pid=' + Process.id);
            });
        }
    });
    Log.i('OpenVoyahControls', 'READY NavigationBar.onClick: ' + Process.id);
    // Announced only once nothing can roll the installation back any more.
    if (selectionHook) console.log('OVC_PANEL_SELECTION_READY');
    } catch (error) {
        report('install', error);
        // Roll back a partially installed feature before the next attempt. The hooks go first, so a failing
        // dispose can neither leave them in place nor prevent the retry.
        for (const hook of [clickHook, initHook, selectionHook, ...holdHooks]) {
            if (!hook) continue;
            try { hook.implementation = null; } catch (resetError) { report('rollback', resetError); }
        }
        try { panelState?.dispose(); } catch (disposeError) { report('rollback', disposeError); }
        try { homeCard?.dispose(); } catch (disposeError) { report('rollback', disposeError); }
        try { weatherCard?.dispose(); } catch (disposeError) { report('rollback', disposeError); }
        setTimeout(() => install(Java, onNavigation, startup, report), 30000);
    }
});
}
