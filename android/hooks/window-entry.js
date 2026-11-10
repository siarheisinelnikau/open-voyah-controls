import Java from 'frida-java-bridge';
import { installWindowPolicy } from './window-policy.js';

installWindowPolicy(Java);
setInterval(() => console.log('OVC_HEARTBEAT ' + Date.now()), 10000);
