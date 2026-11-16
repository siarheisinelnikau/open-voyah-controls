package org.openvoyah.controls.data.frida

import android.test.AndroidTestCase
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText

class ReviewedFirmwareTest : AndroidTestCase() {
    private val device = ReviewedFirmware.Device("sample/bench/device:11/A/1:user/release-keys", "30", "arm64-v8a", "bench")
    private val identity = listOf(device.fingerprint, device.sdk, device.abi, device.product).joinToString("\t")
    private val launcher = "$identity\t${ReviewedFirmware.LAUNCHER}\t${"a".repeat(64)}\t${"b".repeat(64)}"

    fun testRequiresExactReviewedDeviceAndAnExplicitOperation() {
        assertNull(ReviewedFirmware.select("# No entries", "", device))
        assertNull(ReviewedFirmware.select(launcher, "", device.copy(sdk = "28")))
        assertNull(ReviewedFirmware.select(launcher, "", device.copy(abi = "x86_64")))
        assertNull(ReviewedFirmware.select(launcher, "", device.copy(product = "other")))
        assertNull(ReviewedFirmware.select(launcher, "", device.copy(fingerprint = "sample/other")))
        val baseline = requireNotNull(ReviewedFirmware.select(launcher, "", device))
        assertTrue(baseline.operations.isEmpty())
        val reviewed = requireNotNull(ReviewedFirmware.select(launcher, "$identity\twindow-service", device))
        assertEquals(setOf("window-service"), reviewed.operations)
        val passenger = "$identity\tpassenger-window-dpi"
        assertEquals(setOf("passenger-window-dpi"), requireNotNull(ReviewedFirmware.select(launcher, passenger, device)).operations)
        reject { ReviewedFirmware.select(launcher, "$passenger\n$passenger", device) }
    }

    fun testRejectsDuplicateMalformedOrUnanchoredReview() {
        reject { ReviewedFirmware.select("$launcher\n$launcher", "", device) }
        reject { ReviewedFirmware.select(launcher.dropLast(1), "", device) }
        reject { ReviewedFirmware.select(launcher, "$identity\tunknown-operation", device) }
        reject { ReviewedFirmware.select("", "$identity\toem-profiles", device) }
        reject { ReviewedFirmware.select(launcher.replace(ReviewedFirmware.LAUNCHER, ReviewedFirmware.CLUSTER), "", device) }
    }

    fun testCanSteeringRequiresItsOwnExactServicePackage() {
        val operation = "$identity\t${ReviewedFirmware.CAN_STEERING}"
        reject { ReviewedFirmware.select(launcher, operation, device) }
        val can = launcher.replace(ReviewedFirmware.LAUNCHER, ReviewedFirmware.CAN_SERVICE)
        val reviewed = requireNotNull(ReviewedFirmware.select("$launcher\n$can", operation, device))
        assertEquals(setOf(ReviewedFirmware.CAN_STEERING), reviewed.operations)
        assertEquals(2, reviewed.packages.size)
        val edges = "$identity\t${ReviewedFirmware.CAN_STEERING_EDGES}"
        reject { ReviewedFirmware.select(launcher, edges, device) }
        assertEquals(setOf(ReviewedFirmware.CAN_STEERING_EDGES), requireNotNull(ReviewedFirmware.select("$launcher\n$can", edges, device)).operations)
        reject { ReviewedFirmware.select("$launcher\n$can", "$operation\n$edges", device) }
    }

    fun testAnUnreviewedBuildRefusesPrivilegedExecutionBeforeBrokerLookup() {
        try {
            ProductionRootAccess(context).execute("exit 42")
            fail("Unreviewed firmware reached the local broker")
        } catch (error: ProductionRootAccess.Refused) {
            assertEquals(UiText.get(R.string.production_unreviewed_firmware), error.message)
        }
    }

    fun testVoiceReceiptMustMatchTheExactRequestAndProtocolStatus() {
        val marker = "OVC_VOICE_RESULT "
        val request = "some-request"
        val verified = FridaBridge.parseVoiceReply(marker + """{"id":"$request","status":"verified","message":"Read back"}""", request)
        assertTrue(verified.accepted())
        assertEquals("verified", verified.status())
        val other = FridaBridge.parseVoiceReply(marker + """{"id":"other-request","status":"verified","message":"Read back"}""", request)
        assertFalse(other.accepted())
        assertEquals("unknown", other.status())
        val obsolete = FridaBridge.parseVoiceReply(marker + """{"id":"$request","status":"sent","message":"Old protocol"}""", request)
        assertEquals("unknown", obsolete.status())
        assertEquals("unknown", FridaBridge.parseVoiceReply(marker + "{", request).status())
    }

    private fun reject(test: () -> Unit) {
        try {
            test()
            fail("Invalid production catalog was accepted")
        } catch (_: IllegalArgumentException) {
            // A malformed catalog fails closed.
        }
    }
}
