'use strict';

// Original implementation for the observed Qinggan NavigationBar ABI.
// Java bridge is explicitly bundled for Frida 17. No vendor script is embedded here.
export function install(Java) {
Java.perform(function () {
    let clickHook, holdHook, initHook;
    try {
    const Nav = Java.use('com.qinggan.mainlauncher.navigation.NavigationBar');
    const Global = Java.use('android.provider.Settings$Global');
    const Intent = Java.use('android.content.Intent');
    const Component = Java.use('android.content.ComponentName');
    const Options = Java.use('android.app.ActivityOptions');
    const Log = Java.use('android.util.Log');
    const PREFIX = 'org.openvoyah.controls';
    const CONFIG = PREFIX + '.panel';
    const onClick = Nav.onClick.overload('android.view.View');
    const View = Java.use('android.view.View');
    const longClick = View.performLongClick.overload();
    const initViews = Nav.initScreenUpViews.overload();
    clickHook=onClick; holdHook=longClick; initHook=initViews;
    let lastConfigError = '';

    function slotFor(screen, resource) {
        if (screen === 0) {
            if (resource === 'screen_up_icon_1') return 'navbar01';
            if (resource === 'screen_up_icon_2') return 'navbar02';
        }
        if (screen === 1) {
            // Ordering follows physical positions in this OEM launcher, not Android key codes.
            const resources = ['screen_up_home_icon', 'screen_up_icon_1', 'screen_up_icon_2', 'screen_up_icon_3', 'screen_up_icon_4'];
            const index = resources.indexOf(resource);
            if (index >= 0) return 'navbar1' + (index + 1);
        }
        return null;
    }

    function launch(context, screen, action) {
        let intent;
        if (action.kind === 'LAUNCH_APP') {
            if (!/^[A-Za-z0-9_.$]+\/[A-Za-z0-9_.$]+$/.test(action.component || '')) return false;
            const component = Component.unflattenFromString(action.component);
            if (component === null) return false;
            intent = Intent.$new('android.intent.action.MAIN');
            intent.addCategory('android.intent.category.LAUNCHER');
            intent.setComponent(component);
        } else if (action.kind === 'SYSTEM_SETTINGS') {
            intent = Intent.$new('android.settings.SETTINGS');
        } else if (action.kind === 'CONTROLS_SETTINGS') {
            intent = Intent.$new();
            intent.setComponent(Component.$new(PREFIX, PREFIX + '.MainActivity'));
        } else {
            return false;
        }
        intent.addFlags(0x10000000 | 0x00200000);
        const options = Options.makeBasic();
        options.setLaunchDisplayId(screen);
        context.startActivity.overload('android.content.Intent', 'android.os.Bundle').call(context, intent, options.toBundle());
        return true;
    }

    function assigned(nav, view) {
        const slot = slotFor(nav.mScreenId.value, view.getResources().getResourceEntryName(view.getId()).toString());
        if (slot === null) return null;
        const raw = Global.getString(nav.mContext.value.getContentResolver(), CONFIG);
        const config = raw === null ? null : JSON.parse(raw.toString());
        if (!config || config.schema !== 1 || config.enabled !== true || !config.actions) return null;
        const action = config.actions[slot];
        if (!action) return null;
        if (action.kind === 'CONTROLS_SETTINGS' || action.kind === 'SYSTEM_SETTINGS') return action;
        if (action.kind === 'LAUNCH_APP' && /^[A-Za-z0-9_.$]+\/[A-Za-z0-9_.$]+$/.test(action.component || '')) return action;
        return null;
    }

    onClick.implementation = function (view) {
        try {
            const action = assigned(this, view);
            if (action && launch(this.mContext.value, this.mScreenId.value, action)) return;
        } catch (error) {
            const message = String(error);
            if (message !== lastConfigError) { Log.w('OpenVoyahControls', message); lastConfigError = message; }
        }
        // Call OEM code exactly once if unmapped, disabled, or the replacement failed.
        return onClick.call(this, view);
    };

    longClick.implementation = function () {
        let owner = null;
        try {
            const info = Java.cast(this, View).mListenerInfo.value;
            const listener = info === null ? null : info.mOnClickListener.value;
            if (listener !== null && Nav.class.isInstance(listener)) {
                const nav = Java.cast(listener, Nav);
                if (assigned(nav, this)) owner = nav;
            }
        } catch (error) { Log.w('OpenVoyahControls', 'Long press: ' + error); }
        if (owner !== null) {
            // Bypass our click replacement. Returning true consumes the hold;
            // Android then suppresses the click on release and handles drag/cancel.
            onClick.call(owner, this);
            return true;
        }
        return longClick.call(this);
    };
    function enableHolds(nav) {
        const names = nav.mScreenId.value === 0 ? ['mScreenUpItemView1', 'mScreenUpItemView2']
            : nav.mScreenId.value === 1 ? ['mScreenUpHomeView', 'mScreenUpItemView1', 'mScreenUpItemView2', 'mScreenUpItemView3', 'mScreenUpItemView4'] : [];
        for (const name of names) {
            try {
                const view = nav[name].value;
                if (view !== null) view.setLongClickable(true);
            } catch (error) { Log.w('OpenVoyahControls', 'Hold unavailable: ' + name + ': ' + error); }
        }
    }
    initViews.implementation = function () {
        const result = initViews.call(this);
        enableHolds(this);
        return result;
    };
    // Injection normally occurs after launcher has already constructed the bars.
    Java.choose('com.qinggan.mainlauncher.navigation.NavigationBar', {
        onMatch(nav) {
            const kept = Java.retain(nav);
            Java.scheduleOnMainThread(() => { try { enableHolds(kept); } finally { kept.$dispose(); } });
        },
        onComplete() {
            Java.scheduleOnMainThread(() => console.log('OVC_READY NavigationBar click+hold pid=' + Process.id));
        }
    });
    Log.i('OpenVoyahControls', 'READY NavigationBar.onClick: ' + Process.id);
    } catch (error) {
        // Roll back a partially installed feature before the next attempt.
        for(const hook of [clickHook,holdHook,initHook]) if(hook) hook.implementation=null;
        console.log('OVC_PANEL_ERROR ' + error + '; retry in 30s');
        setTimeout(()=>install(Java),30000);
    }
});
}
