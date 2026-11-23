package org.openvoyah.controls.core;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class PanelSlotsTest {
    @Test public void standardLayoutOffersExplicitCenterHomeAndAppAssignments() {
        var expected = Arrays.asList("navbar01", "navbar02", "mainHome", "mainApps",
            "navbar11", "navbar12", "navbar13", "navbar14", "navbar15");
        for (String product : new String[]{"f0506h", "f6802h", "f6805h", "f0505h", "unknown"}) {
            assertEquals(expected, PanelSlots.forProduct(product));
        }
    }

    @Test public void overseasLayoutKeepsItsSeparatePassengerPositions() {
        assertEquals(Arrays.asList("navbar01", "navbar02", "mainHome", "mainApps",
            "navbar11", "navbar12", "passengerApps"), PanelSlots.forProduct("f6811"));
        assertFalse(PanelSlots.forProduct("f6811").contains("navbar13"));
        assertFalse(PanelSlots.forProduct("f0506h").contains("passengerApps"));
        assertTrue(PanelSlots.PANEL.containsAll(PanelSlots.forProduct("f0506h")));
        assertTrue(PanelSlots.PANEL.containsAll(PanelSlots.forProduct("f6811")));
    }
}
