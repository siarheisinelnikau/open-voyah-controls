package org.openvoyah.controls.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class PanelSlots {
    public static final List<String> PANEL = Collections.unmodifiableList(Arrays.asList(
        "navbar01", "navbar02", "navbar11", "navbar12", "navbar13", "navbar14", "navbar15"));
    public static final List<String> STEERING = Collections.unmodifiableList(Arrays.asList(
        "voiceShort", "voiceLong", "customShort", "customLong", "cameraShort", "cameraLong"));

    private PanelSlots() { }
}
