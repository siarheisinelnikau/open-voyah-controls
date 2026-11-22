package org.openvoyah.controls.core;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * The core module is plain Java, so Android lint never checks it, yet it runs on Android 9 (API 28) and 11 head units.
 * These calls pass every JVM test and fail only on the device; a real one stopped the Zipformer recognizer on the car.
 */
public class AndroidApiGuardTest {
    private static final String[][] FORBIDDEN = {
        {"List.of(", "List.of needs API 30; use Arrays.asList"},
        {"Set.of(", "Set.of needs API 30"},
        {"Map.of(", "Map.of needs API 30"},
        {"Map.entry(", "Map.entry needs API 30"},
        {"UNICODE_CHARACTER_CLASS", "Android's ICU regex engine rejects Pattern.UNICODE_CHARACTER_CLASS"},
    };

    @Test public void coreSourcesUseOnlyApisOfTheSupportedHeadUnits() throws IOException {
        Path sources = Paths.get("src", "main", "java");
        assertTrue("Run from the core module directory", Files.isDirectory(sources));
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : (Iterable<Path>) files.filter(path -> path.toString().endsWith(".java"))::iterator) {
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                for (String[] rule : FORBIDDEN) {
                    if (text.contains(rule[0])) problems.add(file + ": " + rule[1]);
                }
                // Matcher.appendReplacement/appendTail with a StringBuilder need API 34; StringBuffer works everywhere.
                if ((text.contains(".appendReplacement(") || text.contains(".appendTail(")) && !text.contains("StringBuffer")) {
                    problems.add(file + ": appendReplacement/appendTail need a StringBuffer below API 34");
                }
            }
        }
        assertTrue(String.join("\n", problems), problems.isEmpty());
    }
}
