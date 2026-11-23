package org.openvoyah.controls.presentation.setup

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openvoyah.controls.R
import org.openvoyah.controls.data.frida.DeploymentProgress
import org.openvoyah.controls.data.frida.FirstRunCheck
import org.openvoyah.controls.domain.setup.SetupProgress
import org.openvoyah.controls.domain.setup.SetupStepId
import org.openvoyah.controls.domain.setup.StepNote
import org.openvoyah.controls.domain.setup.StepState

class SetupStepTextTest {
    private val strings = mapOf(
        R.string.setup_step_root to "Root",
        R.string.setup_step_deploy to "Deploy",
        R.string.setup_state_running to "Running · %1\$s",
        R.string.setup_state_done to "Done · %1\$s",
        R.string.setup_state_failed to "Failed · %1\$s",
        R.string.setup_state_pending to "Waiting",
        R.string.setup_elapsed_seconds to "%1\$d s",
        R.string.setup_elapsed_minutes to "%1\$d min %2\$02d s",
        R.string.setup_note_deploy_uploading to "Sending %1\$s (%2\$d of %3\$d)",
        R.string.setup_note_timeout to "No result within %1\$d s",
        R.string.setup_running_summary to "%1\$d of %2\$d · %3\$s",
        R.string.setup_details_title to "Check",
    )
    private val text = SetupStepText({ strings.getValue(it) }, Locale.ROOT)

    @Test
    fun runningRowsCountTheirOwnSeconds() {
        assertEquals("Running · 12 s", text.status(StepState.Running(since = 3_000), now = 15_200))
        assertEquals("Done · 1 min 05 s", text.status(StepState.Done(65_000), now = 0))
    }

    @Test
    fun deploymentNotesNameTheFileAndItsPosition() {
        val note = FirstRunCheck.deployNote(DeploymentProgress.Stage.UPLOADING, "window.bundle.js", 5, 9)
        assertEquals("Sending window.bundle.js (5 of 9)", text.note(note))
    }

    @Test
    fun detailsListEveryRowWithItsReason() {
        val progress = SetupProgress(startedAt = 0)
            .with(SetupStepId.ROOT, StepState.Done(900))
            .with(SetupStepId.DEPLOY, StepState.Failed(120_000, StepNote(R.string.setup_note_timeout, 120L)))
        val details = text.details(progress.copy(steps = progress.steps.filter { it.id in setOf(SetupStepId.ROOT, SetupStepId.DEPLOY) }),
            "Result", now = 0)
        assertEquals("Check\nResult\n\nRoot: Done · 1 s\nDeploy: Failed · 2 min 00 s — No result within 120 s", details)
    }

    @Test
    fun summaryCountsFinishedRows() {
        val progress = SetupProgress(startedAt = 1_000).with(SetupStepId.ROOT, StepState.Done(1))
        assertEquals("1 of 6 · 9 s", text.summary(progress, now = 10_000))
    }
}
