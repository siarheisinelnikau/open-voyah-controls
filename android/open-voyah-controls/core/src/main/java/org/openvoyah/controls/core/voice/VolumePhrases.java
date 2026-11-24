package org.openvoyah.controls.core.voice;

import java.util.Map;
import org.openvoyah.controls.core.VoiceCatalog.Command;

/** Percentage commands shared by typed input and both offline grammars. */
public final class VolumePhrases {
    public static final String COMMAND_ID = "SetVolume";
    private static final Map<String, Integer> EN = PercentPhrases.phrases("volume ", "en", 0);
    private static final Map<String, Integer> RU = PercentPhrases.phrases("громкость ", "ru", 0);
    private static final Map<String, Integer> KK = PercentPhrases.phrases("дыбыс деңгейі ", "kk", 0);

    private VolumePhrases() { }

    public static Command command(String language) {
        return switch (language) {
            case "en" -> PercentPhrases.command(COMMAND_ID, "Media", EN, "volume fifty percent");
            case "kk" -> PercentPhrases.command(COMMAND_ID, "Media", KK, "дыбыс деңгейі елу пайыз");
            default -> PercentPhrases.command(COMMAND_ID, "Media", RU, "громкость пятьдесят процентов");
        };
    }

    public static int percentage(String phrase) {
        Integer value = PercentPhrases.find(phrase, EN, RU, KK);
        if (value == null) throw new IllegalArgumentException("Expected a volume percentage from 0 to 100");
        return value;
    }
}
