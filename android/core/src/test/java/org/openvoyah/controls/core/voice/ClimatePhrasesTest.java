package org.openvoyah.controls.core.voice;

import org.junit.Test;
import org.openvoyah.controls.core.VoiceCatalog;
import static org.junit.Assert.*;

public class ClimatePhrasesTest {
    @Test public void preservesZoneAndRejectsOutOfRangeValues() throws Exception {
        var catalog = new VoiceCatalog(getClass().getResourceAsStream("/voice-catalog-en.tsv"), ClimatePhrases.commands("en"));
        var passenger = catalog.match("passenger temperature twenty two degrees");
        assertEquals("SetPassengerTemperature", passenger.id());
        assertEquals(22, ClimatePhrases.value(passenger.id(), "passenger temperature 22"));
        assertEquals(20, ClimatePhrases.value("SetDriverTemperature", "температура водителя двадцать градусов"));
        assertEquals(8, ClimatePhrases.value("SetFanSpeed", "скорость вентилятора восемь"));
        assertNull(catalog.match("fan speed nine"));
        assertNull(catalog.match("driver temperature thirty one"));
        assertThrows(IllegalArgumentException.class,
            () -> ClimatePhrases.value("SetDriverTemperature", "passenger temperature twenty two"));
    }
}
