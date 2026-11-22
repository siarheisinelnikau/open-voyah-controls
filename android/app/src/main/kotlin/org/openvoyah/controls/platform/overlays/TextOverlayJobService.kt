package org.openvoyah.controls.platform.overlays

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlin.concurrent.thread
import org.openvoyah.controls.data.overlays.TextOverlayManager
import org.openvoyah.controls.diagnostics.ControlsLog

/**
 * After each start (and after an update of Controls) turns on the text overlays Android now knows, except for the apps
 * the owner switched off. It is a job of its own, so it does not depend on the Frida recovery job; until the root
 * channel is up it retries with a growing delay, and the last failure is shown on the settings page.
 */
class TextOverlayJobService : JobService() {
    @Volatile private var worker: Thread? = null
    /** Set when Android stops the job; the attempt it cut short does not count as a failure. */
    @Volatile private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        stopped = false
        worker = thread(name = "text-overlays-boot") {
            val manager = TextOverlayManager(this)
            try {
                manager.reconcile()
                manager.recordBootResult(null)
                prefs.edit().remove(ATTEMPTS).apply()
                jobFinished(params, false)
            } catch (error: Exception) {
                if (stopped) return@thread
                val attempts = prefs.getInt(ATTEMPTS, 0) + 1
                ControlsLog.warn("text_overlays", "outcome", "boot_reconcile_failed", "attempt", attempts,
                    "error", error.javaClass.simpleName)
                val last = attempts >= MAX_ATTEMPTS
                if (last) {
                    manager.recordBootResult(error.message ?: error.javaClass.simpleName)
                    prefs.edit().remove(ATTEMPTS).apply()
                } else {
                    prefs.edit().putInt(ATTEMPTS, attempts).apply()
                }
                jobFinished(params, !last)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        worker?.interrupt()
        return true
    }

    companion object {
        private const val ID = 17019
        private const val PREFS = "text_overlay_job"
        private const val ATTEMPTS = "attempts"
        private const val MAX_ATTEMPTS = 6

        /** Schedules the job when this build carries overlays; called on boot and after an update. */
        @JvmStatic fun schedule(context: Context) {
            if (context.assets.list(TextOverlayManager.ASSETS).orEmpty().none { it.matches(Regex("[a-z]{2,3}")) }) return
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(ATTEMPTS).apply()
            val job = JobInfo.Builder(ID, ComponentName(context, TextOverlayJobService::class.java))
                .setMinimumLatency(20_000).setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
            val scheduled = context.getSystemService(JobScheduler::class.java).schedule(job) == JobScheduler.RESULT_SUCCESS
            ControlsLog.info("text_overlays", "outcome", if (scheduled) "boot_job_scheduled" else "boot_job_not_scheduled")
        }
    }
}
