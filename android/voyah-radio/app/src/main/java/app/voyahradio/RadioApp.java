package app.voyahradio;

import android.app.Application;
import app.voyahradio.data.RadioCatalog;
import app.voyahradio.data.RadioPreferences;

public final class RadioApp extends Application {
    private RadioCatalog catalog;
    private RadioPreferences preferences;

    @Override public void onCreate() {
        super.onCreate();
        catalog = new RadioCatalog(this);
        preferences = new RadioPreferences(this);
    }

    public RadioCatalog catalog() {
        return catalog;
    }

    public RadioPreferences preferences() {
        return preferences;
    }
}
