package org.openvoyah.controls;

import android.app.Application;
import android.content.res.Configuration;
import org.openvoyah.controls.core.display.DisplayEventBus;
import org.openvoyah.controls.data.settings.LocalSettingsArchive;
import org.openvoyah.controls.diagnostics.ControlsLog;
import org.openvoyah.controls.diagnostics.Diagnostics;
import org.openvoyah.controls.platform.voice.SpeechModels;

/** Process entry: diagnostics come first so the recovery of an interrupted settings restore is already recorded. */
public final class ControlsApplication extends Application {
    private final DisplayEventBus displayEvents = new DisplayEventBus();

    DisplayEventBus displayEvents() { return displayEvents; }

    @Override public void onCreate() {
        super.onCreate();
        UiText.attach(this);
        Diagnostics.install(this, BuildConfig.DEBUG, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE);
        // Every process start (boot, jobs, services) passes here; the archive opens about twenty stores.
        try { if (LocalSettingsArchive.hasPendingRollback(this)) new LocalSettingsArchive(this).recoverInterruptedRestore(); }
        catch (Exception ex) { ControlsLog.error("settings_restore_recovery", ex, "outcome", "failed"); }
        // Off the main thread: the first voice key press after boot then finds the speech model ready.
        SpeechModels.warmUp(this, "startup");
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The localized resources were derived from the previous configuration.
        UiText.invalidate();
    }
}
