package org.openvoyah.controls.platform.boot;

import android.app.job.JobParameters;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Maps a cancellable recovery request onto the Android job lifecycle. */
public final class BridgeRecoveryJob {
    public interface Recovery { void run() throws Exception; }

    private final BooleanSupplier needed;
    private final Recovery recovery;
    private final BiConsumer<JobParameters, Boolean> finished;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Thread worker;

    public BridgeRecoveryJob(BooleanSupplier needed, Recovery recovery, BiConsumer<JobParameters, Boolean> finished) {
        this.needed = needed;
        this.recovery = recovery;
        this.finished = finished;
    }

    public boolean start(JobParameters params) {
        if (!needed.getAsBoolean()) return false;
        worker = new Thread(() -> {
            boolean retry = false;
            try { recovery.run(); }
            catch (Exception ex) {
                retry = needed.getAsBoolean();
                Log.w("OpenVoyahControls", "Bridge recovery will retry", ex);
            }
            boolean reschedule = retry;
            Thread task = Thread.currentThread();
            main.post(() -> {
                if (worker != task) return;
                worker = null;
                finished.accept(params, reschedule);
            });
        }, "controls-boot");
        worker.start();
        return true;
    }

    public boolean stop() {
        close();
        return needed.getAsBoolean();
    }

    public void close() {
        Thread task = worker;
        worker = null;
        if (task != null) task.interrupt();
    }
}
