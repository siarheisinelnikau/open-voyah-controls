package app.voyahradio;

import android.app.Application;
import app.voyahradio.data.RadioCatalog;
import app.voyahradio.data.RadioPreferences;
import app.voyahradio.data.StreamProbe;

public final class RadioApp extends Application {
    private RadioCatalog catalog;
    private RadioPreferences preferences;
    private StreamProbe streamProbe;

    @Override public void onCreate() {
        super.onCreate();
        catalog = new RadioCatalog(this);
        preferences = new RadioPreferences(this);
        streamProbe = new StreamProbe(this);
    }

    public RadioCatalog catalog() {
        return catalog;
    }

    public RadioPreferences preferences() {
        return preferences;
    }

    public StreamProbe streamProbe() {
        return streamProbe;
    }
}
