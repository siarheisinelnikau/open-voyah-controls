package org.openvoyah.controls.presentation.theme;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.openvoyah.controls.R;
import org.openvoyah.controls.UiText;

/** Shared styling for screens that host Android views or display surfaces. */
public final class CockpitViews {
    private CockpitViews() { }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static LinearLayout page(Activity activity, int title) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int space = dp(activity, 20);
        root.setPadding(space, space, space, space);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(space + insets.getSystemWindowInsetLeft(), space + insets.getSystemWindowInsetTop(),
                space + insets.getSystemWindowInsetRight(), space + insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(activity);
        back.setText(UiText.get(R.string.cockpit_back));
        back.setOnClickListener(view -> activity.finish());
        header.addView(back);
        TextView heading = new TextView(activity);
        heading.setText(UiText.get(title));
        heading.setTextSize(26);
        heading.setTextColor(activity.getColor(R.color.cockpit_text));
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setPadding(dp(activity, 16), 0, 0, 0);
        header.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, -2);
        row.bottomMargin = dp(activity, 14);
        root.addView(header, row);
        return root;
    }

    public static LinearLayout card(LinearLayout root) {
        Context context = root.getContext();
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        int space = dp(context, 14);
        card.setPadding(space, space, space, space);
        GradientDrawable background = new GradientDrawable();
        background.setColor(context.getColor(R.color.cockpit_surface));
        background.setCornerRadius(dp(context, 18));
        background.setStroke(dp(context, 1), context.getColor(R.color.cockpit_stroke));
        card.setBackground(background);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = dp(context, 12);
        root.addView(card, size);
        return card;
    }

    public static void addButton(LinearLayout parent, Button button) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = dp(parent.getContext(), 8);
        parent.addView(button, size);
    }
}
