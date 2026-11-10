import Java from 'frida-java-bridge';
import { installNavigationObserver } from './display-events.js';

installNavigationObserver(Java);
setInterval(() => console.log('OVC_HEARTBEAT ' + Date.now()), 10000);
