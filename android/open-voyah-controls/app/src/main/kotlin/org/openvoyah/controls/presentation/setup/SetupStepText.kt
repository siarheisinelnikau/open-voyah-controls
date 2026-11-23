package org.openvoyah.controls.presentation.setup

import java.util.Locale
import org.openvoyah.controls.R
import org.openvoyah.controls.domain.setup.ElapsedText
import org.openvoyah.controls.domain.setup.SetupProgress
import org.openvoyah.controls.domain.setup.SetupStep
import org.openvoyah.controls.domain.setup.SetupStepId
import org.openvoyah.controls.domain.setup.StepNote
import org.openvoyah.controls.domain.setup.StepState

/** Turns setup rows into text through [string] (normally `UiText::get`), so tests can check it without Android. */
internal class SetupStepText(private val string: (Int) -> String, private val locale: Locale = Locale.getDefault()) {
    fun title(id: SetupStepId): String = string(when (id) {
        SetupStepId.ROOT -> R.string.setup_step_root
        SetupStepId.FIRMWARE -> R.string.setup_step_firmware
        SetupStepId.DEPLOY -> R.string.setup_step_deploy
        SetupStepId.AGENT_LAUNCHER -> R.string.setup_step_agent_launcher
        SetupStepId.AGENT_LOCALE_SETTING -> R.string.setup_step_agent_locale_setting
        SetupStepId.AGENT_LOCALE_VEHICLE -> R.string.setup_step_agent_locale_vehicle
        SetupStepId.AGENT_LOCALE_BLUETOOTH -> R.string.setup_step_agent_locale_bluetooth
        SetupStepId.AGENT_LOCALE_HIBOARD -> R.string.setup_step_agent_locale_hiboard
        SetupStepId.AGENT_WINDOW -> R.string.setup_step_agent_window
        SetupStepId.AGENT_NAVIGATION -> R.string.setup_step_agent_navigation
        SetupStepId.AGENT_CAN -> R.string.setup_step_agent_can
        SetupStepId.VOICE -> R.string.setup_step_voice
        SetupStepId.MICROPHONE -> R.string.setup_step_microphone
        SetupStepId.BUTTONS -> R.string.setup_step_buttons
    })

    fun note(note: StepNote?): String? = note?.let { format(it.text, *it.args.toTypedArray()) }

    fun elapsed(elapsedMs: Long): String {
        val time = ElapsedText.of(elapsedMs)
        return if (time.minutes == 0L) format(R.string.setup_elapsed_seconds, time.seconds)
        else format(R.string.setup_elapsed_minutes, time.minutes, time.seconds)
    }

    /** The short status at the end of a row; a running row counts its own seconds from [now]. */
    fun status(state: StepState, now: Long): String = when (state) {
        StepState.Pending -> string(R.string.setup_state_pending)
        is StepState.Running -> format(R.string.setup_state_running, elapsed(now - state.since))
        is StepState.Done -> format(R.string.setup_state_done, elapsed(state.elapsedMs))
        is StepState.Attention -> string(R.string.setup_state_attention)
        is StepState.Skipped -> string(R.string.setup_state_skipped)
        is StepState.Failed -> format(R.string.setup_state_failed, elapsed(state.elapsedMs))
    }

    fun detail(state: StepState): String? = when (state) {
        StepState.Pending -> null
        is StepState.Running -> note(state.note)
        is StepState.Done -> note(state.note)
        is StepState.Attention -> note(state.note)
        is StepState.Skipped -> note(state.note)
        is StepState.Failed -> note(state.reason)
    }

    fun summary(progress: SetupProgress, now: Long): String {
        val finished = progress.steps.count { it.state !is StepState.Pending && it.state !is StepState.Running }
        return format(R.string.setup_running_summary, finished, progress.steps.size, elapsed(now - progress.startedAt))
    }

    /** Plain text for the clipboard: the result, then one line per row with its status and reason. */
    fun details(progress: SetupProgress, result: String?, now: Long): String = buildString {
        appendLine(string(R.string.setup_details_title))
        if (!result.isNullOrBlank()) appendLine(result)
        appendLine()
        for (step in progress.steps) appendLine(line(step, now))
    }.trimEnd()

    fun line(step: SetupStep, now: Long): String {
        val detail = detail(step.state)
        return "${title(step.id)}: ${status(step.state, now)}" + if (detail.isNullOrBlank()) "" else " — $detail"
    }

    private fun format(resource: Int, vararg args: Any): String = String.format(locale, string(resource), *args)
}
