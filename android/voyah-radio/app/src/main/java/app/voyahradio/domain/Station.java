package app.voyahradio.domain;

import java.net.URI;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class Station {
    public final String id;
    public final String name;
    public final RadioTab group;
    public final int trackSourceId;
    public final List<String> streams;
    private final String label;
    private final String russianLabel;
    private final Locale country;
    private final String searchText;

    public Station(String id, String name, String label, String aliases,
                   RadioTab group, List<String> streams, int trackSourceId,
                   String countryCode, String russianLabel) {
        if (id.trim().isEmpty() || name.trim().isEmpty() || streams.isEmpty()) {
            throw new IllegalArgumentException("Incomplete station");
        }
        for (String url : streams) {
            URI uri = URI.create(url);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("Invalid stream URL: " + url);
            }
        }
        this.id = id;
        this.name = name;
        this.label = label;
        this.russianLabel = russianLabel.isEmpty() ? label : russianLabel;
        this.country = countryCode.isEmpty() ? null : new Locale("", countryCode);
        this.group = group;
        this.trackSourceId = trackSourceId;
        this.streams = Collections.unmodifiableList(new ArrayList<>(streams));
        this.searchText = normalize(name + " " + aliases + " " + displayLabel(Locale.ENGLISH)
                + " " + displayLabel(new Locale("ru")));
    }

    public String displayLabel(Locale locale) {
        String detail = locale.getLanguage().equals("ru") ? russianLabel : label;
        if (country == null) return detail;
        String region = country.getDisplayCountry(locale);
        return detail.isEmpty() ? region : region + " · " + detail;
    }

    public boolean matches(String[] words) {
        for (String word : words) {
            if (!searchText.contains(word)) return false;
        }
        return true;
    }

    public static String[] searchWords(String text) {
        return normalize(text).split(" ");
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT).replace('ё', 'е'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }
}
