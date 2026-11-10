'use strict';

import { installPanelIcons } from './panel-icons.js';

const PREFIX = 'org.openvoyah.controls';
const LEFT_PANEL = PREFIX + '.extra.LEFT_PANEL_PX';
const PANELS = [
    [
        ['mScreenUpItemView1', 'screen_up_icon_1', 'navbar01'],
        ['mScreenUpItemView2', 'screen_up_icon_2', 'navbar02']
    ],
    [
        ['mScreenUpHomeView', 'screen_up_home_icon', 'navbar11'],
        ['mScreenUpItemView1', 'screen_up_icon_1', 'navbar12'],
        ['mScreenUpItemView2', 'screen_up_icon_2', 'navbar13'],
        ['mScreenUpItemView3', 'screen_up_icon_3', 'navbar14'],
        ['mScreenUpItemView4', 'screen_up_icon_4', 'navbar15']
    ]
];

function createActionIntent(Java) {
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
            intent.setComponent(Component.$new(PREFIX, PREFIX + '.PanelVoiceActivity'));
            intent.addFlags(0x20000000);
        } else if (action.kind === 'SYSTEM_SETTINGS') {
            intent = Intent.$new('android.settings.SETTINGS');
        } else if (action.kind === 'CONTROLS_SETTINGS') {
            intent = Intent.$new();
            intent.setComponent(Component.$new(PREFIX, PREFIX + '.MainActivity'));
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
export function install(Java, onNavigation = () => {}) {
Java.perform(function () {
    let clickHook, initHook, selectionHook;
    const holdHooks = [];
    let appearance;
    try {
    const Nav = Java.use('com.qinggan.mainlauncher.navigation.NavigationBar');
    const Global = Java.use('android.provider.Settings$Global');
    const buildIntent = createActionIntent(Java);
    const launch = createActionLauncher(Java, buildIntent);
    const Log = Java.use('android.util.Log');
    const CONFIG = PREFIX + '.panel';
    const onClick = Nav.onClick.overload('android.view.View');
    const View = Java.use('android.view.View');
    const initViews = Nav.initScreenUpViews.overload();
    clickHook=onClick; initHook=initViews;
    holdHooks.push(View.performLongClick.overload(), View.performLongClick.overload('float', 'float'));
    let lastConfigError = '';
    let stockClickView = null;

    function readConfig(context) {
        const raw = Global.getString(context.getContentResolver(), CONFIG);
        try { return raw === null ? null : JSON.parse(raw.toString()); }
        catch (error) { return null; }
    }
    function actionFor(config, slot) {
        if (!config || config.schema !== 1 || config.enabled !== true || !config.actions) return null;
        const action = config.actions[slot];
        if (!action) return null;
        if (action.kind === 'CONTROLS_SETTINGS' || action.kind === 'SYSTEM_SETTINGS' || action.kind === 'START_VOICE') return action;
        if (action.kind === 'LAUNCH_APP' && [PREFIX + '/.VoiceActivity', PREFIX + '/' + PREFIX + '.VoiceActivity'].includes(action.component)) {
            return {kind:'START_VOICE'};
        }
        if (action.kind === 'LAUNCH_APP' && /^[A-Za-z0-9_.$]+\/[A-Za-z0-9_.$]+$/.test(action.component || '')) return {...action, slot};
        return null;
    }
    function slotFor(nav, view) {
        const slots = PANELS[nav.mScreenId.value];
        if (!slots) return null;
        const name = view.getResources().getResourceEntryName(view.getId()).toString();
        return slots.find(([, resource]) => resource === name)?.[2] ?? null;
    }

    onClick.implementation = function (view) {
        if (stockClickView !== null && view.equals(stockClickView)) return onClick.call(this, view);
        let slot, action;
        try {
            slot = slotFor(this, view);
            action = slot && actionFor(readConfig(this.mContext.value), slot);
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
        } catch (error) {
            const message = String(error);
            if (message !== lastConfigError) { Log.w('OpenVoyahControls', message); lastConfigError = message; }
        }
        if (action) appearance?.failed(this, slot);
        // Call OEM code exactly once if unmapped, disabled, or the replacement failed.
        return onClick.call(this, view);
    };

    try {
        // The inspected LauncherModel sends these foreground updates to its own display's bar.
        const selected = Nav.updateSelectedApp.overload('java.lang.String', 'java.lang.String');
        selectionHook = selected;
        selected.implementation = function (pkg, activity) {
            const result = selected.call(this, pkg, activity);
            if (pkg === null || activity === null || !String(pkg) || !String(activity)) return result;
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
                    const view = this[field].value;
                    if (view !== null && view.getId() > 0) ids.push(view.getId());
                }
                if (ids.length) {
                    const group = this.mScreenUpRadioGroup.value;
                    if (group !== null && !ids.includes(group.getCheckedRadioButtonId())) group.check(ids[0]);
                }
            } catch (error) {
                const message = 'Panel selection: ' + error;
                if (message !== lastConfigError) { Log.w('OpenVoyahControls', message); lastConfigError = message; }
            }
            return result;
        };
        console.log('OVC_PANEL_SELECTION_READY');
    } catch (error) {
        if (selectionHook) selectionHook.implementation = null;
        selectionHook = null;
        Log.i('OpenVoyahControls', 'OEM panel selection callback unavailable');
    }

    for (const hold of holdHooks) {
        hold.implementation = function (...coordinates) {
            let owner = null;
            try {
                const info = Java.cast(this, View).mListenerInfo.value;
                const listener = info === null ? null : info.mOnClickListener.value;
                if (listener !== null && Nav.class.isInstance(listener)) {
                    const nav = Java.cast(listener, Nav);
                    const slot = slotFor(nav, this);
                    if (slot && actionFor(readConfig(nav.mContext.value), slot)) owner = nav;
                }
            } catch (error) { Log.w('OpenVoyahControls', 'Long press: ' + error); }
            if (owner !== null) {
                // A virtual call from a different hook can re-enter the click implementation.
                const previous = stockClickView;
                stockClickView = this;
                try { onClick.call(owner, this); }
                finally { stockClickView = previous; }
                return true;
            }
            return hold.call(this, ...coordinates);
        };
    }
    function enableHolds(nav) {
        for (const [name] of PANELS[nav.mScreenId.value] ?? []) {
            try {
                const view = nav[name].value;
                if (view !== null) view.setLongClickable(true);
            } catch (error) { Log.w('OpenVoyahControls', 'Hold unavailable: ' + name + ': ' + error); }
        }
    }
    initViews.implementation = function () {
        const result = initViews.call(this);
        enableHolds(this);
        appearance?.track(this);
        return result;
    };
    try { appearance = installPanelIcons(Java, Nav, PANELS, readConfig, actionFor, buildIntent); }
    catch (error) { console.log('OVC_PANEL_ICONS_ERROR ' + error); }
    // Injection normally occurs after launcher has already constructed the bars.
    Java.choose('com.qinggan.mainlauncher.navigation.NavigationBar', {
        onMatch(nav) {
            const kept = Java.retain(nav);
            Java.scheduleOnMainThread(() => {
                try {
                    enableHolds(kept);
                    appearance?.track(kept, true);
                } finally { kept.$dispose(); }
            });
        },
        onComplete() {
            Java.scheduleOnMainThread(() => console.log('OVC_READY NavigationBar click+hold pid=' + Process.id));
        }
    });
    Log.i('OpenVoyahControls', 'READY NavigationBar.onClick: ' + Process.id);
    } catch (error) {
        // Roll back a partially installed feature before the next attempt.
        appearance?.dispose();
        for(const hook of [clickHook,initHook,selectionHook,...holdHooks]) if(hook) hook.implementation=null;
        console.log('OVC_PANEL_ERROR ' + error + '; retry in 30s');
        setTimeout(()=>install(Java, onNavigation),30000);
    }
});
}
