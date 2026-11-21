package org.openvoyah.controls.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class FirmwareTest {
    @Test public void guestProfilesAreRestrictedToVerifiedOriginalBranches() {
        for (String product : new String[]{"f0506h","f6802h","f6805h","f0505h"}) assertTrue(Firmware.guestAccounts(product));
        for (String product : new String[]{"f6811","f0501h","f6801f","f6804h","unknown"}) assertFalse(Firmware.guestAccounts(product));
        assertEquals("FREE_REST_OVERSEA", Firmware.identify("f6811"));
        assertEquals("DREAMER_REST_MI_V2", Firmware.identify("f6806"));
    }
    @Test public void bothLanguagesHaveIdenticalCommandCoverageAndExactMatching() throws Exception {
        VoiceCatalog ru = new VoiceCatalog(getClass().getResourceAsStream("/voice-catalog.tsv"));
        VoiceCatalog en = new VoiceCatalog(getClass().getResourceAsStream("/voice-catalog-en.tsv"));
        assertEquals(134,en.all().size());
        assertEquals(ru.all().size(), en.all().size());
        for (var c : ru.all()) assertNotNull(en.byId(c.id()));
        for (var c : en.all()) for (String phrase : c.phrases()) assertEquals(c,en.match(phrase));
        assertNull(en.match("do not open trunk"));
        assertEquals("VolumeUp", en.match("make it louder").id());
        assertEquals("VolumeUp", ru.match("сделай громче").id());
        assertEquals("RestartNavigator", ru.match("перезапусти навигатор").id());
        assertEquals("DriverB",en.match("driver b").id());
        String[] memoryIds = {"GetFromMemoryCellOne", "GetFromMemoryCellTwo", "GetFromMemoryCellThree"};
        String[] ruNumbers = {"один", "два", "три"};
        String[] enNumbers = {"one", "two", "three"};
        String[] ruSlots = {"а", "б", "ц"};
        String[] enSlots = {"a", "b", "c"};
        for (int i = 0; i < memoryIds.length; i++) {
            assertEquals(memoryIds[i], ru.match("водитель " + ruNumbers[i]).id());
            assertEquals(memoryIds[i], en.match("driver " + enNumbers[i]).id());
            assertEquals("Driver" + (char) ('A' + i), ru.match("водитель " + ruSlots[i]).id());
            assertEquals("Driver" + (char) ('A' + i), en.match("driver " + enSlots[i]).id());
        }
    }
    @Test public void driverWindowVentingUsesNeutralPhrasesDistinctFromTheOtherWindowCommands() throws Exception {
        VoiceCatalog ru = new VoiceCatalog(getClass().getResourceAsStream("/voice-catalog.tsv"));
        VoiceCatalog en = new VoiceCatalog(getClass().getResourceAsStream("/voice-catalog-en.tsv"));
        assertEquals("проветрить у водителя", ru.byId("SmokeCommand").title());
        assertEquals("vent driver window", en.byId("SmokeCommand").title());
        for (String phrase : new String[]{"проветрить у водителя", "приоткрыть окно водителя", "окно водителя на проветривание", "открой окно немного"}) {
            assertEquals("SmokeCommand", ru.match(phrase).id());
        }
        assertEquals("VentilationCommand", ru.match("проветрить").id());
        assertEquals("OpenDriverWindowCommand", ru.match("открой окно").id());
        assertEquals("VentilationCommand", en.match("air out").id());
        assertEquals("OpenDriverWindowCommand", en.match("open window").id());
        for (String slang : new String[]{"давай покурим", "джип", "режим джип", "сейв", "режим сэйв"}) assertNull(ru.match(slang));
        assertNull(en.match("let's have a smoke"));
        assertEquals("нравится", ru.byId("Like").title());
    }
    @Test public void englishStopNeverExecutes() throws Exception {
        VoiceCatalog en = new VoiceCatalog(getClass().getResourceAsStream("/voice-catalog-en.tsv"));
        VoiceSession session = new VoiceSession(en,(c,p,d)->fail());
        session.accept(session.begin(),"stop",message->assertEquals("Cancelled",message));
        session.accept(session.begin(),"cancel",message->assertEquals("Cancelled",message));
    }
}
