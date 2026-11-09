package org.openvoyah.controls;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

public final class BootHookService extends JobService {
    static final int JOB_ID = 17018;
    private volatile Thread worker;
    static void schedule(Context context) {
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, BootHookService.class))
            .setMinimumLatency(5000).setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true).build();
        if (context.getSystemService(JobScheduler.class).schedule(job) != JobScheduler.RESULT_SUCCESS)
            Log.e("OpenVoyahControls", "Could not schedule boot hook");
    }
    static void cancel(Context context) { context.getSystemService(JobScheduler.class).cancel(JOB_ID); }
    @Override public boolean onStartJob(JobParameters params) {
        if (!FridaBridge.needsRecovery(this)) return false;
        Thread task = new Thread(() -> {
            boolean retry = false;
            try { new FridaBridge(this).reconnectAfterBoot(); }
            catch (Exception ex) { retry = FridaBridge.needsRecovery(this); Log.w("OpenVoyahControls", "Bridge recovery will retry", ex); }
            if (worker == Thread.currentThread()) jobFinished(params, retry);
        }, "controls-boot");
        worker = task; task.start(); return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        Thread task = worker; worker = null;
        if (task != null) task.interrupt();
        return FridaBridge.needsRecovery(this);
    }
}
