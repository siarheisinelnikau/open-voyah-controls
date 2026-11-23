package org.openvoyah.controls

import android.test.AndroidTestCase
import org.openvoyah.controls.data.frida.ReviewedVoiceCommands
import org.openvoyah.controls.data.frida.ReviewedVoiceCommands.Identity
import org.openvoyah.controls.data.settings.ActionPermissions
import org.openvoyah.controls.domain.permissions.ActionCatalog
import org.openvoyah.controls.domain.permissions.ActionPermission

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

    fun testASupportedProductAllowsEveryCommandWithoutARow() {
        assertEquals("FREE", ReviewedVoiceCommands.profileOf(fixture))
        assertEquals("FREE_REST", ReviewedVoiceCommands.profileOf(fixture.copy(product = "f6802h")))
        assertNull(ReviewedVoiceCommands.profileOf(fixture.copy(product = "sdk_gphone64_arm64")))
        assertNull(ReviewedVoiceCommands.profileOf(fixture.copy(sdk = 29)))
        assertNull(ReviewedVoiceCommands.profileOf(fixture.copy(abi = "x86_64")))
        // The test device is not a Voyah head unit, so the packaged policy keeps physical commands locked here.
        val policy = ReviewedVoiceCommands(context)
        if (ReviewedVoiceCommands.profileOf(Identity(android.os.Build.PRODUCT, android.os.Build.FINGERPRINT,
                android.os.Build.VERSION.SDK_INT, android.os.Build.SUPPORTED_ABIS.firstOrNull())) == null) {
            for (command in listOf("EnableHeatDriverLevelOne", "DriverA", "RebootTbox", "Sentry", "LampOff")) {
                assertNull(policy.profileFor(command))
            }
        }
    }

    fun testForbiddenCommandsComeFromAllowedActionsAndTheOldSwitchesMigrate() {
        val prefs = context.getSharedPreferences(ActionPermissions.STORE, android.content.Context.MODE_PRIVATE)
        val legacy = context.getSharedPreferences("vehicle_commands", android.content.Context.MODE_PRIVATE)
        val saved = prefs.all
        try {
            prefs.edit().clear().commit()
            legacy.edit().putStringSet("switchedOff", setOf("OpenTrunkCommand", "not a command")).commit()
            val permissions = ActionPermissions(context)
            assertEquals(ActionPermission.FORBIDDEN, permissions.voice("OpenTrunkCommand"))
            assertFalse(legacy.contains("switchedOff"))
            assertEquals(ActionPermission.ALLOWED, permissions.voice("LowBeam"))
            permissions.set(ActionCatalog.voice("LowBeam"), ActionPermission.UNVERIFIED)
            val policy = ReviewedVoiceCommands(context)
            assertEquals(ActionPermission.UNVERIFIED, policy.permission("LowBeam"))
            assertFalse(policy.isSwitchedOff("LowBeam"))
            assertNull(policy.profileFor("OpenTrunkCommand"))
            // A local command is never listed or forbidden.
            assertEquals(ActionPermission.ALLOWED, permissions.voice("MediaPlay"))
        } finally {
            prefs.edit().clear().apply {
                for ((key, value) in saved) if (value is String) putString(key, value)
            }.commit()
        }
    }
}
