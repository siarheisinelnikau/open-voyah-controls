package org.openvoyah.controls;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import org.openvoyah.controls.data.settings.SettingsStore;
import org.openvoyah.controls.platform.media.MediaSessionMonitor;
import org.openvoyah.controls.platform.navigation.YandexDriverGuidance;

/** Retains the Android component name associated with notification listener permission. */
public final class MediaAccessService extends NotificationListenerService {
    private MediaSessionMonitor monitor;
    private YandexDriverGuidance guidance;
    private boolean guidanceEnabled;
    private final SharedPreferences.OnSharedPreferenceChangeListener guidanceSettings = (prefs, key) -> {
        if ("state".equals(key) && guidance != null) {
            boolean enabled = new SettingsStore(this).load().driverNavigationCard;
            if (enabled && !guidanceEnabled) refreshGuidance();
            if (!enabled) guidance.clear();
            guidanceEnabled = enabled;
        }
    };

    @Override public void onListenerConnected() {
        if (monitor == null) {
            monitor = new MediaSessionMonitor(this, new ComponentName(this, MediaAccessService.class), UiText.displayEvents(this));
        }
        monitor.connect();
        if (Build.VERSION.SDK_INT == 30 && guidance == null) {
            guidance = new YandexDriverGuidance(this);
            SettingsStore settings = new SettingsStore(this);
            guidanceEnabled = settings.load().driverNavigationCard;
            settings.preferences().registerOnSharedPreferenceChangeListener(guidanceSettings);
            if (guidanceEnabled) refreshGuidance();
        }
    }

    @Override public void onNotificationPosted(StatusBarNotification notification) {
        if (guidance != null && guidanceEnabled) guidance.posted(notification);
    }

    @Override public void onNotificationRemoved(StatusBarNotification notification) {
        if (guidance != null) guidance.removed(notification);
    }

    @Override public void onListenerDisconnected() {
        detachGuidance();
        if (monitor != null) monitor.disconnect();
    }

    @Override public void onDestroy() {
        detachGuidance();
        if (monitor != null) monitor.close();
        super.onDestroy();
    }

    private void refreshGuidance() {
        try { guidance.refresh(getActiveNotifications()); }
        catch (RuntimeException disconnected) { guidance.clear(); }
    }

    private void detachGuidance() {
        if (guidance == null) return;
        new SettingsStore(this).preferences().unregisterOnSharedPreferenceChangeListener(guidanceSettings);
        guidance.clear();
        guidance = null;
        guidanceEnabled = false;
    }
}
