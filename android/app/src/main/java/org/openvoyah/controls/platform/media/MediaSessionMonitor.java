package org.openvoyah.controls.platform.media;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.openvoyah.controls.core.display.DisplayEventBus.Media;
import org.openvoyah.controls.core.display.DisplayEventBus;
import org.openvoyah.controls.data.display.DisplayPreferences;
import org.openvoyah.controls.platform.DisplayEventText;

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
    private final MediaController.Callback mediaListener = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { if (connected) update(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { if (connected) update(); }
        @Override public void onSessionDestroyed() {
            if (connected) select(manager.getActiveSessions(component));
        }
    };

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
        List<MediaController> available = controllers == null ? Collections.emptyList() : controllers;
        MediaController preferred = available.isEmpty() ? null : available.get(0);
        for (MediaController controller : available) {
            if (isPlaying(controller.getPlaybackState())) { preferred = controller; break; }
        }
        if (current != null && preferred != null && current.getSessionToken().equals(preferred.getSessionToken())) {
            update();
            return;
        }
        if (current != null) current.unregisterCallback(mediaListener);
        current = preferred;
        if (current != null) current.registerCallback(mediaListener, handler);
        update();
    }

    private void update() {
        MediaMetadata metadata = current == null ? null : current.getMetadata();
        Media snapshot = metadata == null ? Media.EMPTY : new Media(
            DisplayEventText.sanitize(metadata.getText(MediaMetadata.METADATA_KEY_TITLE)),
            DisplayEventText.sanitize(metadata.getText(MediaMetadata.METADATA_KEY_ARTIST)),
            DisplayEventText.sanitize(metadata.getText(MediaMetadata.METADATA_KEY_ALBUM)),
            isPlaying(current.getPlaybackState()));
        events.publishMedia(snapshot);
        publish(snapshot);
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
                Log.i("OpenVoyahControls", "Cluster media transport unavailable");
                handler.post(() -> { if (token == revision) lastRequested = null; });
            }
        });
    }

    private static boolean isPlaying(PlaybackState state) {
        return state != null && state.getState() == PlaybackState.STATE_PLAYING;
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
