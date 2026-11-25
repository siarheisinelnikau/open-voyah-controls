package org.openvoyah.controls;

import android.content.ComponentName;
import android.service.notification.NotificationListenerService;
import org.openvoyah.controls.platform.media.MediaSessionMonitor;

/** Retains the Android component name associated with notification listener permission. */
public final class MediaAccessService extends NotificationListenerService {
    private MediaSessionMonitor monitor;

    @Override public void onListenerConnected() {
        if (monitor == null) {
            monitor = new MediaSessionMonitor(this, new ComponentName(this, MediaAccessService.class), UiText.displayEvents(this));
        }
        monitor.connect();
    }

    @Override public void onListenerDisconnected() { if (monitor != null) monitor.disconnect(); }

    @Override public void onDestroy() {
        if (monitor != null) monitor.close();
        super.onDestroy();
    }
}
