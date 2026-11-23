package app.voyahradio.domain;

import java.util.Objects;

public final class Track {
    public final String title;
    public final String artist;

    private Track(String title, String artist) {
        this.title = title;
        this.artist = artist;
    }

    public static Track from(CharSequence title, CharSequence artist, String stationName) {
        String name = clean(title);
        String performer = clean(artist);
        if (name.isEmpty() || name.equalsIgnoreCase(stationName)) return null;
        if (performer.isEmpty()) {
            String[] parts = name.split(" (?:-|–|—) ", 2);
            if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                performer = parts[0];
                name = parts[1];
            }
        }
        return new Track(name, performer);
    }

    private static String clean(CharSequence value) {
        if (value == null) return "";
        String line = value.toString().replaceAll("[\\p{Cntrl}]+", " ").trim();
        int codePoints = line.codePointCount(0, line.length());
        return codePoints > 180 ? line.substring(0, line.offsetByCodePoints(0, 180)) : line;
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Track track)) return false;
        return title.equals(track.title) && artist.equals(track.artist);
    }

    @Override public int hashCode() {
        return Objects.hash(title, artist);
    }
}
