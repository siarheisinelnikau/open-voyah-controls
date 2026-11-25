import { createReporter } from './report.js';

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

/** A Java long arrives as an Int64 and a float is not an integer; the receiver reads an int extra. */
function distance(value, report) {
    let meters = NaN;
    try { if (value !== null && value !== undefined) meters = Math.round(Number(value)); }
    // An unconvertible value is left out, as before, so the rest of the route still reaches the display.
    catch (error) { report('distance', error); }
    return Number.isSafeInteger(meters) && meters >= 0 && meters <= 0x7fffffff ? meters : undefined;
}

export function installNavigationObserver(Java, report = createReporter('OVC_NAVIGATION_ERROR')) {
    Java.perform(() => {
        try {
            const app = Java.use('android.app.ActivityThread').currentApplication();
            if (!app) return setTimeout(() => installNavigationObserver(Java, report), 2000);
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
                        meters: distance(info.getNextDistance(), report)
                    } : {event: 'end'};
                    const fingerprint = JSON.stringify(fields);
                    if (fingerprint !== previous) {
                        sendNavigation(Java, app, fields);
                        previous = fingerprint;
                    }
                } catch (error) { report('event', error); }
                return result;
            };
            console.log('OVC_NAVIGATION_READY');
        } catch (error) { report('install', error); }
    });
}
