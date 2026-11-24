package app.voyahmusic.shared.update;

import android.app.Application;

public abstract class MusicApp extends Application {
    private Updates updates;

    protected abstract String releaseName();

    public Updates updates() {
        if (updates == null) updates = new Updates(this, releaseName());
        return updates;
    }
}
