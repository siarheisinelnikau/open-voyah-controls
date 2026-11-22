package org.openvoyah.controls.domain.setup

import org.openvoyah.controls.R

/**
 * Reads the per-agent readiness probe (`FridaBridge.setupReadiness`) and turns it into rows.
 *
 * The probe prints one line per running channel: `OVC_AGENT <channel> <phase> <detail>`, tab separated. The phase
 * comes from the supervisor's own files: its worker and injector processes, the target's PID receipt and the
 * readiness markers it copies from the agent's `hook.log`. The detail is the agent's or supervisor's latest
 * `OVC_*_ERROR` line, which by contract carries stage and kind codes and never message text.
 */
object AgentReadiness {
    enum class Phase(val token: String) {
        /** No live supervisor worker: not started yet, or it gave up (the detail says why). */
        STOPPED("stopped"),
        /** The worker waits for the target process; translation targets start only when the driver opens them. */
        WAITING_TARGET("waiting_target"),
        /** The worker found the target and its injector is not running yet. */
        ATTACHING("attaching"),
        /** The injector runs; the agent has not confirmed fresh readiness markers yet. */
        STARTING("starting"),
        READY("ready"),
    }

    data class Report(val channel: String, val phase: Phase, val detail: String)

    /** How long a channel may be without a live worker right after deployment before that counts as a failure. */
    const val WORKER_START_GRACE_MS = 20_000L
    private const val MAX_DETAIL = 160

    fun parse(output: String): List<Report> = output.lineSequence().mapNotNull { line ->
        val columns = line.trimEnd('\r').split('\t')
        if (columns.size < 3 || columns[0] != "OVC_AGENT") return@mapNotNull null
        val phase = Phase.entries.firstOrNull { it.token == columns[2] } ?: return@mapNotNull null
        val detail = columns.getOrNull(3).orEmpty().filter { it >= ' ' && it != '\u007f' }.trim().take(MAX_DETAIL)
        Report(columns[1], phase, detail)
    }.toList()

    fun step(channel: String): SetupStepId? = when (channel) {
        "main" -> SetupStepId.AGENT_LAUNCHER
        "locale-setting" -> SetupStepId.AGENT_LOCALE_SETTING
        "locale-vehicle" -> SetupStepId.AGENT_LOCALE_VEHICLE
        "locale-bluetooth" -> SetupStepId.AGENT_LOCALE_BLUETOOTH
        "locale-hiboard" -> SetupStepId.AGENT_LOCALE_HIBOARD
        "window" -> SetupStepId.AGENT_WINDOW
        "navigation" -> SetupStepId.AGENT_NAVIGATION
        "can" -> SetupStepId.AGENT_CAN
        else -> null
    }

    private fun translation(id: SetupStepId) = id.name.startsWith("AGENT_LOCALE_")

    data class Verdict(val agents: Map<SetupStepId, StepState>) {
        /** Every agent answered, or is a translation agent that waits for its app to be opened. */
        val complete: Boolean get() = agents.isNotEmpty() && agents.values.all { it is StepState.Done || it is StepState.Attention }
        val failed: Boolean get() = agents.values.any { it is StepState.Failed }
    }

    /**
     * [startedAt] is when the agents were started, [now] the time of this probe. With [timedOut] every agent that
     * has not answered fails with the reason the probe last saw. A row that was already done keeps its time.
     */
    fun evaluate(
        reports: List<Report>,
        startedAt: Long,
        now: Long,
        previous: Map<SetupStepId, StepState> = emptyMap(),
        timedOut: Boolean = false,
    ): Verdict {
        val elapsed = now - startedAt
        val agents = linkedMapOf<SetupStepId, StepState>()
        for (report in reports) {
            val id = step(report.channel) ?: continue
            val detail = report.detail.takeIf { it.isNotEmpty() }
            val state: StepState = when (report.phase) {
                Phase.READY -> previous[id] as? StepState.Done ?: StepState.Done(elapsed)
                Phase.WAITING_TARGET -> if (translation(id)) {
                    StepState.Attention(StepNote(R.string.setup_agent_waiting_app))
                } else if (timedOut) {
                    StepState.Failed(elapsed, StepNote(R.string.setup_agent_target_missing, seconds(elapsed)))
                } else StepState.Running(startedAt, StepNote(R.string.setup_agent_waiting_target))
                Phase.STOPPED -> if (elapsed >= WORKER_START_GRACE_MS || timedOut) {
                    StepState.Failed(elapsed, if (detail == null) StepNote(R.string.setup_agent_stopped)
                        else StepNote(R.string.setup_agent_stopped_detail, detail))
                } else StepState.Running(startedAt, StepNote(R.string.setup_agent_worker_starting))
                Phase.ATTACHING, Phase.STARTING -> if (timedOut) {
                    StepState.Failed(elapsed, if (detail == null) StepNote(R.string.setup_agent_timeout, seconds(elapsed))
                        else StepNote(R.string.setup_agent_timeout_detail, seconds(elapsed), detail))
                } else StepState.Running(startedAt, when {
                    detail != null -> StepNote(R.string.setup_agent_error_detail, detail)
                    report.phase == Phase.ATTACHING -> StepNote(R.string.setup_agent_attaching)
                    else -> StepNote(R.string.setup_agent_starting)
                })
            }
            agents[id] = state
        }
        return Verdict(agents)
    }

    private fun seconds(elapsedMs: Long): Long = (elapsedMs + 500) / 1000
}
