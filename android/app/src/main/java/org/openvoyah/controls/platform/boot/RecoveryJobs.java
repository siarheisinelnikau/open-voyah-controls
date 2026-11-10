package org.openvoyah.controls.platform.boot;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;
import org.openvoyah.controls.BootHookService;

/** Persists the same Android job ID and component across upgrades. */
public final class RecoveryJobs {
    public static final int ID = 17018;

    private RecoveryJobs() { }

    public static void schedule(Context context) {
        JobInfo job = new JobInfo.Builder(ID, new ComponentName(context, BootHookService.class))
            .setMinimumLatency(5000).setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true).build();
        if (context.getSystemService(JobScheduler.class).schedule(job) != JobScheduler.RESULT_SUCCESS)
            Log.e("OpenVoyahControls", "Could not schedule boot hook");
    }

    public static void cancel(Context context) { context.getSystemService(JobScheduler.class).cancel(ID); }
}
