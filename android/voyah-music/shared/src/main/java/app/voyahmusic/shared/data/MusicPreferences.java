package app.voyahmusic.shared.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

public final class MusicPreferences {
    private final SharedPreferences values;

    public MusicPreferences(Context context) {
        values = context.getSharedPreferences("music_settings", Context.MODE_PRIVATE);
    }

    public String language() {
        return values.getString("language", "system");
    }

    public void language(String value) {
        values.edit().putString("language", value).apply();
    }

    public boolean amber() {
        return values.getBoolean("amber", false);
    }

    public void amber(boolean enabled) {
        values.edit().putBoolean("amber", enabled).apply();
    }

    public boolean startupUpdates() {
        return values.getBoolean("startup_updates", true);
    }

    public void startupUpdates(boolean enabled) {
        values.edit().putBoolean("startup_updates", enabled).apply();
    }

    public static Context localized(Context context) {
        String language = new MusicPreferences(context).language();
        if ("system".equals(language)) return context;
        Configuration override = new Configuration();
        override.setLocales(new LocaleList(Locale.forLanguageTag(language)));
        return context.createConfigurationContext(override);
    }
}
