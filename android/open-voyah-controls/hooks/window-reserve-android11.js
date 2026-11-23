import {
    ownedOnCentralDisplay, panelTypeFilter, reserveConfig, reserveFailure, reserveRect
} from './window-reserve-common.js';

// Android 11 (API 30; read against android-11.0.0_r1 and android-11.0.0_r48).
// Screen decor no longer reaches apps on this release: frames and client insets come from InsetsState. The panel's
// WindowState therefore becomes the window behind the display-0 ITYPE_NAVIGATION_BAR InsetsSourceProvider, the
// slot a real navigation bar uses (refused when one exists). setWindow() links the panel back through
// WindowState.mControllableInsetProvider, so the panel neither receives nor fits its own source. One hook keeps
// that provider from handing the panel's surface to an app: an immersive app could otherwise hide the OEM panel
// with its Home button. No private field is written. Configuration follows through the DisplayPolicy getters of
// display 0. Every framework call below needs WindowManagerService.mGlobalLock.
export function android11Reserve(Java) {
    const WINDOW = 'com.android.server.wm.WindowState';
    const CUTOUT = 'android.view.DisplayCutout';
    const RECT = 'android.graphics.Rect';

    const atm = Java.cast(Java.use('android.app.ActivityTaskManager').getService(),
        Java.use('com.android.server.wm.ActivityTaskManagerService'));
    const Manager = Java.use('com.android.server.wm.WindowManagerService');
    const wms = Java.cast(atm.mWindowManager.value, Manager);
    const Display = Java.use('com.android.server.wm.DisplayContent');
    const Policy = Java.use('com.android.server.wm.DisplayPolicy');
    const Window = Java.use(WINDOW);
    const Provider = Java.use('com.android.server.wm.InsetsSourceProvider');
    const InsetsState = Java.use('android.view.InsetsState');
    const Types = Java.use('android.view.WindowInsets$Type');
    const ViewRoot = Java.use('android.view.ViewRootImpl');
    const UserHandle = Java.use('android.os.UserHandle');
    const Integer = Java.use('java.lang.Integer');
    const boxed = Integer.valueOf.overload('int');
    // Read by name: the numeric values of the internal insets types differ between Android 11 releases.
    const NAV = InsetsState.ITYPE_NAVIGATION_BAR.value;
    const eligible = panelTypeFilter(Java.use('android.view.WindowManager$LayoutParams'));
    const lockObject = wms.mGlobalLock.value;
    // The exact signatures of the reviewed sources. Any mismatch throws here, before a state change.
    const hooks = {
        width: Policy.getNonDecorDisplayWidth.overload('int', 'int', 'int', 'int', CUTOUT),
        configWidth: Policy.getConfigDisplayWidth.overload('int', 'int', 'int', 'int', CUTOUT),
        insets: Policy.getNonDecorInsetsLw.overload('int', 'int', 'int', CUTOUT, RECT),
        stable: Policy.getStableInsetsLw.overload('int', 'int', 'int', CUTOUT, RECT),
        control: Provider.updateControlForTarget.overload('com.android.server.wm.InsetsControlTarget', 'boolean')
    };
    const setProvider = Display.setInsetProvider.overload('int', WINDOW,
        'com.android.internal.util.function.TriConsumer');
    const reconfigureDisplay = Display.reconfigureDisplayLocked.overload();
    const computeConfiguration = Manager.computeNewConfiguration.overload('int');
    const display = () => wms.mRoot.value.getDisplayContent(0);
    const primary = Java.retain(display().getDisplayPolicy());
    let installed = false;
    // The provider whose control stays with the system. Set before setWindow(), so that a pending control target
    // is not applied while the panel is being linked.
    let blocked = null;

    const fullInsetsMode = () => ViewRoot.sNewInsetsMode.value === ViewRoot.NEW_INSETS_MODE_FULL.value;
    const navigationFitsFrames = () =>
        InsetsState.toInternalType(Types.navigationBars()).contains(boxed.call(Integer, NAV));

    function linked(win, provider) {
        const back = win.getControllableInsetProvider();
        return back !== null && back.$isSameObject(provider);
    }

    function declaresInsets(win) {
        // A window that provides an insets type through its own LayoutParams is native, never ours.
        const types = win.mAttrs.value.providesInsetsTypes.value;
        return types !== null && types.length > 0;
    }

    function installHooks(state) {
        if (installed) return;
        // Marked first: if one replacement cannot be installed, removeHooks() still clears the ones that were.
        installed = true;
        try {
            replaceMethods(state);
        } catch (error) {
            removeHooks();
            throw error;
        }
    }

    function replaceMethods(state) {
        let innerWidth = 0;
        let innerInsets = 0;
        const reserve = () => (state.active && state.width > 0 ? state.width : 0);
        // DisplayPolicy exists per display: only the instance of display 0 is adjusted.
        const isPrimary = policy => { try { return primary.$isSameObject(policy); } catch (_) { return false; } };

        // Each hook calls the original exactly once and keeps its own logic inside a try: an exception from the
        // agent must never surface in system_server.
        hooks.width.implementation = function (w, h, rotation, uiMode, cutout) {
            const value = hooks.width.call(this, w, h, rotation, uiMode, cutout);
            try {
                const px = reserve();
                if (px > 0 && w > h && value > px && isPrimary(this)) {
                    innerWidth++;
                    return value - px;
                }
            } catch (_) { }
            return value;
        };
        // getConfigDisplayWidth delegates to getNonDecorDisplayWidth. Subtract here only when that inner call
        // bypassed the hook (inlined or devirtualized by the compiler).
        hooks.configWidth.implementation = function (w, h, rotation, uiMode, cutout) {
            const before = innerWidth;
            const value = hooks.configWidth.call(this, w, h, rotation, uiMode, cutout);
            try {
                const px = reserve();
                if (px > 0 && innerWidth === before && w > h && value > px && isPrimary(this)) return value - px;
            } catch (_) { }
            return value;
        };
        hooks.insets.implementation = function (rotation, w, h, cutout, out) {
            hooks.insets.call(this, rotation, w, h, cutout, out);
            try {
                const px = reserve();
                if (px > 0 && out !== null && w > h && isPrimary(this)) {
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
                if (px > 0 && out !== null && innerInsets === before && w > h && isPrimary(this)) {
                    out.left.value = out.left.value + px;
                }
            } catch (_) { }
        };
        // Runs only when a control target changes (focus, bar control, the pending target of setWindow()). For
        // the panel's provider the target stays null: no leash and no client-side visibility.
        hooks.control.implementation = function (target, force) {
            let skip = false;
            try { skip = blocked !== null && blocked.$isSameObject(this); } catch (_) { }
            if (skip) return;
            return hooks.control.call(this, target, force);
        };
    }

    function removeHooks() {
        if (!installed) return;
        // Every replacement is cleared even if one of them cannot be; the first failure is reported afterwards.
        let failure = null;
        for (const method of Object.values(hooks)) {
            try { method.implementation = null; } catch (error) { failure ??= error; }
        }
        installed = false;
        if (failure !== null) throw failure;
    }

    function detach(win, provider) {
        const owner = provider.mWin.value;
        // setWindow(null) clears the source frame and visibility and, because the provider is controllable, the
        // panel's link back to it.
        if (owner !== null && owner.$isSameObject(win)) setProvider.call(display(), NAV, null, null);
        if (blocked !== null && blocked.$isSameObject(provider)) blocked = null;
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
            // Clients compute insets from InsetsState, and the server fits frames to it, only in the full mode.
            if (!fullInsetsMode()) throw reserveFailure('insets_mode');
            const content = display();
            if (content === null) throw reserveFailure('unsupported_display');
            const policy = content.getDisplayPolicy();
            if (!policy.$isSameObject(primary)) throw reserveFailure('unsupported_framework');
            if (policy.hasNavigationBar() || policy.getNavigationBar() !== null) throw reserveFailure('system_nav_bar');
            if (!navigationFitsFrames()) throw reserveFailure('unsupported_framework');
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
                    alive: !win.mRemoved.value
                };
            } catch (_) {
                return null;
            }
        },
        register(win) {
            const content = display();
            const controller = content.getInsetsStateController();
            const existing = controller.peekSourceProvider(NAV);
            if (existing !== null && existing.hasWindow()) throw reserveFailure('already_reserved');
            if (win.getControllableInsetProvider() !== null || declaresInsets(win)) throw reserveFailure('already_reserved');
            const provider = controller.getSourceProvider(NAV);
            // The navigation source is controllable exactly in the full insets mode.
            if (!provider.isControllable()) throw reserveFailure('unsupported_framework');
            blocked = Java.retain(provider);
            try {
                setProvider.call(content, NAV, win, null);
                if (!linked(win, provider)) throw reserveFailure('unsupported_framework');
            } catch (error) {
                try { detach(win, provider); } catch (_) { }
                blocked = null;
                throw error;
            }
            return {provider: Java.retain(provider)};
        },
        isRegistered(win, registration) {
            try {
                const provider = registration?.provider;
                if (!provider || blocked === null || !blocked.$isSameObject(provider)) return false;
                const owner = provider.mWin.value;
                return owner !== null && owner.$isSameObject(win) && linked(win, provider);
            } catch (_) {
                return false;
            }
        },
        unregister(win, registration) {
            const provider = registration?.provider;
            if (provider) detach(win, provider);
        },
        /** Removes what an agent that ended while the mode was on left behind. Only called when that is recorded. */
        cleanupStale(pkg) {
            const provider = display().getInsetsStateController().peekSourceProvider(NAV);
            if (provider === null || !provider.hasWindow()) return 0;
            const win = provider.mWin.value;
            if (!ownedOnCentralDisplay(win, pkg) || declaresInsets(win)) return 0;
            setProvider.call(display(), NAV, null, null);
            return 1;
        },
        layoutReserved(win, width, registration) {
            const source = registration.provider.getSource();
            const raw = display().getInsetsStateController().getRawInsetsState().peekSource(NAV);
            const frame = reserveRect(source.getFrame());
            const panel = reserveRect(win.getFrameLw());
            return source.isVisible() && raw !== null && raw.isVisible() && frame[0] === 0 && frame[2] === width &&
                frame.every((value, index) => value === panel[index]);
        },
        config: () => reserveConfig(display().getConfiguration()),
        computeConfig: () => reserveConfig(computeConfiguration.call(wms, 0)),
        reconfigure() {
            // Recomputes the display configuration, applies it through ActivityTaskManagerService (the global
            // configuration for display 0) and runs a surface placement.
            reconfigureDisplay.call(display());
        },
        relayout() {
            display().setLayoutNeeded();
            wms.requestTraversal();
        },
        installHooks,
        removeHooks
    };
}
