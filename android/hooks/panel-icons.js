// Application meta-data naming a plate-free glyph for the panel, and PackageManager.GET_META_DATA.
const PANEL_ICON = 'org.openvoyah.controls.PANEL_ICON';
const GET_META_DATA = 0x80;
// Settings.Global document of the custom icons Controls rendered: {"schema":1,"icons":{package: base64 PNG}}.
const PANEL_ICONS = 'org.openvoyah.controls.panelIcons';

/**
 * Builds the selected activity's icon for an existing Qinggan button.
 *
 * The drawable holds only the icon and the pressed ripple. The launcher's QGRadioGroup already draws
 * its selection slider (`dock_app_selected`) under the checked button, so a plate of our own would
 * stack a second rounded square on that entry.
 *
 * Two kinds of icon have no plate of their own, in this order of precedence:
 * - a picture Controls rendered from the driver's icon choice for the app (a custom picture, or the launcher
 *   icon without its white plate, which Yandex Navigator has from the start), published under `PANEL_ICONS`
 *   and named by the configuration's `customIcons`;
 * - a glyph that an app names with the `PANEL_ICON` application meta-data. Open Voyah Controls does:
 *   its launcher icon keeps a dark plate for the app grid, while the panel shows only the winged mark.
 * Any failure there falls back to the launcher icon.
 */
export function createPanelIcon(Java) {
    const Layer = Java.use('android.graphics.drawable.LayerDrawable');
    const Ripple = Java.use('android.graphics.drawable.RippleDrawable');
    const Shape = Java.use('android.graphics.drawable.GradientDrawable');
    const BitmapDrawable = Java.use('android.graphics.drawable.BitmapDrawable');
    const BitmapFactory = Java.use('android.graphics.BitmapFactory');
    const Base64 = Java.use('android.util.Base64');
    const Global = Java.use('android.provider.Settings$Global');
    const Colors = Java.use('android.content.res.ColorStateList');
    const Gravity = Java.use('android.view.Gravity');
    const rippleColor = Colors.valueOf(0x26ffffff);
    let published = {version: null, icons: {}, bitmaps: {}};

    function forget() {
        for (const bitmap of Object.values(published.bitmaps)) {
            try { bitmap?.$dispose(); } catch (_) { /* Only a reference is lost. */ }
        }
    }

    // A bitmap is decoded once per published version and shared; each button gets its own drawable.
    function publishedIcon(view, info, config) {
        const version = config?.customIcons;
        if (typeof version !== 'string' || version === '') return null;
        try {
            if (published.version !== version) {
                const raw = Global.getString(view.getContext().getContentResolver(), PANEL_ICONS);
                const document = raw === null ? null : JSON.parse(raw.toString());
                const icons = document?.schema === 1 && document.icons !== null && typeof document.icons === 'object'
                    ? document.icons : {};
                forget();
                published = {version, icons, bitmaps: {}};
            }
            const packageName = info.activityInfo.value.packageName.value;
            const data = published.icons[packageName];
            if (typeof data !== 'string') return null;
            if (!(packageName in published.bitmaps)) {
                const bytes = Base64.decode(data, 0);
                const bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                published.bitmaps[packageName] = bitmap === null ? null : Java.retain(bitmap);
            }
            const bitmap = published.bitmaps[packageName];
            return bitmap === null ? null : BitmapDrawable.$new(view.getResources(), bitmap);
        } catch (_) {
            return null;
        }
    }

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

    return (view, manager, info, config) => {
        const glyph = publishedIcon(view, info, config) ?? panelGlyph(manager, info);
        const icon = glyph ?? info.loadIcon(manager);
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
        }
        const layer = Layer.$new(Java.array('android.graphics.drawable.Drawable', [icon]));
        layer.setLayerGravity(0, Gravity.CENTER.value);
        layer.setLayerSize(0, Math.max(1, Math.round(iconWidth)), Math.max(1, Math.round(iconHeight)));
        // Without a mask the ripple only shows where the layers paint. A plate-free icon paints just its
        // shape, so its ripple fills the button's rounded bounds instead, where the launcher's slider sits.
        let mask = null;
        if (glyph !== null) {
            mask = Shape.$new();
            mask.setColor(-1);
            mask.setCornerRadius(Math.min(width, height) * 0.12);
        }
        return Ripple.$new(rippleColor, layer, mask);
    };
}
