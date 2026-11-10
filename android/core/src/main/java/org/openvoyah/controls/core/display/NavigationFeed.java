package org.openvoyah.controls.core.display;

import java.util.function.Consumer;

public interface NavigationFeed {
    boolean enabled();
    void setEnabled(boolean enabled, Consumer<String> done);
}
