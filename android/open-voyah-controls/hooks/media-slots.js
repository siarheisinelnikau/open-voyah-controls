/**
 * What the agents that add apps to a factory media card share. The home screen card (launcher) and the instrument
 * cluster card (`com.qinggan.instrumentcard`) both run the factory media framework (`com.qinggan.media.helper`,
 * `com.qinggan.app.mediaCentre`), each in its own process with its own copy of its enum.
 */

const SOURCE_PACKAGE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;
const SOURCE_CLASS = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z_$][A-Za-z0-9_$]*)+$/;
// AudioManager.STREAM_MUSIC, the stream of every media source the cards show.
export const MEDIA_STREAM = 3;

/**
 * Media sources of the framework's `com.qinggan.media.helper.MediaEnum` that no code path of either card uses
 * (checked in the decompiled 1.0.6 launcher and the instrument card 1.0: SYSTEM_BT_MUSIC has no reference, DAB is
 * only added to the configuration when `AppUtils.isSupportDAB()`, which always returns false). Each added app borrows
 * one; its fields point at the app while it does, and go back to these factory values afterwards. A borrowed slot
 * gets a media id of its own: the factory DAB shares 15 with WeCar Flow, and `MediaEnum.getMediaEnumById` must find
 * the slot.
 */
export const MEDIA_SLOTS = [
    {name: 'SYSTEM_BT_MUSIC', id: 9001, factory: {mediaId: 11, streamType: -2, pageName: '',
        serviceName: 'com.qinggan.media.btmusic.BTMediaBrowserService', servicePageName: 'com.android.bluetooth',
        clientId: 'com.qinggan.media.btmusic.BTMediaBrowserService'}},
    {name: 'DAB', id: 9002, factory: {mediaId: 15, streamType: 3, pageName: 'com.ex.dabplayer.pad',
        serviceName: 'com.ex.dabplayer.pad.service.DabMediaService', servicePageName: 'com.qinggan.media',
        clientId: 'com.ex.dabplayer.pad'}}
];

/**
 * The valid, distinct apps of a configuration list ({package, service} with a media browser service), in order and
 * at most one per borrowed slot. A repeated package keeps its first entry.
 */
export function mediaSources(list) {
    const sources = [];
    for (const item of Array.isArray(list) ? list : []) {
        const name = item?.package, service = item?.service;
        if (typeof name !== 'string' || name.length > 128 || !SOURCE_PACKAGE.test(name)) continue;
        if (typeof service !== 'string' || service.length > 200 || !SOURCE_CLASS.test(service)) continue;
        if (sources.some(source => source.package === name)) continue;
        sources.push({package: name, service});
        if (sources.length === MEDIA_SLOTS.length) break;
    }
    return sources;
}

/**
 * Points a borrowed slot at an app: its browser service, its package for the framework's focus and service checks,
 * and the music stream.
 */
export function borrowSlot(value, slot, source) {
    value.mediaId.value = slot.id;
    value.serviceName.value = source.service;
    value.servicePageName.value = source.package;
    value.pageName.value = source.package;
    value.clientId.value = source.package + '.';
    value.streamType.value = MEDIA_STREAM;
}

/** Puts a slot's factory fields back. */
export function returnSlot(value, slot) {
    for (const [field, factory] of Object.entries(slot.factory)) value[field].value = factory;
}

/**
 * Whether the latest media audio focus belongs to the source: the OEM audio policy reports the package that asked
 * for focus, and the AudioManager client id carries the class of the app's focus listener.
 */
export function focusOwner(source, info) {
    if (!info || info.streamType !== MEDIA_STREAM) return false;
    if (info.packageName === source.package) return true;
    return typeof info.clientId === 'string' && info.clientId.includes(source.package + '.');
}
