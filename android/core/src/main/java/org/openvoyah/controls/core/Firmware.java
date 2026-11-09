package org.openvoyah.controls.core;

import java.util.Map;
import java.util.Set;

/** Maps Android product identifiers to vehicle profiles. */
public final class Firmware {
    private Firmware() { }
    public static final Map<String, String> PRODUCTS = Map.of(
        "f0501h", "NXP", "f0506h", "FREE", "f6802h", "FREE_REST",
        "f6805h", "FREE_REST_BY", "f6811", "FREE_REST_OVERSEA", "f6801f", "FREE_NORWAY",
        "f0505h", "DREAMER", "f6804h", "DREAMER_REST", "f6803f", "DREAMER_REST_MI",
        "f6806", "DREAMER_REST_MI_V2");
    public static String identify(String product) { return PRODUCTS.getOrDefault(product, "UNKNOWN"); }
    public static boolean guestAccounts(String product) {
        return Set.of("FREE", "FREE_REST", "FREE_REST_BY", "DREAMER").contains(identify(product));
    }
}
