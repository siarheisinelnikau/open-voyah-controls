import { driverRouteRemaining } from './driver-navigation.js';

const DISPLAY = 'Cluster-Media-Display';
const OWNER = 'com.qinggan.instrumentcard';
const TAG = 'org.openvoyah.controls.driver-media-layout';
const EXACTLY = 0x40000000;
const BACKGROUND = 0xff121821 | 0;
const TITLE = 0xfff2f5fa | 0;
const SECONDARY = 0xffb2c0d0 | 0;
const ACCENT = 0xff86dcd3 | 0;
const CARD_BACKGROUND = 0xff202b38 | 0;
const CARD_BORDER = 0xff3c5061 | 0;

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

/** Center the compact card inside the same viewport as the large player. */
export function driverNavigationGeometry(viewport, density, fontScale, hasArrow = false) {
    if (!Number.isFinite(fontScale) || fontScale <= 0 || fontScale > 1.5) return null;
    const px = dp => Math.round(dp * density);
    const width = hasArrow ? Math.min(px(380), viewport.width - 2 * px(12)) : px(288);
    const tallerHeight = px(Math.max(128, 24 + 104 * fontScale));
    const streetLines = hasArrow && viewport.height >= tallerHeight + 2 * px(8) ? 2 : 1;
    const height = streetLines === 2 ? tallerHeight : px(Math.max(112, 28 + 76 * fontScale));
    const left = Math.floor((viewport.width - width) / 2);
    const top = Math.floor((viewport.height - height) / 2);
    if (left < px(12) || top < px(8)) return null;
    return {width, height, left, top, streetLines, horizontal: px(18), vertical: px(10), radius: px(16), stroke: px(1)};
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

    function create(host, overlay, mode) {
        removeOwned(overlay);
        const context = host.getContext();
        const root = Frame.$new(context);
        root.setTag(tag);
        root.setBackgroundColor(BACKGROUND);
        root.setAlpha(0);
        root.setImportantForAccessibility(4);
        root.setFocusable(false);
        root.setClickable(false);
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
        const layer = {host: Java.retain(host), mode, overlay, root, labels, geometry: '', text: '', at: -1};
        current = layer;
        if (mode === 'navigation') {
            layer.source = line(15, ACCENT, 1);
            layer.title = line(25, TITLE, 1);
            layer.detail = line(18, SECONDARY, 1);
            layer.title.setTypeface(null, 1);
            for (const label of [layer.source, layer.title, layer.detail]) label.setIncludeFontPadding(false);
            layer.maneuver = Image.$new(context);
            layer.maneuver.setScaleType(Scale.FIT_CENTER.value);
            layer.maneuver.setColorFilter(ACCENT);
            layer.maneuver.setVisibility(8);
            layer.maneuverAt = null;
            root.addView(labels);
            root.addView(layer.maneuver);
        } else {
            const art = Image.$new(context);
            art.setScaleType(Scale.CENTER_CROP.value);
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
            Object.assign(layer, {art, source, title, artist, progress, artworkRevision: -1, percent: -1});
        }
        overlay.add(root);
    }

    function geometryOf(host, overlay) {
        return overlay === null ? null : driverMediaGeometry({
            display: DISPLAY, owner: OWNER, attached: host.isAttachedToWindow(), shown: host.isShown(),
            width: host.getWidth(), height: host.getHeight(), density: host.getResources().getDisplayMetrics().density.value
        });
    }

    function select(host, overlay, mode) {
        if (current === null || !current.host.equals(host) || current.mode !== mode) {
            clear();
            create(host, overlay, mode);
        }
        return current;
    }

    function place(view, width, height, x, y) {
        const params = Layout.$new(width, height);
        params.leftMargin.value = x;
        params.topMargin.value = y;
        view.setLayoutParams(params);
    }

    function measure(root, {width, height}) {
        root.measure(EXACTLY | width, EXACTLY | height);
        root.layout(0, 0, width, height);
    }

    function renewLease(root, delay, duration) {
        const animator = root.animate();
        animator.cancel();
        if (root.getAlpha() !== 1) root.setAlpha(1);
        animator.alpha(0).setStartDelay(delay).setDuration(duration).start();
    }

    function update(host, model) {
        if (host === null || model === null || !model.title) { clear(host); return false; }
        const overlay = overlayOf(host);
        const geometry = geometryOf(host, overlay);
        if (geometry === null) { clear(host); return false; }
        const layer = select(host, overlay, 'media');
        const frame = JSON.stringify(geometry);
        let layoutChanged = false;
        if (layer.geometry !== frame) {
            const {width, height, inset, art, left, textWidth, progressHeight} = geometry;
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
        if (layoutChanged) measure(layer.root, geometry);
        renewLease(layer.root, 3500, 200);
        return true;
    }

    function navigationFrame(host, overlay, hasArrow = false) {
        const viewport = geometryOf(host, overlay);
        if (viewport === null) return null;
        const visible = Java.use('android.graphics.Rect').$new();
        if (!host.getLocalVisibleRect(visible) || visible.left.value !== 0 || visible.top.value !== 0 ||
            visible.right.value !== viewport.width || visible.bottom.value !== viewport.height) return null;
        const resources = host.getResources();
        const card = driverNavigationGeometry(viewport, resources.getDisplayMetrics().density.value,
            resources.getConfiguration().fontScale.value, hasArrow);
        return card === null ? null : {viewport, card};
    }

    function updateNavigation(host, model, now) {
        const remaining = driverRouteRemaining(model.at, now);
        const heading = driverMediaText(model.heading);
        const title = driverMediaText(model.title);
        if (remaining === 0 || !heading || !title) return false;
        const overlay = overlayOf(host);
        const hasArrow = model.maneuver != null;
        const frame = navigationFrame(host, overlay, hasArrow);
        if (frame === null) return false;
        const layer = select(host, overlay, 'navigation');
        const geometry = JSON.stringify([frame, hasArrow]);
        const detail = driverMediaText(model.detail);
        const text = JSON.stringify([heading, title, detail]);
        let layoutChanged = false;
        if (layer.geometry !== geometry) {
            const {width, height, left, top, streetLines, horizontal, vertical, radius, stroke} = frame.card;
            layer.title.setMaxLines(streetLines);
            const background = Java.use('android.graphics.drawable.GradientDrawable').$new();
            background.setColor(CARD_BACKGROUND);
            background.setCornerRadius(radius);
            background.setStroke(stroke, CARD_BORDER);
            layer.labels.setBackground(background);
            const arrowSize = Math.round(horizontal * 2.5);
            const textInset = hasArrow ? horizontal * 2 + arrowSize : horizontal;
            layer.labels.setPadding(textInset, vertical, horizontal, vertical);
            place(layer.labels, width, height, left, top);
            if (hasArrow) place(layer.maneuver, arrowSize, arrowSize, left + horizontal,
                top + Math.floor((height - arrowSize) / 2));
            layer.maneuver.setVisibility(hasArrow ? 0 : 8);
            layer.geometry = geometry;
            layoutChanged = true;
        }
        if (layer.text !== text) {
            layer.source.setText(JString.$new(heading));
            layer.title.setText(JString.$new(title));
            layer.detail.setText(JString.$new(detail));
            layer.detail.setVisibility(detail ? 0 : 8);
            layer.text = text;
            layoutChanged = true;
        }
        const maneuverAt = hasArrow ? (model.maneuverAt ?? model.at) : null;
        if (maneuverAt !== layer.maneuverAt) {
            layer.maneuver.setImageBitmap(model.maneuver ?? null);
            layer.maneuverAt = maneuverAt;
        }
        if (layoutChanged) measure(layer.root, frame.viewport);
        if (layer.at !== model.at) {
            renewLease(layer.root, remaining, 0);
            layer.at = model.at;
        }
        return true;
    }

    return {update, clear, updateNavigation, navigationFits: host => navigationFrame(host, overlayOf(host)) !== null};
}
