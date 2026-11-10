package org.openvoyah.controls

import android.test.AndroidTestCase
import org.openvoyah.controls.data.frida.ReviewedVoiceCommands
import org.openvoyah.controls.data.frida.ReviewedVoiceCommands.Identity

class ReviewedVoiceCommandsTest : AndroidTestCase() {
    private val fixture = Identity("f0506h", "fixture/test/unit:11/review/1:user/test-keys", 30, "arm64-v8a")
    private val record = """{"product":"${fixture.product}","fingerprint":"${fixture.fingerprint}",
        "sdk":${fixture.sdk},"abi":"${fixture.abi}","profile":"FREE","commands":["EnableHeatDriverLevelOne"]}"""
    private val catalog = """{"schema":1,"headUnits":[$record]}"""

    fun testExactEvidenceOnlyApprovesTheReviewedCommand() {
        val reviewed = requireNotNull(ReviewedVoiceCommands.parse(catalog, fixture))
        assertEquals("FREE", reviewed.profileFor("EnableHeatDriverLevelOne"))
        assertNull(reviewed.profileFor("EnableHeatPassengerLevelOne"))
        assertNull(ReviewedVoiceCommands.parse(catalog, fixture.copy(fingerprint = "different")))
        assertNull(ReviewedVoiceCommands.parse(catalog, fixture.copy(product = "f0505h")))
        assertNull(ReviewedVoiceCommands.parse(catalog, fixture.copy(sdk = 28)))
        assertNull(ReviewedVoiceCommands.parse(catalog, fixture.copy(abi = "x86_64")))
    }

    fun testAmbiguousEvidenceAndModelMismatchAreRejected() {
        val duplicated = """{"schema":1,"headUnits":[$record,$record]}"""
        assertNull(ReviewedVoiceCommands.parse(duplicated, fixture))
        assertNull(ReviewedVoiceCommands.parse(catalog.replace("\"FREE\"", "\"DREAMER\""), fixture))
    }

    fun testPackagedCatalogLeavesPhysicalCommandsLocked() {
        val policy = ReviewedVoiceCommands(context)
        for (command in listOf("EnableHeatDriverLevelOne", "DriverA", "RebootTbox", "Sentry", "LampOff")) {
            assertNull(policy.profileFor(command))
        }
    }
}
