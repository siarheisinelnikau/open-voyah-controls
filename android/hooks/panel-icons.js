// Application meta-data naming a plate-free glyph for the panel, and PackageManager.GET_META_DATA.
const PANEL_ICON = 'org.openvoyah.controls.PANEL_ICON';
const GET_META_DATA = 0x80;
// An adaptive icon shows the middle 72 dp of its 108 dp layers. A foreground drawn without its plate
// shows that middle part 1.3 times larger, so the Yandex Navigator arrow, which fills about 60% of its
// plate, matches the factory glyphs beside it (about half the button height).
const FOREGROUND_SCALE = 108 / 72 * 1.3;

/**
 * Builds the selected activity's icon for an existing Qinggan button.
 *
 * The drawable holds only the icon and the pressed ripple. The launcher's QGRadioGroup already draws
 * its selection slider (`dock_app_selected`) under the checked button, so a plate of our own would
 * stack a second rounded square on that entry.
 *
 * Two kinds of icon have no plate of their own:
 * - a glyph that an app names with the `PANEL_ICON` application meta-data. Open Voyah Controls does:
 *   its launcher icon keeps a dark plate for the app grid, while the panel shows only the winged mark;
 * - the foreground layer of the adaptive launcher icon of a package that the panel configuration lists
 *   in `foregroundIcons` (Controls lists Yandex Navigator unless the driver turns that off).
 * Any failure there falls back to the launcher icon.
 */
export function createPanelIcon(Java) {
    const Layer = Java.use('android.graphics.drawable.LayerDrawable');
    const Ripple = Java.use('android.graphics.drawable.RippleDrawable');
    const Shape = Java.use('android.graphics.drawable.GradientDrawable');
    const Adaptive = Java.use('android.graphics.drawable.AdaptiveIconDrawable');
    const Colors = Java.use('android.content.res.ColorStateList');
    const Gravity = Java.use('android.view.Gravity');
    const rippleColor = Colors.valueOf(0x26ffffff);

    function panelGlyph(manager, info) {
        try {
            const packageName = info.activityInfo.value.packageName.value;
            const app = manager.getApplicationInfo.overload('java.lang.String', 'int')
                .call(manager, packageName, GET_META_DATA);
            const id = app.metaData.value?.getInt(PANEL_ICON) ?? 0;
            return id === 0 ? null : manager.getDrawable(packageName, id, app);
        } catch (_) {
            return null;
        }
    }

    function foregroundOf(icon, info, config) {
        const packages = config?.foregroundIcons;
        if (icon === null || !Array.isArray(packages)) return null;
        try {
            if (!packages.includes(info.activityInfo.value.packageName.value) || !Adaptive.class.isInstance(icon)) return null;
            return Java.cast(icon, Adaptive).getForeground();
        } catch (_) {
            return null;
        }
    }

    return (view, manager, info, config) => {
        const glyph = panelGlyph(manager, info);
        const launcherIcon = glyph === null ? info.loadIcon(manager) : null;
        const foreground = foregroundOf(launcherIcon, info, config);
        const icon = glyph ?? foreground ?? launcherIcon;
        const layout = view.getLayoutParams();
        const width = view.getWidth() || (layout === null ? 0 : layout.width.value);
        const height = view.getHeight() || (layout === null ? 0 : layout.height.value);
        const size = Math.round(Math.min(width, height) * 0.62);
        if (icon === null || size < 1) return null;
        const aspect = Math.max(1, icon.getIntrinsicWidth()) / Math.max(1, icon.getIntrinsicHeight());
        let iconWidth = size * Math.min(1, aspect), iconHeight = size / Math.max(1, aspect);
        if (glyph !== null && aspect > 1) {
            // App icons fill the square with their plate; a wide glyph fitted to its width would look small
            // beside them. It takes their height instead and runs wider, within the button.
            iconHeight = Math.min(size, width / aspect);
            iconWidth = iconHeight * aspect;
        } else if (foreground !== null) {
            // Square by definition. It overflows the button, but only its transparent margin does.
            iconWidth = iconHeight = size * FOREGROUND_SCALE;
        }
        const layer = Layer.$new(Java.array('android.graphics.drawable.Drawable', [icon]));
        layer.setLayerGravity(0, Gravity.CENTER.value);
        layer.setLayerSize(0, Math.max(1, Math.round(iconWidth)), Math.max(1, Math.round(iconHeight)));
        // Without a mask the ripple only shows where the layers paint. A plate-free icon paints just its
        // shape, so its ripple fills the button's rounded bounds instead, where the launcher's slider sits.
        let mask = null;
        if (glyph !== null || foreground !== null) {
            mask = Shape.$new();
            mask.setColor(-1);
            mask.setCornerRadius(Math.min(width, height) * 0.12);
        }
        return Ripple.$new(rippleColor, layer, mask);
    };
}
