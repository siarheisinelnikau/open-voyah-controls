import Java from 'frida-java-bridge';
import { install } from './launcher-buttons.js';
import { installLocalization } from './localization.js';
import { installVoice } from './voice-commands.js';
import { sendNavigation } from './display-events.js';
import { installSteering } from './steering-qg.js';
import { installOversea } from './navbar-oversea.js';
import { createStartupBrand } from './startup-brand.js';
import { errorKind } from './report.js';
installLocalization(Java);
installVoice(Java);
installSteering(Java);
Java.perform(() => {
    const product = String(Java.use('android.os.Build').PRODUCT.value);
    const panel = product === 'f6811' ? installOversea : install;
    let startup;
    try { startup = createStartupBrand(Java); }
    catch (error) { console.log('OVC_STARTUP_BRAND outcome=unavailable kind=' + errorKind(error)); }
    panel(Java, (context, app) => sendNavigation(Java, context, {event:'open', app}), startup);
});
setInterval(() => console.log('OVC_HEARTBEAT ' + Date.now()),10000);
