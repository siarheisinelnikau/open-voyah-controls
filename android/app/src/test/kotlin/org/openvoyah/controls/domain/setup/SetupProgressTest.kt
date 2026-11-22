package org.openvoyah.controls.domain.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.R

class SetupProgressTest {
    @Test
    fun startsWithTheBaseRowsPending() {
        val progress = SetupProgress()
        assertEquals(SetupStepId.BASE, progress.steps.map { it.id })
        assertTrue(progress.steps.all { it.state == StepState.Pending })
        assertFalse(progress.running)
    }

    @Test
    fun agentRowsSitBetweenDeploymentAndVoiceAndFollowEachProbe() {
        val first = SetupProgress().withAgents(mapOf(
            SetupStepId.AGENT_WINDOW to StepState.Pending,
            SetupStepId.AGENT_LAUNCHER to StepState.Done(1),
        ))
        assertEquals(listOf(SetupStepId.ROOT, SetupStepId.FIRMWARE, SetupStepId.DEPLOY, SetupStepId.AGENT_LAUNCHER,
            SetupStepId.AGENT_WINDOW, SetupStepId.VOICE, SetupStepId.MICROPHONE, SetupStepId.BUTTONS), first.steps.map { it.id })
        val second = first.withAgents(mapOf(SetupStepId.AGENT_LAUNCHER to StepState.Done(1)))
        assertFalse(second.steps.any { it.id == SetupStepId.AGENT_WINDOW })
    }

    @Test
    fun settlingTouchesOnlyUnfinishedRows() {
        val note = StepNote(R.string.setup_note_not_reached)
        val progress = SetupProgress()
            .with(SetupStepId.ROOT, StepState.Failed(10, StepNote(R.string.setup_note_root)))
            .with(SetupStepId.VOICE, StepState.Running(0))
            .settleUnfinished { _, _ -> StepState.Skipped(note) }
        assertTrue(progress[SetupStepId.ROOT] is StepState.Failed)
        assertEquals(StepState.Skipped(note), progress[SetupStepId.VOICE])
        assertTrue(progress.failed)
        assertFalse(progress.running)
    }

    @Test
    fun elapsedRoundsToWholeSeconds() {
        assertEquals(ElapsedText(0, 0), ElapsedText.of(-5))
        assertEquals(ElapsedText(0, 2), ElapsedText.of(1_500))
        assertEquals(ElapsedText(1, 5), ElapsedText.of(64_700))
    }
}
