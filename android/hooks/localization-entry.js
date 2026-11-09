import Java from 'frida-java-bridge';
import { installLocalization } from './localization.js';
installLocalization(Java);
setInterval(() => console.log('OVC_HEARTBEAT ' + Date.now()), 10000);
