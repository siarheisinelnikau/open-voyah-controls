const APP = 'org.openvoyah.controls';
const NAVIGATION = APP + '.action.NAVIGATION_EVENT';

export function sendNavigation(Java, context, fields) {
    const Intent = Java.use('android.content.Intent');
    const intent = Intent.$new(NAVIGATION);
    intent.setClassName(APP, APP + '.LocalNavigationReceiver');
    for (const [name, value] of Object.entries(fields)) {
        if (typeof value === 'string') intent.putExtra.overload('java.lang.String', 'java.lang.String').call(intent, name, value.slice(0, 120));
        if (typeof value === 'number' && Number.isInteger(value)) intent.putExtra.overload('java.lang.String', 'int').call(intent, name, value);
    }
    context.sendBroadcast(intent);
}

export function installClusterMedia(Java) {
    Java.perform(() => {
        try {
            const App = Java.use('android.app.ActivityThread').currentApplication();
            if (!App) return setTimeout(() => installClusterMedia(Java), 2000);
            const Manager = Java.use('com.qinggan.cluster.InstrumentClusterManager');
            const Info = Java.use('com.qinggan.cluster.info.MediaSrcInfo');
            const Source = Java.use('com.qinggan.cluster.info.MediaSrcType');
            const Type = Java.use('com.qinggan.cluster.info.MediaType');
            const State = Java.use('com.qinggan.cluster.info.MediaState');
            const Global = Java.use('android.provider.Settings$Global');
            const resolver = App.getContentResolver();
            const key = APP + '.clusterMedia';
            let hasPublished = false;
            let previous = String(Global.getString(resolver, key) || '');
            setInterval(() => Java.perform(() => {
                const raw = String(Global.getString(resolver, key) || '');
                if (!raw || raw === previous) return;
                previous = raw;
                try {
                    if (raw.length > 4096) return;
                    const {title, artist, album, playing, expires} = JSON.parse(raw);
                    if (!Number.isFinite(expires) || expires < Date.now() || expires > Date.now() + 6000
                            || ![title, artist, album].every(text => typeof text === 'string' && text.length <= 240)
                            || typeof playing !== 'boolean' || (!title && !hasPublished)) return;
                    const cluster = Manager.getInstance(App, null);
                    if (!cluster || !cluster.getClusterConnect()) return;
                    const info = Info.$new(Source.Other.value);
                    info.setMediaType(Type.SimpleAudio.value);
                    info.setMediaState((title ? (playing ? State.PLAY : State.PAUSE) : State.CLEAN).value);
                    info.setSongName(title);
                    info.setArtist(artist);
                    info.setAlbumName(album);
                    cluster.updateMediaSrcInfo(info);
                    hasPublished = Boolean(title);
                } catch (error) { console.log('OVC_CLUSTER_MEDIA_ERROR ' + error.name); }
            }), 1000);
            console.log('OVC_CLUSTER_READY');
        } catch (error) { console.log('OVC_CLUSTER_ERROR ' + error); }
    });
}

export function installNavigationObserver(Java) {
    Java.perform(() => {
        try {
            const app = Java.use('android.app.ActivityThread').currentApplication();
            if (!app) return setTimeout(() => installNavigationObserver(Java), 2000);
            const Cluster = Java.use('com.qinggan.cluster.service.InstrumentClusterService$ClusterManagerService');
            const update = Cluster.updateNaviInfo.overload('com.qinggan.cluster.info.NaviInfo');
            let previous = '';
            update.implementation = function (info) {
                const result = update.call(this, info);
                try {
                    const guidance = info && info.getGuideState();
                    const status = guidance ? String(guidance.name()) : '';
                    const active = status === 'GUIDING' || status === 'START' || status === 'SIMULATING';
                    const fields = active ? {
                        event: 'route',
                        road: String(info.getCurrentRoadName() || ''),
                        nextRoad: String(info.getTurnRoadName() || ''),
                        meters: info.getNextDistance()
                    } : {event: 'end'};
                    const fingerprint = JSON.stringify(fields);
                    if (fingerprint !== previous) {
                        sendNavigation(Java, app, fields);
                        previous = fingerprint;
                    }
                } catch (error) { console.log('OVC_NAVIGATION_EVENT_ERROR ' + error.name); }
                return result;
            };
            console.log('OVC_NAVIGATION_READY');
        } catch (error) { console.log('OVC_NAVIGATION_ERROR ' + error); }
    });
}
