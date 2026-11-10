package org.openvoyah.controls.core.voice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.openvoyah.controls.core.VoiceCatalog.Command;
import org.openvoyah.controls.core.VoiceCatalog;

/** Bounded front climate settings. The injected adapter verifies the OEM branch before dispatch. */
public final class ClimatePhrases {
    private enum Target {
        DRIVER("SetDriverTemperature", "driver temperature ", "температура водителя ", 16, 30, 22),
        PASSENGER("SetPassengerTemperature", "passenger temperature ", "температура пассажира ", 16, 30, 22),
        FAN("SetFanSpeed", "fan speed ", "скорость вентилятора ", 1, 8, 3);

        final String id, english, russian;
        final int minimum, maximum, example;

        Target(String id, String english, String russian, int minimum, int maximum, int example) {
            this.id = id;
            this.english = english;
            this.russian = russian;
            this.minimum = minimum;
            this.maximum = maximum;
            this.example = example;
        }

        String prefix(boolean english) { return english ? this.english : russian; }
    }

    private record Setpoint(Target target, int value) { }
    private static final Map<String, Setpoint> EN = phrases(true);
    private static final Map<String, Setpoint> RU = phrases(false);

    private ClimatePhrases() { }

    public static List<Command> commands(String language) {
        boolean english = language.equals("en");
        Map<String, Setpoint> phrases = english ? EN : RU;
        List<Command> result = new ArrayList<>();
        for (Target target : Target.values()) {
            List<String> labels = new ArrayList<>();
            String example = target.prefix(english) + SpokenNumbers.words(target.example, english);
            labels.add(example);
            for (var phrase : phrases.entrySet()) {
                if (phrase.getValue().target() == target && !phrase.getKey().equals(example)) labels.add(phrase.getKey());
            }
            result.add(new Command(target.id, "Climate", labels));
        }
        return result;
    }

    public static boolean supports(String id) {
        for (Target target : Target.values()) if (target.id.equals(id)) return true;
        return false;
    }

    public static int value(String id, String phrase) {
        String normalized = VoiceCatalog.normalize(phrase);
        Setpoint setpoint = EN.get(normalized);
        if (setpoint == null) setpoint = RU.get(normalized);
        if (setpoint == null || !setpoint.target().id.equals(id))
            throw new IllegalArgumentException("Climate value is outside the supported range");
        return setpoint.value();
    }

    private static Map<String, Setpoint> phrases(boolean english) {
        Map<String, Setpoint> result = new LinkedHashMap<>();
        for (Target target : Target.values()) {
            String prefix = target.prefix(english);
            for (int value = target.minimum; value <= target.maximum; value++) {
                String words = SpokenNumbers.words(value, english);
                Setpoint setpoint = new Setpoint(target, value);
                result.put(prefix + words, setpoint);
                result.put(prefix + value, setpoint);
                if (target != Target.FAN) {
                    String unit = english ? " degrees"
                        : " " + SpokenNumbers.russianUnit(value, "градус", "градуса", "градусов");
                    result.put(prefix + words + unit, setpoint);
                    result.put(prefix + value + unit, setpoint);
                }
            }
        }
        return result;
    }
}
