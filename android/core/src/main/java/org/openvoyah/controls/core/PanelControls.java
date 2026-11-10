package org.openvoyah.controls.core;

import java.util.function.Consumer;

public interface PanelControls {
    enum Operation { CONNECT, STATUS, DISCONNECT }
    record Result(boolean successful, String message) { }

    String health();
    boolean configured();
    void run(Operation operation, Consumer<Result> done);
    void publish(Consumer<String> error);
}
