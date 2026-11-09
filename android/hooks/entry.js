import Java from 'frida-java-bridge';
import { install } from './launcher-buttons.js';
import { installLocalization } from './localization.js';
import { installVoice } from './voice-commands.js';
installLocalization(Java);
installVoice(Java);
install(Java);
setInterval(() => console.log('OVC_HEARTBEAT ' + Date.now()),10000);
