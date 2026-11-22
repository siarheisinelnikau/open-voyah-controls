package org.openvoyah.controls.core.voice;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps the free transcript of an engine without a grammar to the nearest command phrase. A phrase is chosen only when it
 * is at least {@link #CUTOFF} similar (rapidfuzz {@code fuzz.ratio}: twice the longest common subsequence over both
 * lengths) and no other phrase scores the same, so a near miss between two commands is never guessed. This is the
 * matching that the offline engine comparison measured for the Zipformer models.
 */
public final class TranscriptMatcher {
    public static final double CUTOFF = 85.0;
    private static final Pattern PUNCTUATION = Pattern.compile("[^\\w\\s]", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern UNITS = Pattern.compile(
        "(?<![\\w])(процентов|процента|процент|градусов|градуса|градус|percent|degrees)(?![\\w])",
        Pattern.UNICODE_CHARACTER_CLASS);
    private static final Map<String, String> UNIT_ROOT = Map.of("процентов", "процент", "процента", "процент",
        "градусов", "градус", "градуса", "градус");
    private final Map<String, String> phrases = new TreeMap<>();

    public TranscriptMatcher(Collection<String> grammar) {
        for (String phrase : grammar) {
            if (phrase.equals("[unk]")) continue;
            String key = key(phrase);
            if (!key.isEmpty()) phrases.putIfAbsent(key, phrase);
        }
    }

    /** The grammar phrase for [transcript], or null when none is close enough or two are equally close. */
    public String match(String transcript) {
        String key = key(transcript);
        if (key.isEmpty()) return null;
        String exact = phrases.get(key);
        if (exact != null) return exact;
        double best = CUTOFF;
        String chosen = null;
        boolean tie = false;
        for (Map.Entry<String, String> entry : phrases.entrySet()) {
            double score = ratio(key, entry.getKey());
            if (score > best) {
                best = score;
                chosen = entry.getValue();
                tie = false;
            } else if (score == best && chosen != null && !chosen.equals(entry.getValue())) {
                tie = true;
            }
        }
        return tie ? null : chosen;
    }

    /** Lower case, ё as е, % and ° as words, other punctuation as spaces, one unit form. */
    static String key(String text) {
        String value = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
            .replace('ё', 'е').replace("%", " процент ").replace("°", " градус ");
        value = PUNCTUATION.matcher(value).replaceAll(" ").trim().replaceAll("\\s+", " ");
        Matcher units = UNITS.matcher(value);
        StringBuilder out = new StringBuilder();
        while (units.find()) units.appendReplacement(out, UNIT_ROOT.getOrDefault(units.group(1), units.group(1)));
        units.appendTail(out);
        return out.toString();
    }

    static double ratio(String a, String b) {
        int total = a.length() + b.length();
        if (total == 0) return 100.0;
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                current[j] = a.charAt(i - 1) == b.charAt(j - 1) ? previous[j - 1] + 1 : Math.max(previous[j], current[j - 1]);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return 200.0 * previous[b.length()] / total;
    }
}
