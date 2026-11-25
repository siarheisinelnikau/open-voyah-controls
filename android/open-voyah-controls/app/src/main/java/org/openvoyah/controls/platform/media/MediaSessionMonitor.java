package org.openvoyah.controls.platform.media;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import java.util.Collections;
import java.util.List;
import org.openvoyah.controls.core.display.DisplayEventBus;
import org.openvoyah.controls.diagnostics.ControlsLog;

/** Translates Android media callbacks into display events. Runs on the main thread. */
public final class MediaSessionMonitor implements AutoCloseable {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DisplayEventBus events;
    private final MediaSessionManager manager;
    private final ComponentName component;
    private boolean connected;
    private MediaController current;
    private MediaController.Callback callback;
    private MediaSessionReading reading = new MediaSessionReading();
    // Logged on a transition only.
    private boolean accessDenied;

    public MediaSessionMonitor(Context context, ComponentName component, DisplayEventBus events) {
        this.manager = context.getSystemService(MediaSessionManager.class);
        this.component = component;
        this.events = events;
    }

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionListener = controllers -> {
        if (connected) select(controllers);
    };
    private MediaController.Callback listenTo(MediaController selected) {
        return new MediaController.Callback() {
            private boolean active() { return connected && current == selected; }

            @Override public void onMetadataChanged(MediaMetadata metadata) {
                if (active()) update(null, false);
            }

            @Override public void onPlaybackStateChanged(PlaybackState state) {
                if (!active()) return;
                if (state == null) reading.clearPlayback();
                update(state, false);
            }

            @Override public void onSessionDestroyed() {
                if (!active()) return;
                List<MediaController> remaining;
                try { remaining = manager.getActiveSessions(component); }
                catch (SecurityException ex) {
                    // Notification access was withdrawn while connected; behave as a failed connect.
                    accessChanged(ex);
                    disconnect();
                    return;
                }
                select(remaining);
            }
        };
    }

    public void connect() {
        if (connected || manager == null) return;
        try {
            manager.addOnActiveSessionsChangedListener(sessionListener, component, handler);
            connected = true;
            select(manager.getActiveSessions(component));
            accessChanged(null);
        } catch (SecurityException ex) {
            accessChanged(ex);
            disconnect();
        }
    }

    private void accessChanged(SecurityException denied) {
        if ((denied != null) == accessDenied) return;
        accessDenied = denied != null;
        if (denied != null) ControlsLog.warn("media_sessions", denied, "outcome", "access_denied");
        else ControlsLog.info("media_sessions", "outcome", "access_granted");
    }

    private void select(List<MediaController> controllers) {
        MediaController preferred = selectPlaying(controllers == null ? Collections.emptyList() : controllers);
        if (current != null && preferred != null && current.getSessionToken().equals(preferred.getSessionToken())) {
            update(null, false);
            return;
        }
        if (current != null) current.unregisterCallback(callback);
        current = preferred;
        reading = new MediaSessionReading();
        callback = current == null ? null : listenTo(current);
        if (current != null) current.registerCallback(callback, handler);
        update(null, true);
    }

    static MediaController selectPlaying(List<MediaController> available) {
        for (MediaController controller : available) {
            if (MediaSessionReading.isPlaying(controller.getPlaybackState())) return controller;
        }
        return available.isEmpty() ? null : available.get(0);
    }

    private void update(PlaybackState reported, boolean selectedSession) {
        MediaMetadata metadata = current == null ? null : current.getMetadata();
        PlaybackState playback = current == null ? null : current.getPlaybackState();
        var snapshot = reading.read(metadata, playback, selectedSession ? playback : reported);
        events.publishMedia(snapshot.media(), snapshot.progress());
    }

    public void disconnect() {
        if (!connected) return;
        connected = false;
        manager.removeOnActiveSessionsChangedListener(sessionListener);
        select(Collections.emptyList());
    }

    @Override public void close() {
        disconnect();
    }
}
