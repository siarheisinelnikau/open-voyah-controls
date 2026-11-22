import { createReporter } from './report.js';

// Every view this module adds carries this tag, so a later agent can remove one an earlier agent left behind.
const CARD_MARK = 'org.openvoyah.controls.homeCard';
const PACKAGE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;
const TEXT_LIMIT = 80;
// Notification.FLAG_ONGOING_EVENT and FLAG_GROUP_SUMMARY.
const ONGOING = 0x2;
const GROUP_SUMMARY = 0x200;
// The factory map card treats a press and release within 10 px as a tap.
const TAP_SLOP = 10;
const POLL_MS = 2000;
// Id of the widget host the agent registers for the launcher package. The factory launcher hosts no widgets.
const WIDGET_HOST = 0x4f56;

/**
 * The app whose card replaces the factory navigation card, or null for the factory card. A restore request (the
 * `restoreIcons` token Controls publishes before it detaches) always means the factory card.
 */
export function cardPackage(config) {
    if (!config || config.schema !== 1 || config.restoreIcons !== undefined) return null;
    const name = config.homeCard?.package;
    return typeof name === 'string' && name.length <= 128 && PACKAGE.test(name) ? name : null;
}

function clean(value) {
    if (value === null || value === undefined) return '';
    const text = String(value).replace(/\s+/g, ' ').trim();
    return text.length > TEXT_LIMIT ? text.slice(0, TEXT_LIMIT - 1) + '…' : text;
}

/** The navigator's newest ongoing notification, else its newest one. Group summaries carry no guidance. */
export function pickNotification(items) {
    let best = null;
    for (const item of items) {
        if (item.summary) continue;
        if (best === null || item.ongoing && !best.ongoing
            || item.ongoing === best.ongoing && item.postTime > best.postTime) best = item;
    }
    return best;
}

/**
 * The navigator's widget for a card of the given width/height ratio: the tallest one (the traffic widgets come with
 * one or two rows), then the one whose own ratio is nearest the card's. Null when the app has none.
 */
export function pickWidget(providers, aspect) {
    let best = null;
    const distance = item => Math.abs(Math.log((item.minWidth / item.minHeight) / aspect));
    for (const item of providers) {
        if (!(item.minWidth > 0 && item.minHeight > 0)) continue;
        if (best === null || item.minHeight > best.minHeight
            || item.minHeight === best.minHeight && distance(item) < distance(best)) best = item;
    }
    return best;
}

/** Text lines of a notification for the card, or null when it has none (a custom layout, for example). */
export function guidanceLines(item) {
    const lines = {title: clean(item?.title), text: clean(item?.text), sub: clean(item?.sub)};
    return lines.title || lines.text || lines.sub ? lines : null;
}

/**
 * Owns the replacement of the factory navigation cards. Runs on the launcher's UI thread; `env` does the view work.
 * Cards are kept as handles from `keep(card)`; `same(handle, card)` compares one with a live card. The rest take
 * handles: `alive`, `forget(entry)`, `removeOrphans` → count, `addOverlay(handle, name)` → overlay,
 * `render(overlay, model)`, `removeOverlay(handle, overlay)`, `pauseMap`, `resumeMap` and `open(handle, name)`.
 * `launchable(name)` tells whether the app can be started.
 */
export function createHomeCardController(env, report) {
    const entries = [];
    let target = null;
    let model = null;

    function find(card) {
        return entries.find(entry => env.same(entry.card, card));
    }

    function restore(entry) {
        if (entry.overlay === null) return true;
        try {
            env.removeOverlay(entry.card, entry.overlay);
            entry.overlay = null;
            entry.name = null;
            env.resumeMap(entry.card);
            return true;
        } catch (error) {
            report('restore', error);
            return false;
        }
    }

    function apply(entry, name) {
        try {
            entry.overlay = env.addOverlay(entry.card, name);
            entry.name = name;
            env.render(entry.overlay, model);
            env.pauseMap(entry.card);
        } catch (error) {
            report('apply', error);
            restore(entry);
        }
    }

    function reconcile(entry) {
        if (entry.overlay !== null && entry.name !== target && !restore(entry)) return;
        if (target !== null && entry.overlay === null) apply(entry, target);
    }

    return {
        /** Chooses the card's app from the configuration and brings every known card in line. */
        configure(name) {
            let next = null;
            try { next = name !== null && env.launchable(name) ? name : null; }
            catch (error) { report('resolve', error); }
            if (next !== target) model = null;
            target = next;
            for (let i = entries.length - 1; i >= 0; i--) {
                const entry = entries[i];
                if (!env.alive(entry.card)) { entries.splice(i, 1); env.forget?.(entry); continue; }
                reconcile(entry);
            }
        },
        track(card) {
            if (find(card)) return;
            const entry = {card: env.keep(card), overlay: null, name: null};
            // A card covered by an earlier agent that never restored it: take its overlay away first.
            try {
                if (env.removeOrphans(entry.card) > 0 && target === null) env.resumeMap(entry.card);
            } catch (error) { report('orphan', error); }
            entries.push(entry);
            reconcile(entry);
        },
        covers(card) {
            return find(card)?.overlay != null;
        },
        open(card) {
            const entry = find(card);
            if (!entry?.name) return false;
            try { env.open(entry.card, entry.name); return true; }
            catch (error) { report('open', error); return false; }
        },
        /** Navigator content for the covered cards; null shows the app tile. */
        show(next) {
            model = next;
            for (const entry of entries) {
                if (entry.overlay === null) continue;
                try { env.render(entry.overlay, model); }
                catch (error) { report('render', error); }
            }
        },
        target: () => target,
        covering: () => entries.some(entry => entry.overlay !== null),
        /** Puts every factory card back; true when none is covered any more. */
        restoreAll() {
            target = null;
            model = null;
            let restored = true;
            for (const entry of entries) if (!restore(entry)) restored = false;
            return restored;
        }
    };
}

function failure(code) {
    const error = new Error(code);
    error.code = code;
    return error;
}

/**
 * One widget of the navigator, hosted by the launcher for the covered cards. The launcher runs as the system uid,
 * so binding needs no user prompt. Released (host deleted, the navigator told the widget is gone) once no card
 * shows it, so turning the setting off leaves nothing registered.
 */
function createWidgetSlot(Java) {
    const Host = Java.use('android.appwidget.AppWidgetHost');
    const Manager = Java.use('android.appwidget.AppWidgetManager');
    const Info = Java.use('android.appwidget.AppWidgetProviderInfo');
    const Bundle = Java.use('android.os.Bundle');
    const ActivityThread = Java.use('android.app.ActivityThread');
    let host = null, id = -1, users = 0;

    function bind(name, aspect) {
        const app = ActivityThread.currentApplication();
        const manager = Manager.getInstance(app);
        const list = manager.getInstalledProvidersForPackage(name, null);
        const providers = [];
        for (let i = 0; i < list.size(); i++) {
            const info = Java.cast(list.get(i), Info);
            providers.push({minWidth: info.minWidth.value, minHeight: info.minHeight.value, info});
        }
        const chosen = pickWidget(providers, aspect);
        if (chosen === null) return null;
        const provider = chosen.info.provider.value;
        if (host === null) host = Java.retain(Host.$new(app, WIDGET_HOST));
        // Reuse a widget this host still has for the chosen provider; drop any other.
        const ids = host.getAppWidgetIds();
        for (let i = 0; i < (ids?.length ?? 0); i++) {
            const bound = manager.getAppWidgetInfo(ids[i]);
            if (id === -1 && bound !== null && provider.equals(bound.provider.value)) id = ids[i];
            else if (ids[i] !== id) host.deleteAppWidgetId(ids[i]);
        }
        if (id === -1) {
            const next = host.allocateAppWidgetId();
            if (!manager.bindAppWidgetIdIfAllowed.overload('int', 'android.content.ComponentName')
                .call(manager, next, provider)) {
                host.deleteAppWidgetId(next);
                throw failure('widget_refused');
            }
            id = next;
        }
        if (users === 0) host.startListening();
        return manager.getAppWidgetInfo(id);
    }

    return {
        /** A view of the widget sized to the card, or null when the navigator has no widget. */
        create(context, name, width, height) {
            const info = bind(name, width / height);
            if (info === null) return null;
            // The launcher activity's inflater carries AppCompat's and the skin engine's view factories, which
            // replace the widget's TextViews; RemoteViews then refuses the layout ("Couldn't add widget"). A
            // configuration context of the same display has a plain inflater of its own.
            const plain = context.createConfigurationContext(context.getResources().getConfiguration());
            const view = host.createView(plain, id, info);
            users++;
            const density = context.getResources().getDisplayMetrics().density.value;
            const w = Math.round(width / density), h = Math.round(height / density);
            view.setPadding(0, 0, 0, 0);
            view.updateAppWidgetSize(Bundle.$new(), w, h, w, h);
            return view;
        },
        drop() {
            users = Math.max(0, users - 1);
            if (users > 0 || host === null) return;
            const released = host;
            host = null;
            id = -1;
            try { released.stopListening(); }
            finally {
                released.deleteHost();
                released.$dispose();
            }
        }
    };
}

function within(down, x, y) {
    return down !== null && Math.abs(x - down[0]) < TAP_SLOP && Math.abs(y - down[1]) < TAP_SLOP;
}

/**
 * Replaces the launcher's Baidu navigation card with a card for the configured navigator (Yandex Navigator in
 * Controls). The card hosts the navigator's own widget (Yandex Navigator's traffic map). Without one it shows the
 * navigator's ongoing notification, else its icon and name. A tap outside the widget's buttons opens the navigator on
 * display 0. Baidu's map stays in the view tree, paused and
 * covered, so the factory card comes back unchanged when the setting is turned off or Controls detaches.
 *
 * The overlay itself is not clickable: taps reach the factory `MapWidgetLayout`, whose touch dispatch is hooked.
 * If the agent ever disappears without restoring, its hook goes with it and taps open Baidu again.
 */
export function installHomeNavigationCard(Java, {readConfig, launch, onNavigation = () => {},
    report = createReporter('OVC_HOME_CARD_ERROR')} = {}) {
    const hooks = [];
    let controller = null, timer = null, poll = null, disposed = false;
    // `applied` is the configured app last handed to the controller; `shown` keys the notification on screen, and
    // `current` keeps that model (with its retained notification) until the next one replaces it.
    let applied, shown = '', current = null;
    try {
        const Card = Java.use('com.qinggan.app.launcher.homecard.HomeNaviCardView');
        const MapLayout = Java.use('com.baidu.widgetmap.MapWidgetLayout');
        const MapManager = Java.use('com.baidu.widgetmap.MapWidgetManager');
        const View = Java.use('android.view.View');
        const ViewGroup = Java.use('android.view.ViewGroup');
        const FrameLayout = Java.use('android.widget.FrameLayout');
        const FrameParams = Java.use('android.widget.FrameLayout$LayoutParams');
        const LinearLayout = Java.use('android.widget.LinearLayout');
        const LinearParams = Java.use('android.widget.LinearLayout$LayoutParams');
        const ImageView = Java.use('android.widget.ImageView');
        const ScaleType = Java.use('android.widget.ImageView$ScaleType');
        const TextView = Java.use('android.widget.TextView');
        const Truncate = Java.use('android.text.TextUtils$TruncateAt');
        const Typeface = Java.use('android.graphics.Typeface');
        const Shape = Java.use('android.graphics.drawable.GradientDrawable');
        const JString = Java.use('java.lang.String');
        const Reference = Java.use('java.lang.ref.WeakReference');
        const ActivityThread = Java.use('android.app.ActivityThread');

        const retain = object => Java.retain(object);
        const dereference = ref => {
            const object = ref.get();
            return object === null ? null : Java.cast(object, Card);
        };
        const chars = value => JString.$new(value);

        function label(view, value) {
            view.setText.overload('java.lang.CharSequence').call(view, chars(value));
            view.setVisibility(value ? 0 : 8);
        }

        function textView(context, size, color, bold) {
            const view = TextView.$new(context);
            view.setTextSize(0, size);
            view.setTextColor(color);
            view.setGravity(17);
            view.setMaxLines(2);
            view.setEllipsize(Truncate.END.value);
            if (bold) view.setTypeface(Typeface.DEFAULT_BOLD.value);
            return view;
        }

        function cornerRadius(context) {
            const resources = context.getResources();
            const id = resources.getIdentifier('navi_dimens_16dp', 'dimen', context.getPackageName());
            return id === 0 ? 16 * resources.getDisplayMetrics().density.value : resources.getDimension(id);
        }

        function appIcon(context, name) {
            try { return context.getPackageManager().getApplicationIcon.overload('java.lang.String').call(context.getPackageManager(), name); }
            catch (_) { return null; }
        }

        function appLabel(context, name) {
            const manager = context.getPackageManager();
            const info = manager.getApplicationInfo.overload('java.lang.String', 'int').call(manager, name, 0);
            return String(manager.getApplicationLabel(info));
        }

        function removeMarked(card) {
            const group = Java.cast(card, ViewGroup);
            let removed = 0;
            for (let i = group.getChildCount() - 1; i >= 0; i--) {
                const child = group.getChildAt(i);
                const tag = child?.getTag();
                if (tag === null || tag === undefined || String(tag) !== CARD_MARK) continue;
                group.removeViewAt(i);
                removed++;
            }
            return removed;
        }

        function pause(card) {
            MapManager.getInstance(card.getContext()).onPause();
        }

        function cardSize(card, context) {
            const resources = context.getResources();
            const dimension = (name, fallback) => {
                const id = resources.getIdentifier(name, 'dimen', context.getPackageName());
                return id === 0 ? fallback * resources.getDisplayMetrics().density.value : resources.getDimension(id);
            };
            const width = card.getWidth() || dimension('navi_dimens_576dp', 576);
            const height = card.getHeight() || dimension('navi_dimens_652dp', 652);
            return [width, height];
        }

        let widgets = null, textCards = 0;
        try { widgets = createWidgetSlot(Java); }
        catch (error) { report('widget', error); }

        function dropOverlay(overlay) {
            if (overlay.widget) {
                try { widgets.drop(); } catch (error) { report('widget', error); }
            } else textCards = Math.max(0, textCards - 1);
            for (const view of Object.values(overlay.views)) view.$dispose();
        }

        const env = {
            same: (ref, card) => card.equals(ref.get()),
            keep: card => retain(Reference.$new(card)),
            alive: ref => ref.get() !== null,
            forget(entry) {
                if (entry.overlay) dropOverlay(entry.overlay);
                entry.card.$dispose();
            },
            launchable(name) {
                const app = ActivityThread.currentApplication();
                return app !== null && app.getPackageManager().getLaunchIntentForPackage(name) !== null;
            },
            removeOrphans(ref) {
                const card = dereference(ref);
                return card === null ? 0 : removeMarked(card);
            },
            addOverlay(ref, name) {
                const card = dereference(ref);
                if (card === null) throw failure('card_gone');
                removeMarked(card);
                const context = card.getContext();
                const density = context.getResources().getDisplayMetrics().density.value;
                const root = FrameLayout.$new(context);
                root.setTag(chars(CARD_MARK));
                const background = Shape.$new();
                background.setColor(0xff15181d | 0);
                background.setCornerRadius(cornerRadius(context));
                root.setBackground(background);
                root.setClipToOutline(true);
                root.setClickable(false);
                const column = LinearLayout.$new(context);
                column.setOrientation(1);
                column.setGravity(17);
                const pad = Math.round(32 * density);
                column.setPadding(pad, pad, pad, pad);
                const icon = ImageView.$new(context);
                icon.setScaleType(ScaleType.FIT_CENTER.value);
                const iconSize = Math.round(160 * density);
                const iconParams = LinearParams.$new(iconSize, iconSize);
                iconParams.bottomMargin.value = Math.round(24 * density);
                column.addView(icon, iconParams);
                const title = textView(context, 40 * density, -1, true);
                const text = textView(context, 26 * density, 0xccffffff | 0, false);
                const sub = textView(context, 22 * density, 0x99ffffff | 0, false);
                const custom = FrameLayout.$new(context);
                custom.setVisibility(8);
                for (const view of [title, text, sub, custom]) column.addView(view, LinearParams.$new(-1, -2));
                root.addView(column, FrameParams.$new(-1, -1));
                // The navigator's own widget (its traffic map) when it has one; the icon and text otherwise.
                let widget = null;
                try {
                    const [width, height] = cardSize(card, context);
                    widget = widgets?.create(context, name, width, height) ?? null;
                } catch (error) { report('widget', error); }
                if (widget !== null) {
                    column.setVisibility(8);
                    root.addView(widget, FrameParams.$new(-1, -1));
                } else textCards++;
                Java.cast(card, ViewGroup).addView(root, FrameParams.$new(-1, -1));
                let appName = name;
                try { appName = appLabel(context, name); } catch (_) { }
                root.setContentDescription(chars(appName));
                const views = {root: retain(root), icon: retain(icon), title: retain(title),
                    text: retain(text), sub: retain(sub), custom: retain(custom)};
                return {name, appName, widget: widget !== null, views};
            },
            render(overlay, model) {
                if (overlay.widget) return;
                const {icon, title, text, sub, custom} = overlay.views;
                const context = icon.getContext();
                custom.removeAllViews();
                custom.setVisibility(8);
                let drawable = null;
                const visible = model?.name === overlay.name ? model : null;
                if (visible?.notification) {
                    try { drawable = visible.notification.getLargeIcon()?.loadDrawable(context) ?? null; }
                    catch (error) { report('large_icon', error); }
                }
                icon.setImageDrawable(drawable ?? appIcon(context, overlay.name));
                if (visible?.lines) {
                    label(title, visible.lines.title);
                    label(text, visible.lines.text);
                    label(sub, visible.lines.sub);
                    return;
                }
                label(title, overlay.appName);
                label(text, '');
                label(sub, '');
                // A notification without text lines may carry its own layout; show that under the icon.
                const remote = visible?.notification?.contentView.value ?? null;
                if (remote === null) return;
                try {
                    custom.addView(remote.apply(context, custom));
                    custom.setVisibility(0);
                } catch (error) { report('remote_views', error); }
            },
            removeOverlay(ref, overlay) {
                const card = dereference(ref);
                if (card !== null) {
                    removeMarked(card);
                    if (Java.cast(card, ViewGroup).indexOfChild(overlay.views.root) >= 0) throw failure('overlay_kept');
                }
                dropOverlay(overlay);
            },
            pauseMap(ref) {
                const card = dereference(ref);
                if (card !== null) pause(card);
            },
            resumeMap(ref) {
                const card = dereference(ref);
                // The card's own resume checks attachment and the lowered layout before it restarts the map.
                if (card !== null && card.isShown()) card.onResume();
            },
            open(ref, name) {
                const card = dereference(ref);
                const context = card === null ? ActivityThread.currentApplication() : card.getContext();
                const manager = context.getPackageManager();
                const intent = manager.getLaunchIntentForPackage(name);
                const component = intent?.getComponent();
                if (!component || !launch(context, String(component.flattenToString()))) throw failure('not_launchable');
                try { onNavigation(context, name); } catch (_) { }
            }
        };
        controller = createHomeCardController(env, report);

        function cardOf(layout) {
            let parent = layout.getParent();
            for (let depth = 0; depth < 3 && parent !== null; depth++) {
                if (Card.class.isInstance(parent)) return Java.cast(parent, Card);
                if (!View.class.isInstance(parent)) return null;
                parent = Java.cast(parent, View).getParent();
            }
            return null;
        }

        let down = null;
        const touch = MapLayout.dispatchTouchEvent.overload('android.view.MotionEvent');
        hooks.push(touch);
        touch.implementation = function (event) {
            let card = null;
            try {
                const owner = cardOf(this);
                if (owner !== null && controller.covers(owner)) card = owner;
            } catch (error) { report('touch', error); }
            if (card === null) return touch.call(this, event);
            try {
                const action = event.getActionMasked();
                const x = event.getRawX(), y = event.getRawY();
                if (action === 0) down = [x, y];
                else if (action === 3) down = null;
                else if (action === 1) {
                    if (within(down, x, y)) {
                        this.playSoundEffect(0);
                        controller.open(card);
                    }
                    down = null;
                }
            } catch (error) { report('tap', error); }
            // Baidu's map under the card gets no gestures while it is covered.
            return true;
        };

        // The fragment resumes the card, and the card restarts Baidu's map; a covered card pauses it again.
        for (const [method, signature] of [['onResume', []], ['onAttachedToWindow', []], ['doScreenLift', ['int']]]) {
            const hook = Card[method].overload(...signature);
            hooks.push(hook);
            hook.implementation = function (...args) {
                const result = hook.call(this, ...args);
                try {
                    if (method === 'onAttachedToWindow') controller.track(this);
                    if (controller.covers(this)) pause(this);
                } catch (error) { report(method, error); }
                return result;
            };
        }

        function configured() {
            const app = ActivityThread.currentApplication();
            return app === null ? null : cardPackage(readConfig(app));
        }

        let queued = false;
        function refresh() {
            if (queued || disposed) return;
            queued = true;
            Java.scheduleOnMainThread(() => {
                try {
                    if (disposed) return;
                    const name = configured();
                    controller.configure(name);
                    applied = name;
                } catch (error) { report('refresh', error); }
                finally { queued = false; }
            });
        }

        let notifications = null, shownTarget = null;
        try {
            const binder = Java.use('android.os.ServiceManager').getService('notification');
            notifications = Java.cast(Java.use('android.app.INotificationManager$Stub').asInterface(binder),
                Java.use('android.app.INotificationManager'));
        } catch (error) { report('notifications', error); }

        function active(name) {
            const caller = String(ActivityThread.currentApplication().getPackageName());
            const list = typeof notifications.getActiveNotificationsWithAttribution === 'function'
                ? notifications.getActiveNotificationsWithAttribution(caller, null)
                : notifications.getActiveNotifications(caller);
            const items = [];
            // A Java array: indexed, not necessarily iterable.
            for (let i = 0; i < (list?.length ?? 0); i++) {
                const record = list[i];
                if (String(record.getPackageName()) !== name) continue;
                const notification = record.getNotification();
                const extras = notification.extras.value;
                const flags = notification.flags.value;
                const text = key => extras === null ? null : extras.getCharSequence(key);
                items.push({
                    ongoing: (flags & ONGOING) !== 0, summary: (flags & GROUP_SUMMARY) !== 0,
                    postTime: Number(record.getPostTime()), notification,
                    title: text('android.title'), text: text('android.text'), sub: text('android.subText')
                });
            }
            return pickNotification(items);
        }

        function pollNotifications() {
            const name = controller.target();
            if (name !== shownTarget) { shownTarget = name; shown = ''; }
            // Cards showing the navigator's widget need no notification text.
            if (name === null || notifications === null || textCards === 0) { shown = ''; return; }
            const item = active(name);
            const lines = guidanceLines(item);
            const key = item === null ? '' : JSON.stringify([item.postTime, lines]);
            if (key === shown) return;
            shown = key;
            const notification = item === null ? null : retain(item.notification);
            Java.scheduleOnMainThread(() => {
                const next = item === null ? null : {name, lines, notification};
                if (disposed) { notification?.$dispose(); return; }
                try { controller.show(next); }
                catch (error) { report('render', error); }
                current?.notification?.$dispose();
                current = next;
            });
        }

        timer = setInterval(() => Java.perform(() => {
            try { if (configured() !== applied) refresh(); }
            catch (error) { report('refresh', error); }
        }), 1000);
        poll = setInterval(() => Java.perform(() => {
            try { pollNotifications(); }
            catch (error) {
                // Without notification access the card keeps the navigator's tile.
                report('notifications', error);
                notifications = null;
            }
        }), POLL_MS);

        Java.choose('com.qinggan.app.launcher.homecard.HomeNaviCardView', {
            onMatch(card) {
                const kept = Java.retain(card);
                Java.scheduleOnMainThread(() => {
                    try { controller.track(kept); }
                    catch (error) { report('track', error); }
                    finally { kept.$dispose(); }
                });
            },
            onComplete() {
                Java.scheduleOnMainThread(() => {
                    try {
                        if (disposed) return;
                        const name = configured();
                        controller.configure(name);
                        applied = name;
                        console.log('OVC_HOME_CARD_READY');
                    } catch (error) { report('install', error); }
                });
            }
        });
    } catch (error) {
        report('install', error);
        for (const hook of hooks) {
            try { hook.implementation = null; } catch (resetError) { report('rollback', resetError); }
        }
        if (timer !== null) clearInterval(timer);
        if (poll !== null) clearInterval(poll);
        return null;
    }
    return {
        /** UI thread only. Part of the panel's restore handshake before Controls detaches. */
        restoreAll() {
            // Re-read the configuration afterwards, even if it names the same app again.
            applied = undefined;
            shown = '';
            return controller.restoreAll();
        },
        dispose() {
            disposed = true;
            for (const hook of hooks) {
                try { hook.implementation = null; } catch (error) { report('rollback', error); }
            }
            clearInterval(timer);
            clearInterval(poll);
            Java.scheduleOnMainThread(() => {
                try { controller.restoreAll(); } catch (error) { report('rollback', error); }
                current?.notification?.$dispose();
                current = null;
            });
        }
    };
}
