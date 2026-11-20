package org.openvoyah.controls.core.display;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Local, typed display events. Publishers and observers use the same event thread. */
public final class DisplayEventBus {
    public record Media(String title, String artist, String album, boolean playing) {
        public static final Media EMPTY = new Media("", "", "", false);
        public boolean available() { return !title.isEmpty(); }
    }

    public record Navigation(String road, String nextRoad, int meters, boolean routeUpdate) {
        public static final Navigation EMPTY = new Navigation("", "", -1);

        public Navigation(String road, String nextRoad, int meters) {
            this(road, nextRoad, meters, true);
        }

        public static Navigation opened(String label) {
            // Keep the launch label for the existing display preview. It is not a road report.
            return label.isEmpty() ? EMPTY : new Navigation(label, "", -1, false);
        }
    }

    public record Snapshot(Media media, Navigation navigation, PlaybackProgress playbackProgress) {
        public Snapshot(Media media, Navigation navigation) { this(media, navigation, null); }

        public Snapshot {
            if (playbackProgress != null && (!media.available() || media.playing() != playbackProgress.advancing())) {
                playbackProgress = null;
            }
        }
    }

    private final CopyOnWriteArrayList<Consumer<Snapshot>> observers = new CopyOnWriteArrayList<>();
    private volatile Snapshot snapshot = new Snapshot(Media.EMPTY, Navigation.EMPTY);

    public Snapshot snapshot() { return snapshot; }

    public Runnable observe(Consumer<Snapshot> observer) {
        observers.add(observer);
        return () -> observers.remove(observer);
    }

    public void publishMedia(Media media) { publishMedia(media, null); }

    public void publishMedia(Media media, PlaybackProgress playbackProgress) {
        update(new Snapshot(media, snapshot.navigation(), playbackProgress));
    }

    public void publishNavigation(Navigation navigation) {
        Snapshot before = snapshot;
        update(new Snapshot(before.media(), navigation, before.playbackProgress()));
    }

    private void update(Snapshot next) {
        if (next.equals(snapshot)) return;
        snapshot = next;
        for (Consumer<Snapshot> observer : observers) observer.accept(next);
    }
}
