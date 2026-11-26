import { createReporter } from './report.js';
import { MEDIA_SLOTS, borrowSlot, focusOwner, mediaSources, returnSlot } from './media-slots.js';
import { createDriverMediaLayer, driverMediaText } from './driver-media-layout.js';

// Every tick reads the configuration and moves the progress ring of a playing app.
const TICK_MS = 1000;
// Whether an app is installed is read again at most this often (a PackageManager call on the card's UI thread).
const INSTALL_CHECK_MS = 15000;
// The card's source when it leaves an app and no factory source was heard before.
const FALLBACK_SOURCE = 'BT_MUSIC';
// MediaMemoryManager's preferences file and its key for the last source; the singleton is not created for them.
const MEMORY_PREFS = 'media_memory_name';
const MEMORY_KEY = 'key_media_type';
// MusicBaseView's handler message that puts the default disc picture on the cover 3 s after an empty cover.
const DEFAULT_COVER = 1;
// android.media.session.PlaybackState.STATE_PLAYING.
const STATE_PLAYING = 3;
const DURATION = 'android.media.metadata.DURATION';
// Bitmaps an app's session may carry, in order of preference: Voyah Radio sends its station picture as ART.
const ARTWORK = ['android.media.metadata.ALBUM_ART', 'android.media.metadata.ART', 'android.media.metadata.DISPLAY_ICON'];
// The restore token Controls publishes before it stops the agents (FridaBridge.restorePanelIcons).
const RESTORE_TOKEN = /^[0-9a-f]{32}$/;

/**
 * The apps the instrument cluster's media card should show besides its factory sources, or null for the factory
 * card. A restore request (the `restoreIcons` token Controls publishes before it detaches) always means the factory
 * card.
 */
export function clusterCardSetting(config) {
    if (!config || config.schema !== 1 || config.restoreIcons !== undefined) return null;
    const card = config.clusterCard;
    if (!card || typeof card !== 'object') return null;
    const sources = mediaSources(card.sources);
    if (sources.length === 0) return null;
    return card.driverLayout === true ? {sources, driverLayout: true} : {sources};
}

/**
 * Where a session's track is now, in milliseconds of its duration, from the position its last playback state
 * reported; null for a stream without a duration (a radio station).
 */
export function trackProgress({duration, position, updated, speed, playing}, now) {
    if (!Number.isFinite(duration) || duration <= 0 || duration > 0x7fffffff) return null;
    let at = Number.isFinite(position) ? position : 0;
    if (playing && Number.isFinite(updated) && updated > 0 && now > updated) at += (now - updated) * (Number.isFinite(speed) ? speed : 1);
    return {position: Math.round(Math.min(Math.max(at, 0), duration)), duration: Math.round(duration)};
}

/**
 * Lets the instrument cluster's media card (`com.qinggan.instrumentcard`, drawn on the cluster's media display) show
 * Voyah Music and Voyah Radio. The card follows the latest media audio focus among its factory sources through the
 * same media framework as the home screen card: `AudioPolicyHelper.notifyFocusChanged` picks the `MediaEnum` whose
 * focus check matches and `MediaManager` makes it the card's source; an app the framework does not know leaves the
 * card on the factory source heard before (Bluetooth music on the reviewed car). Every added app borrows a slot no
 * code of the card uses (see MEDIA_SLOTS): the slot names the app's browser service, `MediaManager` connects to it as
 * to the factory services, the agent recognizes the app's audio focus by its package, and the card's own views show
 * the app's title, artist and play state. The agent puts the app's name and icon on the card's title and source
 * badge, the session's picture (else the app icon) on the cover, and moves the progress ring for tracks with a
 * duration.
 *
 * Off, or the restore token before Controls detaches, puts the factory slots back and returns the card to a factory
 * source; the token is answered with `OVC_CLUSTER_CARD_RESTORED <token>` once that is done.
 */
export function installClusterMediaCard(Java, {readConfig, report = createReporter('OVC_CLUSTER_CARD_ERROR')} = {}) {
    const hooks = [];
    // Every control this agent created, so a failed step can never leave one connecting forever.
    const controls = [];
    let timer = null, disposed = false;
    // The apps the card offers now: [{source, slot, value, control, label, locale, icon, duration}].
    let active = [];
    let activeKey = '';
    // A restore that failed somewhere is tried again on the next tick.
    let dirty = false;
    // The last restore token this agent answered.
    let answered = '';
    let driverLayout = false, driverLayer = null, driverLayerChecked = false;
    const installed = new Map();
    try {
        const MediaEnum = Java.use('com.qinggan.media.helper.MediaEnum');
        const MediaConfig = Java.use('com.qinggan.media.helper.MediaConfig');
        const BaseConfig = Java.use('com.qinggan.media.helper.impl.BaseConfigImpl');
        const Manager = Java.use('com.qinggan.app.mediaCentre.MediaManager');
        const Control = Java.use('com.qinggan.app.mediaCentre.manager.MediaControlManager');
        const Policy = Java.use('com.qinggan.media.helper.AudioPolicyHelper');
        const BaseView = Java.use('com.qinggan.app.mediaCentre.view.BaseMediaView');
        const MusicView = Java.use('com.qinggan.cardview.panel.media.MusicBaseView');
        const Screen = Java.use('com.qinggan.instrumentcard.ScreenActivity');
        const Framework = Java.use('com.qinggan.app.service.utils.ContextUtils');
        const ArrayList = Java.use('java.util.ArrayList');
        const LinkedList = Java.use('java.util.LinkedList');
        const ActivityThread = Java.use('android.app.ActivityThread');
        const Component = Java.use('android.content.ComponentName');
        const TextView = Java.use('android.widget.TextView');
        const JString = Java.use('java.lang.String');
        const Clock = Java.use('android.os.SystemClock');
        const sources = BaseView.getMediaEnums.overload();
        const update = MusicView.updateView.overload('com.qinggan.media.helper.MediaEnum',
            'com.qinggan.media.helper.base.bean.QinMediaInfo');
        const coverUrl = MusicView.updateUrlImage.overload('com.qinggan.media.helper.MediaEnum', 'java.lang.String');
        const focusWith = Policy.isMediaFocus.overload('com.qinggan.media.helper.MediaEnum', 'com.qinggan.audiopolicy.AudioPolicyInfo');
        const focusLatest = Policy.isMediaFocus.overload('com.qinggan.media.helper.MediaEnum');
        const listOf = ArrayList.$new.overload('java.util.Collection');
        const listAdd = ArrayList.add.overload('java.lang.Object');
        const listRemove = ArrayList.remove.overload('java.lang.Object');
        const linkedRemove = LinkedList.remove.overload('java.lang.Object');
        const setText = TextView.setText.overload('java.lang.CharSequence');

        const app = () => ActivityThread.currentApplication();
        /**
         * Whether the card has set up its media framework (its first PanelView does; nothing resets it). Before that
         * MediaManager's class initializer would run without a context and leave the class broken for the card, and
         * the framework's enum may not even be initialized, so nothing here touches the framework until then.
         */
        const ready = () => Framework.context.value !== null;
        const text = value => (value === null || value === undefined ? '' : String(value));
        const borrowed = () => MEDIA_SLOTS.map(slot => MediaEnum[slot.name].value);
        const isAny = (values, value) => value !== null && values.some(item => item.equals(value));
        /** The framework's configuration; each new PanelView installs a fresh one, so it is read every time. */
        const config = () => {
            const result = Java.cast(MediaConfig.getInstance().getMediaConfigInter(), BaseConfig);
            // Both sets fill on their first read.
            result.getSupportMedias();
            result.getControlMedias();
            return result;
        };

        function sourceOf(value) {
            if (value === null || active.length === 0) return null;
            return active.find(item => item.value.equals(value)) ?? null;
        }
        /** The app's name in the card's current language, read again after a language change. */
        function label(item) {
            const context = app();
            const locale = text(context.getResources().getConfiguration().getLocales().toLanguageTags());
            if (item.locale !== locale) {
                const pm = context.getPackageManager();
                item.label = text(pm.getApplicationLabel(pm.getApplicationInfo(item.source.package, 0)));
                item.locale = locale;
            }
            return item.label;
        }
        function icon(item) {
            if (item.icon === null) item.icon = app().getPackageManager().getApplicationIcon(item.source.package).getConstantState();
            return item.icon.newDrawable();
        }
        /** The app's session controller, or null while the card is not connected to it. */
        function controller(item) {
            const helper = item.control.getMediaBrowserHelper();
            return helper.isConnected() ? helper.getMediaController() : null;
        }
        function artwork(metadata) {
            if (metadata === null) return null;
            for (const key of ARTWORK) {
                const bitmap = metadata.getBitmap(key);
                if (bitmap !== null) return bitmap;
            }
            return null;
        }
        /**
         * The track's progress from the session's playback state and the duration its metadata gave at the last view
         * update; the metadata, with its pictures, is not read again every second.
         */
        function progress(session, item) {
            const state = session === null ? null : session.getPlaybackState();
            if (state === null) return null;
            return trackProgress({duration: item.duration, position: Number(state.getPosition()),
                updated: Number(state.getLastPositionUpdateTime()), speed: Number(state.getPlaybackSpeed()),
                playing: state.getState() === STATE_PLAYING}, Number(Clock.elapsedRealtime()));
        }

        /** The music view the cluster shows now, or null (no card, or the factory radio or empty view). */
        function shown() {
            const screen = Screen.screenActivity.value;
            const panel = screen === null ? null : screen.panelMusicView.value;
            if (panel === null || panel.getChildCount() === 0) return null;
            const child = panel.getChildAt(0);
            return MusicView.class.isInstance(child) ? Java.cast(child, MusicView) : null;
        }

        /** Puts the app's name, icon, picture and progress on a view whose source is an app. */
        function decorate(view) {
            const item = sourceOf(view.getCurMediaEnum());
            if (item === null) return;
            setText.call(view.mTvTitle.value, JString.$new(label(item)));
            view.mIvType.value.setImageDrawable(icon(item));
            const session = controller(item);
            const metadata = session === null ? null : session.getMetadata();
            item.duration = metadata === null ? 0 : Number(metadata.getLong(DURATION));
            item.driverTrack = driverLayout ? driverTrack(metadata) : undefined;
            item.artworkRevision += 1;
            const picture = artwork(metadata);
            if (picture !== null) view.mIvCover.value.setImageBitmap(picture);
            else view.mIvCover.value.setImageDrawable(icon(item));
            // An empty cover asked for the default disc 3 s later; the picture stays instead.
            view.handler.value.removeMessages(DEFAULT_COVER);
            const where = progress(session, item);
            if (where === null) view.updateProgressView(false);
            else view.updateProgress(where.position, where.duration, item.value);
        }

        function driverTrack(metadata) {
            const value = key => driverMediaText(metadata?.getString('android.media.metadata.' + key));
            const title = value('DISPLAY_TITLE') || value('TITLE');
            return title ? {title, artist: value('DISPLAY_SUBTITLE') || value('ARTIST')} : null;
        }

        function layer() {
            if (driverLayerChecked) return driverLayer;
            driverLayerChecked = true;
            try { driverLayer = createDriverMediaLayer(Java); }
            catch (error) { if (driverLayout) report('driver_layout', error); }
            return driverLayer;
        }

        function updateDriver(view, item, session, where) {
            const currentLayer = layer();
            if (currentLayer === null) return;
            try {
                if (!driverLayout || item === null || session === null ||
                    focus(item.value, Policy.getInstance(app()).getLatestMediaInfo()) !== true) {
                    currentLayer.clear(view);
                    return;
                }
                if (item.driverTrack === undefined) item.driverTrack = driverTrack(session.getMetadata());
                const track = item.driverTrack;
                currentLayer.update(view, track === null ? null : {...track, source: label(item), progress: where,
                    cover: view.mIvCover.value.getDrawable(), artworkRevision: item.artworkRevision});
            } catch (error) {
                report('driver_layout', error);
                try { currentLayer.clear(); } catch (restoreError) { report('driver_restore', restoreError); }
            }
        }

        /**
         * The view the cluster shows, once a second while it shows an app: the progress ring moves, and the title the
         * card resets to its factory word after a language change (MusicBaseView.onConfigurationChanged) comes back.
         */
        function refreshShown() {
            if (!ready()) return;
            const view = shown();
            const item = view === null ? null : sourceOf(view.getCurMediaEnum());
            if (item === null) { updateDriver(view, null, null, null); return; }
            const name = label(item);
            if (text(view.mTvTitle.value.getText()) !== name) setText.call(view.mTvTitle.value, JString.$new(name));
            const session = controller(item);
            const where = progress(session, item);
            if (where !== null) view.updateProgress(where.position, where.duration, item.value);
            updateDriver(view, item, session, where);
        }

        function available(source, now) {
            const known = installed.get(source.package);
            if (known !== undefined && now - known.at < INSTALL_CHECK_MS) return known.value;
            let value = false;
            try {
                app().getPackageManager().getServiceInfo(Component.$new(source.package, source.service), 0);
                value = true;
            } catch (_) { /* Not installed, or without the service. */ }
            installed.set(source.package, {value, at: now});
            return value;
        }

        /** A new PanelView brings a configuration without the slots; they go into it again. */
        function keepConfigured() {
            if (active.length === 0) return;
            const settings = config();
            for (const item of active) {
                if (!settings.mediaEnums.value.contains(item.value)) settings.mediaEnums.value.add(item.value);
                if (!settings.controlEnums.value.contains(item.value)) settings.controlEnums.value.add(item.value);
            }
        }

        /**
         * Lets the factory focus path choose the card's source again (an app that already plays, or after off). Not
         * while the audio policy reports no media at all: the helper would then clear the card to its empty view.
         */
        function refocus() {
            const policy = Policy.getInstance(app());
            if (policy.getLatestMediaInfo() !== null) policy.notifyFocusChanged();
        }

        function activate(setting) {
            const manager = Manager.getInstance();
            const context = app().getApplicationContext();
            const settings = config();
            setting.sources.forEach((source, index) => {
                const slot = MEDIA_SLOTS[index];
                const value = MediaEnum[slot.name].value;
                borrowSlot(value, slot, source);
                // MediaBrowserHelper.connectService connects only a source the configuration supports, and the
                // control connects as it is created.
                settings.mediaEnums.value.add(value);
                settings.controlEnums.value.add(value);
                active.push({source, slot, value, control: null, label: '', locale: null, icon: null, duration: 0,
                    driverTrack: undefined, artworkRevision: 0});
                // As MediaManager.initMediaControl does for the factory sources.
                const control = Java.retain(Control.$new(context, value));
                controls.push(control);
                active[active.length - 1].control = control;
                control.addQinMediaListener(manager);
                manager.addMediaControl(control);
            });
            refocus();
        }

        /** The factory source the card should show instead of an app: the one heard most recently, else Bluetooth. */
        function factorySource(manager, slots) {
            const candidates = listOf.call(ArrayList, config().getSupportMedias());
            for (const value of slots) listRemove.call(candidates, value);
            const recent = manager.getMyMediaEnum(candidates);
            return recent !== null && !recent.equals(MediaEnum.NO.value) ? recent : MediaEnum[FALLBACK_SOURCE].value;
        }

        /** The card's remembered last source must not name a slot: it would come back as a factory source. */
        function forgetSlots(slots) {
            const memory = app().getSharedPreferences(MEMORY_PREFS, 0);
            const saved = text(memory.getString(MEMORY_KEY, 'NO'));
            if (slots.some(value => text(value.toString()) === saved)) memory.edit().putString(MEMORY_KEY, FALLBACK_SOURCE).apply();
        }

        /**
         * Puts the factory slots back and returns the card to a factory source. Also run at installation: an agent
         * that vanished without restoring leaves borrowed slots behind, and the factory values are known, not saved.
         */
        function deactivate() {
            active = [];
            activeKey = '';
            // Nothing can borrow a slot before the framework exists, and it never goes away again.
            if (!ready()) {
                dirty = false;
                return true;
            }
            let restored = true;
            const step = (stage, fn) => {
                try { fn(); } catch (error) { restored = false; report(stage, error); }
            };
            step('driver_restore', () => layer()?.clear(shown()));
            const slots = borrowed();
            const ids = MEDIA_SLOTS.map(slot => slot.id);
            let manager = null, shownApp = false;
            step('control', () => {
                manager = Manager.getInstance();
                // The view keeps its own source, which can be an app while MediaManager follows a source it ignores.
                const view = shown();
                shownApp = isAny(slots, manager.curMediaEnum.value) || (view !== null && isAny(slots, view.getCurMediaEnum()));
            });
            // The hooks are inert now (no active apps), and the slots stop matching the apps' audio focus, so stopping
            // a connection below cannot make a slot the current source again.
            step('slots', () => { for (const slot of MEDIA_SLOTS) returnSlot(MediaEnum[slot.name].value, slot); });
            if (manager !== null) restoreFramework(manager, slots, ids, shownApp, step);
            step('memory', () => forgetSlots(slots));
            dirty = !restored;
            return restored;
        }

        function restoreFramework(manager, slots, ids, shownApp, step) {
            const forget = () => {
                if (isAny(slots, manager.curMediaEnum.value)) manager.curMediaEnum.value = MediaEnum.NO.value;
                for (const value of slots) linkedRemove.call(manager.linkedList.value, value);
            };
            // A stopping connection reports its last state to MediaManager at once; while a slot is still current
            // that report makes it current again and remembers it.
            step('control', forget);
            for (const value of slots) {
                step('control', () => {
                    const control = manager.controlMap.value.remove(value);
                    if (control !== null && !controls.some(known => known.equals(control))) {
                        Java.cast(control, Control).getMediaBrowserHelper().onStop();
                    }
                });
            }
            while (controls.length > 0) {
                const control = controls.pop();
                step('control', () => control.getMediaBrowserHelper().onStop());
                try { control.$dispose(); } catch (_) { }
            }
            step('control', forget);
            for (const value of slots) {
                step('control', () => {
                    // Stopping a connection writes the slot's last state; it goes after the stop.
                    manager.mediaInfoMap.value.remove(value);
                    manager.playMap.value.remove(value);
                    manager.playStateMap.value.remove(value);
                });
                step('config', () => {
                    const settings = config();
                    settings.mediaEnums.value.remove(value);
                    settings.controlEnums.value.remove(value);
                });
            }
            step('focus', () => {
                // The policy helper reports a source only when it changes; a slot id it still holds would hide the next.
                const policy = Policy.getInstance(app());
                if (ids.includes(policy.mLatestMedia.value)) policy.mLatestMedia.value = -1;
            });
            if (shownApp) {
                step('restore', () => {
                    const target = factorySource(manager, slots);
                    manager.onMediaTypeChange(target, manager);
                });
            }
            // A factory source that holds the focus now takes the card.
            step('restore', refocus);
        }

        // The card's views accept only the sources in this list; the apps join the factory ones while they are on.
        sources.implementation = function () {
            const result = sources.call(this);
            if (active.length === 0) return result;
            try {
                const list = listOf.call(ArrayList, result);
                for (const item of active) if (!list.contains(item.value)) listAdd.call(list, item.value);
                return list;
            } catch (error) {
                report('sources', error);
                return result;
            }
        };
        hooks.push(sources);
        update.implementation = function (value, info) {
            const result = update.call(this, value, info);
            try { if (active.length > 0) decorate(this); } catch (error) { report('decorate', error); }
            return result;
        };
        hooks.push(update);
        // The factory card loads a session's media URI as a cover image; an app's URI may be its stream.
        coverUrl.implementation = function (value, url) {
            try {
                if (active.length > 0 && sourceOf(value) !== null) return;
            } catch (error) { report('cover', error); }
            return coverUrl.call(this, value, url);
        };
        hooks.push(coverUrl);
        function focus(value, info) {
            const item = sourceOf(value);
            if (item === null) return null;
            if (info === null) return false;
            return focusOwner(item.source, {streamType: info.getStreamType(), packageName: text(info.getPackageName()),
                clientId: text(info.getClientId())});
        }
        focusWith.implementation = function (value, info) {
            try {
                const owned = active.length === 0 ? null : focus(value, info);
                if (owned !== null) return owned;
            } catch (error) { report('focus', error); }
            return focusWith.call(this, value, info);
        };
        hooks.push(focusWith);
        focusLatest.implementation = function (value) {
            try {
                if (active.length > 0 && sourceOf(value) !== null) {
                    return focus(value, Policy.getInstance(app()).getLatestMediaInfo()) === true;
                }
            } catch (error) { report('focus', error); }
            return focusLatest.call(this, value);
        };
        hooks.push(focusLatest);

        function sync(setting, now) {
            const wanted = setting === null ? [] : setting.sources.filter(source => available(source, now));
            const key = wanted.length === 0 ? '' : JSON.stringify(wanted);
            if (key === activeKey && !dirty) {
                keepConfigured();
                return;
            }
            if (active.length > 0 || activeKey !== '' || dirty) deactivate();
            if (key === '' || dirty) return;
            try {
                activate({sources: wanted});
                activeKey = key;
            } catch (error) {
                // Back to the factory card; the same configuration is not tried again until it changes.
                report('activate', error);
                deactivate();
                activeKey = key;
            }
        }

        function apply(now) {
            const context = app();
            if (context === null) return;
            const configuration = readConfig(context);
            const setting = clusterCardSetting(configuration);
            driverLayout = setting?.driverLayout === true;
            if (ready()) sync(setting, now);
            // Controls stops this agent right after the launcher confirms the same token, and waits for this answer.
            const token = configuration?.restoreIcons;
            if (typeof token === 'string' && RESTORE_TOKEN.test(token) && token !== answered && active.length === 0 && !dirty) {
                answered = token;
                console.log('OVC_CLUSTER_CARD_RESTORED ' + token);
            }
        }

        timer = setInterval(() => Java.perform(() => {
            if (disposed) return;
            Java.scheduleOnMainThread(() => {
                if (disposed) return;
                try { apply(Date.now()); } catch (error) { report('refresh', error); }
                try { refreshShown(); } catch (error) { report('progress', error); }
            });
        }), TICK_MS);

        Java.scheduleOnMainThread(() => {
            try {
                if (disposed) return;
                // Slots a vanished predecessor borrowed go back before this agent borrows them again.
                deactivate();
                apply(Date.now());
                console.log('OVC_CLUSTER_CARD_READY');
            } catch (error) { report('install', error); }
        });

        return {
            /** UI thread only. */
            restoreAll() {
                if (active.length === 0 && activeKey === '' && !dirty) {
                    if (ready()) layer()?.clear(shown());
                    return true;
                }
                return deactivate();
            },
            dispose() {
                disposed = true;
                clearInterval(timer);
                // The factory card first, while the hooks still keep a slot's source accepted.
                Java.scheduleOnMainThread(() => {
                    try {
                        if (active.length > 0 || dirty) deactivate();
                        else if (ready()) layer()?.clear(shown());
                    }
                    catch (error) { report('rollback', error); }
                    for (const hook of hooks) {
                        try { hook.implementation = null; } catch (error) { report('rollback', error); }
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
