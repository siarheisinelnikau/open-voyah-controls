package org.openvoyah.controls.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Maps Android product identifiers to vehicle profiles. */
public final class Firmware {
    private Firmware() { }
    public static final Map<String, String> PRODUCTS;
    static {
        Map<String, String> products = new LinkedHashMap<>();
        products.put("f0501h", "NXP");
        products.put("f0506h", "FREE");
        products.put("f6802h", "FREE_REST");
        products.put("f6805h", "FREE_REST_BY");
        products.put("f6811", "FREE_REST_OVERSEA");
        products.put("f6801f", "FREE_NORWAY");
        products.put("f0505h", "DREAMER");
        products.put("f6804h", "DREAMER_REST");
        products.put("f6803f", "DREAMER_REST_MI");
        products.put("f6806", "DREAMER_REST_MI_V2");
        PRODUCTS = Collections.unmodifiableMap(products);
    }
    public static String identify(String product) { return PRODUCTS.getOrDefault(product, "UNKNOWN"); }
    public static boolean guestAccounts(String product) {
        return switch (identify(product)) {
            case "FREE", "FREE_REST", "FREE_REST_BY", "DREAMER" -> true;
            default -> false;
        };
    }
}
