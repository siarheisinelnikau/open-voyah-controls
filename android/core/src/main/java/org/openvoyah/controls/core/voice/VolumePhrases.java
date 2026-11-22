package org.openvoyah.controls.core.voice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.openvoyah.controls.core.VoiceCatalog.Command;
import org.openvoyah.controls.core.VoiceCatalog;

/** Percentage commands shared by typed input and both offline grammars. */
public final class VolumePhrases {
    public static final String COMMAND_ID = "SetVolume";
    private static final Map<String, Integer> EN = phrases("en");
    private static final Map<String, Integer> RU = phrases("ru");
    private static final Map<String, Integer> KK = phrases("kk");

    private VolumePhrases() { }

    public static Command command(String language) {
        Map<String, Integer> phrases = switch (language) {
            case "en" -> EN;
            case "kk" -> KK;
            default -> RU;
        };
        List<String> ordered = new ArrayList<>(phrases.keySet());
        String example = switch (language) {
            case "en" -> "volume fifty percent";
            case "kk" -> "дыбыс деңгейі елу пайыз";
            default -> "громкость пятьдесят процентов";
        };
        ordered.remove(example);
        ordered.add(0, example);
        return new Command(COMMAND_ID, "Media", ordered);
    }

    public static int percentage(String phrase) {
        String normalized = VoiceCatalog.normalize(phrase);
        Integer value = EN.get(normalized);
        if (value == null) value = RU.get(normalized);
        if (value == null) value = KK.get(normalized);
        if (value == null) throw new IllegalArgumentException("Expected a volume percentage from 0 to 100");
        return value;
    }

    private static Map<String, Integer> phrases(String language) {
        Map<String, Integer> result = new LinkedHashMap<>();
        String prefix = switch (language) {
            case "en" -> "volume ";
            case "kk" -> "дыбыс деңгейі ";
            default -> "громкость ";
        };
        for (int number = 0; number <= 100; number++) {
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

}
