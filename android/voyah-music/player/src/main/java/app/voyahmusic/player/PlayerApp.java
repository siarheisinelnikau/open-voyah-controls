package app.voyahmusic.player;

import app.voyahmusic.player.data.MusicLibrary;
import app.voyahmusic.shared.update.MusicApp;

public final class PlayerApp extends MusicApp {
    private MusicLibrary library;

    public MusicLibrary library() {
        if (library == null) library = new MusicLibrary(this);
        return library;
    }

    @Override
    protected String releaseName() {
        return "VoyahMusic";
    }
}
