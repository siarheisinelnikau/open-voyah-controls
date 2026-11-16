import Java from 'frida-java-bridge';
import {inspectOverseaProfiles} from './oversea-profiles.js';
inspectOverseaProfiles(Java, globalThis.OVC_PROFILE_PROBE_ID);
