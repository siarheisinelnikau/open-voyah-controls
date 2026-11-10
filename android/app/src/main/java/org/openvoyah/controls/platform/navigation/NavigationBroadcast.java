package org.openvoyah.controls.platform.navigation;

import android.content.Intent;
import org.openvoyah.controls.core.display.DisplayEventBus.Navigation;
import org.openvoyah.controls.core.display.DisplayEventBus;
import org.openvoyah.controls.platform.DisplayEventText;

/** Decodes events from the permission-protected launcher receiver. */
public final class NavigationBroadcast {
    public static final String ACTION = "org.openvoyah.controls.action.NAVIGATION_EVENT";

    private NavigationBroadcast() { }

    public static void publish(Intent intent, DisplayEventBus events) {
        if (!ACTION.equals(intent.getAction())) return;
        String event = intent.getStringExtra("event");
        if ("end".equals(event)) {
            events.publishNavigation(Navigation.EMPTY);
        } else if ("route".equals(event)) {
            int meters = intent.getIntExtra("meters", -1);
            events.publishNavigation(new Navigation(
                DisplayEventText.sanitize(intent.getStringExtra("road")),
                DisplayEventText.sanitize(intent.getStringExtra("nextRoad")),
                meters >= 0 && meters <= 1_000_000 ? meters : -1));
        } else if ("open".equals(event)) {
            events.publishNavigation(new Navigation(DisplayEventText.sanitize(intent.getStringExtra("app")), "", -1));
        }
    }
}
