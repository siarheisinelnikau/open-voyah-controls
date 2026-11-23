package org.openvoyah.controls.core.updates;

import java.util.Locale;

/** Mirrors installer/src/releases.rs; no alternate catalogs or unverified asset aliases. */
public final class ReleasePolicy {
    public static final String CATALOG_URL = "https://api.github.com/repos/siarheisinelnikau/open-voyah-controls/releases?per_page=30";
    public static final String DOWNLOAD_PREFIX = "https://github.com/siarheisinelnikau/open-voyah-controls/releases/download/";
    public static final long MAX_APK_BYTES = 512L * 1024 * 1024;
    private static final String ASSET_PREFIX = "OpenVoyahControls-v";

    private ReleasePolicy() { }

    public static final class Candidate {
        public final ReleaseVersion version;
        public final String tag;
        public final String name;
        public final String url;
        public final long bytes;
        public final String sha256;

        private Candidate(ReleaseVersion version, String tag, String name, String url, long bytes, String sha256) {
            this.version = version;
            this.tag = tag;
            this.name = name;
            this.url = url;
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }

    public static Candidate candidate(String tag, boolean draft, boolean prerelease, String name,
                                      String url, long bytes, String digest) {
        if (draft || prerelease || tag == null || tag.isEmpty() || tag.length() > 128 || name == null ||
            !name.startsWith(ASSET_PREFIX) || !name.endsWith(".apk") || bytes <= 0 || bytes > MAX_APK_BYTES ||
            digest == null || !digest.startsWith("sha256:") || !isSha256(digest.substring(7))) return null;
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
                  c == '.' || c == '-' || c == '_')) return null;
        }
        ReleaseVersion version = ReleaseVersion.parse(name.substring(ASSET_PREFIX.length(), name.length() - 4));
        if (version == null || !((DOWNLOAD_PREFIX + tag + "/" + name).equals(url))) return null;
        return new Candidate(version, tag, name, url, bytes, digest.substring(7).toLowerCase(Locale.ROOT));
    }

    public static Candidate newer(Candidate current, Candidate proposed, ReleaseVersion installed) {
        if (proposed == null || installed == null || proposed.version.compareTo(installed) <= 0) return current;
        return current == null || proposed.version.compareTo(current.version) > 0 ? proposed : current;
    }

    public static boolean isSha256(String value) {
        if (value == null || value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }
}
