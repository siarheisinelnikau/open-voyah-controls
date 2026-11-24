package org.openvoyah.controls.core.voice;

import java.util.Map;
import org.openvoyah.controls.core.VoiceCatalog.Command;

/**
 * Central screen brightness from 1 to 100 percent, the range of the factory brightness slider. The day, night and
 * automatic modes are separate catalog commands (BackLightMode*).
 */
public final class BrightnessPhrases {
    public static final String COMMAND_ID = "SetBrightness";
    public static final int MINIMUM = 1;
    public static final int MAXIMUM = 100;
    private static final Map<String, Integer> EN = PercentPhrases.phrases("brightness ", "en", MINIMUM);
    private static final Map<String, Integer> RU = PercentPhrases.phrases("яркость ", "ru", MINIMUM);
    private static final Map<String, Integer> KK = PercentPhrases.phrases("жарықтық ", "kk", MINIMUM);

    private BrightnessPhrases() { }

    public static Command command(String language) {
        return switch (language) {
            case "en" -> PercentPhrases.command(COMMAND_ID, "Light", EN, "brightness fifty percent");
            case "kk" -> PercentPhrases.command(COMMAND_ID, "Light", KK, "жарықтық елу пайыз");
            default -> PercentPhrases.command(COMMAND_ID, "Light", RU, "яркость пятьдесят процентов");
        };
    }

    public static int percentage(String phrase) {
        Integer value = PercentPhrases.find(phrase, EN, RU, KK);
        if (value == null) throw new IllegalArgumentException("Expected a screen brightness from 1 to 100 percent");
        return value;
    }
}
