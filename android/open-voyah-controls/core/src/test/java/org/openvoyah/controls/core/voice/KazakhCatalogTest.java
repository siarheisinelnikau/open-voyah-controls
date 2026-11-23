package org.openvoyah.controls.core.voice;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.openvoyah.controls.core.VoiceCatalog;

public class KazakhCatalogTest {
    private VoiceCatalog catalog(String resource, String language) throws Exception {
        List<VoiceCatalog.Command> extra = new ArrayList<>(ClimatePhrases.commands(language));
        extra.add(VolumePhrases.command(language));
        return new VoiceCatalog(getClass().getResourceAsStream(resource), extra);
    }

    @Test public void theKazakhCatalogHasEveryCommandWithItsBuiltInPhrases() throws Exception {
        VoiceCatalog kk = catalog("/voice-catalog-kk.tsv", "kk");
        VoiceCatalog ru = catalog("/voice-catalog.tsv", "ru");
        assertEquals(ru.all().size(), kk.all().size());
        for (var command : ru.all()) assertNotNull(command.id(), kk.byId(command.id()));
        assertEquals("GetFromMemoryCellOne", kk.match("жүргізуші бір").id());
        assertEquals("GetFromMemoryCellTwo", kk.match("жүргізуші 2").id());
        assertEquals("SetVolume", kk.match("дыбыс деңгейі елу пайыз").id());
        assertEquals(50, VolumePhrases.percentage("дыбыс деңгейі елу пайыз"));
        assertEquals(17, VolumePhrases.percentage("дыбыс деңгейі он жеті"));
        assertEquals(22, ClimatePhrases.value("SetDriverTemperature", "жүргізуші температурасы жиырма екі градус"));
        assertEquals(3, ClimatePhrases.value("SetFanSpeed", "желдеткіш жылдамдығы үш"));
    }

    @Test public void kazakhNumbersUseTensAndUnits() {
        assertEquals("он бір", SpokenNumbers.words(11, "kk"));
        assertEquals("жиырма бес", SpokenNumbers.words(25, "kk"));
        assertEquals("жүз", SpokenNumbers.words(100, "kk"));
        assertEquals("нөл", SpokenNumbers.words(0, "kk"));
        assertEquals(Integer.valueOf(45), SpokenNumbers.kazakh("сақтау қырық бес"));
        assertNull(SpokenNumbers.kazakh("открой люк пятьдесят"));
    }

    @Test public void theAgentGetsKazakhNumbersInRussian() {
        assertEquals("люк пятьдесят", AgentPhrases.forAgent("OpenSunroofWithPercent", "люк елу пайызға аш"));
        assertEquals("люк сто", AgentPhrases.forAgent("OpenSunroofWithPercent", "люк жүз"));
        assertEquals("шторка тридцать", AgentPhrases.forAgent("OpenSunshadeWithPercent", "перде отыз пайыз"));
        assertEquals("сохранение двадцать пять", AgentPhrases.forAgent("SaveModeWithSOC", "сақтау жиырма бес"));
        assertEquals("Russian stays as spoken", "открой люк пятьдесят",
            AgentPhrases.forAgent("OpenSunroofWithPercent", "открой люк пятьдесят"));
        assertEquals("Out of range stays as spoken", "люк бес", AgentPhrases.forAgent("OpenSunroofWithPercent", "люк бес"));
    }

    @Test public void everyKazakhSunroofAndChargePhraseReachesTheAgentAsRussian() throws Exception {
        VoiceCatalog kk = catalog("/voice-catalog-kk.tsv", "kk");
        for (String id : new String[]{"OpenSunroofWithPercent", "OpenSunshadeWithPercent", "SaveModeWithSOC"}) {
            for (String phrase : kk.byId(id).phrases()) {
                String agent = AgentPhrases.forAgent(id, phrase);
                assertNotEquals(id + ": " + phrase, phrase, agent);
                assertTrue(agent, agent.startsWith("люк ") || agent.startsWith("шторка ") || agent.startsWith("сохранение "));
            }
        }
    }
}
