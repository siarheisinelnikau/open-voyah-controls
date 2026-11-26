package org.openvoyah.controls.platform.navigation;

import android.content.Intent;
import org.openvoyah.controls.core.display.DisplayEventBus.Navigation;
import org.openvoyah.controls.core.display.DisplayEventBus;
import org.openvoyah.controls.platform.DisplayEventText;

/** Decodes events from the permission-protected launcher receiver. */
public final class NavigationBroadcast {
    public static final String ACTION = "org.openvoyah.controls.action.NAVIGATION_EVENT";

    private NavigationBroadcast() { }

    /** Returns the decoded observation for the local driver card, or null for an unrelated intent. */
    public static Navigation publish(Intent intent, DisplayEventBus events) {
        if (!ACTION.equals(intent.getAction())) return null;
        String event = intent.getStringExtra("event");
        Navigation navigation;
        if ("end".equals(event)) {
            navigation = Navigation.EMPTY;
        } else if ("route".equals(event)) {
            int meters = intent.getIntExtra("meters", -1);
            navigation = new Navigation(
                DisplayEventText.sanitize(intent.getStringExtra("road")),
                DisplayEventText.sanitize(intent.getStringExtra("nextRoad")),
                meters >= 0 && meters <= 1_000_000 ? meters : -1);
        } else if ("open".equals(event)) {
            navigation = Navigation.opened(DisplayEventText.sanitize(intent.getStringExtra("app")));
        } else return null;
        if (intent.getIntExtra("driverOnly", 0) != 1) events.publishNavigation(navigation);
        return navigation;
    }
}
