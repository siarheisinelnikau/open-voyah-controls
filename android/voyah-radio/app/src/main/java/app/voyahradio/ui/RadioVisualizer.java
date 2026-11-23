package app.voyahradio.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

import app.voyahradio.R;
import app.voyahradio.playback.AudioMeter;

final class RadioVisualizer extends View {
    private static final long FRAME_INTERVAL_MS = 33;
    private static final float WAVE_RESPONSE_MS = 50f;
    private static final float SPECTRUM_RISE_MS = 75f;
    private static final float SPECTRUM_FALL_MS = 170f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int accent;
    private final Runnable frameTick = this::drawNextFrame;
    private AudioMeter meter;
    private boolean playing;
    private boolean running;
    private boolean smoothing;
    private float[] smoothedSamples;
    private long lastSmoothingUpdate;
    private String mode = "spectrum";

    RadioVisualizer(Context context) {
        super(context);
        accent = RadioTheme.color(context, R.attr.radio_accent_role);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void update(AudioMeter meter, String mode, boolean playing, boolean smoothing) {
        if (this.meter != meter && this.meter != null) this.meter.setEnabled(false);
        if (this.meter != meter || !this.mode.equals(mode) || this.smoothing != smoothing || !playing) {
            lastSmoothingUpdate = 0;
        }
        this.meter = meter;
        this.mode = mode;
        this.playing = playing;
        this.smoothing = smoothing;
        refresh();
    }

    private void refresh() {
        boolean active = meter != null && playing && !"off".equals(mode) && isAttachedToWindow()
                && isShown() && getWindowVisibility() == VISIBLE;
        if (meter != null) meter.setEnabled(active);
        if (active && !running) post(frameTick);
        if (!active && running) {
            removeCallbacks(frameTick);
            lastSmoothingUpdate = 0;
        }
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
        lastSmoothingUpdate = 0;
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
        boolean waveform = "wave".equals(mode);
        float[] samples = waveform ? frame.wave : frame.spectrum;
        if (smoothing) samples = smooth(samples, waveform);
        if (waveform) {
            path.reset();
            for (int i = 0; i < samples.length; i++) {
                float x = width * i / (samples.length - 1);
                float y = middle - Math.max(-1, Math.min(1, samples[i] * 1.6f)) * height * 0.38f;
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
            float step = width / samples.length;
            float halfBar = step * 0.28f;
            for (int i = 0; i < samples.length; i++) {
                float x = step * (i + 0.5f);
                float bar = Math.max(0.035f, samples[i]) * height * 0.43f;
                paint.setAlpha(200);
                canvas.drawRoundRect(x - halfBar, middle - bar, x + halfBar, middle - 1, 4, 4, paint);
                paint.setAlpha(92);
                canvas.drawRoundRect(x - halfBar, middle + 1, x + halfBar, middle + bar, 4, 4, paint);
            }
        }
    }

    private float[] smooth(float[] samples, boolean waveform) {
        long now = SystemClock.uptimeMillis();
        if (smoothedSamples == null || smoothedSamples.length != samples.length) {
            smoothedSamples = samples.clone();
        } else if (lastSmoothingUpdate == 0) {
            System.arraycopy(samples, 0, smoothedSamples, 0, samples.length);
        } else {
            long elapsed = Math.min(now - lastSmoothingUpdate, 100);
            float rise = (float) (1 - Math.exp(-elapsed / (waveform ? WAVE_RESPONSE_MS : SPECTRUM_RISE_MS)));
            float fall = waveform ? rise : (float) (1 - Math.exp(-elapsed / SPECTRUM_FALL_MS));
            for (int i = 0; i < samples.length; i++) {
                float difference = samples[i] - smoothedSamples[i];
                smoothedSamples[i] += difference * (difference >= 0 ? rise : fall);
            }
        }
        lastSmoothingUpdate = now;
        return smoothedSamples;
    }
}
