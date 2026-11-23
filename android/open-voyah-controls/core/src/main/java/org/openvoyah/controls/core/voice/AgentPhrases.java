package org.openvoyah.controls.core.voice;

import org.openvoyah.controls.core.VoiceCatalog;

/**
 * The vehicle agent reads the percentage of a sunroof or sunshade phrase and the charge level of a save-mode phrase
 * from Russian or English number words. A Kazakh phrase is passed to it in the equivalent Russian form, so the agent
 * on the head unit needs no change for a new recognition language.
 */
public final class AgentPhrases {
    private AgentPhrases() { }

    public static String forAgent(String id, String phrase) {
        Integer value = SpokenNumbers.kazakh(VoiceCatalog.normalize(phrase));
        if (value == null) return phrase;
        return switch (id) {
            case "OpenSunroofWithPercent" -> percent(value) ? "люк " + SpokenNumbers.words(value, "ru") : phrase;
            case "OpenSunshadeWithPercent" -> percent(value) ? "шторка " + SpokenNumbers.words(value, "ru") : phrase;
            case "SaveModeWithSOC" -> value >= 25 && value <= 80 && value % 5 == 0
                ? "сохранение " + SpokenNumbers.words(value, "ru") : phrase;
            default -> phrase;
        };
    }

    private static boolean percent(int value) { return value >= 10 && value <= 100 && value % 10 == 0; }
}
