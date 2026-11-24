package org.openvoyah.controls;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import java.util.Locale;
import org.openvoyah.controls.core.display.DisplayEventBus;

/**
 * Strings in the app's own language setting, for screens as well as services and transport adapters.
 *
 * A screen asks for hundreds of strings while it composes, so the localized resources are kept until the language
 * or the device configuration changes instead of building a configuration context for every lookup.
 */
public final class UiText {
    private static final String STORE = "ui";
    private static final String LANGUAGE = "language";

    private static volatile ControlsApplication application;
    private static volatile SharedPreferences preferences;
    private static volatile Localized localized;

    private UiText() { }

    private static final class Localized {
        final String language;
        final Resources resources;

        Localized(String language, Resources resources) {
            this.language = language;
            this.resources = resources;
        }
    }

    static void attach(ControlsApplication app) {
        application = app;
        preferences = app.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        localized = null;
    }

    static void invalidate() { localized = null; }

    public static DisplayEventBus displayEvents(Context context) {
        return ((ControlsApplication) context.getApplicationContext()).displayEvents();
    }

    public static String language() {
        String deviceLanguage = Locale.getDefault().getLanguage();
        String stored = preferences.getString(LANGUAGE, null);
        if (supportsLanguage(stored)) return stored;
        return supportsLanguage(deviceLanguage) ? deviceLanguage : "en";
    }

    public static boolean supportsLanguage(String value) {
        return "en".equals(value) || "ru".equals(value) || "kk".equals(value);
    }

    public static int languageName(String value) {
        return switch (value) {
            case "ru" -> R.string.language_name_russian;
            case "kk" -> R.string.language_name_kazakh;
            default -> R.string.language_name_english;
        };
    }

    public static void language(String value) {
        if (!supportsLanguage(value)) throw new IllegalArgumentException("Invalid UI language");
        if (!preferences.edit().putString(LANGUAGE, value).commit()) throw new IllegalStateException("Could not save language");
        localized = null;
    }

    public static String get(int resource) {
        return resources().getString(resource);
    }

    public static String[] getArray(int resource) {
        return resources().getStringArray(resource);
    }

    private static Resources resources() {
        // The stored value is compared on every lookup because a settings restore writes the store directly.
        String language = language();
        Localized current = localized;
        if (current == null || !current.language.equals(language)) {
            Configuration config = new Configuration(application.getResources().getConfiguration());
            config.setLocale(Locale.forLanguageTag(language));
            current = new Localized(language, application.createConfigurationContext(config).getResources());
            localized = current;
        }
        return current.resources;
    }
}
