package org.openvoyah.controls;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;
import org.openvoyah.controls.core.display.DisplayEventBus;
import org.openvoyah.controls.data.settings.LocalSettingsArchive;

/** Resource access also works in services and transport adapters. */
public final class UiText extends Application {
    private static Context application;
    private final DisplayEventBus displayEvents = new DisplayEventBus();
    public static DisplayEventBus displayEvents(Context context) {
        return ((UiText) context.getApplicationContext()).displayEvents;
    }
    @Override public void onCreate() {
        super.onCreate();
        application = this;
        try { new LocalSettingsArchive(this).recoverInterruptedRestore(); }
        catch (Exception ex) { android.util.Log.e("OpenVoyahControls", "Could not recover local settings", ex); }
    }
    public static String language() {
        String deviceLanguage = Locale.getDefault().getLanguage();
        return application.getSharedPreferences("ui", MODE_PRIVATE).getString("language", deviceLanguage.equals("ru") ? "ru" : "en");
    }
    public static void language(String value) {
        if (!value.equals("ru") && !value.equals("en")) throw new IllegalArgumentException("Invalid UI language");
        if (!application.getSharedPreferences("ui", MODE_PRIVATE).edit().putString("language", value).commit())
            throw new IllegalStateException("Could not save language");
    }
    public static String get(int resource) {
        Configuration config = new Configuration(application.getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(language()));
        return application.createConfigurationContext(config).getString(resource);
    }
}
