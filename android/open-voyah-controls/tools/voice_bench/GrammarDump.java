import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.TreeSet;
import org.openvoyah.controls.core.VoiceCatalog;
import org.openvoyah.controls.core.voice.BrightnessPhrases;
import org.openvoyah.controls.core.voice.ClimatePhrases;
import org.openvoyah.controls.core.voice.SpokenValueCommands;
import org.openvoyah.controls.core.voice.VolumePhrases;

/**
 * Prints the recognition grammar and the phrase-to-command table exactly as the app builds them (catalog TSV plus the
 * commands with a spoken value: climate, volume and screen brightness, without contacts). Output: tab-separated
 * `phrase  label  in_grammar  group`. A label is the command ID, or `ID:value` for the commands with a value.
 */
public final class GrammarDump {
    public static void main(String[] args) throws Exception {
        String language = args[0];
        VoiceCatalog catalog;
        try (InputStream input = new FileInputStream(args[1])) {
            catalog = new VoiceCatalog(input, SpokenValueCommands.commands(language));
        }
        // The app drops phrases with digits from the Vosk grammar; they stay valid transcript matches.
        TreeSet<String> grammar = new TreeSet<>();
        for (String phrase : catalog.grammar()) {
            if (phrase.codePoints().noneMatch(Character::isDigit)) grammar.add(phrase);
        }
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        TreeSet<String> seen = new TreeSet<>();
        for (VoiceCatalog.Command command : catalog.all()) {
            for (String phrase : command.phrases()) {
                String key = VoiceCatalog.normalize(phrase);
                if (!seen.add(key)) continue;
                out.println(key + "\t" + label(command.id(), phrase) + "\t" + (grammar.contains(key) ? 1 : 0) + "\t"
                    + command.group());
            }
        }
        // Spellings with ё are separate grammar entries; their normalized key is printed above.
        for (String phrase : grammar) {
            if (!seen.contains(phrase)) {
                VoiceCatalog.Command command = catalog.match(phrase);
                out.println(phrase + "\t" + label(command.id(), phrase) + "\t1\t" + command.group());
            }
        }
    }

    private static String label(String id, String phrase) {
        if (id.equals(VolumePhrases.COMMAND_ID)) return id + ":" + VolumePhrases.percentage(phrase);
        if (id.equals(BrightnessPhrases.COMMAND_ID)) return id + ":" + BrightnessPhrases.percentage(phrase);
        if (ClimatePhrases.supports(id)) return id + ":" + ClimatePhrases.value(id, phrase);
        return id;
    }
}
