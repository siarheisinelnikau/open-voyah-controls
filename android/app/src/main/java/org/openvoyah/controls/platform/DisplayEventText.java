package org.openvoyah.controls.platform;

public final class DisplayEventText {
    private DisplayEventText() { }

    public static String sanitize(CharSequence value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder();
        value.toString().codePoints().filter(ch -> !Character.isISOControl(ch)).limit(120)
            .forEach(result::appendCodePoint);
        return result.toString().trim();
    }
}
