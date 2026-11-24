package org.openvoyah.controls.core.voice;

import java.util.ArrayList;
import java.util.List;
import org.openvoyah.controls.core.VoiceCatalog.Command;

/**
 * Commands that carry a spoken number. Their phrases are generated for each recognition language instead of being
 * listed in the catalog files, and every catalog adds them after its own rows.
 */
public final class SpokenValueCommands {
    private SpokenValueCommands() { }

    public static List<Command> commands(String language) {
        List<Command> commands = new ArrayList<>(ClimatePhrases.commands(language));
        commands.add(VolumePhrases.command(language));
        commands.add(BrightnessPhrases.command(language));
        return commands;
    }
}
