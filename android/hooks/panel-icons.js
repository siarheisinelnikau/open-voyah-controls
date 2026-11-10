/** Draws the selected activity's icon in the existing Qinggan button. */
export function installPanelIcons(Java, Nav, panels, readConfig, actionFor, buildIntent) {
    const theme = Nav.updateTheme.overload();
    const release = Nav.release.overload();
    const ActivityThread = Java.use('android.app.ActivityThread');
    const PackageManager = Java.use('android.content.pm.PackageManager');
    const Layer = Java.use('android.graphics.drawable.LayerDrawable');
    const Ripple = Java.use('android.graphics.drawable.RippleDrawable');
    const States = Java.use('android.graphics.drawable.StateListDrawable');
    const Highlight = Java.use('android.graphics.drawable.GradientDrawable');
    const Color = Java.use('android.graphics.drawable.ColorDrawable');
    const Attr = Java.use('android.R$attr');
    const Colors = Java.use('android.content.res.ColorStateList');
    const Gravity = Java.use('android.view.Gravity');
    const bars = Java.use('java.util.WeakHashMap').$new();
    const rippleColor = Colors.valueOf(0x26ffffff);
    let applied = '', queued = false, lastError = '';
    let restoringTheme = null;

    function logError(error) {
        const message = String(error);
        if (message !== lastError) {
            lastError = message;
            console.log('OVC_PANEL_ICONS_ERROR ' + message);
        }
    }

    function draw(view, manager, intent) {
        const info = manager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY.value);
        if (info === null || info.activityInfo.value === null) return;
        const icon = info.loadIcon(manager);
        const layout = view.getLayoutParams();
        const width = view.getWidth() || (layout === null ? 0 : layout.width.value);
        const height = view.getHeight() || (layout === null ? 0 : layout.height.value);
        const size = Math.round(Math.min(width, height) * 0.62);
        if (icon === null || size < 1) return;
        const aspect = Math.max(1, icon.getIntrinsicWidth()) / Math.max(1, icon.getIntrinsicHeight());
        const checked = Highlight.$new();
        checked.setColor(0x3383d6ff);
        checked.setCornerRadius(Math.min(width, height) * 0.12);
        const states = States.$new();
        states.addState(Java.array('int', [Attr.state_checked.value]), checked);
        states.addState(Java.array('int', []), Color.$new(0));
        const layer = Layer.$new(Java.array('android.graphics.drawable.Drawable', [states, icon]));
        layer.setLayerGravity(1, Gravity.CENTER.value);
        layer.setLayerSize(1, Math.max(1, Math.round(size * Math.min(1, aspect))),
            Math.max(1, Math.round(size / Math.max(1, aspect))));
        const padding = [view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), view.getPaddingBottom()];
        view.setBackground(Ripple.$new(rippleColor, layer, null));
        view.setPadding(...padding);
    }

    function paint(nav, config, skip = null) {
        const slots = panels[nav.mScreenId.value];
        if (!slots || config?.enabled !== true) return;
        const manager = nav.mContext.value.getPackageManager();
        for (const [field, , slot] of slots) {
            const action = slot === skip ? null : actionFor(config, slot);
            if (action === null) continue;
            try {
                const view = nav[field].value;
                const intent = buildIntent(action);
                if (view !== null && intent !== null) draw(view, manager, intent);
            } catch (error) { logError(error); }
        }
    }

    function refresh(nav, config, reset = false, skip = null) {
        try {
            if (!panels[nav.mScreenId.value]) return true;
            if (reset) {
                const previous = restoringTheme;
                restoringTheme = nav;
                try { theme.call(nav); }
                finally { restoringTheme = previous; }
            }
            paint(nav, config, skip);
            return true;
        } catch (error) {
            logError(error);
            return false;
        }
    }

    function track(nav, reset = false) {
        try {
            if (!panels[nav.mScreenId.value]) return;
            bars.put(nav, null);
            refresh(nav, readConfig(nav.mContext.value), reset);
        } catch (error) { logError(error); }
    }

    release.implementation = function () {
        try { bars.remove(this); } catch (error) { logError(error); }
        return release.call(this);
    };
    try {
        theme.implementation = function () {
            const result = theme.call(this);
            if (restoringTheme === null || !this.equals(restoringTheme)) track(this);
            return result;
        };
    } catch (error) {
        release.implementation = null;
        throw error;
    }

    // The hook owns this timer. Only changes to panel assignments schedule work on the UI thread.
    const timer = setInterval(() => Java.perform(() => {
        try {
            const app = ActivityThread.currentApplication();
            if (app === null || queued) return;
            const config = readConfig(app);
            const valid = config?.schema === 1;
            const token = valid && config.enabled === false && /^[0-9a-f]{32}$/.test(config.restoreIcons) ? config.restoreIcons : '';
            const assignments = valid && config.enabled === true ? config.actions : null;
            const signature = JSON.stringify([assignments, token]);
            if (signature === applied) return;
            queued = true;
            Java.scheduleOnMainThread(() => {
                try {
                    let restored = true;
                    for (const nav of bars.keySet().toArray()) {
                        if (!refresh(Java.cast(nav, Nav), config, true)) restored = false;
                    }
                    applied = signature;
                    if (token && restored) console.log('OVC_PANEL_ICONS_RESTORED ' + token);
                } catch (error) { logError(error); }
                finally { queued = false; }
            });
        } catch (error) {
            queued = false;
            logError(error);
        }
    }), 1000);
    console.log('OVC_PANEL_ICONS_READY');
    return {
        track,
        dispose() {
            clearInterval(timer);
            theme.implementation = null;
            release.implementation = null;
        },
        failed(nav, slot) {
            try { refresh(nav, readConfig(nav.mContext.value), true, slot); }
            catch (error) { logError(error); }
        }
    };
}
