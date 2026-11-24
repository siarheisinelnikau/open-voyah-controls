package org.openvoyah.controls.core.voice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.openvoyah.controls.core.VoiceCatalog;
import org.openvoyah.controls.core.VoiceCatalog.Command;

/** A command word followed by a percentage, in words or digits, with or without the unit. */
final class PercentPhrases {
    private PercentPhrases() { }

    static Map<String, Integer> phrases(String prefix, String language, int minimum) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int number = minimum; number <= 100; number++) {
            String words = SpokenNumbers.words(number, language);
            String unit = switch (language) {
                case "en" -> " percent";
                case "kk" -> " пайыз";
                default -> " " + SpokenNumbers.russianUnit(number, "процент", "процента", "процентов");
            };
            result.put(prefix + words, number);
            result.put(prefix + words + unit, number);
            result.put(prefix + number, number);
            result.put(prefix + number + unit, number);
        }
        return result;
    }

    /** The command with [example] as its title, followed by every other phrase. */
    static Command command(String id, String group, Map<String, Integer> phrases, String example) {
        List<String> ordered = new ArrayList<>(phrases.keySet());
        ordered.remove(example);
        ordered.add(0, example);
        return new Command(id, group, ordered);
    }

    /** The percentage of [phrase] in the first of [languages] that knows it, or null. */
    @SafeVarargs
    static Integer find(String phrase, Map<String, Integer>... languages) {
        String normalized = VoiceCatalog.normalize(phrase);
        for (Map<String, Integer> phrases : languages) {
            Integer value = phrases.get(normalized);
            if (value != null) return value;
        }
        return null;
    }
}
