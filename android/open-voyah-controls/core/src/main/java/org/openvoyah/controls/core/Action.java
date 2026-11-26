package org.openvoyah.controls.core;

import java.util.Objects;

/** Only user-selected, explicit actions; no arbitrary shell or intent URI execution. */
public final class Action {
    public enum Kind { NONE, LAUNCH_APP, SYSTEM_SETTINGS, CONTROLS_SETTINGS, START_VOICE, CONTROLS_APPS, RUN_ROUTINE }
    public static final Action NONE = new Action(Kind.NONE, "", "");
    public final Kind kind;
    public final String component;
    public final String label;

    public Action(Kind kind, String component, String label) {
        this.kind = Objects.requireNonNull(kind);
        this.component = component == null ? "" : component;
        this.label = label == null ? "" : label;
        if (kind == Kind.LAUNCH_APP && (this.component.indexOf('/') <= 0 || this.component.endsWith("/"))) {
            throw new IllegalArgumentException("Application component required");
        }
        if (kind == Kind.RUN_ROUTINE &&
            !this.component.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Saved routine ID required");
        }
    }
    public boolean isAssigned() { return kind != Kind.NONE; }
}
