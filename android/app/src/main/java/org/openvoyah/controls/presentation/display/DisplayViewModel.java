package org.openvoyah.controls.presentation.display;

import java.util.function.Consumer;
import org.openvoyah.controls.core.display.DisplayEventBus;
import org.openvoyah.controls.core.display.DisplaySettings;
import org.openvoyah.controls.core.display.NavigationFeed;

/** Keeps Android surfaces and preference storage out of display state. */
public final class DisplayViewModel {
    private final DisplayEventBus events;
    private final DisplaySettings settings;
    private final NavigationFeed navigation;
    private boolean changingNavigation;

    public DisplayViewModel(DisplayEventBus events, DisplaySettings settings, NavigationFeed navigation) {
        this.events = events;
        this.settings = settings;
        this.navigation = navigation;
    }

    public DisplayEventBus.Snapshot state() { return events.snapshot(); }
    public Runnable observe(Consumer<DisplayEventBus.Snapshot> observer) { return events.observe(observer); }
    public int density() { return settings.density(); }
    public void density(int value) { settings.density(value); }
    public boolean navigationEnabled() { return navigation.enabled(); }
    public boolean changingNavigation() { return changingNavigation; }
    public void navigationEnabled(boolean enabled, Consumer<String> done) {
        if (changingNavigation) return;
        changingNavigation = true;
        navigation.setEnabled(enabled, message -> {
            changingNavigation = false;
            if (!navigation.enabled()) events.publishNavigation(DisplayEventBus.Navigation.EMPTY);
            done.accept(message);
        });
    }

    public boolean clusterMedia() { return settings.clusterMedia(); }
    public void clusterMedia(boolean enabled) { settings.clusterMedia(enabled); }
}
