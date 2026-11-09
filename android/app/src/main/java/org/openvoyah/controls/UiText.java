package org.openvoyah.controls;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;

/** Resource access also works in services and transport adapters. */
public final class UiText extends Application {
    private static Context application;
    @Override public void onCreate() { super.onCreate(); application = this; }
    static String language() { return application.getSharedPreferences("ui", MODE_PRIVATE).getString("language", "ru"); }
    static void language(String value) {
        if (!value.equals("ru") && !value.equals("en")) throw new IllegalArgumentException("Invalid UI language");
        if (!application.getSharedPreferences("ui", MODE_PRIVATE).edit().putString("language", value).commit())
            throw new IllegalStateException("Could not save language");
    }
    static String get(int resource) {
        Configuration config = new Configuration(application.getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(language()));
        return application.createConfigurationContext(config).getString(resource);
    }
}
