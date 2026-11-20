package org.openvoyah.controls.core.display;

/** Keeps position observations in order within a media session. */
public final class PlaybackProgressTracker {
    private long durationMillis;
    private long newestSample;
    private long previousTrackSample;
    private PlaybackProgress progress;

    public void trackChanged(long durationMillis) {
        this.durationMillis = durationMillis;
        previousTrackSample = newestSample;
        clear();
    }

    public void accept(PlaybackProgress sample) {
        if (sample.durationMillis() != durationMillis || sample.sampledAtElapsedMillis() <= previousTrackSample
                || sample.sampledAtElapsedMillis() < newestSample) return;
        newestSample = sample.sampledAtElapsedMillis();
        progress = sample;
    }

    public PlaybackProgress current() { return progress; }

    public void clear() { progress = null; }
}
