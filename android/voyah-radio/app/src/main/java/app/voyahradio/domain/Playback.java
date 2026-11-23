package app.voyahradio.domain;

public final class Playback {
    public enum Status {
        READY, CONNECTING, BUFFERING, RECONNECTING, LIVE, PAUSED, STREAM_ERROR, NETWORK_ERROR, FOCUS_ERROR
    }

    public final String stationId;
    public final Status status;
    public final Track track;

    public Playback(String stationId, Status status) {
        this(stationId, status, null);
    }

    public Playback(String stationId, Status status, Track track) {
        this.stationId = stationId;
        this.status = status;
        this.track = track;
    }

    public boolean active() {
        return status == Status.CONNECTING || status == Status.BUFFERING
                || status == Status.RECONNECTING || status == Status.LIVE;
    }

    public boolean failed() {
        return status == Status.STREAM_ERROR || status == Status.NETWORK_ERROR || status == Status.FOCUS_ERROR;
    }
}
