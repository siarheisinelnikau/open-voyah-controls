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
        assertEquals(129,en.all().size());
        for (var c : ru.all()) assertNotNull(en.byId(c.id()));
        for (var c : en.all()) for (String phrase : c.phrases()) assertEquals(c,en.match(phrase));
        assertNull(en.match("do not open trunk"));
        assertEquals("DriverB",en.match("driver b").id());
    }
    @Test public void englishStopNeverExecutes() throws Exception {
        VoiceCatalog en = new VoiceCatalog(getClass().getResourceAsStream("/voice-catalog-en.tsv"));
        VoiceSession session = new VoiceSession(en,(c,p,d)->fail());
        session.accept(session.begin(),"stop",message->assertEquals("Cancelled",message));
        session.accept(session.begin(),"cancel",message->assertEquals("Cancelled",message));
    }
}
