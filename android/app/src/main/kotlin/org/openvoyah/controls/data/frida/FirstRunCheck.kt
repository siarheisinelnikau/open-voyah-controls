package org.openvoyah.controls.data.frida

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.openvoyah.controls.ButtonAccessibilityService
import org.openvoyah.controls.R
import org.openvoyah.controls.data.settings.SettingsStore
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.data.voice.VoskModels
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.setup.AgentReadiness
import org.openvoyah.controls.domain.setup.SetupProgress
import org.openvoyah.controls.domain.setup.SetupStepId
import org.openvoyah.controls.domain.setup.StepNote
import org.openvoyah.controls.domain.setup.StepState
import org.openvoyah.controls.platform.voice.SpeechModels

/**
 * The installer asks the app UID to exercise its own channel and initialize reviewed handlers. Every step reports
 * live rows ([SetupProgress]) and has its own time limit; together they stay inside the installer's 240 s wait.
 * The speech model is prepared while the agents deploy, since one is app CPU and storage work and the other
 * waits on the root shell.
 */
internal class FirstRunCheck(private val context: Context) {
    private val lock = Any()
    private var progress = SetupProgress()
    private lateinit var publish: (SetupProgress) -> Unit
    private var started = 0L

    private fun now() = SystemClock.elapsedRealtime()

    private fun set(id: SetupStepId, state: StepState) = update { it.with(id, state) }

    private fun update(change: (SetupProgress) -> SetupProgress) {
        val next = synchronized(lock) { change(progress).also { progress = it } }
        publish(next)
    }

    private fun running(id: SetupStepId, note: StepNote?): Long = now().also { set(id, StepState.Running(it, note)) }

    /** Time left for a step: its own [limit], but never past the installer's wait. */
    private fun allowance(limit: Long): Long = minOf(limit, started + BUDGET_MS - now()).coerceAtLeast(0)

    /** Why a step ran out of time: its own limit, or the installer budget that cut it short. */
    private fun timeout(since: Long): StepNote =
        if (now() >= started + BUDGET_MS) StepNote(R.string.setup_note_budget)
        else StepNote(R.string.setup_note_timeout, (now() - since + 500) / 1000)

    private fun failure(error: Throwable): StepNote = StepNote(R.string.setup_reason_text, FridaFailureText.message(error))

    suspend fun run(nonce: String, listener: (SetupProgress) -> Unit): Int {
        publish = listener
        started = now()
        progress = SetupProgress(startedAt = started)
        publish(progress)
        var phase = "root"
        var channel: AuthorizedRootChannel? = null
        var mic = false
        var buttons = false
        var status = "failed"
        var result = R.string.setup_failed
        try {
            val rootSince = running(SetupStepId.ROOT, StepNote(R.string.setup_note_root))
            val root = try {
                withTimeoutOrNull(allowance(ROOT_LIMIT_MS)) {
                    runInterruptible(Dispatchers.IO) {
                        AuthorizedRootChannel.existing(context).also {
                            check(it.execute("test \"\$(id -u)\" = 0 && printf OVC_ROOT_OK", "first_run_probe") == "OVC_ROOT_OK")
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
                set(SetupStepId.ROOT, StepState.Failed(now() - rootSince, failure(error)))
                throw error
            }
            if (root == null) {
                set(SetupStepId.ROOT, StepState.Failed(now() - rootSince, timeout(rootSince)))
                throw StepTimeout("root")
            }
            channel = root
            set(SetupStepId.ROOT, StepState.Done(now() - rootSince))

            val outcome = coroutineScope {
                val voice = async { prepareVoice() }
                phase = "firmware"
                val hooks = connectHandlers()
                phase = "voice"
                val voiceError = voice.await()
                if (voiceError != null) throw voiceError
                mic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                set(SetupStepId.MICROPHONE, if (mic) StepState.Done(0, StepNote(R.string.setup_note_microphone_granted))
                    else StepState.Attention(StepNote(R.string.setup_note_microphone_missing)))
                phase = if (hooks == Hooks.UNREVIEWED) "firmware" else "hooks"
                hooks
            }
            buttons = awaitButtonService()
            when (outcome) {
                Hooks.UNREVIEWED -> { status = "unreviewed"; result = R.string.setup_unreviewed }
                Hooks.READY -> {
                    status = if (mic && buttons) "ready" else "permissions"
                    result = if (mic && buttons) R.string.setup_ready else R.string.setup_permissions
                }
                Hooks.FAILED -> throw StepTimeout("hooks")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error !is StepTimeout) ControlsLog.warn("first_run_check", error, "phase", phase)
            result = when (phase) {
                "root" -> R.string.setup_root_failed
                "voice" -> if (error is VoskModels.StorageUnavailable) R.string.setup_space_failed else R.string.setup_voice_failed
                else -> R.string.setup_handlers_failed
            }
            update { current -> current.settleUnfinished { _, _ -> StepState.Skipped(StepNote(R.string.setup_note_not_reached)) } }
        }
        // No key, speech, OEM data or error text is sent to the installer.
        val report = "$nonce\t$status\t$phase\t${if (mic) 1 else 0}\t${if (buttons) 1 else 0}"
        val reported = try {
            runInterruptible(Dispatchers.IO) {
                channel?.execute("umask 077\nprintf '%s\\n' '$report' > /data/adb/open-voyah-controls-access/app-probe\n",
                    "first_run_report") ?: throw java.io.IOException()
            }
            true
        } catch (cancelled: CancellationException) {
            // The page that asked is gone; a cancelled report is neither a failed nor a delivered one.
            throw cancelled
        } catch (error: Exception) {
            ControlsLog.warn("first_run_check", error, "phase", "report")
            false
        }
        val finished = synchronized(lock) { progress }
        ControlsLog.info("first_run_check", "outcome", status, "phase", phase, "microphone", mic, "buttons", buttons,
            "reported", reported, "elapsed_ms", now() - started,
            "failed_step", finished.steps.firstOrNull { it.state is StepState.Failed }?.id?.name?.lowercase() ?: "none")
        return if (reported) result else R.string.setup_root_failed
    }

    private enum class Hooks { READY, UNREVIEWED, FAILED }

    /** Thrown to end the check after a step already recorded its own reason. */
    private class StepTimeout(step: String) : Exception(step)

    /** Returns the failure to report, or null. A timeout is reported as a failed preparation. */
    private suspend fun prepareVoice(): Exception? {
        val since = running(SetupStepId.VOICE, StepNote(R.string.setup_note_voice))
        return try {
            // The model stays prepared for the first voice request instead of being loaded a second time.
            val done = withTimeoutOrNull(allowance(VOICE_LIMIT_MS)) {
                SpeechModels.prepareForSetup(context, runInterruptible(Dispatchers.IO) { VoiceSettings(context).language() })
            }
            if (done == null) {
                set(SetupStepId.VOICE, StepState.Failed(now() - since, timeout(since)))
                StepTimeout("voice")
            } else {
                set(SetupStepId.VOICE, StepState.Done(now() - since))
                null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            set(SetupStepId.VOICE, StepState.Failed(now() - since, failure(error)))
            error
        }
    }

    private suspend fun connectHandlers(): Hooks {
        val firmwareSince = running(SetupStepId.FIRMWARE, StepNote(R.string.setup_note_firmware))
        val reviewed = try {
            runInterruptible(Dispatchers.IO) { ReviewedFirmware.load(context) }
            true
        } catch (_: ProductionRootAccess.Refused) {
            false
        } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
            set(SetupStepId.FIRMWARE, StepState.Failed(now() - firmwareSince, failure(error)))
            ControlsLog.warn("first_run_check", error, "phase", "firmware")
            return Hooks.FAILED
        }
        if (!reviewed) {
            set(SetupStepId.FIRMWARE, StepState.Attention(StepNote(R.string.setup_note_unreviewed)))
            set(SetupStepId.DEPLOY, StepState.Skipped(StepNote(R.string.setup_note_unreviewed)))
            return Hooks.UNREVIEWED
        }
        set(SetupStepId.FIRMWARE, StepState.Done(now() - firmwareSince, StepNote(R.string.setup_note_firmware_reviewed)))

        val deploySince = running(SetupStepId.DEPLOY, StepNote(R.string.setup_note_deploy_checking))
        val bridge = FridaBridge(context)
        var sent = 0
        var kept = 0
        val listener = DeploymentProgress { stage, file, index, count ->
            when (stage) {
                DeploymentProgress.Stage.UPLOADING -> sent++
                DeploymentProgress.Stage.REUSING, DeploymentProgress.Stage.COPYING -> kept++
                else -> Unit
            }
            set(SetupStepId.DEPLOY, StepState.Running(deploySince, deployNote(stage, file.orEmpty(), index, count)))
        }
        val deployed = try {
            withTimeoutOrNull(allowance(DEPLOY_LIMIT_MS)) {
                runInterruptible(Dispatchers.IO) { bridge.connect(SettingsStore(context).load(), listener) }
            }
        } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
            set(SetupStepId.DEPLOY, StepState.Failed(now() - deploySince, failure(error)))
            ControlsLog.warn("first_run_check", error, "phase", "deploy")
            return Hooks.FAILED
        }
        if (deployed == null) {
            set(SetupStepId.DEPLOY, StepState.Failed(now() - deploySince, timeout(deploySince)))
            ControlsLog.warn("first_run_check", "phase", "deploy", "outcome", "timeout", "elapsed_ms", now() - deploySince)
            return Hooks.FAILED
        }
        set(SetupStepId.DEPLOY, StepState.Done(now() - deploySince, StepNote(R.string.setup_note_deploy_files, sent, kept)))
        return awaitAgents(bridge)
    }

    /** Polls the supervisors' own files once a second until every agent answered, one failed, or time is up. */
    private suspend fun awaitAgents(bridge: FridaBridge): Hooks {
        val since = now()
        val limit = allowance(AGENTS_LIMIT_MS)
        var agents: Map<SetupStepId, StepState> = emptyMap()
        var reports: List<AgentReadiness.Report> = emptyList()
        while (true) {
            val timedOut = now() - since >= limit
            try {
                reports = AgentReadiness.parse(runInterruptible(Dispatchers.IO) { bridge.setupReadiness() })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: ProductionRootAccess.Refused) {
                // A changed package or a recovery marker will not clear by waiting.
                val reason = failure(refused)
                update { it.withAgents(agents.ifEmpty { mapOf(SetupStepId.AGENT_LAUNCHER to StepState.Pending) })
                    .settleUnfinished { id, state -> if (id.agent) StepState.Failed(now() - since, reason) else state } }
                ControlsLog.warn("first_run_check", refused, "phase", "agents")
                return Hooks.FAILED
            } catch (error: Exception) {
                // One lost probe is retried; the row keeps its last known state until the limit.
                ControlsLog.debug("first_run_check", "phase", "agents", "outcome", "probe_failed", "error", error)
            }
            val verdict = AgentReadiness.evaluate(reports, since, now(), agents, timedOut)
            agents = verdict.agents
            if (timedOut && agents.isEmpty()) {
                agents = mapOf(SetupStepId.AGENT_LAUNCHER to StepState.Failed(now() - since, StepNote(R.string.setup_agent_none)))
            }
            update { it.withAgents(agents) }
            if (verdict.complete) return Hooks.READY
            if (verdict.failed || timedOut || agents.values.any { it is StepState.Failed }) {
                ControlsLog.warn("first_run_check", "phase", "agents", "outcome", if (timedOut) "timeout" else "failed",
                    "elapsed_ms", now() - since, "waiting", agents.filterValues { it !is StepState.Done && it !is StepState.Attention }
                        .keys.joinToString(",") { it.name.removePrefix("AGENT_").lowercase() })
                return Hooks.FAILED
            }
            delay(POLL_MS)
        }
    }

    /** The service usually connects within a second of the app start; wait a little only when it has not. */
    private suspend fun awaitButtonService(): Boolean {
        if (!ButtonAccessibilityService.isConnected()) {
            val since = running(SetupStepId.BUTTONS, StepNote(R.string.setup_note_buttons_waiting))
            withTimeoutOrNull(allowance(BUTTONS_LIMIT_MS)) {
                while (!ButtonAccessibilityService.isConnected()) delay(250)
            }
            if (!ButtonAccessibilityService.isConnected()) {
                set(SetupStepId.BUTTONS, StepState.Attention(StepNote(R.string.setup_note_buttons_missing)))
                return false
            }
            set(SetupStepId.BUTTONS, StepState.Done(now() - since, StepNote(R.string.setup_note_buttons_connected)))
            return true
        }
        set(SetupStepId.BUTTONS, StepState.Done(0, StepNote(R.string.setup_note_buttons_connected)))
        return true
    }

    companion object {
        /** The installer and provision.ps1 wait 240 s for the report; keep a margin for writing it. */
        const val BUDGET_MS = 225_000L
        const val ROOT_LIMIT_MS = 35_000L
        const val VOICE_LIMIT_MS = 150_000L
        const val DEPLOY_LIMIT_MS = 120_000L
        const val AGENTS_LIMIT_MS = 75_000L
        const val BUTTONS_LIMIT_MS = 5_000L
        const val POLL_MS = 1_000L

        fun deployNote(stage: DeploymentProgress.Stage, file: String, index: Int, count: Int): StepNote = when (stage) {
            DeploymentProgress.Stage.CHECKING -> StepNote(R.string.setup_note_deploy_checking)
            DeploymentProgress.Stage.STOPPING -> StepNote(R.string.setup_note_deploy_stopping)
            DeploymentProgress.Stage.VERIFYING -> StepNote(R.string.setup_note_deploy_verifying, file, index, count)
            DeploymentProgress.Stage.UPLOADING -> StepNote(R.string.setup_note_deploy_uploading, file, index, count)
            DeploymentProgress.Stage.REUSING -> StepNote(R.string.setup_note_deploy_reused, file, index, count)
            DeploymentProgress.Stage.COPYING -> StepNote(R.string.setup_note_deploy_copied, file, index, count)
            DeploymentProgress.Stage.SEALING -> StepNote(R.string.setup_note_deploy_sealing)
            DeploymentProgress.Stage.STARTING -> StepNote(R.string.setup_note_deploy_starting)
        }
    }
}
