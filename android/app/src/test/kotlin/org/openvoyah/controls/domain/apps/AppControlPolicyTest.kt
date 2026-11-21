package org.openvoyah.controls.domain.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppControlPolicyTest {
    private val roles = PackageRoles(
        controls = "org.openvoyah.controls",
        home = "com.qinggan.app.launcher",
        inputMethod = "ru.yandex.androidkeyboard",
        deviceAdmins = setOf("org.example.admin"),
    )

    private fun app(
        packageName: String = "ru.yandex.yandexnavi",
        uid: Int = 10_091,
        preinstalled: Boolean = false,
        persistent: Boolean = false,
        sharedUser: Boolean = false,
    ) = InstalledPackage(packageName, uid, preinstalled, persistent, sharedUser)

    @Test fun anAppInstalledOnTopOfTheFirmwareIsManaged() {
        assertNull(AppControlPolicy.refusal(app(), roles))
        // The same app in a secondary Android user keeps its application UID range.
        assertNull(AppControlPolicy.refusal(app(uid = 1_010_091), roles))
    }

    @Test fun controlsNeverClosesOrRewritesItself() {
        assertEquals(AppControlRefusal.CONTROLS, AppControlPolicy.refusal(app("org.openvoyah.controls", uid = 10_062), roles))
    }

    @Test fun firmwareAppsStayWithTheFirmware() {
        // The OEM launcher, a persistent OEM service and an ordinary preinstalled app are all refused as firmware.
        assertEquals(AppControlRefusal.PREINSTALLED,
            AppControlPolicy.refusal(app("com.qinggan.app.launcher", uid = 1_000, preinstalled = true, persistent = true), roles))
        assertEquals(AppControlRefusal.PREINSTALLED,
            AppControlPolicy.refusal(app("com.qinggan.app.music", uid = 10_040, preinstalled = true), roles))
    }

    @Test fun theMakersAppsCountAsFirmwareEvenOutsideTheSystemImage() {
        assertEquals(AppControlRefusal.PREINSTALLED, AppControlPolicy.refusal(app("com.qinggan.app.music", uid = 10_140), roles))
        assertEquals(AppControlRefusal.PREINSTALLED, AppControlPolicy.stopRefusal(app("com.pateo.example", uid = 10_141), roles))
    }

    @Test fun closingAlsoSparesAppsWhoseServiceAndroidWouldTurnOff() {
        val mapper = app("org.example.buttonmapper", uid = 10_150)
        val withService = roles.copy(boundServices = setOf(mapper.packageName))
        // Permissions stay manageable: revoking one ends the process but leaves the service enabled.
        assertNull(AppControlPolicy.refusal(mapper, withService))
        assertEquals(AppControlRefusal.BOUND_SERVICE, AppControlPolicy.stopRefusal(mapper, withService))
        assertNull(AppControlPolicy.stopRefusal(app(), withService))
        // Anything refused for permissions is refused for closing as well.
        assertEquals(AppControlRefusal.CAR_ROLE, AppControlPolicy.stopRefusal(app("ru.yandex.androidkeyboard", uid = 10_057), withService))
    }

    @Test fun carRolesAreRefusedEvenWhenTheUserInstalledThem() {
        assertEquals(AppControlRefusal.CAR_ROLE, AppControlPolicy.refusal(app("ru.yandex.androidkeyboard", uid = 10_057), roles))
        assertEquals(AppControlRefusal.CAR_ROLE, AppControlPolicy.refusal(app("org.example.launcher"),
            roles.copy(home = "org.example.launcher")))
        assertEquals(AppControlRefusal.CAR_ROLE, AppControlPolicy.refusal(app("org.example.admin"), roles))
    }

    @Test fun systemIdentitiesAreRefused() {
        assertEquals(AppControlRefusal.SYSTEM_IDENTITY, AppControlPolicy.refusal(app(sharedUser = true), roles))
        assertEquals(AppControlRefusal.SYSTEM_IDENTITY, AppControlPolicy.refusal(app(persistent = true), roles))
        assertEquals(AppControlRefusal.SYSTEM_IDENTITY, AppControlPolicy.refusal(app(uid = 1_000), roles))
        assertEquals(AppControlRefusal.SYSTEM_IDENTITY, AppControlPolicy.refusal(app(uid = 1_009_999), roles))
    }

    @Test fun missingRolesProtectNothingExtra() {
        assertNull(AppControlPolicy.refusal(app(), PackageRoles("org.openvoyah.controls")))
    }

    @Test fun appOpModesFollowAndroidsRuleForAppOpPermissions() {
        for (granted in listOf(false, true)) {
            assertTrue(AppOpMode.ALLOWED.allows(granted))
            assertTrue(AppOpMode.FOREGROUND.allows(granted))
            assertFalse(AppOpMode.IGNORED.allows(granted))
            assertFalse(AppOpMode.ERRORED.allows(granted))
            assertEquals(granted, AppOpMode.DEFAULT.allows(granted))
        }
    }
}
