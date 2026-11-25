import Java from 'frida-java-bridge';
import { installClusterMediaCard } from './cluster-media-card.js';
import { createReporter } from './report.js';

// The panel configuration Controls publishes for the launcher agent; its `clusterCard` part is for this one.
const CONFIG = 'org.openvoyah.controls.panel';
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
            const readConfig = context => {
                const raw = Global.getString(context.getContentResolver(), CONFIG);
                try { return raw === null ? null : JSON.parse(raw.toString()); }
                catch (_) { return null; }
            };
            installClusterMediaCard(Java, {readConfig, report});
        } catch (error) { report('install', error); }
    });
}

install();
setInterval(() => console.log('OVC_HEARTBEAT ' + Date.now()), 10000);
