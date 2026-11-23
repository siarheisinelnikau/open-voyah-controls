package org.openvoyah.controls.core.voice;

import java.text.Normalizer;
import java.util.*;

/**
 * Maps the free transcript of an engine without a grammar to the nearest command phrase. A phrase is chosen only when it
 * is at least {@link #CUTOFF} similar (rapidfuzz {@code fuzz.ratio}: twice the longest common subsequence over both
 * lengths) and no other phrase scores the same, so a near miss between two commands is never guessed. This is the
 * matching that the offline engine comparison measured for the Zipformer models.
 *
 * <p>Plain character tests instead of regular expressions: Android's regex engine rejects the Unicode character class
 * flag, and Matcher replacement into a StringBuilder needs API 34 (see AndroidApiGuardTest).
 */
public final class TranscriptMatcher {
    public static final double CUTOFF = 85.0;
    private static final Map<String, String> UNIT_ROOT = new HashMap<>();
    static {
        for (String unit : new String[]{"процентов", "процента", "процент"}) UNIT_ROOT.put(unit, "процент");
        for (String unit : new String[]{"градусов", "градуса", "градус"}) UNIT_ROOT.put(unit, "градус");
    }
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
        StringBuilder cleaned = new StringBuilder(value.length());
        value.codePoints().forEach(point -> {
            if (wordCharacter(point)) cleaned.appendCodePoint(point);
            else cleaned.append(' ');
        });
        StringBuilder out = new StringBuilder(cleaned.length());
        for (String word : cleaned.toString().trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(UNIT_ROOT.getOrDefault(word, word));
        }
        return out.toString();
    }

    /** Python's Unicode {@code \w}: letters, digits, marks and the connector underscore. */
    private static boolean wordCharacter(int point) {
        if (Character.isLetterOrDigit(point) || point == '_') return true;
        int type = Character.getType(point);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
            || type == Character.ENCLOSING_MARK;
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
