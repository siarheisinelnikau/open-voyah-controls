package org.openvoyah.controls.core.updates;

import java.util.Objects;

/** The stable numeric release format shared by official Android and Windows assets. */
public final class ReleaseVersion implements Comparable<ReleaseVersion> {
    public final long major;
    public final long minor;
    public final long patch;

    private ReleaseVersion(long major, long minor, long patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    public static ReleaseVersion parse(String value) {
        if (value == null || value.length() > 59) return null;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 3) return null;
        long[] numbers = new long[3];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 19 || (part.length() > 1 && part.charAt(0) == '0')) return null;
            for (int c = 0; c < part.length(); c++) {
                if (part.charAt(c) < '0' || part.charAt(c) > '9') return null;
            }
            try { numbers[i] = Long.parseLong(part); }
            catch (NumberFormatException ignored) { return null; }
        }
        return new ReleaseVersion(numbers[0], numbers[1], numbers[2]);
    }

    @Override public int compareTo(ReleaseVersion other) {
        int result = Long.compare(major, other.major);
        if (result == 0) result = Long.compare(minor, other.minor);
        if (result == 0) result = Long.compare(patch, other.patch);
        return result;
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof ReleaseVersion)) return false;
        ReleaseVersion version = (ReleaseVersion) other;
        return major == version.major && minor == version.minor && patch == version.patch;
    }

    @Override public int hashCode() { return Objects.hash(major, minor, patch); }

    @Override public String toString() { return major + "." + minor + "." + patch; }
}
