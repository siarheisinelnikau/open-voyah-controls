import Java from 'frida-java-bridge';
import { install } from './launcher-buttons.js';
import { installLocalization } from './localization.js';
import { installVoice } from './voice-commands.js';
import { installClusterMedia, sendNavigation } from './display-events.js';
import { installSteering } from './steering-android11.js';
import { installOversea } from './navbar-oversea.js';
installLocalization(Java);
installVoice(Java);
installClusterMedia(Java);
installSteering(Java);
Java.perform(() => {
    const product = String(Java.use('android.os.Build').PRODUCT.value);
    const panel = product === 'f6811' ? installOversea : install;
    panel(Java, (context, app) => sendNavigation(Java, context, {event:'open', app}));
});
setInterval(() => console.log('OVC_HEARTBEAT ' + Date.now()),10000);
