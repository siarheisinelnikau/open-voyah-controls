package app.voyahradio.domain;

import java.util.Set;

public enum RadioTab {
    BELARUS, RUSSIA, KAZAKHSTAN, INTERNATIONAL, FAVORITES;

    public boolean contains(Station station, Set<String> favorites) {
        return this == FAVORITES ? favorites.contains(station.id) : station.group == this;
    }

    public static RadioTab parse(String name, RadioTab fallback) {
        if (name == null) return fallback;
        try {
            return valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
