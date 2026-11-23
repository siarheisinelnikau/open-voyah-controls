package app.voyahradio.domain;

public final class Playback {
    public enum Status { READY, CONNECTING, BUFFERING, RECONNECTING, LIVE, PAUSED, STREAM_ERROR, FOCUS_ERROR }

    public final String stationId;
    public final Status status;

    public Playback(String stationId, Status status) {
        this.stationId = stationId;
        this.status = status;
    }

    public boolean active() {
        return status == Status.CONNECTING || status == Status.BUFFERING
                || status == Status.RECONNECTING || status == Status.LIVE;
    }

    public boolean failed() {
        return status == Status.STREAM_ERROR || status == Status.FOCUS_ERROR;
    }
}
