package org.openvoyah.controls.platform.media;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.openvoyah.controls.core.display.DisplayEventBus.Media;
import org.openvoyah.controls.core.display.DisplayEventBus;
import org.openvoyah.controls.data.display.DisplayPreferences;
import org.openvoyah.controls.diagnostics.ControlsLog;

/** Translates Android media callbacks into display events. Runs on the main thread. */
public final class MediaSessionMonitor implements AutoCloseable {
    public interface ClusterOutput {
        boolean available();
        void publish(Media media) throws IOException;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final DisplayEventBus events;
    private final DisplayPreferences preferences;
    private final ClusterOutput cluster;
    private final MediaSessionManager manager;
    private final ComponentName component;
    private boolean connected;
    private MediaController current;
    private MediaController.Callback callback;
    private MediaSessionReading reading = new MediaSessionReading();
    private Runnable unsubscribe;
    private volatile long revision;
    private Media lastRequested = Media.EMPTY;

    public MediaSessionMonitor(Context context, ComponentName component, DisplayEventBus events,
            DisplayPreferences preferences, ClusterOutput cluster) {
        this.manager = context.getSystemService(MediaSessionManager.class);
        this.component = component;
        this.events = events;
        this.preferences = preferences;
        this.cluster = cluster;
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
                if (active()) select(manager.getActiveSessions(component));
            }
        };
    }

    public void connect() {
        if (connected || manager == null) return;
        try {
            manager.addOnActiveSessionsChangedListener(sessionListener, component, handler);
            connected = true;
            unsubscribe = preferences.observeClusterMedia(() -> publish(events.snapshot().media()));
            select(manager.getActiveSessions(component));
        } catch (SecurityException ex) { disconnect(); }
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
        publish(snapshot.media());
    }

    private void publish(Media snapshot) {
        Media desired = preferences.clusterMedia() && snapshot.available() ? snapshot : Media.EMPTY;
        if (desired.equals(lastRequested) || !cluster.available() || worker.isShutdown()) return;
        lastRequested = desired;
        long token = ++revision;
        worker.execute(() -> {
            if (token != revision) return;
            try { cluster.publish(desired); }
            catch (IOException ex) {
                ControlsLog.info("cluster_media_publish", "outcome", "transport_unavailable", "error", ex);
                handler.post(() -> { if (token == revision) lastRequested = null; });
            }
        });
    }

    public void disconnect() {
        if (!connected) return;
        connected = false;
        if (unsubscribe != null) { unsubscribe.run(); unsubscribe = null; }
        manager.removeOnActiveSessionsChangedListener(sessionListener);
        select(Collections.emptyList());
    }

    @Override public void close() {
        disconnect();
        worker.shutdown();
    }
}
