import { createPanelIcon } from './panel-icons.js';
import { createReporter } from './report.js';

// Plain tag of every button added to a lowered menu, followed by its slot. It keeps the button recognisable when the
// instance that added it is gone (an agent unloaded without restoring) or has lost track of it.
const LOWERED_TAG = 'org.openvoyah.controls.lowered:';
// Keyed tags that the OEM click reads from a normal-panel button (`startAppBySubType` takes the subtype). An added
// button carries its mirror's, so OEM code that reaches it without the agent still finds them.
const OEM_TAGS = ['screen_up_item_app_sub_type', 'screen_up_item_package', 'screen_up_item_icon'];

function loweredSlot(view) {
    try {
        const tag = view.getTag();
        const text = tag === null || tag === undefined ? '' : String(tag);
        return text.startsWith(LOWERED_TAG) ? text.slice(LOWERED_TAG.length) : null;
    } catch (_) { return null; }
}

/**
 * Owns reversible mutations of the observed launcher views on the UI thread.
 *
 * `lifecycle.lowered` optionally describes the lowered-screen menu of each display:
 * `{button: class, screens: [{group, anchor, frame, gap, added: [[field, slot], ...]}, ...]}`. That menu holds only
 * the `anchor` (Home) in `group`, so every assigned `slot` gets a button of our own after it, in the listed order,
 * mirroring the normal panel's view in `field`. The buttons live in the menu while their assignment does, so they
 * follow the OEM switch between the normal and lowered layouts without hooks of their own.
 */
export function installPanelState(Java, Nav, panels, readConfig, actionFor, buildIntent, ownsView, lifecycle = {}) {
    const Reference = Java.use('java.lang.ref.WeakReference');
    const View = Java.use('android.view.View');
    const RadioGroup = Java.use('android.widget.RadioGroup');
    const ActivityThread = Java.use('android.app.ActivityThread');
    const PackageManager = Java.use('android.content.pm.PackageManager');
    let release;
    if (!lifecycle.skipRelease) {
        try { release = Nav.release.overload(); }
        catch (error) { if (!lifecycle.allowMissingRelease) throw error; }
    }
    const contextOf = lifecycle.contextOf ?? (nav => nav.mContext.value);
    const report = createReporter('OVC_PANEL_ICONS_ERROR');
    const bars = [];
    let drawIcon, theme, lowered = null;
    let ready = false, disposed = false, queued = false, applied = '';
    if (lifecycle.lowered) {
        // Without these the normal panel still works; the lowered menu keeps only its own Home.
        try {
            lowered = {
                ...lifecycle.lowered,
                Button: Java.use(lifecycle.lowered.button),
                Params: Java.use('android.widget.RadioGroup$LayoutParams'),
                Margins: Java.use('android.view.ViewGroup$MarginLayoutParams'),
                Listener: Java.use('android.view.View$OnClickListener'),
                Text: Java.use('java.lang.String')
            };
        } catch (error) { report('lowered', error); }
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
        entry.mirror?.$dispose();
        entry.view.$dispose();
    }

    function findBar(nav) {
        return bars.find(bar => nav.equals(bar.owner.get()));
    }

    function rememberBar(nav) {
        const current = findBar(nav);
        if (current) return current;
        const bar = {owner: reference(nav), views: [], selections: [], layout: null, released: false};
        bars.push(bar);
        return bar;
    }

    function viewField(nav, field) {
        // Optional views differ between launcher builds and models.
        try { return nav[field].value ?? null; }
        catch (_) { return null; }
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

    function removeAdded(view) {
        const parent = view.getParent();
        if (parent === null) return;
        const group = Java.cast(parent, RadioGroup);
        // A removed button would otherwise stay the group's checked id, and the slider would stay on its place.
        if (group.getCheckedRadioButtonId() === view.getId()) group.clearCheck();
        group.removeView(view);
        if (view.getParent() !== null) throw Error('Could not remove the added button');
    }

    function restoreView(entry) {
        entry.active = false;
        try {
            const view = dereference(entry.view, View);
            if (view === null) return true;
            if (entry.added) {
                removeAdded(view);
                return true;
            }
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
            report('restore', error, {slot: entry.slot});
            return false;
        }
    }

    // Each radio group of a bar (the normal panel and the lowered menu) keeps its own selection to restore.
    function restoreSelection(bar) {
        if (!bar?.selections.length) return true;
        bar.selections = bar.selections.filter(selection => {
            const {original, applied} = selection;
            try {
                const group = dereference(selection.group, RadioGroup);
                if (group !== null && group.getCheckedRadioButtonId() === applied) {
                    group.check(original);
                    if (group.getCheckedRadioButtonId() !== original) throw Error('Could not restore the native selection');
                }
                selection.group.$dispose();
                return false;
            } catch (error) {
                report('selection', error);
                return true;
            }
        });
        return bar.selections.length === 0;
    }

    // The lowered menu's own spacing comes back once none of our buttons is left in it.
    function restoreLayout(bar) {
        const layout = bar.layout;
        if (layout === null || bar.views.some(entry => entry.added)) return true;
        try {
            const group = dereference(layout.group, View);
            if (group !== null) {
                const params = Java.cast(group.getLayoutParams(), lowered.Margins);
                params.topMargin.value = layout.margins[0];
                params.bottomMargin.value = layout.margins[1];
                group.setLayoutParams(params);
            }
            layout.group.$dispose();
            bar.layout = null;
            return true;
        } catch (error) {
            report('restore', error, {slot: 'lowered'});
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
        return restoreLayout(bar) && restored;
    }

    function resolve(manager, action) {
        const intent = buildIntent(action);
        if (intent === null) return null;
        const info = manager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY.value);
        return info === null || info.activityInfo.value === null ? null : info;
    }

    function addLowered(nav, bar, config, manager) {
        const layout = lowered?.screens[nav.mScreenId.value];
        if (!layout || !drawIcon) return true;
        const group = viewField(nav, layout.group), anchor = viewField(nav, layout.anchor);
        if (group === null || anchor === null) return true;
        const index = group.indexOfChild(anchor);
        if (index < 0) return true;
        sweepLowered(bar, group);
        const buttons = [];
        for (const [field, slot] of layout.added) {
            try {
                const action = actionFor(config, slot);
                const mirror = action && viewField(nav, field);
                if (!mirror || mirror.getId() <= 0 || !ownsView(nav, mirror, slot)) continue;
                const info = resolve(manager, action);
                if (info !== null) buttons.push({slot, action, mirror, info});
            } catch (error) { report('lowered', error, {slot}); }
        }
        if (!buttons.length) return true;
        try {
            const size = anchor.getLayoutParams();
            const [width, height] = [size.width.value, size.height.value];
            // Keep the normal panel's spacing where it fits the menu's frame, and close it up where it does not.
            const frame = viewField(nav, layout.frame)?.getLayoutParams()?.height.value ?? 0;
            const room = frame - group.getPaddingTop() - group.getPaddingBottom() - (buttons.length + 1) * height;
            const gap = frame > 0 ? Math.max(0, Math.min(layout.gap, Math.floor(room / buttons.length))) : layout.gap;
            const params = Java.cast(group.getLayoutParams(), lowered.Margins);
            bar.layout = {group: reference(group), margins: [params.topMargin.value, params.bottomMargin.value]};
            // The menu centres its group; the group's own margins would push the added buttons off the frame.
            params.topMargin.value = 0;
            params.bottomMargin.value = 0;
            group.setLayoutParams(params);
            let position = index + 1;
            for (const {slot, action, mirror, info} of buttons) {
                // The OEM button class logs the resource name of its id on every touch, so the button reuses the
                // id of the normal-panel view it stands for. Ids only need to be unique within this group.
                const view = lowered.Button.$new.overload('android.content.Context', 'android.util.AttributeSet', 'int')
                    .call(lowered.Button, anchor.getContext(), null, 0);
                const entry = {slot, view: reference(view), mirror: reference(mirror), action, added: true,
                    changedHold: false, active: false, icon: null};
                bar.views.push(entry);
                try {
                    view.setId(mirror.getId());
                    view.setTag(lowered.Text.$new(LOWERED_TAG + slot));
                    copyOemTags(anchor, mirror, view);
                    const place = lowered.Params.$new.overload('int', 'int').call(lowered.Params, width, height);
                    place.topMargin.value = gap;
                    view.setLayoutParams(place);
                    const drawable = drawIcon(view, manager, info, config);
                    if (!drawable) throw Error('No icon for the added button');
                    entry.icon = {stock: null, current: Java.retain(drawable), insets: padding(view)};
                    view.setBackground(drawable);
                    try { view.setContentDescription(info.loadLabel(manager)); } catch (_) { /* Label only. */ }
                    view.setOnClickListener(Java.cast(nav, lowered.Listener));
                    view.setLongClickable(true);
                    // RadioGroup declares only the three-argument addView, and Frida offers no inherited overload.
                    group.addView.overload('android.view.View', 'int', 'android.view.ViewGroup$LayoutParams')
                        .call(group, view, position, place);
                    position++;
                    entry.active = true;
                } catch (error) {
                    report('lowered', error, {slot});
                    if (!dropAdded(bar, entry)) return false;
                }
            }
        } catch (error) {
            report('lowered', error);
            return dropAdded(bar);
        }
        return restoreLayout(bar);
    }

    // Buttons a previous agent instance added and never removed have no hooks behind them any more.
    function sweepLowered(bar, group) {
        let swept = 0;
        for (let i = group.getChildCount() - 1; i >= 0; i--) {
            const child = group.getChildAt(i);
            if (child === null || loweredSlot(child) === null || findView(bar, child)) continue;
            removeAdded(child);
            swept++;
        }
        if (swept) console.log('OVC_PANEL_ICONS_NOTICE stage=lowered_orphans count=' + swept);
    }

    function copyOemTags(anchor, mirror, view) {
        const resources = anchor.getResources(), packageName = anchor.getContext().getPackageName();
        for (const name of OEM_TAGS) {
            const key = resources.getIdentifier(name, 'id', packageName);
            if (key === 0) continue;
            const value = mirror.getTag(key);
            if (value !== null) view.setTag(key, value);
        }
    }

    // A lowered menu that cannot take our buttons keeps its own Home; only a failed cleanup asks for a retry,
    // so a persistent failure does not redraw the whole panel every second.
    function dropAdded(bar, only = null) {
        bar.views = bar.views.filter(entry => {
            if (!entry.added || (only !== null && entry !== only)) return true;
            if (!restoreView(entry)) return true;
            forgetView(entry);
            return false;
        });
        return !bar.views.some(entry => entry.added && (only === null || entry === only)) && restoreLayout(bar);
    }

    function refresh(nav, bar, config) {
        if (!restoreBar(bar)) return false;
        const slots = panels[nav.mScreenId.value];
        if (!slots || config?.enabled !== true) return true;
        const manager = contextOf(nav).getPackageManager();
        for (const [field, , slot] of slots) {
            const action = actionFor(config, slot);
            if (action === null) continue;
            try {
                const view = viewField(nav, field);
                if (view === null || !ownsView(nav, view, slot)) continue;
                const info = resolve(manager, action);
                if (info === null) continue;
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
                    try { drawable = drawIcon?.(view, manager, info, config); }
                    catch (error) { report('icon', error, {slot}); }
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
                    report('apply', error, {slot});
                    if (!restoreBar(bar, slot)) return false;
                }
            } catch (error) { report('refresh', error, {slot}); }
        }
        try { return addLowered(nav, bar, config, manager); }
        catch (error) {
            report('lowered', error);
            return dropAdded(bar);
        }
    }

    function track(nav) {
        if (disposed || !panels[nav.mScreenId.value]) return;
        try {
            const bar = rememberBar(nav);
            bar.released = false;
            if (!refresh(nav, bar, readConfig(contextOf(nav)))) applied = '';
        } catch (error) { applied = ''; report('track', error); }
    }

    function restore(nav) {
        try {
            const bar = findBar(nav);
            if (!bar || restoreBar(bar)) return true;
        } catch (error) { report('restore', error); }
        applied = '';
        return false;
    }

    if (release) release.implementation = function () {
        try {
            const bar = findBar(this);
            if (bar) {
                bar.released = true;
                if (restoreBar(bar)) {
                    bars.splice(bars.indexOf(bar), 1);
                    bar.owner.$dispose();
                } else applied = '';
            }
        } catch (error) { applied = ''; report('release', error); }
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
    } catch (error) { report('theme', error); }

    function signature(config) {
        const valid = config?.schema === 1;
        const token = valid && config.enabled === false && /^[0-9a-f]{32}$/.test(config.restoreIcons)
            ? config.restoreIcons : '';
        // Icon choices redraw the panel like assignments do.
        const assignments = valid && config.enabled === true ? [config.actions, config.customIcons ?? null] : null;
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

    // Reclaim collected bars without reapplying unchanged live views.
    const timer = setInterval(() => Java.perform(() => {
        if (!ready || disposed || queued) return;
        try {
            const app = ActivityThread.currentApplication();
            const changed = app !== null && signature(readConfig(app)).key !== applied;
            if (!changed && !lifecycle.startup?.pending() &&
                !bars.some(bar => bar.released || bar.owner.get() === null)) return;
            queued = true;
            Java.scheduleOnMainThread(() => {
                try {
                    if (disposed) return;
                    const current = ActivityThread.currentApplication();
                    if (current === null) { visitBars(() => true); return; }
                    const config = readConfig(current);
                    const {key, token} = signature(config);
                    const changed = key !== applied;
                    const complete = visitBars((nav, bar) => {
                        if (changed && !refresh(nav, bar, config)) return false;
                        if (config?.enabled === true && nav.mScreenId.value === 0 && lifecycle.startup?.pending()) {
                            for (const entry of bar.views) {
                                if (!entry.active) continue;
                                const view = dereference(entry.view, View);
                                if (view !== null && ownsView(nav, view, entry.slot) &&
                                    lifecycle.startup.showIfReady(current, view)) break;
                            }
                        }
                        return true;
                    });
                    if (changed && complete) {
                        applied = key;
                        if (token) {
                            if (lifecycle.restored) lifecycle.restored(token);
                            else console.log('OVC_PANEL_ICONS_RESTORED ' + token);
                        }
                    }
                } catch (error) { report('refresh', error); }
                finally { queued = false; }
            });
        } catch (error) {
            queued = false;
            report('refresh', error);
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
            catch (error) { report('selection', error); return false; }
        },
        select(nav, group, id) {
            const bar = findBar(nav);
            const original = group.getCheckedRadioButtonId();
            if (bar && original !== id) {
                bar.selections.push({original, applied: id, group: reference(group)});
                group.check(id);
            }
        },
        /** The slot of a button we added to the lowered menu, or null for any other view. */
        addedSlot(nav, view) {
            const entry = findView(findBar(nav), view);
            return entry?.added ? entry.slot : entry ? null : loweredSlot(view);
        },
        /** Whether this instance added the button and still tracks it. */
        tracked(nav, view) {
            return findView(findBar(nav), view)?.added === true;
        },
        /**
         * The view whose OEM click an added button stands for; any other view stands for itself. An added button this
         * instance does not track stands for its slot's normal view, or for nothing (null) if that view is missing.
         */
        nativeView(nav, view) {
            const entry = findView(findBar(nav), view);
            if (entry?.added) return dereference(entry.mirror, View);
            const slot = entry ? null : loweredSlot(view);
            if (slot === null) return view;
            const field = lowered?.screens[nav.mScreenId.value]?.added.find(([, added]) => added === slot)?.[0];
            return field ? viewField(nav, field) : null;
        },
        /** Live added buttons of a bar with the field of the radio group that holds them. */
        added(nav) {
            const group = lowered?.screens[nav.mScreenId.value]?.group;
            const result = [];
            for (const entry of findBar(nav)?.views ?? []) {
                const view = entry.added && entry.active ? dereference(entry.view, View) : null;
                if (view !== null) result.push({slot: entry.slot, view, group});
            }
            return result;
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
            // Hooks first, so a failure below cannot leave them installed.
            if (theme) theme.implementation = null;
            if (release) release.implementation = null;
            clearInterval(timer);
            Java.scheduleOnMainThread(() => {
                for (const bar of bars) bar.released = true;
                visitBars(() => true);
            });
        }
    };
}
