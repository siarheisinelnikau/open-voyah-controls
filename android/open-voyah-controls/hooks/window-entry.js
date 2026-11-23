import Java from 'frida-java-bridge';
import { installKeyboardPolicy } from './keyboard-policy.js';
import { installWindowPolicy } from './window-policy.js';

installWindowPolicy(Java);
// Independent of the window policy: either one may be unsupported without the other.
installKeyboardPolicy(Java);
setInterval(() => console.log('OVC_HEARTBEAT ' + Date.now()), 10000);
