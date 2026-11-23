package app.voyahradio.playback;

import app.voyahradio.R;
import app.voyahradio.domain.Playback;

public final class PlaybackText {
    private PlaybackText() {}

    public static int label(Playback.Status status) {
        return switch (status) {
            case READY -> R.string.ready;
            case CONNECTING -> R.string.connecting;
            case BUFFERING -> R.string.buffering;
            case RECONNECTING -> R.string.reconnecting;
            case LIVE -> R.string.live;
            case PAUSED -> R.string.paused;
            case STOPPED -> R.string.stopped;
            case STREAM_ERROR -> R.string.stream_error;
            case NETWORK_ERROR -> R.string.network_error;
            case FOCUS_ERROR -> R.string.focus_error;
        };
    }
}
