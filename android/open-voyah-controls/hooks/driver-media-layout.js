const DISPLAY = 'Cluster-Media-Display';
const OWNER = 'com.qinggan.instrumentcard';
const TAG = 'org.openvoyah.controls.driver-media-layout';
const EXACTLY = 0x40000000;
const BACKGROUND = 0xff121821 | 0;
const TITLE = 0xfff2f5fa | 0;
const SECONDARY = 0xffb2c0d0 | 0;
const ACCENT = 0xff86dcd3 | 0;

export function driverMediaText(value) {
    return String(value ?? '').slice(0, 240).replace(/[\x00-\x1f\x7f\u202a-\u202e\u2066-\u2069]/g, ' ')
        .replace(/\s+/g, ' ').trim();
}

/** Viewport coordinates are relative to the current OEM music view. */
export function driverMediaGeometry({display, owner, attached, shown, width, height, density}) {
    if (display !== DISPLAY || owner !== OWNER || !attached || !shown || !Number.isFinite(density) ||
        density < 0.5 || density > 4 || !Number.isInteger(width) || !Number.isInteger(height) ||
        width < 320 * density || height < 160 * density || width > 1280 * density || height > 800 * density) return null;
    const px = dp => Math.round(dp * density);
    const inset = px(18);
    const art = Math.min(Math.round(width * 0.32), height - inset * 2, px(180));
    const left = inset * 2 + art;
    return {width, height, inset, art, left, textWidth: width - inset - left, progressHeight: px(4)};
}

/** An optional read-only view in Android's drawing overlay. Its lease fades even if the agent stops. */
export function createDriverMediaLayer(Java) {
    if (Java.use('android.os.Build$VERSION').SDK_INT.value !== 30) return null;
    const ViewGroup = Java.use('android.view.ViewGroup');
    const overlayGroup = Java.use('android.view.ViewOverlay').getOverlayView.overload();
    const Frame = Java.use('android.widget.FrameLayout');
    const Layout = Java.use('android.widget.FrameLayout$LayoutParams');
    const Linear = Java.use('android.widget.LinearLayout');
    const Image = Java.use('android.widget.ImageView');
    const Scale = Java.use('android.widget.ImageView$ScaleType');
    const Text = Java.use('android.widget.TextView');
    const Ellipsize = Java.use('android.text.TextUtils$TruncateAt');
    const JString = Java.use('java.lang.String');
    const Progress = Java.use('android.widget.ProgressBar');
    const Tint = Java.use('android.content.res.ColorStateList');
    const tag = JString.$new(TAG);
    let current = null;

    function overlayOf(host) {
        if (host === null || !ViewGroup.class.isInstance(host)) return null;
        const display = host.getDisplay();
        if (display === null || String(display.getName()) !== DISPLAY ||
            String(host.getContext().getPackageName()) !== OWNER) return null;
        return Java.cast(host, ViewGroup).getOverlay();
    }

    function removeOwned(overlay) {
        if (overlay === null) return;
        // Android 11 exposes this accessor in the running framework. Verify it before any attachment.
        const group = overlayGroup.call(overlay);
        const previous = group.findViewWithTag(tag);
        if (previous !== null && previous.getParent()?.equals(group)) overlay.remove(previous);
    }

    function clear(host = null) {
        if (current !== null) {
            // Clear only this view. The OEM may draw its own overlay on the same host.
            current.root.animate().cancel();
            current.root.setAlpha(0);
            current.overlay.remove(current.root);
            current.host.$dispose();
            current = null;
        }
        if (host !== null) removeOwned(overlayOf(host));
    }

    function create(host, overlay) {
        removeOwned(overlay);
        const context = host.getContext();
        const root = Frame.$new(context);
        root.setTag(tag);
        root.setBackgroundColor(BACKGROUND);
        root.setAlpha(0);
        root.setImportantForAccessibility(4);
        root.setFocusable(false);
        root.setClickable(false);
        const art = Image.$new(context);
        art.setScaleType(Scale.CENTER_CROP.value);
        const labels = Linear.$new(context);
        labels.setOrientation(1);
        labels.setGravity(16);
        function line(size, color, lines) {
            const view = Text.$new(context);
            view.setTextSize(size);
            view.setTextColor(color);
            view.setMaxLines(lines);
            view.setEllipsize(Ellipsize.END.value);
            labels.addView(view);
            return view;
        }
        const source = line(14, ACCENT, 1);
        const title = line(27, TITLE, 2);
        const artist = line(17, SECONDARY, 1);
        const progress = Progress.$new(context, null, 16842872); // android.R.attr.progressBarStyleHorizontal
        progress.setMax(100);
        progress.setProgressTintList(Tint.valueOf(ACCENT));
        progress.setVisibility(8);
        root.addView(art);
        root.addView(labels);
        root.addView(progress);
        current = {host: Java.retain(host), overlay, root, art, source, title, artist, labels, progress,
            geometry: '', text: '', artworkRevision: -1, percent: -1};
        overlay.add(root);
    }

    function update(host, model) {
        if (host === null || model === null || !model.title) { clear(host); return false; }
        const overlay = overlayOf(host);
        const geometry = overlay === null ? null : driverMediaGeometry({
            display: DISPLAY, owner: OWNER, attached: host.isAttachedToWindow(), shown: host.isShown(),
            width: host.getWidth(), height: host.getHeight(), density: host.getResources().getDisplayMetrics().density.value
        });
        if (geometry === null) { clear(host); return false; }
        if (current === null || !current.host.equals(host)) {
            clear();
            create(host, overlay);
        }
        const layer = current;
        const frame = JSON.stringify(geometry);
        let layoutChanged = false;
        if (layer.geometry !== frame) {
            const {width, height, inset, art, left, textWidth, progressHeight} = geometry;
            function place(view, w, h, x, y) {
                const params = Layout.$new(w, h);
                params.leftMargin.value = x;
                params.topMargin.value = y;
                view.setLayoutParams(params);
            }
            place(layer.art, art, art, inset, Math.round((height - art) / 2));
            place(layer.labels, textWidth, height - inset * 2, left, inset);
            place(layer.progress, textWidth, progressHeight, left, height - inset - progressHeight);
            layer.geometry = frame;
            layoutChanged = true;
        }
        const text = JSON.stringify([model.source, model.title, model.artist]);
        if (layer.text !== text) {
            layer.source.setText(JString.$new(model.source));
            layer.title.setText(JString.$new(model.title));
            layer.artist.setText(JString.$new(model.artist));
            layer.text = text;
            layoutChanged = true;
        }
        if (model.artworkRevision !== layer.artworkRevision) {
            const drawable = model.cover?.getConstantState()?.newDrawable() ?? null;
            layer.art.setImageDrawable(drawable);
            layer.artworkRevision = model.artworkRevision;
        }
        const percent = model.progress === null ? -1 : Math.round(100 * model.progress.position / model.progress.duration);
        if (layer.percent !== percent) {
            if ((layer.percent < 0) !== (percent < 0) || layer.percent === -1) {
                layer.progress.setVisibility(percent < 0 ? 8 : 0);
                layoutChanged = true;
            }
            if (percent >= 0) layer.progress.setProgress(percent);
            layer.percent = percent;
        }
        if (layoutChanged) {
            layer.root.measure(EXACTLY | geometry.width, EXACTLY | geometry.height);
            layer.root.layout(0, 0, geometry.width, geometry.height);
        }
        const animator = layer.root.animate();
        animator.cancel();
        if (layer.root.getAlpha() !== 1) layer.root.setAlpha(1);
        animator.alpha(0).setStartDelay(3500).setDuration(200).start();
        return true;
    }

    return {update, clear};
}
