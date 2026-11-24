package org.openvoyah.controls.presentation.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openvoyah.controls.core.VoiceCatalog
import org.openvoyah.controls.core.voice.ClimatePhrases
import org.openvoyah.controls.core.voice.VolumePhrases

class VoiceCommandGuideTest {
    @Test
    fun eachBuiltInCommandHasADescriptionInEveryRecognitionCatalog() {
        for ((language, resource) in mapOf("en" to "voice-catalog-en", "ru" to "voice-catalog", "kk" to "voice-catalog-kk")) {
            val input = checkNotNull(VoiceCatalog::class.java.getResourceAsStream("/$resource.tsv"))
            val catalog = VoiceCatalog(input, listOf(VolumePhrases.command(language)) + ClimatePhrases.commands(language))
            assertEquals(language, catalog.all().map { it.id() }.toSet(), guidedVoiceCommandIds)
        }
    }

    @Test
    fun searchesAlternatePhrasesAndEffectsInTheSelectedCategory() {
        val media = VoiceCommandGuide(VoiceCatalog.Command("VolumeUp", "Media", listOf("volume up", "louder")),
            VoiceGuideCategory.MEDIA, "Raises volume one step.", null, true)
        val climate = VoiceCommandGuide(VoiceCatalog.Command("SetFanSpeed", "Climate", listOf("fan speed three")),
            VoiceGuideCategory.CLIMATE, "Sets the fan speed.", null, true)
        val guides = listOf(climate, media)
        assertEquals(listOf(media), filterVoiceGuides(guides, null, "  LOUDER! ").values.flatten())
        assertEquals(listOf(climate), filterVoiceGuides(guides, VoiceGuideCategory.CLIMATE, "sets the fan").values.flatten())
        assertEquals(emptyMap<VoiceGuideCategory, List<VoiceCommandGuide>>(),
            filterVoiceGuides(guides, VoiceGuideCategory.MEDIA, "fan"))
        assertEquals(listOf(VoiceGuideCategory.MEDIA, VoiceGuideCategory.CLIMATE), filterVoiceGuides(guides, null, "").keys.toList())
    }
}
