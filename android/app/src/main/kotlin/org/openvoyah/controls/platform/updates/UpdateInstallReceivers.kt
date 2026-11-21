package org.openvoyah.controls.platform.updates

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.openvoyah.controls.diagnostics.ControlsLog

/** Persist callbacks within the broadcast lifetime; a job performs potentially large installed-APK reads. */
class UpdateInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    val callback = AndroidUpdatePackageSessions.callback(context, intent) ?: return@withTimeout
                    if (PackageUpdateInstaller.get(context).handleResult(callback) && callback.kind != UpdateCallbackKind.APPROVAL) {
                        UpdateRecoveryJobService.schedule(context)
                    }
                }
            } catch (failure: Exception) {
                // The durable receipt remains available to the screen or package-replacement recovery.
                ControlsLog.warn("update_session_result", failure, "outcome", "not_recorded")
            } finally { pending.finish() }
        }
    }
}

class UpdatePackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { UpdateRecoveryJobService.schedule(context) }
            finally { pending.finish() }
        }
    }
}

/** Recovery never recommits or launches Android approval after process death. */
class UpdateRecoveryJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null
    @Volatile private var owner: Any? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val token = Any()
        owner = token
        work = scope.launch {
            try { withTimeout(120_000) { PackageUpdateInstaller.get(this@UpdateRecoveryJobService).recover() } }
            catch (failure: Exception) {
                // A stopped or failed probe leaves the persisted transaction paused.
                ControlsLog.warn("update_recovery_job", failure, "outcome", if (owner === token) "failed" else "stopped")
            }
            finally { if (owner === token) jobFinished(params, false) }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        owner = null
        work?.cancel()
        // Android may retry a stopped recovery probe; it never resubmits the package session.
        return true
    }

    override fun onDestroy() {
        owner = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 0x4f564355

        fun schedule(context: Context) {
            try {
                val scheduler = context.getSystemService(JobScheduler::class.java) ?: run {
                    ControlsLog.warn("update_recovery_job", "outcome", "scheduler_unavailable")
                    return
                }
                val result = scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, UpdateRecoveryJobService::class.java))
                    .setPersisted(true).setOverrideDeadline(1_000).build())
                if (result != JobScheduler.RESULT_SUCCESS) ControlsLog.warn("update_recovery_job", "outcome", "schedule_rejected")
            } catch (failure: RuntimeException) {
                // The Updates screen can still recover the durable receipt.
                ControlsLog.warn("update_recovery_job", failure, "outcome", "schedule_failed")
            }
        }
    }
}
