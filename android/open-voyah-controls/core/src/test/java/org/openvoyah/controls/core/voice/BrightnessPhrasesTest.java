package org.openvoyah.controls.core.voice;

import static org.junit.Assert.*;

import org.junit.Test;
import org.openvoyah.controls.core.VoiceCatalog;

public class BrightnessPhrasesTest {
    private static VoiceCatalog catalog(String resource, String language) throws Exception {
        return new VoiceCatalog(BrightnessPhrasesTest.class.getResourceAsStream(resource),
            SpokenValueCommands.commands(language));
    }

    @Test public void spokenAndTypedLevelsFromOneToOneHundred() {
        assertEquals(30, BrightnessPhrases.percentage("яркость тридцать"));
        assertEquals(30, BrightnessPhrases.percentage("Яркость 30!"));
        assertEquals(30, BrightnessPhrases.percentage("яркость 30 процентов"));
        assertEquals(21, BrightnessPhrases.percentage("яркость двадцать один процент"));
        assertEquals(42, BrightnessPhrases.percentage("яркость сорок два процента"));
        assertEquals(100, BrightnessPhrases.percentage("яркость сто"));
        assertEquals(1, BrightnessPhrases.percentage("brightness one percent"));
        assertEquals(75, BrightnessPhrases.percentage("brightness seventy five"));
        assertEquals(17, BrightnessPhrases.percentage("жарықтық он жеті пайыз"));
        for (String outside : new String[]{"яркость ноль", "яркость 0", "яркость 101", "brightness zero", "яркость"}) {
            assertThrows(outside, IllegalArgumentException.class, () -> BrightnessPhrases.percentage(outside));
        }
    }

    @Test public void everyCatalogMatchesBrightnessWithoutTouchingTheModesOrVolume() throws Exception {
        VoiceCatalog ru = catalog("/voice-catalog.tsv", "ru");
        assertEquals(BrightnessPhrases.COMMAND_ID, ru.match("яркость тридцать").id());
        assertEquals(BrightnessPhrases.COMMAND_ID, ru.match("яркость 30").id());
        assertEquals("BackLightModeAuto", ru.match("яркость авто").id());
        assertEquals("SetVolume", ru.match("громкость тридцать").id());
        assertNull(ru.match("яркость ноль"));
        assertEquals("яркость пятьдесят процентов", ru.byId(BrightnessPhrases.COMMAND_ID).title());
        VoiceCatalog en = catalog("/voice-catalog-en.tsv", "en");
        assertEquals(BrightnessPhrases.COMMAND_ID, en.match("brightness thirty percent").id());
        assertEquals("BackLightModeAuto", en.match("backlight auto").id());
        VoiceCatalog kk = catalog("/voice-catalog-kk.tsv", "kk");
        assertEquals(BrightnessPhrases.COMMAND_ID, kk.match("жарықтық отыз").id());
        assertEquals("BackLightModeAuto", kk.match("жарықтық авто").id());
        for (VoiceCatalog catalog : new VoiceCatalog[]{ru, en, kk}) {
            for (String phrase : catalog.byId(BrightnessPhrases.COMMAND_ID).phrases()) {
                int level = BrightnessPhrases.percentage(phrase);
                assertTrue(phrase, level >= BrightnessPhrases.MINIMUM && level <= BrightnessPhrases.MAXIMUM);
            }
        }
    }

    @Test public void theZipformerTranscriptOfALevelFindsThatLevel() throws Exception {
        TranscriptMatcher matcher = new TranscriptMatcher(catalog("/voice-catalog.tsv", "ru").grammar());
        assertEquals(30, BrightnessPhrases.percentage(matcher.match("Яркость 30%.")));
        assertEquals(35, BrightnessPhrases.percentage(matcher.match("яркость тридцать пять процентов")));
    }
}
