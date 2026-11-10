package org.openvoyah.controls.core.voice;

import org.junit.Test;
import org.openvoyah.controls.core.VoiceCatalog;
import static org.junit.Assert.*;

public class VolumePhrasesTest {
    @Test public void typedAndSpokenPercentagesMatchTheirCatalogs() throws Exception {
        var russian = VolumePhrases.command("ru");
        var english = VolumePhrases.command("en");
        assertEquals(russian.id(), english.id());
        assertEquals(51, VolumePhrases.percentage("Громкость пятьдесят один процент!"));
        assertEquals(22, VolumePhrases.percentage("громкость двадцать два процента"));
        assertEquals(100, VolumePhrases.percentage("volume one hundred percent"));
        assertEquals(0, VolumePhrases.percentage("volume zero"));
        assertEquals(73, VolumePhrases.percentage("volume 73"));
        assertTrue(russian.phrases().contains("громкость 100 процентов"));
        assertTrue(english.phrases().contains("volume one hundred percent"));
        var catalog = new VoiceCatalog(getClass().getResourceAsStream("/voice-catalog-en.tsv"),
            java.util.Collections.singletonList(english));
        assertEquals(english, catalog.match("volume fifty percent"));
        assertNull(catalog.match("volume 101"));
        assertNull(catalog.match("don't volume one hundred percent"));
    }
}
