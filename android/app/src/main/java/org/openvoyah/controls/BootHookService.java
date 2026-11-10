package org.openvoyah.controls;

import android.app.job.JobParameters;
import android.app.job.JobService;
import org.openvoyah.controls.data.frida.FridaBridge;
import org.openvoyah.controls.platform.boot.BridgeRecoveryJob;

/** Stable entry point for the persisted launcher recovery job. */
public final class BootHookService extends JobService {
    private BridgeRecoveryJob job;

    @Override public void onCreate() {
        super.onCreate();
        job = new BridgeRecoveryJob(() -> FridaBridge.needsRecovery(this),
            new FridaBridge(this)::reconnectAfterBoot, this::jobFinished);
    }

    @Override public boolean onStartJob(JobParameters params) { return job.start(params); }
    @Override public boolean onStopJob(JobParameters params) { return job.stop(); }

    @Override public void onDestroy() {
        job.close();
        super.onDestroy();
    }
}
