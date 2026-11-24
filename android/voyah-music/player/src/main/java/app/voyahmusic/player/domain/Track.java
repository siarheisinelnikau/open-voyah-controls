package app.voyahmusic.player.domain;

import java.io.File;
import java.util.Locale;

public record Track(String id, File file, String title, String artist, String album, long duration) {
    public boolean matches(String query) {
        return (title + "\n" + artist + "\n" + album + "\n" + file.getName())
                .toLowerCase(Locale.ROOT).contains(query);
    }
}
