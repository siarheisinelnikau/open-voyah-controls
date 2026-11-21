import {
    ownedOnCentralDisplay, panelTypeFilter, reserveConfig, reserveFailure, reserveRect
} from './window-reserve-common.js';

// Android 9 (API 28; read against android-9.0.0_r61).
// The panel's WindowState joins PhoneWindowManager.mScreenDecorWindows with PRIVATE_FLAG_IS_SCREEN_DECOR, which
// is how the automotive system UI of that release reserved its own bars. layoutScreenDecorWindows() then moves
// mDock, mRestricted, mRestrictedOverscan, mContent, mCurrent, mVoiceContent and mSystem on every layout pass in
// framework code (mStable is not moved). Configuration follows through the non-decor width and insets of
// display 0. Every framework call below needs the WindowManagerService.mWindowMap monitor.
export function android9Reserve(Java) {
    const LAYOUT_PARAMS = 'android.view.WindowManager$LayoutParams';
    const POLICY = 'com.android.server.policy.PhoneWindowManager';
    const POLICY_WINDOW = 'com.android.server.policy.WindowManagerPolicy$WindowState';
    const DISPLAY_CONTENT = 'com.android.server.wm.DisplayContent';
    const CUTOUT = 'android.view.DisplayCutout';
    const RECT = 'android.graphics.Rect';

    const am = Java.cast(Java.use('android.app.ActivityManager').getService(),
        Java.use('com.android.server.am.ActivityManagerService'));
    const Manager = Java.use('com.android.server.wm.WindowManagerService');
    const wms = Java.cast(am.mWindowManager.value, Manager);
    const Policy = Java.use(POLICY);
    const rawPolicy = wms.mPolicy.value;
    const policyClass = String(rawPolicy.getClass().getName());
    const policy = Java.cast(rawPolicy, Policy);
    const Display = Java.use(DISPLAY_CONTENT);
    const Window = Java.use('com.android.server.wm.WindowState');
    const Params = Java.use(LAYOUT_PARAMS);
    const UserHandle = Java.use('android.os.UserHandle');
    const decorFlag = Params.PRIVATE_FLAG_IS_SCREEN_DECOR.value;
    const eligible = panelTypeFilter(Params);
    const lockObject = wms.mWindowMap.value;
    // The exact signatures of the reviewed sources. Any mismatch throws here, before a state change.
    const hooks = {
        keep: Policy.adjustWindowParamsLw.overload(POLICY_WINDOW, LAYOUT_PARAMS, 'boolean'),
        width: Policy.getNonDecorDisplayWidth.overload('int', 'int', 'int', 'int', 'int', CUTOUT),
        configWidth: Policy.getConfigDisplayWidth.overload('int', 'int', 'int', 'int', 'int', CUTOUT),
        insets: Policy.getNonDecorInsetsLw.overload('int', 'int', 'int', CUTOUT, RECT),
        stable: Policy.getStableInsetsLw.overload('int', 'int', 'int', CUTOUT, RECT),
        screen: Display.computeScreenConfiguration.overload('android.content.res.Configuration'),
        stableLocked: Manager.getStableInsetsLocked.overload('int', RECT)
    };
    const reconfigureDisplay = Manager.reconfigureDisplayLocked.overload(DISPLAY_CONTENT);
    const computeConfiguration = Manager.computeNewConfiguration.overload('int');
    Policy.layoutScreenDecorWindows.overload('com.android.server.wm.DisplayFrames', RECT, RECT, RECT);
    if (String(Policy.class.getDeclaredField('mScreenDecorWindows').getType().getName()) !== 'android.util.ArraySet') {
        throw reserveFailure('unsupported_framework');
    }
    const display = () => wms.mRoot.value.getDisplayContent(0);
    let installed = false;

    function installHooks(state) {
        if (installed) return;
        // getNonDecorInsetsLw has no displayId on this release. The callers that compute per-display values
        // (computeScreenConfiguration, getStableInsetsLocked) mark the current thread. All of them run under
        // mWindowMap, so a marker cannot leak into an unrelated computation.
        let context = null;
        let innerWidth = 0;
        let innerInsets = 0;
        const thread = () => Process.getCurrentThreadId();
        const reserve = () => (state.active && state.width > 0 ? state.width : 0);
        const primary = () => context !== null && context.display === 0 && context.thread === thread();

        // Each hook calls the original exactly once and keeps its own logic inside a try: an exception from the
        // agent must never surface in system_server.
        hooks.screen.implementation = function (config) {
            const previous = context;
            try { context = {display: this.mDisplayId.value, thread: thread()}; } catch (_) { context = null; }
            try { return hooks.screen.call(this, config); }
            finally { context = previous; }
        };
        hooks.stableLocked.implementation = function (displayId, out) {
            const previous = context;
            try { context = {display: displayId, thread: thread()}; } catch (_) { context = null; }
            try { return hooks.stableLocked.call(this, displayId, out); }
            finally { context = previous; }
        };
        hooks.width.implementation = function (w, h, rotation, uiMode, displayId, cutout) {
            const value = hooks.width.call(this, w, h, rotation, uiMode, displayId, cutout);
            try {
                const px = reserve();
                if (px > 0 && displayId === 0 && w > h && value > px) {
                    innerWidth++;
                    return value - px;
                }
            } catch (_) { }
            return value;
        };
        // getConfigDisplayWidth delegates to getNonDecorDisplayWidth. Subtract here only when that inner call
        // bypassed the hook (inlined or devirtualized by the compiler).
        hooks.configWidth.implementation = function (w, h, rotation, uiMode, displayId, cutout) {
            const before = innerWidth;
            const value = hooks.configWidth.call(this, w, h, rotation, uiMode, displayId, cutout);
            try {
                const px = reserve();
                if (px > 0 && innerWidth === before && displayId === 0 && w > h && value > px) return value - px;
            } catch (_) { }
            return value;
        };
        hooks.insets.implementation = function (rotation, w, h, cutout, out) {
            hooks.insets.call(this, rotation, w, h, cutout, out);
            try {
                const px = reserve();
                if (px > 0 && out !== null && w > h && primary()) {
                    out.left.value = out.left.value + px;
                    innerInsets++;
                }
            } catch (_) { }
        };
        hooks.stable.implementation = function (rotation, w, h, cutout, out) {
            const before = innerInsets;
            hooks.stable.call(this, rotation, w, h, cutout, out);
            try {
                const px = reserve();
                if (px > 0 && out !== null && innerInsets === before && w > h && primary()) {
                    out.left.value = out.left.value + px;
                }
            } catch (_) { }
        };
        // relayoutWindow() passes the client's LayoutParams here. Without the private flag the policy would drop
        // the panel from mScreenDecorWindows on the launcher's next updateViewLayout().
        hooks.keep.implementation = function (win, attrs, permitted) {
            try {
                if (state.active && state.panel !== null && win !== null && attrs !== null &&
                    state.panel.$isSameObject(win)) {
                    attrs.privateFlags.value = attrs.privateFlags.value | decorFlag;
                }
            } catch (_) { }
            return hooks.keep.call(this, win, attrs, permitted);
        };
        installed = true;
    }

    function removeHooks() {
        if (!installed) return;
        for (const method of Object.values(hooks)) method.implementation = null;
        installed = false;
    }

    return {
        lock(fn) {
            let result;
            Java.synchronized(lockObject, () => { result = fn(); });
            return result;
        },
        appIdOf(pkg) {
            const context = Java.use('android.app.ActivityThread').currentActivityThread().getSystemContext();
            return UserHandle.getAppId(context.getPackageManager().getPackageUid(pkg, 0));
        },
        probe() {
            // A vendor subclass may override the layout or the hooked getters; refuse instead of guessing.
            if (policyClass !== POLICY) throw reserveFailure('oem_policy');
            if (display() === null) throw reserveFailure('unsupported_display');
        },
        display() {
            const info = display().getDisplayInfo();
            return {width: info.logicalWidth.value, height: info.logicalHeight.value};
        },
        windows() {
            return Array.from(wms.mWindowMap.value.values().toArray(), value => Java.cast(value, Window));
        },
        describeWindow(win) {
            try {
                const type = win.mAttrs.value.type.value;
                return {
                    displayId: win.getDisplayId(),
                    pkg: String(win.getOwningPackage()),
                    appId: UserHandle.getAppId(win.mOwnerUid.value),
                    type,
                    eligible: eligible(type),
                    frame: reserveRect(win.getFrameLw()),
                    visible: win.isVisibleLw(),
                    // A hidden panel loses its surface but stays added; only removal ends the registration.
                    alive: !win.mRemoved.value
                };
            } catch (_) {
                return null;
            }
        },
        register(win, ownedBefore) {
            const decor = policy.mScreenDecorWindows.value;
            const attrs = win.mAttrs.value;
            const hadFlag = (attrs.privateFlags.value & decorFlag) !== 0;
            // A window that is screen decor before our first registration belongs to someone else.
            if (!ownedBefore && (hadFlag || decor.contains(win))) throw reserveFailure('already_reserved');
            attrs.privateFlags.value = attrs.privateFlags.value | decorFlag;
            try {
                if (!decor.contains(win)) decor.add(win);
            } catch (error) {
                if (!hadFlag) attrs.privateFlags.value = attrs.privateFlags.value & ~decorFlag;
                throw error;
            }
            return {};
        },
        isRegistered(win) {
            try {
                return policy.mScreenDecorWindows.value.contains(win) &&
                    (win.mAttrs.value.privateFlags.value & decorFlag) !== 0;
            } catch (_) {
                return false;
            }
        },
        unregister(win) {
            policy.mScreenDecorWindows.value.remove(win);
            const attrs = win.mAttrs.value;
            attrs.privateFlags.value = attrs.privateFlags.value & ~decorFlag;
        },
        /** Removes what an agent that ended while the mode was on left behind. Only called when that is recorded. */
        cleanupStale(pkg) {
            const decor = policy.mScreenDecorWindows.value;
            let removed = 0;
            for (let i = decor.size() - 1; i >= 0; i--) {
                const win = Java.cast(decor.valueAt(i), Window);
                if (!ownedOnCentralDisplay(win, pkg)) continue;
                decor.removeAt(i);
                const attrs = win.mAttrs.value;
                attrs.privateFlags.value = attrs.privateFlags.value & ~decorFlag;
                removed++;
            }
            return removed;
        },
        layoutReserved(win, width) {
            const frames = display().mDisplayFrames.value;
            const right = win.getFrameLw().right.value;
            return right === width && frames.mDock.value.left.value === right &&
                frames.mRestricted.value.left.value === right;
        },
        config: () => reserveConfig(display().getConfiguration()),
        computeConfig: () => reserveConfig(computeConfiguration.call(wms, 0)),
        reconfigure() {
            // The path of "wm size": recompute, freeze if the configuration changed, post
            // SEND_NEW_CONFIGURATION to ActivityManagerService and run a surface placement.
            reconfigureDisplay.call(wms, display());
        },
        relayout() {
            display().setLayoutNeeded();
            wms.requestTraversal();
        },
        installHooks,
        removeHooks
    };
}
