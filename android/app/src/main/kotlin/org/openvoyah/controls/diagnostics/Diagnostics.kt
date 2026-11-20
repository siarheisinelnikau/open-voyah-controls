package org.openvoyah.controls.diagnostics

import android.content.Context
import android.os.Build

/** Process-wide diagnostics: the on-device journal and the record of an uncaught failure. */
object Diagnostics {
    private const val CRASH_FLUSH_MILLIS = 750L

    @Volatile private var journal: DiagnosticJournal? = null

    /** The journal of this process, or null before [install] ran (for example in a local JVM test). */
    @JvmStatic fun journal(): DiagnosticJournal? = journal

    /**
     * Called once from the application. Later calls are ignored, so a second entry point cannot stack handlers.
     * [verbose] also sends stack traces to Logcat; the journal never stores them.
     */
    @JvmStatic
    @Synchronized
    fun install(context: Context, verbose: Boolean, versionName: String, versionCode: Int) {
        if (journal != null) return
        ControlsLog.setVerbose(verbose)
        val created = DiagnosticJournal.create(context.applicationContext.filesDir)
        journal = created
        ControlsLog.addSink(created)
        recordUncaughtFailures(created)
        ControlsLog.info("process_start", "version", versionName, "code", versionCode, "sdk", Build.VERSION.SDK_INT,
            "product", Build.PRODUCT, "abi", Build.SUPPORTED_ABIS.firstOrNull())
    }

    private fun recordUncaughtFailures(journal: DiagnosticJournal) {
        val platform = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            try {
                ControlsLog.error("uncaught_exception", failure, "thread", thread.name)
                // The platform handler kills the process; give the single writer a bounded moment to finish.
                journal.flush(CRASH_FLUSH_MILLIS)
            } catch (_: Throwable) {
                // Reporting must not replace the original failure.
            }
            platform?.uncaughtException(thread, failure)
        }
    }
}
