package org.openvoyah.controls;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Boot starts a background job, never the settings Activity. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            if (FridaBridge.needsRecovery(context)) BootHookService.schedule(context);
        }
    }
}
