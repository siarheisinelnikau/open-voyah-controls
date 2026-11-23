package org.openvoyah.controls.platform.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstallStatusCodeTest {
    @Test fun onlyTheLeadingCodeOfAStatusMessageIsKept() {
        assertEquals("INSTALL_FAILED_VERSION_DOWNGRADE", installStatusCode("INSTALL_FAILED_VERSION_DOWNGRADE"))
        assertEquals("INSTALL_PARSE_FAILED_NO_CERTIFICATES", installStatusCode(
            "INSTALL_PARSE_FAILED_NO_CERTIFICATES: Failed collecting certificates for /data/app/vmdl42.tmp/base.apk"))
        assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE", installStatusCode(
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package org.example.private signatures do not match"))
    }

    @Test fun textWithoutACodeYieldsNothing() {
        assertNull(installStatusCode(null))
        assertNull(installStatusCode(""))
        assertNull(installStatusCode("Package org.example.private is already installed"))
        assertNull(installStatusCode("See INSTALL_FAILED_INVALID_APK in /sdcard/Download/private.apk"))
        assertNull(installStatusCode("install_failed_lowercase"))
        // A code is capitals and underscores only; anything glued to it is not one.
        assertNull(installStatusCode("INSTALL_FAILED_x/data/app/private"))
        assertNull(installStatusCode("INSTALL_" + "A".repeat(80)))
    }
}
