package org.openvoyah.controls.domain.setup

/**
 * The first-run check as rows the owner can follow: each step says whether it waits, runs (and since when), is
 * done, needs attention, was skipped or failed and why. Times are `SystemClock.elapsedRealtime()` milliseconds,
 * so a running row can show its elapsed seconds without a fake timer.
 */
enum class SetupStepId {
    ROOT, FIRMWARE, DEPLOY,
    AGENT_LAUNCHER, AGENT_LOCALE_SETTING, AGENT_LOCALE_VEHICLE, AGENT_LOCALE_BLUETOOTH, AGENT_LOCALE_HIBOARD,
    AGENT_WINDOW, AGENT_NAVIGATION, AGENT_CAN,
    VOICE, MICROPHONE, BUTTONS;

    val agent: Boolean get() = name.startsWith("AGENT_")

    companion object {
        /** The rows shown before the agents are known; agent rows are inserted after [DEPLOY]. */
        val BASE = listOf(ROOT, FIRMWARE, DEPLOY, VOICE, MICROPHONE, BUTTONS)
    }
}

/** A localized line: a string resource and its format arguments, resolved only when it is drawn or copied. */
data class StepNote(val text: Int, val args: List<Any> = emptyList()) {
    constructor(text: Int, vararg args: Any) : this(text, args.toList())
}

sealed interface StepState {
    data object Pending : StepState
    data class Running(val since: Long, val note: StepNote? = null) : StepState
    data class Done(val elapsedMs: Long, val note: StepNote? = null) : StepState
    /** Finished, but the owner has something to do (a permission) or the step waits for an app to start. */
    data class Attention(val note: StepNote) : StepState
    data class Skipped(val note: StepNote) : StepState
    data class Failed(val elapsedMs: Long, val reason: StepNote) : StepState
}

data class SetupStep(val id: SetupStepId, val state: StepState)

/** [startedAt] is when the check began, for the overall elapsed time. */
data class SetupProgress(
    val steps: List<SetupStep> = SetupStepId.BASE.map { SetupStep(it, StepState.Pending) },
    val startedAt: Long = 0L,
) {
    operator fun get(id: SetupStepId): StepState? = steps.firstOrNull { it.id == id }?.state

    /** Replaces the state of [id], adding the row in its natural order when it is not shown yet. */
    fun with(id: SetupStepId, state: StepState): SetupProgress {
        if (steps.any { it.id == id }) return copy(steps = steps.map { if (it.id == id) SetupStep(id, state) else it })
        return copy(steps = (steps + SetupStep(id, state)).sortedBy { it.id.ordinal })
    }

    /** Agent rows for exactly the channels this deployment started; rows of channels no longer running go away. */
    fun withAgents(agents: Map<SetupStepId, StepState>): SetupProgress =
        copy(steps = (steps.filterNot { it.id.agent } + agents.map { SetupStep(it.key, it.value) }).sortedBy { it.id.ordinal })

    /** Every step that has not finished yet ends as [state]; used when the check stops early. */
    fun settleUnfinished(state: (SetupStepId, StepState) -> StepState): SetupProgress =
        copy(steps = steps.map { step ->
            if (step.state is StepState.Pending || step.state is StepState.Running) SetupStep(step.id, state(step.id, step.state)) else step
        })

    val running: Boolean get() = steps.any { it.state is StepState.Running }
    val failed: Boolean get() = steps.any { it.state is StepState.Failed }
}

/** Elapsed time as the rows show it: whole seconds below a minute, minutes and seconds above. */
data class ElapsedText(val minutes: Long, val seconds: Long) {
    companion object {
        fun of(elapsedMs: Long): ElapsedText {
            val total = (elapsedMs.coerceAtLeast(0) + 500) / 1000
            return ElapsedText(total / 60, total % 60)
        }
    }
}
