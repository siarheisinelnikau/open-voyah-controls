package org.openvoyah.controls;

import android.content.ComponentName;
import android.service.notification.NotificationListenerService;
import java.io.IOException;
import org.openvoyah.controls.core.display.DisplayEventBus.Media;
import org.openvoyah.controls.data.display.DisplayPreferences;
import org.openvoyah.controls.data.frida.FridaBridge;
import org.openvoyah.controls.platform.media.MediaSessionMonitor;

/** Retains the Android component name associated with notification listener permission. */
public final class MediaAccessService extends NotificationListenerService {
    private MediaSessionMonitor monitor;

    @Override public void onListenerConnected() {
        if (monitor == null) {
            FridaBridge bridge = new FridaBridge(this);
            monitor = new MediaSessionMonitor(this, new ComponentName(this, MediaAccessService.class),
                UiText.displayEvents(this), new DisplayPreferences(this), new MediaSessionMonitor.ClusterOutput() {
                    @Override public boolean available() { return FridaBridge.wasConfigured(MediaAccessService.this); }
                    @Override public void publish(Media media) throws IOException { bridge.publishClusterMedia(media); }
                });
        }
        monitor.connect();
    }

    @Override public void onListenerDisconnected() { if (monitor != null) monitor.disconnect(); }

    @Override public void onDestroy() {
        if (monitor != null) monitor.close();
        super.onDestroy();
    }
}
