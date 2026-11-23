package app.voyahradio.ui;

import android.app.Activity;
import android.content.Context;
import android.util.TypedValue;

import app.voyahradio.R;

final class RadioTheme {
    private RadioTheme() {}

    static void apply(Activity activity, String theme) {
        activity.setTheme("amber".equals(theme) ? R.style.RadioTheme_Amber : R.style.RadioTheme);
    }

    static int color(Context context, int role) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(role, value, true);
        return value.data;
    }
}
