import { android9Reserve } from './window-reserve-android9.js';
import { android11Reserve } from './window-reserve-android11.js';
import { reserveFailure } from './window-reserve-common.js';
import { OEM_LAUNCHER } from './window-profiles.js';

// Experimental: Android itself keeps the OEM left panel of the central display clear, instead of this agent
// moving every launched task beside it.
//   Android 9:  the panel window becomes a screen-decor window of PhoneWindowManager.
//   Android 11: the panel window becomes the insets source that a navigation bar would be.
// On both, the non-decor width and insets of display 0 are reduced, so Configuration and app bounds agree with
// the layout. Nothing here runs per window per layout pass, and release() undoes every change.

const MIN_PANEL_PX = 16;
const MAX_PANEL_PX = 600;
const WIDTH_TOLERANCE_PX = 2;
const VERIFY_DELAY_MS = 1500;
const VERIFY_DEADLINE_MS = 6000;
const PANEL_GRACE_MS = 15000;
const failures = new Set([
    'unsupported_sdk', 'unsupported_framework', 'oem_policy', 'insets_mode', 'system_nav_bar', 'unsupported_display',
    'left_inset_present', 'no_panel', 'ambiguous_panel', 'already_reserved', 'panel_moved', 'verify_failed',
    'rollback_failed', 'busy'
]);

/** The status of an attempt. Only these codes leave the agent; anything else is reported as `failed`. */
export function reserveStatus(error) {
    return failures.has(error?.code) ? error.code : 'failed';
}

const sameRect = (a, b) => Array.isArray(a) && Array.isArray(b) && a.length === 4 &&
    a.every((value, index) => value === b[index]);

/**
 * Selects the OEM panel among plain window descriptions produced by an adapter. The panel must be visible on
 * display 0, owned by the launcher, of an eligible system type and docked at the left edge over the full display
 * height. One such window is the panel. Among several, only the one whose width the launcher itself reports is
 * accepted; without that the choice would be a guess.
 */
export function choosePanel(display, windows, expected) {
    if (!(display.width > display.height)) throw reserveFailure('unsupported_display');
    const limit = Math.min(MAX_PANEL_PX, Math.floor(display.width / 2) - 1);
    const docked = [];
    for (let index = 0; index < windows.length; index++) {
        const w = windows[index];
        if (!w || w.displayId !== 0 || !w.alive || !w.visible || !w.eligible || w.pkg !== expected.pkg) continue;
        if (expected.appId !== undefined && w.appId !== expected.appId) continue;
        if (expected.type !== undefined && w.type !== expected.type) continue;
        const [left, top, right, bottom] = w.frame;
        const width = right - left;
        if (left !== 0 || top !== 0 || bottom !== display.height || width < MIN_PANEL_PX || width > limit) continue;
        docked.push({index, width});
    }
    if (docked.length === 0) throw reserveFailure('no_panel');
    if (docked.length === 1) return docked[0];
    const reported = expected.width > 0
        ? docked.filter(item => Math.abs(item.width - expected.width) <= WIDTH_TOLERANCE_PX) : [];
    if (reported.length !== 1) throw reserveFailure('ambiguous_panel');
    return reported[0];
}

export function systemReserve(Java, sdk, options = {}) {
    const factory = {28: android9Reserve, 30: android11Reserve}[sdk];
    const pkg = options.launcherPackage ?? OEM_LAUNCHER;
    const now = options.now ?? (() => Date.now());
    const state = {
        active: false, width: 0, panel: null, registration: null, signature: null, before: null,
        verifyAt: 0, verifyDeadline: 0, missingSince: 0
    };
    let adapter = null;
    let adapterFailure = null;
    let busy = false;

    function platform() {
        if (!factory) throw reserveFailure('unsupported_sdk');
        if (adapterFailure !== null) throw adapterFailure;
        if (adapter === null) {
            try {
                adapter = factory(Java, options);
            } catch (error) {
                // A missing class, field or overload means this firmware is not the reviewed framework.
                adapterFailure = reserveFailure(failures.has(error?.code) ? error.code : 'unsupported_framework');
                throw adapterFailure;
            }
        }
        return adapter;
    }

    function dispose(wrapper) {
        try { wrapper?.$dispose?.(); } catch (_) { }
    }

    function clearState() {
        dispose(state.panel);
        state.active = false;
        state.width = 0;
        state.panel = null;
        state.registration = null;
        state.signature = null;
        state.before = null;
        state.verifyAt = 0;
        state.verifyDeadline = 0;
        state.missingSince = 0;
    }

    /** One caller at a time, and no exception: every outcome of an operation is a status. */
    function guarded(operation) {
        if (busy) return {status: 'busy'};
        busy = true;
        try {
            return operation();
        } catch (error) {
            return {status: reserveStatus(error)};
        } finally {
            busy = false;
        }
    }

    function owner(api) {
        // PackageManager is asked before the window lock is taken.
        try { return api.appIdOf(pkg); } catch (_) { throw reserveFailure('no_panel'); }
    }

    function locate(api, expected) {
        const handles = api.windows();
        const described = handles.map(win => api.describeWindow(win));
        let choice;
        try {
            choice = choosePanel(api.display(), described, expected);
        } catch (error) {
            handles.forEach(dispose);
            throw error;
        }
        handles.forEach((win, index) => { if (index !== choice.index) dispose(win); });
        return {win: handles[choice.index], info: described[choice.index], width: choice.width};
    }

    function expectReduced(before, after) {
        const [left, top, right, bottom] = before.appBounds;
        if (!sameRect(after.appBounds, [left + state.width, top, right, bottom])) return false;
        const expectedDp = Math.floor((right - left - state.width) * 160 / after.densityDpi);
        return Math.abs(after.screenWidthDp - expectedDp) <= 1;
    }

    function rollback(api, undo) {
        let restored = true;
        for (const step of undo.reverse()) {
            try { step(); } catch (_) { restored = false; }
        }
        try { api.reconfigure(); } catch (_) { restored = false; }
        return restored;
    }

    function applyLocked(api, reported, appId, reclaim) {
        api.probe();
        if (state.active) {
            const current = state.panel === null ? null : api.describeWindow(state.panel);
            if (current?.alive && api.isRegistered(state.panel, state.registration)) {
                return {status: 'applied', width: state.width, unchanged: true};
            }
            const released = releaseLocked(api, false);
            if (released.status !== 'released') return released;
        } else if (reclaim && api.cleanupStale(pkg) > 0) {
            // A previous agent that ended while the mode was on left its registration behind. Its hooks ended
            // with it, so the configuration is computed again now; whatever follows starts from the native one.
            api.reconfigure();
        }
        const panel = locate(api, {pkg, appId, width: reported});
        const before = api.computeConfig();
        if (!before.appBounds || before.appBounds[0] !== 0) {
            // A left navigation bar or cutout already moves the app bounds; composing with it is not reviewed.
            dispose(panel.win);
            throw reserveFailure('left_inset_present');
        }
        const undo = [];
        try {
            api.installHooks(state);
            undo.push(() => api.removeHooks());
            state.panel = Java.retain(panel.win);
            dispose(panel.win);
            state.width = panel.width;
            state.signature = {pkg, appId, type: panel.info.type};
            state.registration = api.register(state.panel, false);
            undo.push(() => api.unregister(state.panel, state.registration));
            state.active = true;
            undo.push(() => { state.active = false; });
            api.reconfigure();
            if (!api.isRegistered(state.panel, state.registration)) throw reserveFailure('verify_failed');
            if (!expectReduced(before, api.computeConfig())) throw reserveFailure('verify_failed');
            const frame = api.describeWindow(state.panel)?.frame;
            if (!sameRect(frame, panel.info.frame)) throw reserveFailure('panel_moved');
            state.before = before;
            state.verifyAt = now() + VERIFY_DELAY_MS;
            state.verifyDeadline = now() + VERIFY_DEADLINE_MS;
            state.missingSince = 0;
            return {status: 'applied', width: state.width};
        } catch (error) {
            const restored = rollback(api, undo);
            clearState();
            if (!restored) return {status: 'rollback_failed'};
            throw error;
        }
    }

    function releaseLocked(api, reclaim) {
        let restored = true;
        let changed = state.active || state.registration !== null;
        state.active = false;
        if (state.panel !== null && state.registration !== null) {
            try { api.unregister(state.panel, state.registration); } catch (_) { restored = false; }
        }
        if (!changed && reclaim) {
            try { changed = api.cleanupStale(pkg) > 0; } catch (_) { restored = false; }
        }
        if (changed) {
            try { api.reconfigure(); } catch (_) { restored = false; }
        }
        if (restored && state.before !== null) {
            // Pixel app bounds only: a density change in the meantime legitimately changes screenWidthDp.
            try {
                if (!sameRect(api.computeConfig().appBounds, state.before.appBounds)) restored = false;
            } catch (_) { restored = false; }
        }
        try { api.removeHooks(); } catch (_) { restored = false; }
        clearState();
        return {status: restored ? 'released' : 'rollback_failed'};
    }

    function tryAdopt(api) {
        let panel;
        try {
            panel = locate(api, {...state.signature, width: state.width});
        } catch (_) {
            return false;
        }
        // The configuration is sized for the old width; another width needs a new apply().
        if (panel.width !== state.width) { dispose(panel.win); return false; }
        const retained = Java.retain(panel.win);
        dispose(panel.win);
        try {
            state.registration = api.register(retained, false);
        } catch (_) {
            dispose(retained);
            return false;
        }
        state.panel = retained;
        state.missingSince = 0;
        state.verifyAt = now() + VERIFY_DELAY_MS;
        state.verifyDeadline = now() + VERIFY_DEADLINE_MS;
        api.relayout();
        return true;
    }

    function releasedAs(api, status) {
        const released = releaseLocked(api, false);
        return released.status === 'released' ? {status} : released;
    }

    function pollLocked(api) {
        const time = now();
        const current = state.panel === null ? null : api.describeWindow(state.panel);
        if (!current?.alive) {
            if (state.panel !== null) {
                try { api.unregister(state.panel, state.registration); } catch (_) { }
                dispose(state.panel);
                state.panel = null;
                state.registration = null;
            }
            if (!state.missingSince) state.missingSince = time;
            if (tryAdopt(api)) return {status: 'readopted', width: state.width};
            if (time - state.missingSince >= PANEL_GRACE_MS) return releasedAs(api, 'panel_lost');
            return {status: 'waiting_panel', width: state.width};
        }
        state.missingSince = 0;
        if (current.visible) {
            const [left, top, right, bottom] = current.frame;
            // A panel that slides or resizes would resize every app with each frame of its animation.
            if (left !== 0 || top !== 0 || bottom !== api.display().height || right - left !== state.width) {
                return releasedAs(api, 'panel_moved');
            }
        }
        if (!api.isRegistered(state.panel, state.registration)) {
            try {
                state.registration = api.register(state.panel, true);
            } catch (_) {
                return releasedAs(api, 'already_reserved');
            }
            api.relayout();
            return {status: 'reregistered', width: state.width};
        }
        if (state.verifyAt && time >= state.verifyAt) {
            if (expectReduced(state.before, api.config()) &&
                (!current.visible || api.layoutReserved(state.panel, state.width, state.registration))) {
                state.verifyAt = 0;
                return {status: 'verified', width: state.width};
            }
            if (time >= state.verifyDeadline) return releasedAs(api, 'verify_failed');
            return {status: 'verifying', width: state.width};
        }
        return {status: state.verifyAt ? 'applied' : 'verified', width: state.width};
    }

    const reportedWidth = value => Number.isInteger(value) && value > 0 && value <= MAX_PANEL_PX ? value : 0;

    return {
        /** Read-only: whether apply() would find what it needs. Never changes framework state. */
        inspect(panelWidthPx = 0) {
            return guarded(() => {
                const api = platform();
                const appId = owner(api);
                return api.lock(() => {
                    api.probe();
                    const panel = locate(api, {pkg, appId, width: reportedWidth(panelWidthPx)});
                    dispose(panel.win);
                    return {status: 'ready', width: panel.width};
                });
            });
        },
        /**
         * Reserves the panel strip. [panelWidthPx] is the width the launcher reports, or 0. [reclaim] allows
         * removing a registration that an earlier agent left behind; without it such a window is someone else's.
         */
        apply(panelWidthPx = 0, reclaim = false) {
            return guarded(() => {
                const api = platform();
                const appId = owner(api);
                return api.lock(() => applyLocked(api, reportedWidth(panelWidthPx), appId, reclaim === true));
            });
        },
        release(reclaim = false) {
            return guarded(() => {
                const api = platform();
                return api.lock(() => releaseLocked(api, reclaim === true));
            });
        },
        /** Periodic maintenance: deferred verification, a re-created panel and a lost one. */
        poll() {
            if (!state.active) return {status: 'inactive'};
            return guarded(() => {
                const api = platform();
                return api.lock(() => pollLocked(api));
            });
        },
        active: () => state.active,
        /** The reserved width in pixels, or 0 while nothing is reserved. */
        width: () => (state.active ? state.width : 0)
    };
}
