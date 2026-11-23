import Java from 'frida-java-bridge';
import { installApolloFactory } from './apollo-factory.js';
installApolloFactory(Java);
setInterval(() => console.log('OVC_HEARTBEAT ' + Date.now()), 10000);
