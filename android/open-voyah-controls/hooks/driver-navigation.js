const DRIVER_ROUTE_URI = 'content://org.openvoyah.controls.driverNavigation';
const DRIVER_ROUTE_MAX_AGE_MS = 5000;

export function driverNavigationToken(config) {
    return config?.schema === 1 && config.restoreIcons === undefined &&
        typeof config.driverNavigation === 'string' && /^[0-9a-f]{32}$/.test(config.driverNavigation)
        ? config.driverNavigation : null;
}

export function driverRouteRemaining(at, now) {
    const age = now - at;
    return Number.isSafeInteger(at) && at >= 0 && Number.isSafeInteger(age) && age >= 0 && age < DRIVER_ROUTE_MAX_AGE_MS
        ? DRIVER_ROUTE_MAX_AGE_MS - age : 0;
}

export function createPanelConfigReader(Java) {
    const Global = Java.use('android.provider.Settings$Global');
    return context => {
        const raw = Global.getString(context.getContentResolver(), 'org.openvoyah.controls.panel');
        try { return raw === null ? null : JSON.parse(raw.toString()); }
        catch (_) { return null; }
    };
}

/** Called away from the instrument card's main thread. A missing provider or route leaves the native card in place. */
export function createDriverRouteReader(Java) {
    const Uri = Java.use('android.net.Uri');
    const Clock = Java.use('android.os.SystemClock');
    const uri = Uri.parse(DRIVER_ROUTE_URI);
    return (context, token) => {
        const data = context.getContentResolver().call(uri, 'route', token, null);
        if (data === null) return null;
        const at = Number(data.getLong('at', -1));
        if (driverRouteRemaining(at, Number(Clock.elapsedRealtime())) === 0) return null;
        return {at, heading: data.getString('heading'), title: data.getString('title'), detail: data.getString('detail')};
    };
}
