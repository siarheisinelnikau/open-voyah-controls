package org.openvoyah.controls.data.frida

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonServiceGrantTest {
    private val pkg = "org.openvoyah.controls"
    private val service = "org.openvoyah.controls.ButtonAccessibilityService"

    @Test fun theScriptRecognizesBothFormsAndKeepsOtherServices() {
        val script = ButtonServiceGrant.script(pkg, service, explicit = false)
        assertTrue(script.contains("ovc_component='org.openvoyah.controls/org.openvoyah.controls.ButtonAccessibilityService'"))
        assertTrue(script.contains("ovc_short='org.openvoyah.controls/.ButtonAccessibilityService'"))
        assertTrue(script.contains("*) ovc_others=\"\${ovc_others:+\$ovc_others:}\$ovc_service\";;"))
        assertTrue(script.contains("settings put secure enabled_accessibility_services \"\${ovc_others:+\$ovc_others:}\$ovc_component\""))
        assertTrue(script.contains("settings put secure accessibility_enabled 1"))
    }

    @Test fun aBootRestoreNeedsSetupsMarkerAndLeavesAListedServiceAlone() {
        val script = ButtonServiceGrant.script(pkg, service, explicit = false)
        assertTrue(script.contains("test \"\$ovc_listed\" = 0 || { echo 'OVC_BUTTON_SERVICE present'; exit 0; }"))
        assertTrue(script.contains("test -f \"\$ovc_marker\" || { echo 'OVC_BUTTON_SERVICE not_granted'; exit 0; }"))
        assertFalse(script.contains("touch"))
        assertFalse(script.contains("sleep"))
    }

    @Test fun anExplicitRequestRecordsTheMarkerOnlyForAServiceItAdds() {
        val script = ButtonServiceGrant.script(pkg, service, explicit = true)
        assertTrue(script.contains("elif [ ! -f \"\$ovc_marker\" ]; then"))
        assertTrue(script.contains("touch \"\$ovc_marker\" && chmod 600 \"\$ovc_marker\""))
        // A listed service is removed and added again so Android binds it anew.
        assertTrue(script.contains("settings put secure enabled_accessibility_services \"\$ovc_others\" || exit 1\n    sleep 1"))
        assertFalse(script.contains("not_granted"))
    }

    @Test fun theAccessDirectoryAndMarkerMustNotBeLinks() {
        val script = ButtonServiceGrant.script(pkg, service, explicit = true)
        assertTrue(script.contains("ovc_access='/data/adb/open-voyah-controls-access'"))
        assertTrue(script.contains("test \"\$(stat -c '%u:%a' \"\$ovc_access\")\" = 0:700 || exit 77"))
        assertTrue(script.contains("test ! -L \"\$ovc_marker\" || exit 77"))
    }

    @Test fun aClassOutsideThePackageUsesTheFullFormTwice() {
        val script = ButtonServiceGrant.script("com.example.debug", service, explicit = false)
        assertTrue(script.contains("ovc_short='com.example.debug/org.openvoyah.controls.ButtonAccessibilityService'"))
    }

    @Test fun namesThatCouldEscapeTheShellAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { ButtonServiceGrant.script("org.openvoyah'x", service, false) }
        assertThrows(IllegalArgumentException::class.java) { ButtonServiceGrant.script(pkg, "Service;reboot", false) }
    }

    @Test fun theLastReportedResultWins() {
        assertEquals(ButtonServiceGrant.Outcome.RESTORED, ButtonServiceGrant.outcome("noise\nOVC_BUTTON_SERVICE restored\n"))
        assertEquals(ButtonServiceGrant.Outcome.REBOUND, ButtonServiceGrant.outcome("OVC_BUTTON_SERVICE rebound\r\n"))
        assertEquals(ButtonServiceGrant.Outcome.PRESENT, ButtonServiceGrant.outcome("OVC_BUTTON_SERVICE present"))
        assertEquals(ButtonServiceGrant.Outcome.NOT_GRANTED, ButtonServiceGrant.outcome("OVC_BUTTON_SERVICE not_granted"))
    }

    @Test fun aMissingOrUnknownResultIsAFailure() {
        assertThrows(IOException::class.java) { ButtonServiceGrant.outcome("") }
        assertThrows(IOException::class.java) { ButtonServiceGrant.outcome("OVC_BUTTON_SERVICE maybe") }
    }
}
