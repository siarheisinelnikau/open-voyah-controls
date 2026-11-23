package org.openvoyah.controls.core.updates;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ReleasePolicyTest {
    private static final String SHA = "a".repeat(64);

    private ReleasePolicy.Candidate candidate(String version) {
        String name = "OpenVoyahControls-v" + version + ".apk";
        return ReleasePolicy.candidate("android-v" + version, false, false, name,
            ReleasePolicy.DOWNLOAD_PREFIX + "android-v" + version + "/" + name, 123, "sha256:" + SHA);
    }

    @Test public void comparesNumericVersionsAndRejectsUnsupportedFormats() {
        assertTrue(ReleaseVersion.parse("0.10.0").compareTo(ReleaseVersion.parse("0.9.99")) > 0);
        assertTrue(ReleaseVersion.parse("2.0.0").compareTo(ReleaseVersion.parse("1.999.999")) > 0);
        assertEquals("0.18.0", ReleaseVersion.parse("0.18.0").toString());
        for (String value : new String[]{"", "0.18", "0.18.0.1", "v0.18.0", " 0.18.0", "0.18.0\n", "0.018.0",
            "0.18.0-rc.1", "0.18.0+build", "+0.18.0", "0.-1.0", "0.9223372036854775808.0"}) {
            assertNull(value, ReleaseVersion.parse(value));
        }
    }

    @Test public void usesOnlyExactStableCanonicalAssetsWithDigestAndBoundedSize() {
        ReleasePolicy.Candidate valid = candidate("0.19.0");
        assertNotNull(valid);
        assertEquals(SHA, valid.sha256);
        assertNull(ReleasePolicy.candidate(valid.tag, true, false, valid.name, valid.url, valid.bytes, "sha256:" + SHA));
        assertNull(ReleasePolicy.candidate(valid.tag, false, true, valid.name, valid.url, valid.bytes, "sha256:" + SHA));
        assertNull(ReleasePolicy.candidate(valid.tag, false, false, valid.name, valid.url, valid.bytes, null));
        assertNull(ReleasePolicy.candidate(valid.tag, false, false, valid.name, valid.url, valid.bytes, "sha512:" + SHA));
        assertNull(ReleasePolicy.candidate(valid.tag, false, false, valid.name, valid.url, 0, "sha256:" + SHA));
        assertNull(ReleasePolicy.candidate(valid.tag, false, false, valid.name, valid.url, ReleasePolicy.MAX_APK_BYTES + 1, "sha256:" + SHA));
        assertNull(ReleasePolicy.candidate(valid.tag, false, false, valid.name, valid.url + "?download=1", valid.bytes, "sha256:" + SHA));
        assertNull(ReleasePolicy.candidate(valid.tag, false, false, valid.name, valid.url.replace("https:", "http:"), valid.bytes, "sha256:" + SHA));
        assertNull(ReleasePolicy.candidate(valid.tag, false, false, valid.name, valid.url.replace("siarheisinelnikau", "other"), valid.bytes, "sha256:" + SHA));
        assertNull(ReleasePolicy.candidate("../v1", false, false, valid.name, valid.url, valid.bytes, "sha256:" + SHA));
        assertNull(candidate("0.19.0-rc.1"));
        assertNull(candidate("0.19.0-arm64"));
    }

    @Test public void selectsHighestNewerReleaseIndependentlyOfCatalogOrder() {
        ReleaseVersion installed = ReleaseVersion.parse("0.18.0");
        ReleasePolicy.Candidate result = null;
        for (String version : new String[]{"0.20.0", "0.17.0", "0.19.0", "0.18.0"}) {
            result = ReleasePolicy.newer(result, candidate(version), installed);
        }
        assertEquals("0.20.0", result.version.toString());
        assertNull(ReleasePolicy.newer(null, candidate("0.18.0"), installed));
    }
}
