package org.openvoyah.controls.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Vocabulary is data. Matching does not depend on Android, a microphone or CAN. */
public final class VoiceCatalog {
    public record Command(String id, String group, List<String> phrases) {
        public Command { phrases = List.copyOf(phrases); }
        public String title() { return phrases.get(0); }
    }
    private final Map<String, Command> commands = new LinkedHashMap<>();
    private final Map<String, Command> phrases = new HashMap<>();
    public VoiceCatalog(InputStream input) throws IOException { this(input,List.of()); }
    public VoiceCatalog(InputStream input, List<Command> extra) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split("\t", -1);
                if (parts.length != 3) throw new IOException("Invalid voice catalog");
                Command command = new Command(parts[0], parts[1], Arrays.asList(parts[2].split("\\|")));
                add(command);
            }
        }
        for(Command command:extra) add(command);
    }
    private void add(Command command) throws IOException {
        if(command.phrases().isEmpty() || commands.putIfAbsent(command.id(),command)!=null) throw new IOException("Duplicate/empty command");
        for(String phrase:command.phrases()) {
            Command previous=phrases.putIfAbsent(normalize(phrase),command);
            if(previous!=null && !previous.id().equals(command.id())) throw new IOException("Ambiguous phrase: "+phrase);
        }
    }
    public static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace('ё', 'е')
            .replaceAll("[.,!?;:]", " ").trim().replaceAll("\\s+", " ");
    }
    public Command match(String text) { return phrases.get(normalize(text)); }
    public Command byId(String id) { return commands.get(id); }
    public List<Command> all() { return List.copyOf(commands.values()); }
    public Set<String> grammar() { return Set.copyOf(phrases.keySet()); }
}
