package org.openvoyah.controls

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openvoyah.controls.core.VoiceCatalog
import org.openvoyah.controls.core.voice.VoiceCommandUseCase
import org.openvoyah.controls.domain.seatmemory.SeatMemorySlot
import org.openvoyah.controls.domain.voice.VoiceOutcome
import org.openvoyah.controls.domain.voice.VoiceResult

class SeatMemoryVoiceRoutingTest {
    private val useCase = VoiceCommandUseCase({ false }, { "" })

    @Test fun unreviewedNumberedPhrasesOpenOnlyTheirOwnGuidance() = runBlocking {
        val phrases = mapOf(
            "/voice-catalog.tsv" to ("водитель" to listOf("один", "два", "три")),
            "/voice-catalog-en.tsv" to ("driver" to listOf("one", "two", "three")),
        )
        val guidance = VoiceResult("Opened driver memory", outcome = VoiceOutcome.LOCAL_REQUESTED)
        for ((resource, forms) in phrases) {
            val catalog = VoiceCatalog(checkNotNull(VoiceCatalog::class.java.getResourceAsStream(resource)))
            val (driver, numbers) = forms
            for ((index, number) in numbers.withIndex()) {
                val slot = SeatMemorySlot.entries[index]
                for (phrase in listOf("$driver $number", "$driver ${slot.number}")) {
                    val request = useCase.prepare(checkNotNull(catalog.match(phrase)), phrase)
                    val result = routeVehicleVoice(request, recallApproved = { id ->
                        assertEquals(slot.recallCommandId, id)
                        false
                    }, dispatch = { error("Unreviewed numbered recall reached the vehicle dispatcher") }, openDriverMemory = {
                        assertEquals(slot, it)
                        guidance
                    })
                    assertEquals(guidance, result)
                }
            }
        }
    }

    @Test fun reviewedSafetyOutcomeAndOtherVehicleCommandsKeepTheirDispatcher() = runBlocking {
        val catalog = VoiceCatalog(checkNotNull(VoiceCatalog::class.java.getResourceAsStream("/voice-catalog-en.tsv")))
        val request = useCase.prepare(checkNotNull(catalog.match("driver 2")), "driver 2")
        val blocked = VoiceResult("Park required", failed = true, outcome = VoiceOutcome.PARK_REQUIRED)
        val result = routeVehicleVoice(request, recallApproved = { it == SeatMemorySlot.TWO.recallCommandId },
            dispatch = {
                assertEquals(request, it)
                blocked
            },
            openDriverMemory = { error("Reviewed command must keep the original safety outcome") })
        assertEquals(blocked, result)

        val other = useCase.prepare(VoiceCatalog.Command("DriverA", "Accounts", listOf("driver a")), "driver a")
        assertEquals(blocked, routeVehicleVoice(other, recallApproved = { error("Not a numbered recall") },
            dispatch = {
                assertEquals(other, it)
                blocked
            },
            openDriverMemory = { error("Account command must stay with its dispatcher") }))
    }
}
