// Shared helpers of the experimental system-reserved panel strip (window-reserve*.js).

// Window types that are never the OEM panel: system bars, IME, wallpaper, toasts and system overlays.
const EXCLUDED_TYPES = ['TYPE_STATUS_BAR', 'TYPE_NAVIGATION_BAR', 'TYPE_INPUT_METHOD',
    'TYPE_INPUT_METHOD_DIALOG', 'TYPE_WALLPAPER', 'TYPE_DOCK_DIVIDER', 'TYPE_KEYGUARD_DIALOG', 'TYPE_BOOT_PROGRESS',
    'TYPE_DREAM', 'TYPE_POINTER', 'TYPE_DRAG', 'TYPE_SCREENSHOT', 'TYPE_TOAST', 'TYPE_VOICE_INTERACTION',
    'TYPE_VOICE_INTERACTION_STARTING', 'TYPE_PRESENTATION', 'TYPE_PRIVATE_PRESENTATION', 'TYPE_MAGNIFICATION_OVERLAY',
    'TYPE_ACCESSIBILITY_OVERLAY', 'TYPE_QS_DIALOG', 'TYPE_NOTIFICATION_SHADE'];

export function reserveFailure(code) {
    return Object.assign(Error(code), {code});
}

export function reserveRect(rect) {
    return rect === null ? null : [rect.left.value, rect.top.value, rect.right.value, rect.bottom.value];
}

export function reserveConfig(config) {
    return {
        screenWidthDp: config.screenWidthDp.value,
        densityDpi: config.densityDpi.value,
        appBounds: reserveRect(config.windowConfiguration.value.getAppBounds())
    };
}

/** System window types that may be the OEM panel. Constants are read by name; absent ones are skipped. */
export function panelTypeFilter(Params) {
    const excluded = new Set();
    for (const name of EXCLUDED_TYPES) {
        try { excluded.add(Params[name].value); } catch (_) { }
    }
    const first = Params.FIRST_SYSTEM_WINDOW.value;
    const last = Params.LAST_SYSTEM_WINDOW.value;
    return type => type >= first && type <= last && !excluded.has(type);
}

export function ownedOnCentralDisplay(win, pkg) {
    try { return win !== null && win.getDisplayId() === 0 && String(win.getOwningPackage()) === pkg; }
    catch (_) { return false; }
}
