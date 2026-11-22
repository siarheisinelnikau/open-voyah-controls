package org.openvoyah.controls.core.voice;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;
import org.openvoyah.controls.core.VoiceCatalog;

public class TranscriptMatcherTest {
    @Test public void ratioMatchesRapidfuzzRatio() {
        // fuzz.ratio("открой люк", "открой лук") == 90.0 and ("abc", "") == 0.0
        assertEquals(90.0, TranscriptMatcher.ratio("открой люк", "открой лук"), 1e-9);
        assertEquals(0.0, TranscriptMatcher.ratio("abc", ""), 1e-9);
        assertEquals(100.0, TranscriptMatcher.ratio("", ""), 1e-9);
    }

    @Test public void keysNormalizeCaseLettersPunctuationAndUnits() {
        assertEquals("громкость пятьдесят процент", TranscriptMatcher.key("Громкость, пятьдесят процентов!"));
        assertEquals("громкость 50 процент", TranscriptMatcher.key("громкость 50%"));
        assertEquals("включи подогрев", TranscriptMatcher.key("Включи  подогрёв"));
        assertEquals("открой люк на пятьдесят процент", TranscriptMatcher.key("«Открой люк» — на пятьдесят процентов…"));
        assertEquals("жүргізуші бір", TranscriptMatcher.key("Жүргізуші, бір!"));
        assertEquals("", TranscriptMatcher.key("…!?"));
    }

    @Test public void aSlightlyMisheardCommandFindsItsPhrase() {
        TranscriptMatcher matcher = new TranscriptMatcher(List.of("открой люк", "закрой люк", "подогрев руля", "[unk]"));
        assertEquals("открой люк", matcher.match("Открой люк."));
        assertEquals("подогрев руля", matcher.match("подогрева руля"));
        assertNull("Ordinary talk is not a command", matcher.match("давай заедем в магазин"));
        assertNull(matcher.match(""));
        assertNull(matcher.match("[unk]"));
    }

    @Test public void twoEquallyClosePhrasesAreNotGuessed() {
        TranscriptMatcher matcher = new TranscriptMatcher(List.of("режим один", "режим одна"));
        // "режим одн" scores 94.7 against both phrases.
        assertEquals(TranscriptMatcher.ratio("режим одн", "режим один"), TranscriptMatcher.ratio("режим одн", "режим одна"), 1e-9);
        assertTrue(TranscriptMatcher.ratio("режим одн", "режим один") > TranscriptMatcher.CUTOFF);
        assertNull(matcher.match("режим одн"));
        assertEquals("режим один", matcher.match("режим один"));
    }

    @Test public void everyCatalogPhraseMatchesItself() throws Exception {
        for (String resource : new String[]{"/voice-catalog.tsv", "/voice-catalog-en.tsv"}) {
            VoiceCatalog catalog = new VoiceCatalog(getClass().getResourceAsStream(resource));
            TranscriptMatcher matcher = new TranscriptMatcher(catalog.grammar());
            for (String phrase : catalog.grammar()) {
                assertEquals(resource, catalog.match(phrase), catalog.match(matcher.match(phrase)));
            }
        }
    }
}
