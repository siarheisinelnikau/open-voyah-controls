package org.openvoyah.controls;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.openvoyah.controls.platform.navigation.NavigationBroadcast;

/** Stable component targeted by installed launcher agents. */
public final class LocalNavigationReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        NavigationBroadcast.publish(intent, UiText.displayEvents(context));
    }
}
