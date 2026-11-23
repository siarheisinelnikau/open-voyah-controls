package app.voyahradio.domain;

public enum Availability {
    AVAILABLE, UNAVAILABLE, UNKNOWN;

    public Availability merge(Availability other) {
        if (this == AVAILABLE || other == AVAILABLE) return AVAILABLE;
        if (this == UNKNOWN || other == UNKNOWN) return UNKNOWN;
        return UNAVAILABLE;
    }

    public static boolean isDefinitiveHttpFailure(int status) {
        return status == 404 || status == 410 || status == 451;
    }
}
