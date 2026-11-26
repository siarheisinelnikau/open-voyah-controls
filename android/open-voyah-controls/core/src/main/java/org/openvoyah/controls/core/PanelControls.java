package org.openvoyah.controls.core;

import java.util.function.Consumer;

public interface PanelControls {
    /**
     * BUTTON_SERVICE enables the hardware-button accessibility service through the same root channel.
     * NAVIGATOR_WIDGET opens Yandex Navigator's own settings for the widget in the home screen card.
     */
    enum Operation { CONNECT, STATUS, DISCONNECT, BUTTON_SERVICE, NAVIGATOR_WIDGET, OPEN_DRIVER_NAVIGATOR, CLOSE_DRIVER_NAVIGATOR }
    record Result(boolean successful, String message) { }

    String health();
    boolean configured();
    void run(Operation operation, Consumer<Result> done);
    void publish(Consumer<String> error);

    /** Whether the boot supervisor paused the activation (it re-arms itself after the next restart), as last checked. */
    default boolean activationPaused() { return false; }

    /** Checks the pause again off the main thread and reports the answer on the main thread; nothing without root. */
    default void checkActivation(Consumer<Boolean> done) { }
}
