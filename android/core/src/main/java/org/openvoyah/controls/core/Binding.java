package org.openvoyah.controls.core;

import java.util.Objects;

public final class Binding {
    public final String id;
    public final String label;
    /** Stable InputDevice descriptor; empty string matches any input device. */
    public final String device;
    public final int keyCode;
    public final Action shortPress;
    public final Action longPress;
    public final boolean enabled;

    public Binding(String id, String label, String device, int keyCode,
                   Action shortPress, Action longPress, boolean enabled) {
        this.id = Objects.requireNonNull(id);
        this.label = Objects.requireNonNull(label);
        this.device = Objects.requireNonNull(device);
        if (id.isEmpty() || keyCode <= 0 || keyCode > 65535) throw new IllegalArgumentException("Invalid binding");
        this.keyCode = keyCode;
        this.shortPress = Objects.requireNonNull(shortPress);
        this.longPress = Objects.requireNonNull(longPress);
        this.enabled = enabled;
    }
    public boolean matches(String descriptor, int code) {
        return enabled && keyCode == code && (device.isEmpty() || device.equals(descriptor));
    }
    public boolean isAssigned() { return shortPress.isAssigned() || longPress.isAssigned(); }
}
