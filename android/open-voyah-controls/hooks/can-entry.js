import Java from 'frida-java-bridge';
import {installCanSteering} from './can-steering.js';
installCanSteering(Java);
setInterval(() => console.log('OVC_HEARTBEAT ' + Date.now()), 10000);
