import { createPanelIcon } from './panel-icons.js';

/** Owns reversible mutations of the observed launcher views on the UI thread. */
export function installPanelState(Java, Nav, panels, readConfig, actionFor, buildIntent, ownsView) {
    const Reference = Java.use('java.lang.ref.WeakReference');
    const View = Java.use('android.view.View');
    const RadioGroup = Java.use('android.widget.RadioGroup');
    const ActivityThread = Java.use('android.app.ActivityThread');
    const PackageManager = Java.use('android.content.pm.PackageManager');
    const release = Nav.release.overload();
    const bars = [];
    let drawIcon, theme;
    let ready = false, disposed = false, queued = false, applied = '', lastError = '';

    function report(error) {
        const message = String(error);
        if (message !== lastError) {
            lastError = message;
            console.log('OVC_PANEL_ICONS_ERROR ' + message);
        }
    }

    function reference(object) {
        return Java.retain(Reference.$new(object));
    }

    function dereference(ref, type) {
        const object = ref.get();
        return object === null ? null : Java.cast(object, type);
    }

    function forgetView(entry) {
        entry.icon?.stock?.$dispose();
        entry.icon?.current.$dispose();
        entry.view.$dispose();
    }

    function findBar(nav) {
        return bars.find(bar => nav.equals(bar.owner.get()));
    }

    function rememberBar(nav) {
        const current = findBar(nav);
        if (current) return current;
        const bar = {owner: reference(nav), views: [], selection: null, released: false};
        bars.push(bar);
        return bar;
    }

    function findView(bar, view) {
        return bar?.views.find(entry => view.equals(entry.view.get()));
    }

    function padding(view) {
        return [view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), view.getPaddingBottom()];
    }

    function setBackground(view, drawable, insets) {
        view.setBackground(drawable);
        view.setPadding(...insets);
    }

    function restoreView(entry) {
        entry.active = false;
        try {
            const view = dereference(entry.view, View);
            if (view === null) return true;
            if (entry.changedHold) {
                view.setLongClickable(false);
                if (view.isLongClickable()) throw Error('Could not restore the native hold flag');
                entry.changedHold = false;
            }
            if (entry.icon) {
                const {current, stock, insets} = entry.icon;
                const matchesStock = background => stock === null ? background === null : background?.equals(stock);
                if (view.getBackground()?.equals(current)) {
                    entry.icon.restoring = true;
                    view.setBackground(stock);
                    if (!matchesStock(view.getBackground())) throw Error('Could not restore the native background');
                }
                if (entry.icon.restoring && matchesStock(view.getBackground())) {
                    view.setPadding(...insets);
                    if (padding(view).some((value, index) => value !== insets[index])) {
                        throw Error('Could not restore the native padding');
                    }
                }
            }
            return true;
        } catch (error) {
            report(error);
            return false;
        }
    }

    function restoreSelection(bar) {
        const selection = bar?.selection;
        if (!selection) return true;
        const {original, applied} = selection;
        bar.selection = null;
        try {
            const group = dereference(selection.group, RadioGroup);
            if (group !== null && group.getCheckedRadioButtonId() === applied) {
                group.check(original);
                if (group.getCheckedRadioButtonId() !== original) throw Error('Could not restore the native selection');
            }
            selection.group.$dispose();
            return true;
        } catch (error) {
            bar.selection = selection;
            report(error);
            return false;
        }
    }

    function restoreBar(bar, slot = null) {
        let restored = restoreSelection(bar);
        bar.views = bar.views.filter(entry => {
            if (slot !== null && entry.slot !== slot) return true;
            if (!restoreView(entry)) { restored = false; return true; }
            forgetView(entry);
            return false;
        });
        return restored;
    }

    function refresh(nav, bar, config) {
        if (!restoreBar(bar)) return false;
        const slots = panels[nav.mScreenId.value];
        if (!slots || config?.enabled !== true) return true;
        const manager = nav.mContext.value.getPackageManager();
        for (const [field, , slot] of slots) {
            const action = actionFor(config, slot);
            if (action === null) continue;
            try {
                const view = nav[field].value;
                if (view === null || !ownsView(nav, view, slot)) continue;
                const intent = buildIntent(action);
                if (intent === null) continue;
                const info = manager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY.value);
                if (info === null || info.activityInfo.value === null) continue;
                const entry = {
                    slot, view: reference(view), action, changedHold: !view.isLongClickable(), active: false, icon: null
                };
                bar.views.push(entry);
                try {
                    if (entry.changedHold) {
                        view.setLongClickable(true);
                        if (!view.isLongClickable()) throw Error('Could not enable the assigned hold action');
                    }
                    let drawable;
                    try { drawable = drawIcon?.(view, manager, info); }
                    catch (error) { report(error); }
                    if (drawable) {
                        const original = view.getBackground();
                        const insets = padding(view);
                        entry.icon = {
                            stock: original === null ? null : Java.retain(original),
                            current: Java.retain(drawable),
                            insets
                        };
                        setBackground(view, drawable, insets);
                    }
                    entry.active = true;
                } catch (error) {
                    report(error);
                    if (!restoreBar(bar, slot)) return false;
                }
            } catch (error) { report(error); }
        }
        return true;
    }

    function track(nav) {
        if (disposed || !panels[nav.mScreenId.value]) return;
        try {
            const bar = rememberBar(nav);
            bar.released = false;
            if (!refresh(nav, bar, readConfig(nav.mContext.value))) applied = '';
        } catch (error) { applied = ''; report(error); }
    }

    function restore(nav) {
        try {
            const bar = findBar(nav);
            if (!bar || restoreBar(bar)) return true;
        } catch (error) { report(error); }
        applied = '';
        return false;
    }

    release.implementation = function () {
        try {
            const bar = findBar(this);
            if (bar) {
                bar.released = true;
                if (restoreBar(bar)) {
                    bars.splice(bars.indexOf(bar), 1);
                    bar.owner.$dispose();
                } else applied = '';
            }
        } catch (error) { applied = ''; report(error); }
        return release.call(this);
    };

    try {
        const nextTheme = Nav.updateTheme.overload();
        const nextDraw = createPanelIcon(Java);
        nextTheme.implementation = function () {
            restore(this);
            const result = nextTheme.call(this);
            track(this);
            return result;
        };
        theme = nextTheme;
        drawIcon = nextDraw;
    } catch (error) { report(error); }

    function signature(config) {
        const valid = config?.schema === 1;
        const token = valid && config.enabled === false && /^[0-9a-f]{32}$/.test(config.restoreIcons)
            ? config.restoreIcons : '';
        const assignments = valid && config.enabled === true ? config.actions : null;
        return {key: JSON.stringify([assignments, token]), token};
    }

    function visitBars(update) {
        let complete = true;
        for (let i = bars.length - 1; i >= 0; i--) {
            const bar = bars[i];
            const nav = dereference(bar.owner, Nav);
            if (nav === null || bar.released) {
                if (!restoreBar(bar)) { complete = false; continue; }
                bar.owner.$dispose();
                bars.splice(i, 1);
            } else if (!update(nav, bar)) complete = false;
        }
        return complete;
    }

    // Observe only assignment changes and keep weak references to launcher views.
    const timer = setInterval(() => Java.perform(() => {
        if (!ready || disposed || queued) return;
        try {
            const app = ActivityThread.currentApplication();
            if (app === null || signature(readConfig(app)).key === applied) return;
            queued = true;
            Java.scheduleOnMainThread(() => {
                try {
                    const current = ActivityThread.currentApplication();
                    if (disposed || current === null) return;
                    const config = readConfig(current);
                    const {key, token} = signature(config);
                    if (key !== applied && visitBars((nav, bar) => refresh(nav, bar, config))) {
                        applied = key;
                        if (token) console.log('OVC_PANEL_ICONS_RESTORED ' + token);
                    }
                } catch (error) { report(error); }
                finally { queued = false; }
            });
        } catch (error) {
            queued = false;
            report(error);
        }
    }), 1000);

    return {
        track,
        restore,
        active(nav, view, action) {
            const entry = findView(findBar(nav), view);
            return entry?.active === true && entry.action.kind === action?.kind
                && entry.action.component === action.component;
        },
        restoreSelection(nav) {
            try { return restoreSelection(findBar(nav)); }
            catch (error) { report(error); return false; }
        },
        select(nav, group, id) {
            const bar = findBar(nav);
            const original = group.getCheckedRadioButtonId();
            if (bar && original !== id) {
                bar.selection = {original, applied: id, group: reference(group)};
                group.check(id);
            }
        },
        failed(nav, slot) {
            const bar = findBar(nav);
            if (bar) restoreBar(bar, slot);
        },
        finishScan() {
            if (disposed) return false;
            ready = true;
            console.log(drawIcon ? 'OVC_PANEL_ICONS_READY' : 'OVC_PANEL_ICONS_READY mode=stock-icons');
            return true;
        },
        dispose() {
            disposed = true;
            clearInterval(timer);
            if (theme) theme.implementation = null;
            release.implementation = null;
            Java.scheduleOnMainThread(() => {
                for (const bar of bars) bar.released = true;
                visitBars(() => true);
            });
        }
    };
}
