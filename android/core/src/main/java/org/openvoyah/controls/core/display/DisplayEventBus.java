package org.openvoyah.controls.core.display;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Local, typed display events. Publishers and observers use the same event thread. */
public final class DisplayEventBus {
    public record Media(String title, String artist, String album, boolean playing) {
        public static final Media EMPTY = new Media("", "", "", false);
        public boolean available() { return !title.isEmpty(); }
    }

    public record Navigation(String road, String nextRoad, int meters) {
        public static final Navigation EMPTY = new Navigation("", "", -1);
    }

    public record Snapshot(Media media, Navigation navigation) { }

    private final CopyOnWriteArrayList<Consumer<Snapshot>> observers = new CopyOnWriteArrayList<>();
    private volatile Snapshot snapshot = new Snapshot(Media.EMPTY, Navigation.EMPTY);

    public Snapshot snapshot() { return snapshot; }

    public Runnable observe(Consumer<Snapshot> observer) {
        observers.add(observer);
        return () -> observers.remove(observer);
    }

    public void publishMedia(Media media) { update(new Snapshot(media, snapshot.navigation())); }
    public void publishNavigation(Navigation navigation) { update(new Snapshot(snapshot.media(), navigation)); }

    private void update(Snapshot next) {
        if (next.equals(snapshot)) return;
        snapshot = next;
        for (Consumer<Snapshot> observer : observers) observer.accept(next);
    }
}
