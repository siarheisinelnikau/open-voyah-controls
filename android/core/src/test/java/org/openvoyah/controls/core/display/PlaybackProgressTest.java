package org.openvoyah.controls.core.display;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PlaybackProgressTest {
    @Test public void timelineUsesTheSampleClockAndSpeed() {
        var advancing = new PlaybackProgress(10_000, 1000, 3000, 1.5f);
        assertEquals(1000, advancing.positionAt(2000));
        assertEquals(2500, advancing.positionAt(4000));
        assertEquals(10_000, advancing.positionAt(Long.MAX_VALUE));
        assertEquals(1000, new PlaybackProgress(10_000, 1000, 3000, 0f).positionAt(4000));
    }

    @Test public void unavailableSamplesNeverCreateAPreviewTimeline() {
        assertNull(PlaybackProgress.fromSample(0, 0, 3000, 4000, 1f));
        assertNull(PlaybackProgress.fromSample(10_000, -1, 3000, 4000, 1f));
        assertNull(PlaybackProgress.fromSample(10_000, 10_001, 3000, 4000, 1f));
        assertNull(PlaybackProgress.fromSample(10_000, 1000, 0, 4000, 1f));
        assertNull(PlaybackProgress.fromSample(10_000, 1000, 4001, 4000, 1f));
        assertNull(PlaybackProgress.fromSample(10_000, 1000, 3000, 4000, -1f));
        assertNull(PlaybackProgress.fromSample(10_000, 1000, 3000, 4000, Float.NaN));
        assertNull(PlaybackProgress.fromSample(10_000, 1000, 3000, 4000, Float.POSITIVE_INFINITY));
    }

    @Test public void trackChangesAndSeeksKeepSamplesInOrder() {
        var tracker = new PlaybackProgressTracker();
        var previousTrack = new PlaybackProgress(10_000, 8000, 3000, 1f);
        tracker.trackChanged(10_000);
        tracker.accept(previousTrack);
        assertEquals(previousTrack, tracker.current());

        tracker.trackChanged(10_000);
        tracker.accept(previousTrack);
        tracker.accept(new PlaybackProgress(20_000, 1000, 4000, 1f));
        assertNull(tracker.current());

        var currentTrack = new PlaybackProgress(10_000, 1000, 4000, 1f);
        tracker.accept(currentTrack);
        tracker.accept(new PlaybackProgress(10_000, 2000, 3500, 1f));
        assertEquals(currentTrack, tracker.current());

        var seek = new PlaybackProgress(10_000, 500, 5000, 1f);
        tracker.accept(seek);
        assertEquals(seek, tracker.current());
        tracker.clear();
        tracker.accept(currentTrack);
        assertNull(tracker.current());
    }

    @Test public void displayEventsKeepMediaAndProgressPaired() {
        var events = new DisplayEventBus();
        var observed = new ArrayList<DisplayEventBus.Snapshot>();
        events.observe(observed::add);
        var track = new DisplayEventBus.Media("Track", "", "", true);
        var nextTrack = new DisplayEventBus.Media("Next track", "", "", true);
        var route = new DisplayEventBus.Navigation("Road", "", -1);
        var progress = new PlaybackProgress(10_000, 1000, 3000, 1f);
        events.publishMedia(track, progress);
        events.publishNavigation(route);
        events.publishMedia(nextTrack);

        assertEquals(List.of(
            new DisplayEventBus.Snapshot(track, DisplayEventBus.Navigation.EMPTY, progress),
            new DisplayEventBus.Snapshot(track, route, progress),
            new DisplayEventBus.Snapshot(nextTrack, route)
        ), observed);
        assertNull(new DisplayEventBus.Snapshot(DisplayEventBus.Media.EMPTY, route, progress).playbackProgress());
        assertNull(new DisplayEventBus.Snapshot(track, route, new PlaybackProgress(10_000, 1000, 3000, 0f))
            .playbackProgress());
    }
}
