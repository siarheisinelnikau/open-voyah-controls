package app.voyahradio.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import app.voyahradio.data.RadioPreferences;

import java.util.Locale;

public final class RadioLocale {
    private RadioLocale() {}

    public static Context apply(Context context, RadioPreferences preferences) {
        String language = preferences.language();
        if (context.getResources().getConfiguration().getLocales().get(0).getLanguage().equals(language)) {
            return context;
        }
        Locale locale = Locale.forLanguageTag(language);
        Configuration override = new Configuration();
        override.setLocales(new LocaleList(locale));
        return context.createConfigurationContext(override);
    }
}
