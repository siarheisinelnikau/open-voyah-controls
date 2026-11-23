package org.openvoyah.controls.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class PanelSlots {
    public static final List<String> PANEL = Collections.unmodifiableList(Arrays.asList(
        "navbar01", "navbar02", "navbar11", "navbar12", "navbar13", "navbar14", "navbar15",
        "mainHome", "mainApps", "passengerApps"));
    public static final List<String> STEERING = Collections.unmodifiableList(Arrays.asList(
        "voiceShort", "voiceLong", "customShort", "customLong", "cameraShort", "cameraLong"));
    private static final List<String> STANDARD = Collections.unmodifiableList(Arrays.asList(
        "navbar01", "navbar02", "mainHome", "mainApps", "navbar11", "navbar12", "navbar13", "navbar14", "navbar15"));
    private static final List<String> OVERSEA = Collections.unmodifiableList(Arrays.asList(
        "navbar01", "navbar02", "mainHome", "mainApps", "navbar11", "navbar12", "passengerApps"));

    private PanelSlots() { }

    public static List<String> forProduct(String product) {
        return "f6811".equals(product) ? OVERSEA : STANDARD;
    }
}
