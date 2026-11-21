package org.openvoyah.controls.core;

import java.util.function.Consumer;

public interface PanelControls {
    /** BUTTON_SERVICE enables the hardware-button accessibility service through the same root channel. */
    enum Operation { CONNECT, STATUS, DISCONNECT, BUTTON_SERVICE }
    record Result(boolean successful, String message) { }

    String health();
    boolean configured();
    void run(Operation operation, Consumer<Result> done);
    void publish(Consumer<String> error);
}
