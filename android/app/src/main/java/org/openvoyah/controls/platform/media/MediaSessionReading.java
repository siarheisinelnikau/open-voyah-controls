package org.openvoyah.controls.platform.media;

import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import java.util.Objects;
import org.openvoyah.controls.core.display.DisplayEventBus.Media;
import org.openvoyah.controls.core.display.PlaybackProgress;
import org.openvoyah.controls.core.display.PlaybackProgressTracker;
import org.openvoyah.controls.platform.DisplayEventText;

/** Translates a single session's metadata and playback state for display consumers. */
final class MediaSessionReading {
    record Track(String mediaId, String title, String artist, String album, long itemId, long duration) { }
    record Snapshot(Media media, Track track, PlaybackProgress progress) { }

    private Track track;
    private final PlaybackProgressTracker progress = new PlaybackProgressTracker();

    Snapshot read(MediaMetadata metadata, PlaybackState playback, PlaybackState observation) {
        Media media = media(metadata, playback);
        Track current = track(media, metadata, playback);
        if (!Objects.equals(track, current)) {
            track = current;
            progress.trackChanged(track == null ? 0 : track.duration());
        }
        return new Snapshot(media, current, position(playback, observation));
    }

    void clearPlayback() { progress.clear(); }

    private PlaybackProgress position(PlaybackState current, PlaybackState observation) {
        float speed = speed(current);
        if (track == null || !Float.isFinite(speed)) {
            progress.clear();
            return null;
        }

        // A controller getter can extrapolate its timestamp. After a track change, rebind only
        // to an actual playback callback that agrees with the current session state.
        if (observation != null && observation.getActiveQueueItemId() == track.itemId()) {
            PlaybackProgress sample = PlaybackProgress.fromSample(track.duration(), observation.getPosition(),
                observation.getLastPositionUpdateTime(), SystemClock.elapsedRealtime(), speed(observation));
            if (sample != null && matchesPosition(sample, current, speed)) progress.accept(sample);
        }
        PlaybackProgress accepted = progress.current();
        if (accepted != null && !matchesPosition(accepted, current, speed)) {
            progress.clear();
            return null;
        }
        return accepted;
    }

    static Media media(MediaMetadata metadata, PlaybackState playback) {
        return metadata == null ? Media.EMPTY : new Media(
            DisplayEventText.sanitize(metadata.getText(MediaMetadata.METADATA_KEY_TITLE)),
            DisplayEventText.sanitize(metadata.getText(MediaMetadata.METADATA_KEY_ARTIST)),
            DisplayEventText.sanitize(metadata.getText(MediaMetadata.METADATA_KEY_ALBUM)),
            isPlaying(playback));
    }

    static Track track(Media media, MediaMetadata metadata, PlaybackState playback) {
        return metadata == null || !media.available() ? null : new Track(
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID), media.title(), media.artist(), media.album(),
            playback == null ? MediaSession.QueueItem.UNKNOWN_ID : playback.getActiveQueueItemId(),
            metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
    }

    static boolean isPlaying(PlaybackState state) {
        return state != null && state.getState() == PlaybackState.STATE_PLAYING;
    }

    private static boolean matchesPosition(PlaybackProgress progress, PlaybackState state, float speed) {
        long timestamp = state.getLastPositionUpdateTime();
        long currentPosition = state.getPosition();
        return progress.speed() == speed && timestamp >= progress.sampledAtElapsedMillis()
            && currentPosition >= 0 && Math.abs(currentPosition - progress.positionAt(timestamp)) <= 1;
    }

    private static float speed(PlaybackState state) {
        if (state == null) return Float.NaN;
        float speed = state.getPlaybackSpeed();
        if (!Float.isFinite(speed) || speed < 0) return Float.NaN;
        if (state.getState() == PlaybackState.STATE_PAUSED) return 0;
        return isPlaying(state) && speed > 0 ? speed : Float.NaN;
    }
}
