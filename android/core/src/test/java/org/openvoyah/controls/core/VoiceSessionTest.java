package org.openvoyah.controls.core;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class VoiceSessionTest {
    private VoiceCatalog catalog() throws Exception { return new VoiceCatalog(getClass().getResourceAsStream("/voice-catalog.tsv")); }
    @Test public void allPhrasesRoundTrip() throws Exception {
        VoiceCatalog c = catalog(); assertTrue(c.all().size() >= 121);
        for (var command : c.all()) for (String phrase : command.phrases()) assertEquals(command, c.match(phrase));
        assertNotNull(c.match("  ВКЛЮЧИ МУЗЫКУ! "));
    }
    @Test public void oppositeAndUnknownCommandsNeverFuzzyMatch() throws Exception {
        VoiceCatalog c = catalog(); assertNull(c.match("не открывай багажник"));
        assertNull(c.match("открой багажник пожалуйста неизвестно"));
        assertNotEquals(c.match("открой багажник"), c.match("закрой багажник"));
    }
    @Test public void finalCallbackCannotExecuteTwiceOrAfterCancel() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        VoiceSession s = new VoiceSession(catalog(), (c,p,done) -> { calls.incrementAndGet(); done.accept("ok"); });
        long first = s.begin(); s.cancel(); s.accept(first,"музыка", x -> fail());
        long second = s.begin(); s.accept(second,"музыка", x -> {}); s.accept(second,"музыка", x -> fail());
        assertEquals(1,calls.get());
    }
    @Test public void cancelAndUnknownDoNotExecute() throws Exception {
        VoiceSession s = new VoiceSession(catalog(), (c,p,d) -> fail());
        s.accept(s.begin(), "отмена", x -> assertEquals("Cancelled",x));
        s.accept(s.begin(), "совсем не команда", x -> assertTrue(x.contains("Unknown command")));
    }
    @Test public void executorFailureDoesNotPoisonNextSession() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        VoiceSession s = new VoiceSession(catalog(), (c,p,d) -> { if (calls.incrementAndGet()==1) throw new IllegalStateException("offline"); d.accept("ok"); });
        s.accept(s.begin(),"музыка",x -> assertTrue(x.contains("offline")));
        s.accept(s.begin(),"музыка",x -> assertEquals("ok",x));
    }
    @Test public void ambiguousVocabularyRejected() throws Exception {
        try { new VoiceCatalog(new ByteArrayInputStream("a\tx\tслово\nb\tx\tслово".getBytes(StandardCharsets.UTF_8))); fail(); }
        catch(IOException expected) { assertTrue(expected.getMessage().contains("Ambiguous")); }
    }

    @Test public void digitAliasesMatchExactlyAndStayOutOfTheSpokenGrammar() throws Exception {
        VoiceCatalog catalog = new VoiceCatalog(vocabulary("seatTwo\tVehicle\tdriver two\t driver 2 \n"));
        assertEquals("seatTwo", catalog.match("  DRIVER  2! ").id());
        assertEquals("driver two", catalog.byId("seatTwo").title());
        assertEquals(Set.of("driver two"), catalog.grammar());
        for (String unknown : List.of("driver 02", "driver 4", "driver 2 or 3", "not driver 2")) {
            assertNull(unknown, catalog.match(unknown));
        }
    }

    @Test public void existingCustomExactPhraseKeepsPriorityOverTheTranscriptAlias() throws Exception {
        var custom = new VoiceCatalog.Command("custom", "Apps", List.of("driver 2"));
        VoiceCatalog catalog = new VoiceCatalog(vocabulary("seatTwo\tVehicle\tdriver two\tdriver 2\n"), List.of(custom));
        assertEquals(custom, catalog.match("driver 2"));
        assertEquals("seatTwo", catalog.match("driver two").id());
        assertEquals(Set.of("driver two", "driver 2"), catalog.grammar());
    }

    @Test public void blankAndAmbiguousTranscriptAliasesAreRejected() {
        for (String invalid : List.of(
                "a\tx\tfirst\t\n",
                "a\tx\tfirst\tdriver 1|\n",
                "a\tx\tfirst\t\u00a0\n",
                "a\tx\tfirst\t...\n",
                "a\tx\tfirst\tdriver 1\nb\tx\tsecond\tDRIVER 1!\n",
                "a\tx\tfirst\tdriver 1\nb\tx\tdriver 1\n")) {
            assertThrows(IOException.class, () -> new VoiceCatalog(vocabulary(invalid)));
        }
    }

    private static InputStream vocabulary(String lines) {
        return new ByteArrayInputStream(lines.getBytes(StandardCharsets.UTF_8));
    }
}
