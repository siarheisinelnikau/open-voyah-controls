package app.voyahradio.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

@SuppressLint("ViewConstructor") // Glyphs are constructed in code with an explicit shape and size.
final class RadioGlyph extends View {
    enum Kind { SEARCH, CLEAR, STAR, STAR_FILLED, PLAY, PAUSE, PREVIOUS, NEXT, SIGNAL, SETTINGS, BACK, UP, DOWN }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private static final RectF OUTER_SIGNAL = new RectF(2, 2, 22, 22);
    private static final RectF INNER_SIGNAL = new RectF(6, 6, 18, 18);
    private Kind kind;
    private int color;
    private final float size;

    RadioGlyph(Context context, Kind kind, int color, int sizeDp) {
        super(context);
        this.kind = kind;
        this.color = color;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        size = sizeDp * getResources().getDisplayMetrics().density;
    }

    void appearance(Kind kind, int color) {
        if (this.kind == kind && this.color == color) return;
        this.kind = kind;
        this.color = color;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(size, Math.min(getWidth(), getHeight())) / 24f;
        canvas.save();
        canvas.translate((getWidth() - 24 * scale) / 2f, (getHeight() - 24 * scale) / 2f);
        canvas.scale(scale, scale);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.7f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        switch (kind) {
            case SEARCH -> {
                canvas.drawCircle(10.5f, 10.5f, 5.5f, paint);
                canvas.drawLine(14.5f, 14.5f, 19.5f, 19.5f, paint);
            }
            case CLEAR -> {
                canvas.drawLine(6.5f, 6.5f, 17.5f, 17.5f, paint);
                canvas.drawLine(6.5f, 17.5f, 17.5f, 6.5f, paint);
            }
            case STAR, STAR_FILLED -> {
                if (kind == Kind.STAR_FILLED) paint.setStyle(Paint.Style.FILL_AND_STROKE);
                path.reset();
                for (int point = 0; point < 10; point++) {
                    double angle = Math.PI * (point / 5.0 - 0.5);
                    float radius = point % 2 == 0 ? 8.7f : 4.2f;
                    float x = 12 + (float) Math.cos(angle) * radius;
                    float y = 12 + (float) Math.sin(angle) * radius;
                    if (point == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                path.close();
                canvas.drawPath(path, paint);
            }
            case PLAY -> triangle(canvas, 8, 19, 5, 19);
            case PAUSE -> {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(7, 5, 10.5f, 19, 0.8f, 0.8f, paint);
                canvas.drawRoundRect(13.5f, 5, 17, 19, 0.8f, 0.8f, paint);
            }
            case PREVIOUS -> {
                canvas.drawLine(6, 6, 6, 18, paint);
                triangle(canvas, 18, 8.5f, 6, 18);
            }
            case NEXT -> {
                canvas.drawLine(18, 6, 18, 18, paint);
                triangle(canvas, 6, 15.5f, 6, 18);
            }
            case SETTINGS -> {
                canvas.drawCircle(12, 12, 6.2f, paint);
                canvas.drawCircle(12, 12, 2.2f, paint);
                for (int tooth = 0; tooth < 8; tooth++) {
                    float dx = (float) Math.cos(tooth * Math.PI / 4);
                    float dy = (float) Math.sin(tooth * Math.PI / 4);
                    canvas.drawLine(12 + 6.2f * dx, 12 + 6.2f * dy,
                            12 + 9 * dx, 12 + 9 * dy, paint);
                }
            }
            case BACK -> {
                canvas.drawLine(5, 12, 19, 12, paint);
                canvas.drawLine(5, 12, 11, 6, paint);
                canvas.drawLine(5, 12, 11, 18, paint);
            }
            case UP, DOWN -> {
                float tip = kind == Kind.UP ? 8.5f : 15.5f;
                float end = kind == Kind.UP ? 15.5f : 8.5f;
                canvas.drawLine(6, end, 12, tip, paint);
                canvas.drawLine(12, tip, 18, end, paint);
            }
            case SIGNAL -> {
                paint.setStrokeWidth(1.25f);
                canvas.drawArc(OUTER_SIGNAL, 140, 80, false, paint);
                canvas.drawArc(OUTER_SIGNAL, -40, 80, false, paint);
                canvas.drawArc(INNER_SIGNAL, 140, 80, false, paint);
                canvas.drawArc(INNER_SIGNAL, -40, 80, false, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(12, 12, 2, paint);
            }
        }
        canvas.restore();
    }

    private void triangle(Canvas canvas, float base, float tip, float top, float bottom) {
        paint.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(base, top);
        path.lineTo(tip, 12);
        path.lineTo(base, bottom);
        path.close();
        canvas.drawPath(path, paint);
    }
}
