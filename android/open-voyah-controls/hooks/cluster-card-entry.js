import Java from 'frida-java-bridge';
import { installClusterMediaCard } from './cluster-media-card.js';
import { createPanelConfigReader, createDriverRouteReader } from './driver-navigation.js';
import { DRIVER_NAVIGATOR_REQUEST } from './driver-navigator-window.js';
import { createReporter } from './report.js';

const report = createReporter('OVC_CLUSTER_CARD_ERROR');

function install() {
    Java.perform(() => {
        try {
            // The supervisor may attach while the card's process is still starting.
            if (Java.use('android.app.ActivityThread').currentApplication() === null) {
                setTimeout(install, 2000);
                return;
            }
            const Global = Java.use('android.provider.Settings$Global');
            const readNavigatorRequest = context => Global.getString(context.getContentResolver(), DRIVER_NAVIGATOR_REQUEST);
            installClusterMediaCard(Java, {readConfig: createPanelConfigReader(Java), readRoute: createDriverRouteReader(Java),
                readNavigatorRequest, report});
        } catch (error) { report('install', error); }
    });
}

install();
setInterval(() => console.log('OVC_HEARTBEAT ' + Date.now()), 10000);
