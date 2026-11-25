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

    @Test public void aTemperatureWithoutASeatIsBothZonesInEveryLanguage() throws Exception {
        for (String[] language : new String[][]{{"ru", "/voice-catalog.tsv"}, {"en", "/voice-catalog-en.tsv"},
                {"kk", "/voice-catalog-kk.tsv"}}) {
            var catalog = new VoiceCatalog(getClass().getResourceAsStream(language[1]),
                SpokenValueCommands.commands(language[0]));
            assertNotNull(language[0], catalog.byId("SetTemperature"));
        }
        var ru = new VoiceCatalog(getClass().getResourceAsStream("/voice-catalog.tsv"), SpokenValueCommands.commands("ru"));
        assertEquals("SetTemperature", ru.match("температура двадцать пять").id());
        assertEquals("SetTemperature", ru.match("температура 25 градусов").id());
        assertEquals("SetDriverTemperature", ru.match("температура водителя двадцать пять").id());
        assertEquals(25, ClimatePhrases.value("SetTemperature", "температура двадцать пять градусов"));
        assertEquals(19, ClimatePhrases.value("SetTemperature", "temperature nineteen"));
        assertEquals(24, ClimatePhrases.value("SetTemperature", "температура жиырма төрт"));
        assertNull(ru.match("температура тридцать один"));
        assertThrows(IllegalArgumentException.class,
            () -> ClimatePhrases.value("SetTemperature", "температура водителя двадцать пять"));
    }
}
