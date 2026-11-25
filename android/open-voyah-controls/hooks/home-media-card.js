import { createReporter } from './report.js';
import { MEDIA_SLOTS, borrowSlot, focusOwner, mediaSources, returnSlot } from './media-slots.js';

const MEDIA_POLL_MS = 2000;
// Whether an app is installed is read again at most this often (a PackageManager call on the launcher's UI thread).
const MEDIA_INSTALL_CHECK_MS = 15000;
/**
 * Entries of the card's tab enum (`MediaResEnum97C`) with the source each stands for in the factory launcher. An
 * added app borrows an entry whose source the card does not support on this car (Kuwo without
 * `persist.app.kuwo.support`, the FM radio without a tuner antenna): its name and icon are replaced while it is shown,
 * and its source points at the borrowed slot. Entries are enum constants with real Java methods, so a tab left in the
 * list after the agent vanished keeps working instead of calling into a script that is gone.
 */
const MEDIA_TABS = [['LOCAL_RADIO', 'LOCAL_RADIO'], ['KW_MUSIC', 'KW_MUSIC'], ['WE_CAR', 'WECAR_FLOW']];
/**
 * Factory sources the card may hide: the Chinese online services (Tencent WeCar Flow, shown as "Funn Audio", Kuwo,
 * Ximalaya, Yunting), which do not work outside China. Bluetooth and USB music always stay.
 */
const HIDEABLE_SOURCES = ['WECAR_FLOW', 'KW_MUSIC', 'XMLA_MUSIC', 'RADIO_YUNTING'];
// The source the card falls back to when its default (WeCar Flow or Kuwo) is hidden.
const FALLBACK_SOURCE = 'BT_MUSIC';
// MediaMemoryManager's preference for the last source (`MediaConstants.CommonParams.KEY_MEDIA_TYPE`).
const MEMORY_KEY = 'key_media_type';

/**
 * What the home screen media card should change, or null for the factory card: the apps to offer besides its factory
 * sources, in order (`sources`), and the factory sources to hide (`hide`). A restore request (the `restoreIcons`
 * token Controls publishes before it detaches) always means the factory card.
 */
export function mediaSetting(config) {
    if (!config || config.schema !== 1 || config.restoreIcons !== undefined) return null;
    const card = config.mediaCard;
    if (!card || typeof card !== 'object') return null;
    const sources = mediaSources(card.sources);
    const hide = HIDEABLE_SOURCES.filter(name => Array.isArray(card.hide) && card.hide.includes(name));
    return sources.length || hide.length ? {sources, hide} : null;
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
 * Adds apps with a media browser service (Voyah Music, Voyah Radio) to the launcher's home screen media card
 * (`BigMediaView97cV2`) and hides the factory sources that do not work outside China. The card's sources are a fixed
 * list in its code (`initMedia`), each a value of the media framework's `MediaEnum` that its `MediaManager` connects
 * to with a `MediaBrowserCompat`. Every added app borrows a slot no code of this launcher uses (see MEDIA_SLOTS): the
 * slot names the app's browser service, the framework connects to it as to the factory services, and the card's own
 * code shows the app's title and artist and drives its play, pause, previous and next buttons. The agent puts the
 * app's name and icon on its tab and title, the app's icon on the cover, opens the app when the card is tapped, and
 * recognizes the app's audio focus by its package (Media3 players ask for focus with a listener of the library, so the
 * client id does not always name the app).
 *
 * Off, or the restore handshake before Controls detaches, puts back the factory tabs, sources and slots.
 */
export function installHomeMediaCard(Java, {readConfig, launch, report = createReporter('OVC_HOME_MEDIA_ERROR')} = {}) {
    const hooks = [];
    const cards = [];
    // Every control this agent created, so a failed step can never leave one connecting forever.
    const controls = [];
    let timer = null, disposed = false;
    // The sources the card shows now: [{source, slot, tab, value, entry, label, icon, locale}].
    let active = [];
    // MediaEnum values of the hidden factory sources.
    let hidden = [];
    let activeKey = '';
    // A restore that failed somewhere is tried again on the next poll.
    let dirty = false;
    const installed = new Map();
    try {
        const MediaEnum = Java.use('com.qinggan.media.helper.MediaEnum');
        const MediaConfig = Java.use('com.qinggan.media.helper.MediaConfig');
        const BaseConfig = Java.use('com.qinggan.media.helper.impl.BaseConfigImpl');
        const Manager = Java.use('com.qinggan.app.mediaCentre.MediaManager');
        const Memory = Java.use('com.qinggan.app.mediaCentre.manager.MediaMemoryManager');
        const Control = Java.use('com.qinggan.app.mediaCentre.manager.MediaControlManager');
        const Policy = Java.use('com.qinggan.media.helper.AudioPolicyHelper');
        const Card = Java.use('com.pateo.voyah.mediaCard.home.h97cV2.BigMediaView97cV2');
        const Helper = Java.use('com.pateo.voyah.mediaCard.home.h97c.MediaInfoHelper97C');
        const BaseHelper = Java.use('com.qinggan.app.mediaCentre.widget.BaseWidgetHelper');
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
        const defaultSource = BaseHelper.getDefaultMediaEnum.overload();
        const focusWith = Policy.isMediaFocus.overload('com.qinggan.media.helper.MediaEnum', 'com.qinggan.audiopolicy.AudioPolicyInfo');
        const focusLatest = Policy.isMediaFocus.overload('com.qinggan.media.helper.MediaEnum');
        const listOf = ArrayList.$new.overload('java.util.Collection');
        const listAdd = ArrayList.add.overload('java.lang.Object');
        const listRemove = ArrayList.remove.overload('java.lang.Object');
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
        const borrowedValues = () => MEDIA_SLOTS.map(slot => MediaEnum[slot.name].value);
        const isAny = (values, value) => value !== null && values.some(item => item.equals(value));

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
            const result = [];
            for (let i = cards.length - 1; i >= 0; i--) {
                // One read: the card may be collected between two.
                const card = cards[i].get();
                if (card !== null) {
                    result.unshift(Java.cast(card, Card));
                    continue;
                }
                cards[i].$dispose();
                cards.splice(i, 1);
            }
            return result;
        }

        /**
         * The factory source a card should show instead of an app or a hidden source: the one heard most recently,
         * else the card's default.
         */
        function factorySource(helper, excluded) {
            const candidates = listOf.call(ArrayList, helper.supportSet.value);
            for (const value of excluded) listRemove.call(candidates, value);
            const recent = Manager.getInstance().getMyMediaEnum(candidates);
            if (recent !== null && !recent.equals(MediaEnum.NO.value)) return recent;
            return helper.getDefaultMediaEnum();
        }

        /** Moves a card that shows an excluded source to a factory one, as after a start without media. */
        function leave(helper, excluded) {
            if (!isAny(excluded, helper.getCurMediaEnum())) return;
            const target = factorySource(helper, excluded);
            helper.setCurMediaEnum(MediaEnum.NO.value);
            // Switching from NO runs onChangeMediaEnum: the title, icon and cover follow the new source.
            helper.updateMediaEnum(target);
        }

        /**
         * Builds the card's tabs: the factory list from the card's own code, without the hidden sources, plus a tab per
         * active app. Lets the card's helper take the apps and leave a hidden source it shows.
         */
        function present(card) {
            const helper = card.mediaInfoHelper.value;
            const support = helper.supportSet.value;
            for (const item of active) support.add(item.value);
            const tabs = card.binding.value.mediaTab.value;
            const shown = tabs.mediaTabAdapter.value.data.value;
            const list = ArrayList.$new();
            for (let i = 0; i < shown.size(); i++) {
                const entry = shown.get(i);
                if (!isAny(hidden, Java.cast(entry, Bean).getMediaEnum())) listAdd.call(list, entry);
            }
            for (const item of active) listAdd.call(list, item.entry);
            tabs.fillData(list);
            // A card built while one of the apps plays missed the change: its helper did not take the source then.
            const current = Manager.getInstance().curMediaEnum.value;
            if (sourceOf(current) !== null && !current.equals(helper.getCurMediaEnum())) helper.updateMediaEnum(current);
            leave(helper, hidden);
        }

        /** Remembers a card; true when it was new. */
        function remember(card) {
            if (cards.some(ref => card.equals(ref.get()))) return false;
            cards.push(Java.retain(Reference.$new(card)));
            return true;
        }

        function track(card) {
            if (!remember(card) || (active.length === 0 && hidden.length === 0)) return;
            // The tab list again from the card's own code; the hook adds the apps and drops the hidden sources.
            card.initMedia();
        }

        /** Tab entries the card does not use on this car, from a card helper's sources (before any is added). */
        function freeTabs(card) {
            const support = card.mediaInfoHelper.value.supportSet.value;
            return MEDIA_TABS.filter(([, factory]) => !support.contains(MediaEnum[factory].value)).map(([name]) => name);
        }

        function available(source, now) {
            const known = installed.get(source.package);
            if (known !== undefined && now - known.at < MEDIA_INSTALL_CHECK_MS) return known.value;
            let value = false;
            try {
                app().getPackageManager().getServiceInfo(Component.$new(source.package, source.service), 0);
                value = true;
            } catch (_) { /* Not installed, or without the service. */ }
            installed.set(source.package, {value, at: now});
            return value;
        }

        function activate(setting) {
            const cardsNow = live();
            if (cardsNow.length === 0) return false;
            hidden = setting.hide.map(name => MediaEnum[name].value);
            if (setting.sources.length > 0) {
                const settings = config();
                const manager = Manager.getInstance();
                const context = app().getApplicationContext();
                for (const {source, slot, tab} of assignSlots(setting.sources, freeTabs(cardsNow[0]))) {
                    const value = MediaEnum[slot.name].value;
                    const item = {source, slot, tab, value, entry: TabEnum[tab].value, label: '', locale: null, icon: null};
                    borrowSlot(value, slot, source);
                    item.entry.mediaEnum.value = value;
                    settings.mediaEnums.value.add(value);
                    settings.controlEnums.value.add(value);
                    active.push(item);
                    // As MediaManager.initMediaControl does for the factory sources; it connects at once.
                    const control = Control.$new(context, value);
                    controls.push(Java.retain(control));
                    control.addQinMediaListener(manager);
                    manager.addMediaControl(control);
                }
            }
            for (const card of cardsNow) {
                try { card.initMedia(); } catch (error) { report('present', error); }
            }
            return true;
        }

        /** The launcher's remembered last source must not name a slot: it would show an empty card later. */
        function forgetSlots(borrowed) {
            const memory = Memory.getInstance().spUtil.value;
            const saved = text(memory.getString(MEMORY_KEY, 'NO'));
            if (borrowed.some(value => text(value.toString()) === saved)) memory.putString(MEMORY_KEY, FALLBACK_SOURCE);
        }

        /**
         * Puts the factory slots, tab entries, sources and tabs back. Also run at installation: an agent that vanished
         * without restoring leaves borrowed slots behind, and the factory values are known, not saved.
         */
        function deactivate() {
            const borrowed = borrowedValues();
            const manager = Manager.getInstance();
            let restored = true;
            const step = (stage, fn) => {
                try { fn(); } catch (error) { restored = false; report(stage, error); }
            };
            // The hooks go inert first, and the slots stop matching the apps' audio focus, so stopping a connection
            // below cannot make a slot the current source again.
            active = [];
            hidden = [];
            activeKey = '';
            step('slots', () => {
                for (const slot of MEDIA_SLOTS) returnSlot(MediaEnum[slot.name].value, slot);
                for (const [name, factory] of MEDIA_TABS) TabEnum[name].value.mediaEnum.value = MediaEnum[factory].value;
            });
            step('control', () => {
                for (const value of borrowed) {
                    if (value.equals(manager.curMediaEnum.value)) manager.curMediaEnum.value = MediaEnum.NO.value;
                    linkedRemove.call(manager.linkedList.value, value);
                }
            });
            for (const value of borrowed) {
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
            for (const value of borrowed) {
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
            step('memory', () => forgetSlots(borrowed));
            for (const card of live()) {
                step('restore', () => {
                    const helper = card.mediaInfoHelper.value;
                    const support = helper.supportSet.value;
                    for (const value of borrowed) support.remove(value);
                    // The factory tabs again; the hook adds and hides nothing now.
                    card.initMedia();
                    leave(helper, borrowed);
                });
            }
            dirty = !restored;
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
                if (active.length > 0 || hidden.length > 0) present(this);
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
            try {
                const item = active.length === 0 ? null : sourceOf(value);
                if (item !== null) {
                    view.setImageDrawable(icon(item));
                    this.imageUrl.value = url;
                    return;
                }
            } catch (error) { report('cover', error); }
            return cover.call(this, view, url, value);
        };
        hooks.push(cover);
        // The card's default source without media is WeCar Flow or Kuwo; while it is hidden, Bluetooth music.
        defaultSource.implementation = function () {
            const result = defaultSource.call(this);
            try {
                if (hidden.length > 0 && isAny(hidden, result)) return MediaEnum[FALLBACK_SOURCE].value;
            } catch (error) { report('default', error); }
            return result;
        };
        hooks.push(defaultSource);
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
                        this.upArrow();
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

        function apply(now) {
            const context = app();
            if (context === null) return;
            const setting = mediaSetting(readConfig(context));
            const wanted = setting === null ? null
                : {sources: setting.sources.filter(source => available(source, now)), hide: setting.hide};
            const effective = wanted !== null && (wanted.sources.length > 0 || wanted.hide.length > 0) ? wanted : null;
            const key = effective === null ? '' : JSON.stringify(effective);
            if (key === activeKey && !dirty) return;
            if (active.length > 0 || hidden.length > 0 || activeKey !== '' || dirty) deactivate();
            if (effective === null || dirty) return;
            try {
                if (activate(effective)) activeKey = key;
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
                try { if (!disposed) apply(Date.now()); }
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
                        apply(Date.now());
                        console.log('OVC_HOME_MEDIA_READY');
                    } catch (error) { report('install', error); }
                });
            }
        });

        return {
            /** UI thread only. Part of the panel's restore handshake before Controls detaches. */
            restoreAll() {
                if (active.length === 0 && hidden.length === 0 && activeKey === '' && !dirty) return true;
                return deactivate();
            },
            dispose() {
                disposed = true;
                clearInterval(timer);
                // The factory card first, while the card hook can still rebuild its tabs from the factory list.
                Java.scheduleOnMainThread(() => {
                    try { if (active.length > 0 || hidden.length > 0 || dirty) deactivate(); }
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
