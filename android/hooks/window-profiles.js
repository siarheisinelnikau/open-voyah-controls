export const WINDOW_CONFIG = 'org.openvoyah.controls.windowProfiles';
export const WINDOW_REQUEST = 'org.openvoyah.controls.windowRequest';
export const PANEL_CONFIG = 'org.openvoyah.controls.panel';
export const PANEL_EDGE = 'org.openvoyah.controls.extra.LEFT_PANEL_PX';
export const CONTROLS = 'org.openvoyah.controls';
export const OEM_LAUNCHER = 'com.qinggan.app.launcher';

const protectedPackages = new Set([
    CONTROLS, 'android', 'com.android.settings', 'com.android.systemui', OEM_LAUNCHER,
    'com.qinggan.app.setting', 'com.qinggan.app.vehicle', 'com.qinggan.cluster'
]);

export function componentKey(value) {
    if (typeof value !== 'string' || value.length > 200) return null;
    const match = /^([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+)\/(\.?[a-zA-Z_$][a-zA-Z0-9_.$]*)$/.exec(value);
    if (!match || protectedPackages.has(match[1])) return null;
    return match[1] + '/' + (match[2].startsWith('.') ? match[1] + match[2] : match[2]);
}

export function normalizeProfile(value) {
    if (!value || typeof value !== 'object') return null;
    const component = componentKey(value.component);
    const {width, dpi, keepPanel, panelPx} = value;
    if (!component || !Number.isInteger(width) || width < 50 || width > 100 || width % 5 !== 0 ||
        !(dpi === 0 || Number.isInteger(dpi) && dpi >= 100 && dpi <= 320 && dpi % 10 === 0) ||
        typeof keepPanel !== 'boolean' || !Number.isInteger(panelPx) || panelPx < 0 || panelPx > 600) return null;
    // Keep accepting the old field from saved profiles. The strip is now reserved for every managed launch.
    return {component, width, dpi, panelPx};
}

function json(raw) {
    if (!raw || raw.length > 8192) return null;
    try { return JSON.parse(raw); } catch (_) { return null; }
}

export function readWindowConfig(raw) {
    const config = json(raw);
    if (!config || config.schema !== 1 || typeof config.enabled !== 'boolean' || !Number.isInteger(config.user)
        || config.user < 0 || !Array.isArray(config.apps) || config.apps.length > 16) return null;
    const apps = new Map();
    for (const entry of config.apps) {
        const profile = normalizeProfile(entry);
        if (!profile || apps.has(profile.component.split('/')[0])) return null;
        apps.set(profile.component.split('/')[0], profile);
    }
    return {enabled: config.enabled, user: config.user, apps};
}

export function readNavigator(raw) {
    const panel = json(raw);
    const action = panel?.actions?.navbar01;
    return panel?.schema === 1 && panel.enabled === true && action?.kind === 'LAUNCH_APP'
        ? componentKey(action.component) : null;
}

export function profileFor(config, component, user, withDefault = false) {
    if (!config?.enabled || config.user !== user || !component) return null;
    const profile = config.apps.get(component.split('/')[0]);
    return profile ?? (withDefault ? defaultProfile(component) : null);
}

export function defaultProfile(component) {
    return {component, width:100, dpi:0, panelPx:0};
}

export function launchProfile(config, navigator, launch) {
    const fromLauncher = launch.caller === CONTROLS || launch.caller === OEM_LAUNCHER || launch.fromHome;
    return profileFor(config, launch.key, launch.user, fromLauncher || navigator === launch.key);
}

export function readWindowRequest(raw, now) {
    const value = json(raw);
    if (!value || value.schema !== 1 || !/^[a-f0-9]{32}$/.test(value.id ?? '') ||
        !Number.isInteger(value.user) || value.user < 0 || !Number.isInteger(value.expires) ||
        value.expires < now || value.expires > now + 10000) return null;
    if (value.operation === 'release') return {id:value.id, user:value.user, operation:value.operation};
    const component = componentKey(value.component);
    if (!component || (value.operation !== 'apply' && value.operation !== 'reset')) return null;
    return {id:value.id, user:value.user, operation:value.operation, component};
}

/** Width is a percentage of the usable display after excluding the left strip. */
export function windowBounds(frame, profile, measuredPanel, minWidth = 0, minHeight = 0) {
    const {bounds, stable} = frame;
    const displayWidth = bounds[2] - bounds[0];
    const panel = Math.max(profile.panelPx, measuredPanel || 145);
    if (panel > displayWidth / 3) return null;
    const usableLeft = Math.max(stable[0], bounds[0] + panel);
    const width = Math.round((stable[2] - usableLeft) * profile.width / 100);
    const height = stable[3] - stable[1];
    if (width < Math.max(1, minWidth) || height < Math.max(1, minHeight)) return null;
    return [stable[2] - width, stable[1], stable[2], stable[3]];
}

export function rectValues(rect) {
    return [rect.left.value, rect.top.value, rect.right.value, rect.bottom.value];
}

export function sameBounds(a, b) {
    return a !== null && b !== null && a.every((value, index) => value === b[index]);
}
