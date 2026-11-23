package org.openvoyah.controls

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.test.AndroidTestCase
import org.openvoyah.controls.platform.ui.VehicleSettingsDestination
import org.openvoyah.controls.platform.ui.VehicleSettingsLauncher

class FactoryAssistanceShortcutTest : AndroidTestCase() {
    fun testOnlyTheObservedPublicAssistanceActionIsResolvedWithoutActivationData() {
        val target = activity()
        val launcher = VehicleSettingsLauncher(queryActivities = { query ->
            assertEquals(ACTION, query.action)
            assertEquals(PACKAGE, query.`package`)
            assertEquals(setOf(Intent.CATEGORY_DEFAULT), query.categories)
            assertNull(query.component)
            assertNull(query.extras)
            assertNull(query.data)
            listOf(target)
        }, hasPermission = { error("The public fixture does not require a permission") })
        val result = checkNotNull(launcher.intent(VehicleSettingsDestination.ASSISTANCE))
        assertEquals(ACTION, result.action)
        assertEquals(ComponentName(PACKAGE, target.name), result.component)
        assertNull("A menu handoff must not contain activation or configuration data", result.extras)
        assertNull(result.data)
    }

    fun testNonSystemPrivateDisabledWrongPackageAndUnpermittedTargetsAreRejected() {
        val denied = listOf(
            activity().apply { exported = false },
            activity().apply { enabled = false },
            activity().apply { applicationInfo.enabled = false },
            activity().apply { applicationInfo.flags = 0 },
            activity().apply { packageName = "org.example.unrelated" },
            activity().apply { permission = "org.example.signature_only" },
        )
        denied.forEach { target ->
            assertNull(VehicleSettingsLauncher({ listOf(target) }, { false }).intent(VehicleSettingsDestination.ASSISTANCE))
        }
        val granted = activity().apply {
            permission = "org.example.allowed"
            applicationInfo.flags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        }
        assertNotNull(VehicleSettingsLauncher({ listOf(granted) }, { it == "org.example.allowed" })
            .intent(VehicleSettingsDestination.ASSISTANCE))
    }

    fun testAmbiguousOrMissingAssistanceMenusDoNotInventAnotherAction() {
        val queries = mutableListOf<String?>()
        val launcher = VehicleSettingsLauncher({ query ->
            queries += query.action
            listOf(activity(), activity().apply { name = "$PACKAGE.SecondFixtureActivity" })
        }, { true })
        assertNull(launcher.intent(VehicleSettingsDestination.ASSISTANCE))
        assertEquals(listOf(ACTION), queries)
        queries.clear()
        assertNull(VehicleSettingsLauncher({ query -> queries += query.action; emptyList() }, { true })
            .intent(VehicleSettingsDestination.ASSISTANCE))
        assertEquals(listOf(ACTION), queries)
    }

    private fun activity() = ActivityInfo().apply {
        packageName = PACKAGE
        name = "$PACKAGE.FactoryAssistanceFixtureActivity"
        exported = true
        enabled = true
        applicationInfo = ApplicationInfo().apply {
            enabled = true
            flags = ApplicationInfo.FLAG_SYSTEM
        }
    }

    private companion object {
        const val PACKAGE = "com.qinggan.app.vehiclesetting"
        // This typo is the exact action exported by the inspected factory package.
        const val ACTION = "$PACKAGE.dirveassistance"
    }
}
