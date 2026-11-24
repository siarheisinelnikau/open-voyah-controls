import { createReporter } from './report.js';

const SOURCE_PACKAGE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;
const SOURCE_CLASS = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z_$][A-Za-z0-9_$]*)+$/;
// AudioManager.STREAM_MUSIC, the stream of every media source the card shows.
const MEDIA_STREAM = 3;
const MEDIA_POLL_MS = 2000;
/**
 * Media sources of the launcher's media framework (`com.qinggan.media.helper.MediaEnum`) that no code path of this
 * launcher uses (checked in the decompiled 1.0.6 launcher: SYSTEM_BT_MUSIC has no reference, DAB is only added to the
 * configuration when `AppUtils.isSupportDAB()`, which always returns false). Each added app borrows one; its fields
 * point at the app while it does, and go back to these factory values afterwards. A borrowed slot gets a media id of
 * its own: the factory DAB shares 15 with WeCar Flow, and `MediaEnum.getMediaEnumById` must find the slot.
 */
const MEDIA_SLOTS = [
    {name: 'SYSTEM_BT_MUSIC', id: 9001, factory: {mediaId: 11, streamType: -2, pageName: '',
        serviceName: 'com.qinggan.media.btmusic.BTMediaBrowserService', servicePageName: 'com.android.bluetooth',
        clientId: 'com.qinggan.media.btmusic.BTMediaBrowserService'}},
    {name: 'DAB', id: 9002, factory: {mediaId: 15, streamType: 3, pageName: 'com.ex.dabplayer.pad',
        serviceName: 'com.ex.dabplayer.pad.service.DabMediaService', servicePageName: 'com.qinggan.media',
        clientId: 'com.ex.dabplayer.pad'}}
];
/**
 * Entries of the card's tab enum (`MediaResEnum97C`) with the source each stands for in the factory launcher. An
 * added app borrows an entry whose source the card does not support on this car (Kuwo without
 * `persist.app.kuwo.support`, the FM radio without a tuner antenna): its name and icon are replaced while it is shown,
 * and its source points at the borrowed slot. Entries are enum constants with real Java methods, so a tab left in the
 * list after the agent vanished keeps working instead of calling into a script that is gone.
 */
const MEDIA_TABS = [['LOCAL_RADIO', 'LOCAL_RADIO'], ['KW_MUSIC', 'KW_MUSIC'], ['WE_CAR', 'WECAR_FLOW']];

/**
 * The apps the home screen media card should offer besides its factory sources, in order, or null when the setting
 * is off. A restore request (the `restoreIcons` token Controls publishes before it detaches) always means off.
 */
export function mediaSources(config) {
    if (!config || config.schema !== 1 || config.restoreIcons !== undefined) return null;
    const list = config.mediaCard?.sources;
    if (!Array.isArray(list)) return null;
    const result = [];
    for (const item of list) {
        const name = item?.package, service = item?.service;
        if (typeof name !== 'string' || name.length > 128 || !SOURCE_PACKAGE.test(name)) continue;
        if (typeof service !== 'string' || service.length > 200 || !SOURCE_CLASS.test(service)) continue;
        if (result.some(source => source.package === name)) continue;
        result.push({package: name, service});
        if (result.length === MEDIA_SLOTS.length) break;
    }
    return result.length ? result : null;
}

/**
 * Pairs each available source with a borrowed slot and a free tab entry, in order. Sources beyond the free entries
 * are left out.
 */
export function assignSlots(sources, freeTabs) {
    const count = Math.min(sources.length, freeTabs.length, MEDIA_SLOTS.length);
    return sources.slice(0, count).map((source, index) => ({source, slot: MEDIA_SLOTS[index], tab: freeTabs[index]}));
}

/**
 * Whether the launcher's latest media audio focus belongs to the source: the OEM audio policy reports the package
 * that asked for focus, and the AudioManager client id carries the class of the app's focus listener.
 */
export function focusOwner(source, info) {
    if (!info || info.streamType !== MEDIA_STREAM) return false;
    if (info.packageName === source.package) return true;
    return typeof info.clientId === 'string' && info.clientId.includes(source.package + '.');
}

/**
 * Adds apps with a media browser service (Voyah Music, Voyah Radio) to the launcher's home screen media card
 * (`BigMediaView97cV2`). The card's sources are a fixed list in its code (`initMedia`), each a value of the media
 * framework's `MediaEnum` that its `MediaManager` connects to with a `MediaBrowserCompat`. Every added app borrows
 * a slot no code of this launcher uses (see MEDIA_SLOTS): the slot names the app's browser service, the framework connects
 * to it as to the factory services, and the card's own code shows the app's title and artist and drives its play,
 * pause, previous and next buttons. The agent puts the app's name and icon on its tab and title, the app's icon on the
 * cover, opens the app when the card is tapped, and recognizes the app's audio focus by its package (Media3 players
 * ask for focus with a listener of the library, so the client id does not always name the app).
 *
 * Off, or the restore handshake before Controls detaches, puts back the factory tabs, sources and slots.
 */
export function installHomeMediaCard(Java, {readConfig, launch, report = createReporter('OVC_HOME_MEDIA_ERROR')} = {}) {
    const hooks = [];
    const cards = [];
    let timer = null, disposed = false;
    // The sources the card shows now: [{source, slot, tab, value, entry, label, icon, locale}].
    let active = [];
    let activeKey = '';
    try {
        const MediaEnum = Java.use('com.qinggan.media.helper.MediaEnum');
        const MediaConfig = Java.use('com.qinggan.media.helper.MediaConfig');
        const BaseConfig = Java.use('com.qinggan.media.helper.impl.BaseConfigImpl');
        const Manager = Java.use('com.qinggan.app.mediaCentre.MediaManager');
        const Control = Java.use('com.qinggan.app.mediaCentre.manager.MediaControlManager');
        const Listener = Java.use('com.qinggan.app.mediaCentre.inter.QinMediaListener');
        const Policy = Java.use('com.qinggan.media.helper.AudioPolicyHelper');
        const Card = Java.use('com.pateo.voyah.mediaCard.home.h97cV2.BigMediaView97cV2');
        const Helper = Java.use('com.pateo.voyah.mediaCard.home.h97c.MediaInfoHelper97C');
        const Holder = Java.use('com.pateo.voyah.mediaCard.home.view.mediaTab97c.MediaTabAdapter97c$MediaTabHolder');
        const Bean = Java.use('com.pateo.voyah.mediaCard.home.inter.MediaBeanInter');
        const TabEnum = Java.use('com.pateo.voyah.mediaCard.home.enums.MediaResEnum97C');
        const ArrayList = Java.use('java.util.ArrayList');
        const LinkedList = Java.use('java.util.LinkedList');
        const Reference = Java.use('java.lang.ref.WeakReference');
        const ActivityThread = Java.use('android.app.ActivityThread');
        const Component = Java.use('android.content.ComponentName');
        const TextView = Java.use('android.widget.TextView');
        const JString = Java.use('java.lang.String');
        const initMedia = Card.initMedia.overload();
        const titleUi = Card.updateTitleUI.overload('com.pateo.voyah.mediaCard.home.inter.MediaBeanInter');
        const openPage = Card.openMediaPage.overload();
        const itemClick = Card.onItemClick.overload('com.pateo.voyah.mediaCard.home.inter.MediaBeanInter');
        const bindTab = Holder.bindView.overload('int');
        const cover = Helper.updateCoverUrl.overload('android.widget.ImageView', 'java.lang.String', 'com.qinggan.media.helper.MediaEnum');
        const focusWith = Policy.isMediaFocus.overload('com.qinggan.media.helper.MediaEnum', 'com.qinggan.audiopolicy.AudioPolicyInfo');
        const focusLatest = Policy.isMediaFocus.overload('com.qinggan.media.helper.MediaEnum');
        const listAdd = ArrayList.add.overload('java.lang.Object');
        const linkedRemove = LinkedList.remove.overload('java.lang.Object');
        const setText = TextView.setText.overload('java.lang.CharSequence');

        const app = () => ActivityThread.currentApplication();
        const text = value => (value === null || value === undefined ? '' : String(value));
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
        function sourceOfTab(bean) {
            return bean === null ? null : sourceOf(Java.cast(bean, Bean).getMediaEnum());
        }
        /** The app's name in the launcher's current language, read again after a language change. */
        function label(item) {
            const context = app();
            const locale = text(context.getResources().getConfiguration().getLocales().toLanguageTags());
            if (item.locale !== locale) {
                const pm = context.getPackageManager();
                item.label = text(pm.getApplicationLabel(pm.getApplicationInfo(item.source.package, 0)));
                item.locale = locale;
            }
            return JString.$new(item.label);
        }
        function icon(item) {
            if (item.icon === null) item.icon = app().getPackageManager().getApplicationIcon(item.source.package).getConstantState();
            return item.icon.newDrawable();
        }

        function live() {
            for (let i = cards.length - 1; i >= 0; i--) {
                if (cards[i].get() !== null) continue;
                cards[i].$dispose();
                cards.splice(i, 1);
            }
            return cards.map(ref => Java.cast(ref.get(), Card));
        }

        /** Shows the factory tabs plus the active sources' tabs, and lets the card's helper take the sources. */
        function present(card) {
            const helper = card.mediaInfoHelper.value;
            const support = helper.supportSet.value;
            for (const item of active) support.add(item.value);
            const tabs = card.binding.value.mediaTab.value;
            const shown = tabs.mediaTabAdapter.value.data.value;
            const list = ArrayList.$new();
            // The factory tabs never hold a borrowed entry (its source is unsupported here), so this is idempotent.
            for (let i = 0; i < shown.size(); i++) {
                const entry = shown.get(i);
                if (!active.some(item => item.entry.equals(entry))) listAdd.call(list, entry);
            }
            for (const item of active) listAdd.call(list, item.entry);
            tabs.fillData(list);
            // A card built while one of the apps plays missed the change: its helper did not take the source then.
            const current = Manager.getInstance().curMediaEnum.value;
            if (sourceOf(current) !== null && !current.equals(helper.getCurMediaEnum())) helper.updateMediaEnum(current);
        }

        /** Remembers a card; true when it was new. */
        function remember(card) {
            if (cards.some(ref => card.equals(ref.get()))) return false;
            cards.push(Java.retain(Reference.$new(card)));
            return true;
        }

        function track(card) {
            if (remember(card) && active.length > 0) present(card);
        }

        /** Tab entries the card does not use on this car, from a card helper's sources (before any is added). */
        function freeTabs(card) {
            const support = card.mediaInfoHelper.value.supportSet.value;
            return MEDIA_TABS.filter(([, factory]) => !support.contains(MediaEnum[factory].value)).map(([name]) => name);
        }

        function available(source) {
            try {
                app().getPackageManager().getServiceInfo(Component.$new(source.package, source.service), 0);
                return true;
            } catch (_) { return false; }
        }

        function activate(sources) {
            const cardsNow = live();
            if (cardsNow.length === 0) return false;
            const settings = config();
            const manager = Manager.getInstance();
            const context = app().getApplicationContext();
            for (const {source, slot, tab} of assignSlots(sources, freeTabs(cardsNow[0]))) {
                const value = MediaEnum[slot.name].value;
                const item = {source, slot, tab, value, entry: TabEnum[tab].value, label: '', locale: null, icon: null};
                value.mediaId.value = slot.id;
                value.serviceName.value = source.service;
                value.servicePageName.value = source.package;
                value.pageName.value = source.package;
                value.clientId.value = source.package + '.';
                value.streamType.value = MEDIA_STREAM;
                item.entry.mediaEnum.value = value;
                settings.mediaEnums.value.add(value);
                settings.controlEnums.value.add(value);
                active.push(item);
                // As MediaManager.initMediaControl does for the factory sources; it connects at once.
                const control = Control.$new(context, value);
                control.addQinMediaListener(manager);
                manager.addMediaControl(control);
            }
            for (const card of cardsNow) {
                try { present(card); } catch (error) { report('present', error); }
            }
            return true;
        }

        /**
         * Puts the factory slots, tab entries, sources and tabs back. Also run at installation: an agent that vanished
         * without restoring leaves borrowed slots behind, and the factory values are known, not saved.
         */
        function deactivate() {
            const borrowed = MEDIA_SLOTS.map(slot => MediaEnum[slot.name].value);
            const manager = Manager.getInstance();
            let restored = true;
            const step = (stage, fn) => {
                try { fn(); } catch (error) { restored = false; report(stage, error); }
            };
            for (const value of borrowed) {
                step('control', () => {
                    const control = manager.controlMap.value.remove(value);
                    if (control !== null) Java.cast(control, Control).getMediaBrowserHelper().onStop();
                    manager.mediaInfoMap.value.remove(value);
                    manager.playMap.value.remove(value);
                    manager.playStateMap.value.remove(value);
                    linkedRemove.call(manager.linkedList.value, value);
                    if (value.equals(manager.curMediaEnum.value)) manager.curMediaEnum.value = MediaEnum.NO.value;
                });
                step('config', () => {
                    const settings = config();
                    settings.mediaEnums.value.remove(value);
                    settings.controlEnums.value.remove(value);
                });
            }
            active = [];
            activeKey = '';
            step('slots', () => {
                for (const slot of MEDIA_SLOTS) {
                    const value = MediaEnum[slot.name].value;
                    for (const [field, factory] of Object.entries(slot.factory)) value[field].value = factory;
                }
                for (const [name, factory] of MEDIA_TABS) TabEnum[name].value.mediaEnum.value = MediaEnum[factory].value;
            });
            for (const card of live()) {
                step('restore', () => {
                    const helper = card.mediaInfoHelper.value;
                    const support = helper.supportSet.value;
                    let shown = false;
                    for (const value of borrowed) {
                        support.remove(value);
                        if (value.equals(helper.getCurMediaEnum())) shown = true;
                    }
                    // The factory tabs again; the hook adds nothing now.
                    card.initMedia();
                    if (!shown) return;
                    // As after a start without media: the last factory source, else the default one.
                    helper.setCurMediaEnum(MediaEnum.NO.value);
                    helper.clearMediaInfo();
                    Java.cast(helper.listener.value, Listener).onNoMediaEnum();
                });
            }
            return restored;
        }

        function openApp(context, item) {
            const intent = context.getPackageManager().getLaunchIntentForPackage(item.source.package);
            const component = intent === null ? null : intent.getComponent();
            if (component === null) return false;
            return launch(context, text(component.flattenToString())) !== false;
        }

        // A card builds its tabs once, when it is created (a theme or density change creates new cards).
        initMedia.implementation = function () {
            const result = initMedia.call(this);
            try {
                remember(this);
                if (active.length > 0) present(this);
            } catch (error) { report('track', error); }
            return result;
        };
        hooks.push(initMedia);
        titleUi.implementation = function (bean) {
            const result = titleUi.call(this, bean);
            try {
                const item = sourceOfTab(bean);
                if (item !== null) {
                    const binding = this.binding.value;
                    setText.call(binding.tvMediaName.value, label(item));
                    binding.mediaIcon.value.setImageDrawable(icon(item));
                }
            } catch (error) { report('title', error); }
            return result;
        };
        hooks.push(titleUi);
        bindTab.implementation = function (position) {
            const result = bindTab.call(this, position);
            try {
                const item = sourceOfTab(this.mediaBean.value);
                if (item !== null) {
                    setText.call(this.tvName.value, label(item));
                    this.ivIcon.value.setImageDrawable(icon(item));
                }
            } catch (error) { report('tab', error); }
            return result;
        };
        hooks.push(bindTab);
        cover.implementation = function (view, url, value) {
            const item = active.length === 0 ? null : sourceOf(value);
            if (item !== null) {
                try {
                    view.setImageDrawable(icon(item));
                    this.imageUrl.value = url;
                    return;
                } catch (error) { report('cover', error); }
            }
            return cover.call(this, view, url, value);
        };
        hooks.push(cover);
        openPage.implementation = function () {
            try {
                const item = active.length === 0 ? null : sourceOf(this.mediaInfoHelper.value.getCurMediaEnum());
                if (item !== null && openApp(this.getContext(), item)) return;
            } catch (error) { report('open', error); }
            return openPage.call(this);
        };
        hooks.push(openPage);
        // A tab of an app the launcher could not connect to (not running, or just installed) opens the app instead.
        itemClick.implementation = function (bean) {
            try {
                const item = active.length === 0 ? null : sourceOfTab(bean);
                if (item !== null) {
                    const control = Manager.getInstance().controlMap.value.get(item.value);
                    const helper = control === null ? null : Java.cast(control, Control).getMediaBrowserHelper();
                    if (helper === null || !helper.isConnected()) {
                        helper?.reConnect();
                        this.binding.value.flRcy.value.setVisibility(8);
                        if (openApp(this.getContext(), item)) return;
                    }
                }
            } catch (error) { report('click', error); }
            return itemClick.call(this, bean);
        };
        hooks.push(itemClick);
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

        function apply() {
            const context = app();
            if (context === null) return;
            const wanted = (mediaSources(readConfig(context)) ?? []).filter(available);
            const key = JSON.stringify(wanted);
            if (key === activeKey) return;
            if (active.length > 0 || activeKey !== '') deactivate();
            if (wanted.length === 0) return;
            try {
                if (activate(wanted)) activeKey = key;
            } catch (error) {
                // Back to the factory card; the same configuration is not tried again until it changes.
                report('activate', error);
                deactivate();
                activeKey = key;
            }
        }

        timer = setInterval(() => Java.perform(() => {
            if (disposed) return;
            Java.scheduleOnMainThread(() => {
                try { if (!disposed) apply(); }
                catch (error) { report('refresh', error); }
            });
        }), MEDIA_POLL_MS);

        Java.choose('com.pateo.voyah.mediaCard.home.h97cV2.BigMediaView97cV2', {
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
                        // Slots a vanished predecessor borrowed go back before this agent borrows them again.
                        deactivate();
                        apply();
                        console.log('OVC_HOME_MEDIA_READY');
                    } catch (error) { report('install', error); }
                });
            }
        });

        return {
            /** UI thread only. Part of the panel's restore handshake before Controls detaches. */
            restoreAll() {
                if (active.length === 0 && activeKey === '') return true;
                return deactivate();
            },
            dispose() {
                disposed = true;
                clearInterval(timer);
                for (const hook of hooks) {
                    try { hook.implementation = null; } catch (error) { report('rollback', error); }
                }
                Java.scheduleOnMainThread(() => {
                    try { if (active.length > 0) deactivate(); } catch (error) { report('rollback', error); }
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
