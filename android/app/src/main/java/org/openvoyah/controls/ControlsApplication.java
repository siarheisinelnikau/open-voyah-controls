package org.openvoyah.controls;

import android.app.Application;
import android.content.res.Configuration;
import org.openvoyah.controls.core.display.DisplayEventBus;
import org.openvoyah.controls.data.settings.LocalSettingsArchive;
import org.openvoyah.controls.diagnostics.ControlsLog;
import org.openvoyah.controls.diagnostics.Diagnostics;
import org.openvoyah.controls.platform.audio.MediaDuck;
import org.openvoyah.controls.platform.startup.StartupWork;
import org.openvoyah.controls.platform.voice.SpeechModels;

/** Process entry: diagnostics come first so the recovery of an interrupted settings restore is already recorded. */
public final class ControlsApplication extends Application {
    private final DisplayEventBus displayEvents = new DisplayEventBus();

    DisplayEventBus displayEvents() { return displayEvents; }

    @Override public void onCreate() {
        super.onCreate();
        UiText.attach(this);
        Diagnostics.install(this, BuildConfig.DEBUG, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE);
        // The short-lived process that relaunches Controls from Settings does none of the start-up work below.
        if (AppRestartActivity.isHelperProcess()) return;
        // Every process start (boot, jobs, services) passes here; the archive opens about twenty stores.
        try { if (LocalSettingsArchive.hasPendingRollback(this)) new LocalSettingsArchive(this).recoverInterruptedRestore(); }
        catch (Exception ex) { ControlsLog.error("settings_restore_recovery", ex, "outcome", "failed"); }
        // Heavy start-up work waits while the startup intro plays, so the intro keeps the CPU it needs to stay smooth.
        StartupWork.afterIntro(() -> {
            // Off the main thread: the first voice key press after boot then finds the speech model ready.
            SpeechModels.warmUp(this, "startup");
            // Off the main thread too: the first open of the app grid then shows its apps, icons and pages at once.
            org.openvoyah.controls.platform.apps.AppCatalogCache.start(this);
        });
        // Puts back a media level a killed process left lowered, and lowers media during phone calls.
        MediaDuck.start(this);
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The localized resources were derived from the previous configuration.
        UiText.invalidate();
    }
}
