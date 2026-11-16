import { createActionIntent, createActionLauncher } from './launcher-buttons.js';
import { installPanelState } from './panel-state.js';

const LAYOUTS = [
    {name:'com.qinggan.launcher.navigation.NavigationBarMain', screen:0, slots:[
        ['mScreenUpItemView1', '', 'navbar01'], ['mScreenUpItemView2', '', 'navbar02'],
        ['mScreenUpHomeView', '', 'mainHome'], ['mScreenUpAllAppView', '', 'mainApps']
    ]},
    {name:'com.qinggan.launcher.navigation.NavigationBarSecond', screen:1, slots:[
        ['mScreenUpHomeView', '', 'navbar11'], ['mScreenUpSeatView', '', 'navbar12'],
        ['mScreenUpAllAppView', '', 'passengerApps']
    ]}
];

/** Uses the overseas launcher's separate bars, without changing seat or screen-lift policy. */
export function installOversea(Java, onNavigation = () => {}) {
    Java.perform(() => {
        const scopes = [], holds = [];
        let nativeClick = null, complete = 0;
        const restored = new Map();
        try {
            const Global = Java.use('android.provider.Settings$Global');
            const Thread = Java.use('android.app.ActivityThread');
            const View = Java.use('android.view.View');
            const buildIntent = createActionIntent(Java);
            const launch = createActionLauncher(Java, buildIntent);
            const contextOf = () => {
                const app = Thread.currentApplication();
                if (app === null) throw Error('Launcher context unavailable');
                return app;
            };
            const readConfig = context => {
                try {
                    const raw = Global.getString(context.getContentResolver(), 'org.openvoyah.controls.panel');
                    return raw === null ? null : JSON.parse(String(raw));
                } catch (_) { return null; }
            };
            const actionFor = (config, slot) => {
                if (config?.schema !== 1 || config.enabled !== true) return null;
                const action = config.actions?.[slot];
                if (!action || !['LAUNCH_APP','START_VOICE','SYSTEM_SETTINGS','CONTROLS_SETTINGS'].includes(action.kind)) return null;
                if (action.kind === 'LAUNCH_APP' && !/^[A-Za-z0-9_.$]+\/[A-Za-z0-9_.$]+$/.test(action.component ?? '')) return null;
                return action;
            };
            function slotFor(scope, nav, view) {
                if (view === null || nav.mScreenId.value !== scope.layout.screen) return null;
                return scope.layout.slots.find(([field]) => nav[field]?.value?.equals(view))?.[2] ?? null;
            }
            function ownerOf(scope, view) {
                const listener = Java.cast(view, View).mListenerInfo.value?.mOnClickListener.value;
                return listener && scope.Nav.class.isInstance(listener) ? Java.cast(listener, scope.Nav) : null;
            }
            function track(scope, nav) {
                const kept = Java.retain(nav);
                Java.scheduleOnMainThread(() => {
                    try { scope.state.track(kept); }
                    finally { kept.$dispose(); }
                });
            }
            for (const layout of LAYOUTS) {
                const Nav = Java.use(layout.name);
                const click = Nav.onClick.overload('android.view.View');
                if (click.returnType.className !== 'void') throw Error('Unsupported navbar click signature');
                // Require each class's theme callback before changing either bar.
                if (Nav.updateTheme.overload().returnType.className !== 'void') throw Error('Unsupported navbar theme signature');
                const scope = {layout, Nav, click, constructors:[], state:null};
                scopes.push(scope);
            }
            for (const scope of scopes) {
                const {Nav, layout, click} = scope;
                const panels = [];
                panels[layout.screen] = layout.slots;
                scope.state = installPanelState(Java, Nav, panels, readConfig, actionFor, buildIntent,
                    (nav, view, slot) => slotFor(scope, nav, view) === slot && nav.equals(ownerOf(scope, view)), {
                        contextOf, skipRelease:true,
                        restored(token) {
                            restored.set(layout.screen, token);
                            if (scopes.every(item => restored.get(item.layout.screen) === token)) {
                                console.log('OVC_PANEL_ICONS_RESTORED ' + token);
                            }
                        }
                    });
                click.implementation = function (view) {
                    if (nativeClick?.equals(view)) return click.call(this, view);
                    let slot;
                    try {
                        slot = slotFor(scope, this, view);
                        const action = slot && actionFor(readConfig(contextOf()), slot);
                        if (action && scope.state.active(this, view, action) && launch(contextOf(), layout.screen, action)) {
                            if (slot === 'navbar01' && action.kind === 'LAUNCH_APP') {
                                try { onNavigation(contextOf(), action.component.split('/')[0]); } catch (_) { }
                            }
                            return;
                        }
                    } catch (_) { if (slot) scope.state.failed(this, slot); }
                    return click.call(this, view);
                };
                // Constructors cover a recreated passenger bar after sleep or launcher changes.
                for (const constructor of Nav.$init.overloads) {
                    scope.constructors.push(constructor);
                    constructor.implementation = function (...args) {
                        const result = constructor.call(this, ...args);
                        track(scope, this);
                        return result;
                    };
                }
            }
            for (const signature of [[], ['float', 'float']]) {
                const hold = View.performLongClick.overload(...signature);
                holds.push(hold);
                hold.implementation = function (...coordinates) {
                    if (nativeClick?.equals(this)) return true;
                    for (const scope of scopes) {
                        let nav, slot, action;
                        try {
                            nav = ownerOf(scope, this);
                            slot = nav && slotFor(scope, nav, this);
                            action = slot && actionFor(readConfig(contextOf()), slot);
                            if (!action || !scope.state.active(nav, this, action)) continue;
                        } catch (_) { continue; }
                        const previous = nativeClick;
                        nativeClick = this;
                        try { scope.click.call(nav, this); }
                        catch (_) { console.log('OVC_PANEL_ERROR original overseas action failed'); }
                        finally { nativeClick = previous; }
                        return true;
                    }
                    return hold.call(this, ...coordinates);
                };
            }
            for (const scope of scopes) Java.choose(scope.layout.name, {
                onMatch(nav) { track(scope, nav); },
                onComplete() {
                    Java.scheduleOnMainThread(() => {
                        if (scope.state.finishScan() && ++complete === scopes.length) {
                            console.log('OVC_READY NavigationBar click+hold adapter=oversea pid=' + Process.id);
                        }
                    });
                }
            });
        } catch (error) {
            for (const scope of scopes) {
                scope.state?.dispose();
                scope.click.implementation = null;
                for (const constructor of scope.constructors) constructor.implementation = null;
            }
            for (const hold of holds) hold.implementation = null;
            console.log('OVC_PANEL_ERROR overseas adapter unavailable: ' + error);
        }
    });
}
