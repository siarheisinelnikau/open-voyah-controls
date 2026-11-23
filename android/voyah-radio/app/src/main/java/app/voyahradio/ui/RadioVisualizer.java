package app.voyahradio.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import app.voyahradio.R;
import app.voyahradio.playback.AudioMeter;

final class RadioVisualizer extends View {
    private static final long FRAME_INTERVAL_MS = 33;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int accent;
    private final Runnable frameTick = this::drawNextFrame;
    private AudioMeter meter;
    private boolean playing;
    private boolean running;
    private String mode = "spectrum";

    RadioVisualizer(Context context) {
        super(context);
        accent = RadioTheme.color(context, R.attr.radio_accent_role);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void update(AudioMeter meter, String mode, boolean playing) {
        if (this.meter != meter && this.meter != null) this.meter.setEnabled(false);
        this.meter = meter;
        this.mode = mode;
        this.playing = playing;
        refresh();
    }

    private void refresh() {
        boolean active = meter != null && playing && !"off".equals(mode) && isAttachedToWindow()
                && isShown() && getWindowVisibility() == VISIBLE;
        if (meter != null) meter.setEnabled(active);
        if (active && !running) post(frameTick);
        if (!active && running) removeCallbacks(frameTick);
        running = active;
        if (!active) invalidate();
    }

    private void drawNextFrame() {
        if (!running) return;
        invalidate();
        postDelayed(frameTick, FRAME_INTERVAL_MS);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refresh();
    }

    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (frameTick != null) refresh();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (frameTick != null) refresh();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(frameTick);
        running = false;
        if (meter != null) meter.setEnabled(false);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float middle = height * 0.5f;
        paint.setColor(accent);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setAlpha(42);
        canvas.drawLine(0, middle, width, middle, paint);
        AudioMeter.Frame frame = meter == null ? null : meter.frame();
        if (frame == null) return;
        if ("wave".equals(mode)) {
            path.reset();
            for (int i = 0; i < frame.wave.length; i++) {
                float x = width * i / (frame.wave.length - 1);
                float y = middle - Math.max(-1, Math.min(1, frame.wave[i] * 1.6f)) * height * 0.38f;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            paint.setAlpha(255);
            paint.setStrokeWidth(2.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(path, paint);
        } else {
            paint.setStyle(Paint.Style.FILL);
            float step = width / frame.spectrum.length;
            float halfBar = step * 0.28f;
            for (int i = 0; i < frame.spectrum.length; i++) {
                float x = step * (i + 0.5f);
                float bar = Math.max(0.035f, frame.spectrum[i]) * height * 0.43f;
                paint.setAlpha(200);
                canvas.drawRoundRect(x - halfBar, middle - bar, x + halfBar, middle - 1, 4, 4, paint);
                paint.setAlpha(92);
                canvas.drawRoundRect(x - halfBar, middle + 1, x + halfBar, middle + bar, 4, 4, paint);
            }
        }
    }
}
