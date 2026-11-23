package org.openvoyah.controls;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.openvoyah.controls.data.frida.FridaBridge;
import org.openvoyah.controls.platform.boot.RecoveryJobs;
import org.openvoyah.controls.platform.overlays.TextOverlayJobService;
import org.openvoyah.controls.platform.weather.HomeWeatherJobs;

/** Boot starts a background job, never the settings Activity. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            if (FridaBridge.needsRecovery(context)) RecoveryJobs.schedule(context);
            // Android drops the app's jobs when it is updated; the home screen weather puts its own back.
            HomeWeatherJobs.ensure(context);
            // Overlays placed before this start come up off; their own job turns on the ones the owner wants.
            TextOverlayJobService.schedule(context);
        }
    }
}
