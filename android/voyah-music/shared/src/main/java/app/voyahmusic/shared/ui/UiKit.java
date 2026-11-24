package app.voyahmusic.shared.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.voyahmusic.shared.R;

public final class UiKit {
    public static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    public static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    public static final int BACKGROUND = 0xff0f141c;
    public static final int PANEL = 0xff17202d;
    public static final int SURFACE = 0xff192230;
    public static final int TEXT = 0xffedf2fa;
    public static final int MUTED = 0xffadbace;
    public static final int ERROR = 0xffffbaaa;

    public enum Icon { NOTE, SEND, SETTINGS, BACK, PREVIOUS, NEXT, PLAY, PAUSE, SHUFFLE, REPEAT, REPEAT_ONE, MORE,
        CLOSE, SEARCH, FOLDER, FOLDER_ADD }

    public final int accent;
    public final int onAccent;
    public final int selected;
    public final int selectedEdge;
    public final int edge;
    private final Context context;

    public UiKit(Context context) {
        this.context = context;
        accent = role(R.attr.music_accent);
        onAccent = role(R.attr.music_on_accent);
        selected = role(R.attr.music_selected);
        selectedEdge = role(R.attr.music_selected_edge);
        edge = role(R.attr.music_edge);
    }

    public int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public LinearLayout row() {
        LinearLayout layout = new LinearLayout(context);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    public LinearLayout card(int padding) {
        LinearLayout column = column();
        column.setBackground(box(PANEL, edge, 20));
        column.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        return column;
    }

    public TextView text(CharSequence value, int size, int color, boolean medium) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (medium) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    public void truncate(TextView view, int lines) {
        view.setMaxLines(lines);
        view.setEllipsize(TextUtils.TruncateAt.END);
    }

    public Button button(CharSequence label, boolean primary, Runnable action) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextSize(15);
        button.setText(label);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(13), dp(8), dp(13), dp(8));
        button.setStateListAnimator(null);
        styleButton(button, primary);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    public void styleButton(Button button, boolean primary) {
        button.setTextColor(primary ? onAccent : TEXT);
        button.setBackground(actionBackground(primary ? accent : SURFACE, primary ? 0 : edge, 13));
    }

    public ImageButton iconButton(Icon icon, CharSequence label, boolean primary, Runnable action) {
        ImageButton button = new ImageButton(context);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setImageDrawable(glyph(icon, primary ? onAccent : TEXT, primary ? 26 : 22));
        button.setBackground(actionBackground(primary ? accent : SURFACE, primary ? 0 : edge, 14));
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    public ImageView mark(Icon icon, int size) {
        ImageView view = new ImageView(context);
        view.setImageDrawable(glyph(icon, accent, size));
        view.setScaleType(ImageView.ScaleType.CENTER);
        return view;
    }

    public ImageView emblem(int side) {
        ImageView view = mark(Icon.NOTE, side / 2);
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { selected, SURFACE });
        fill.setCornerRadius(dp(22));
        fill.setStroke(dp(1), selectedEdge);
        view.setBackground(fill);
        return view;
    }

    public void enabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.45f);
    }

    public GradientDrawable box(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    public Drawable actionBackground(int fill, int stroke, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x30ffffff),
                box(fill, stroke, radius), box(0xffffffff, 0, radius));
    }

    public void add(LinearLayout column, View view, int width, int height, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.topMargin = dp(top);
        column.addView(view, params);
    }

    public void center(LinearLayout column, View view, int width, int height, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(top);
        column.addView(view, params);
    }

    public LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, WRAP, 1f);
    }

    public void gap(LinearLayout row, int size) {
        row.addView(new View(context), dp(size), 1);
    }

    public FrameLayout.LayoutParams centeredSquare(int side) {
        return new FrameLayout.LayoutParams(dp(side), dp(side), Gravity.CENTER);
    }

    private int role(int attribute) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attribute, value, true);
        return value.data;
    }

    public Drawable glyph(Icon icon, int color, int size) {
        return new Glyph(icon, color, dp(size));
    }

    private static final class Glyph extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Icon icon;
        private final int size;

        Glyph(Icon icon, int color, int size) {
            this.icon = icon;
            this.size = size;
            paint.setColor(color);
            paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            canvas.save();
            canvas.translate(bounds.left, bounds.top);
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f);
            paint.setStyle(Paint.Style.STROKE);
            switch (icon) {
                case NOTE -> {
                    lines(canvas, 9, 17, 9, 5.8f, 20, 3, 20, 14.5f);
                    lines(canvas, 9, 9, 20, 6.2f);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawOval(3, 15, 10, 21, paint);
                    canvas.drawOval(14, 12.2f, 21, 18.2f, paint);
                }
                case SEND -> {
                    lines(canvas, 12, 15, 12, 3, 7.5f, 7.5f);
                    lines(canvas, 12, 3, 16.5f, 7.5f);
                    lines(canvas, 4, 13, 4, 20, 20, 20, 20, 13);
                }
                case SETTINGS -> {
                    canvas.drawCircle(12, 12, 6.5f, paint);
                    canvas.drawCircle(12, 12, 2.7f, paint);
                    for (int i = 0; i < 8; i++) {
                        canvas.drawLine(12, 3, 12, 5.4f, paint);
                        canvas.rotate(45, 12, 12);
                    }
                }
                case BACK -> {
                    lines(canvas, 20, 12, 4, 12, 10, 6);
                    lines(canvas, 4, 12, 10, 18);
                }
                case PREVIOUS -> {
                    canvas.drawLine(6, 5, 6, 19, paint);
                    paint.setStyle(Paint.Style.FILL);
                    lines(canvas, 8, 12, 19, 5, 19, 19, 8, 12);
                }
                case NEXT -> {
                    canvas.drawLine(18, 5, 18, 19, paint);
                    paint.setStyle(Paint.Style.FILL);
                    lines(canvas, 16, 12, 5, 5, 5, 19, 16, 12);
                }
                case PLAY -> {
                    paint.setStyle(Paint.Style.FILL);
                    lines(canvas, 7, 4, 20, 12, 7, 20, 7, 4);
                }
                case PAUSE -> {
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawRoundRect(6, 5, 10.2f, 19, 0.8f, 0.8f, paint);
                    canvas.drawRoundRect(13.8f, 5, 18, 19, 0.8f, 0.8f, paint);
                }
                case SHUFFLE -> {
                    lines(canvas, 3, 6, 6, 6, 18, 18, 21, 18, 18, 15);
                    lines(canvas, 21, 18, 18, 21);
                    lines(canvas, 3, 18, 6, 18, 9.8f, 14.2f);
                    lines(canvas, 13.8f, 10.2f, 18, 6, 21, 6, 18, 3);
                    lines(canvas, 21, 6, 18, 9);
                }
                case REPEAT, REPEAT_ONE -> {
                    lines(canvas, 4, 11, 4, 6, 20, 6, 17, 3);
                    lines(canvas, 20, 6, 17, 9);
                    lines(canvas, 20, 13, 20, 18, 4, 18, 7, 21);
                    lines(canvas, 4, 18, 7, 15);
                    if (icon == Icon.REPEAT_ONE) lines(canvas, 11, 11, 13, 10, 13, 15);
                }
                case MORE -> {
                    paint.setStyle(Paint.Style.FILL);
                    for (int y = 5; y <= 19; y += 7) canvas.drawCircle(12, y, 1.65f, paint);
                }
                case CLOSE -> {
                    lines(canvas, 6, 6, 18, 18);
                    lines(canvas, 6, 18, 18, 6);
                }
                case SEARCH -> {
                    canvas.drawCircle(10, 10, 5.5f, paint);
                    lines(canvas, 14.2f, 14.2f, 20, 20);
                }
                case FOLDER, FOLDER_ADD -> {
                    lines(canvas, 3, 5, 10, 5, 12.4f, 8, 21, 8, 21, 20, 3, 20, 3, 5);
                    if (icon == Icon.FOLDER_ADD) {
                        canvas.drawLine(12, 10.5f, 12, 17.5f, paint);
                        canvas.drawLine(8.5f, 14, 15.5f, 14, paint);
                    }
                }
            }
            canvas.restore();
        }

        private void lines(Canvas canvas, float... points) {
            path.rewind();
            path.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
            canvas.drawPath(path, paint);
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
