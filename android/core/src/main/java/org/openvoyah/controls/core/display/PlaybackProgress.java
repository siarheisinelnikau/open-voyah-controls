package org.openvoyah.controls.core.display;

/** A position sample and effective speed from a single track on the elapsed-realtime clock. */
public record PlaybackProgress(long durationMillis, long positionMillis, long sampledAtElapsedMillis, float speed) {
    public PlaybackProgress {
        if (!valid(durationMillis, positionMillis, sampledAtElapsedMillis, speed)) {
            throw new IllegalArgumentException("Invalid playback progress");
        }
    }

    public static PlaybackProgress fromSample(long duration, long position, long sampledAt, long now, float speed) {
        return sampledAt <= now && valid(duration, position, sampledAt, speed)
            ? new PlaybackProgress(duration, position, sampledAt, speed) : null;
    }

    private static boolean valid(long duration, long position, long sampledAt, float speed) {
        return duration > 0 && position >= 0 && position <= duration && sampledAt > 0
            && Float.isFinite(speed) && speed >= 0;
    }

    public boolean advancing() { return speed > 0; }

    public long positionAt(long elapsedRealtimeMillis) {
        if (!advancing() || elapsedRealtimeMillis <= sampledAtElapsedMillis) return positionMillis;
        double advanced = (elapsedRealtimeMillis - sampledAtElapsedMillis) * (double) speed;
        return advanced >= durationMillis - positionMillis ? durationMillis : positionMillis + (long) advanced;
    }
}
