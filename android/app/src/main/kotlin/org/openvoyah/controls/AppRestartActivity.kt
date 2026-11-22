package org.openvoyah.controls

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Process
import org.openvoyah.controls.diagnostics.ControlsLog

/**
 * Settings → "Restart Open Voyah Controls". Runs in its own `:restart` process: it covers the old window, so Android
 * stops the old activity (which flushes pending preference writes), ends the old process, opens Controls again in a
 * fresh process and exits. The car agents and the boot activation live outside the app process and are not touched.
 */
class AppRestartActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previous = intent.getIntExtra(EXTRA_PID, 0)
        window.decorView.postDelayed({ relaunch(previous) }, SETTLE_MS)
    }

    private fun relaunch(previous: Int) {
        if (done) return
        done = true
        // Process.killProcess only reaches processes of this app's own user ID.
        if (previous > 0 && previous != Process.myPid()) Process.killProcess(previous)
        ControlsLog.info("app_restart", "outcome", "relaunched")
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        Runtime.getRuntime().exit(0)
    }

    companion object {
        private const val EXTRA_PID = "org.openvoyah.controls.extra.RESTART_PID"
        private const val PROCESS_SUFFIX = ":restart"
        /** Long enough for the covered activity to stop and write its preferences. */
        private const val SETTLE_MS = 900L

        fun start(activity: Activity) {
            ControlsLog.info("app_restart", "outcome", "requested")
            activity.startActivity(Intent(activity, AppRestartActivity::class.java).putExtra(EXTRA_PID, Process.myPid()))
            @Suppress("DEPRECATION") activity.overridePendingTransition(0, 0)
        }

        /** The helper process only relaunches Controls; process-wide start-up work belongs to the real one. */
        @JvmStatic fun isHelperProcess(): Boolean = Application.getProcessName().orEmpty().endsWith(PROCESS_SUFFIX)
    }
}
