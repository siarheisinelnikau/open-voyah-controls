/** Builds the selected activity's icon for an existing Qinggan button. */
export function createPanelIcon(Java) {
    const Layer = Java.use('android.graphics.drawable.LayerDrawable');
    const Ripple = Java.use('android.graphics.drawable.RippleDrawable');
    const States = Java.use('android.graphics.drawable.StateListDrawable');
    const Highlight = Java.use('android.graphics.drawable.GradientDrawable');
    const Color = Java.use('android.graphics.drawable.ColorDrawable');
    const Attr = Java.use('android.R$attr');
    const Colors = Java.use('android.content.res.ColorStateList');
    const Gravity = Java.use('android.view.Gravity');
    const rippleColor = Colors.valueOf(0x26ffffff);

    return (view, manager, info) => {
        const icon = info.loadIcon(manager);
        const layout = view.getLayoutParams();
        const width = view.getWidth() || (layout === null ? 0 : layout.width.value);
        const height = view.getHeight() || (layout === null ? 0 : layout.height.value);
        const size = Math.round(Math.min(width, height) * 0.62);
        if (icon === null || size < 1) return null;
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
        return Ripple.$new(rippleColor, layer, null);
    };
}
