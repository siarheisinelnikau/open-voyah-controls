package org.openvoyah.controls

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.test.AndroidTestCase
import org.openvoyah.controls.platform.ui.FactoryAssistanceHandoff
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

    fun testHandoffFallsBackToTheFactorySettingsLauncherWhenTheDirectShortcutDisappears() {
        val actions = mutableListOf<String?>()
        val launcher = VehicleSettingsLauncher({ listOf(activity()) }, { true })
        val outcome = launcher.requestFactoryAssistance(preferAssistance = true) { intent ->
            actions += intent.action
            if (intent.action == ACTION) throw ActivityNotFoundException()
            assertEquals(PACKAGE, intent.component?.packageName)
            assertEquals(setOf(Intent.CATEGORY_LAUNCHER), intent.categories)
            assertNull(intent.extras)
            assertNull(intent.data)
        }
        assertEquals(FactoryAssistanceHandoff.VEHICLE_MENU_REQUESTED, outcome)
        assertEquals(listOf(ACTION, Intent.ACTION_MAIN), actions)
    }

    fun testHandoffUsesTheExistingVehicleLauncherWhenFactorySettingsHasNoEntry() {
        val launcher = VehicleSettingsLauncher({ query ->
            if (query.`package` == VEHICLE_PACKAGE) listOf(activity(VEHICLE_PACKAGE)) else emptyList()
        }, { true })
        val outcome = launcher.requestFactoryAssistance(preferAssistance = true) { intent ->
            assertEquals(Intent.ACTION_MAIN, intent.action)
            assertEquals(VEHICLE_PACKAGE, intent.component?.packageName)
        }
        assertEquals(FactoryAssistanceHandoff.VEHICLE_MENU_REQUESTED, outcome)
    }

    fun testHandoffStopsWhenTheDirectLaunchResultIsUnclear() {
        val launcher = VehicleSettingsLauncher({ listOf(activity()) }, { true })
        val actions = mutableListOf<String?>()
        val outcome = launcher.requestFactoryAssistance(preferAssistance = true) { intent ->
            actions += intent.action
            error("Dispatch result is unknown")
        }
        assertEquals(FactoryAssistanceHandoff.FAILED, outcome)
        assertEquals(listOf(ACTION), actions)
    }

    private fun activity(owner: String = PACKAGE) = ActivityInfo().apply {
        packageName = owner
        name = "$owner.FactoryAssistanceFixtureActivity"
        exported = true
        enabled = true
        applicationInfo = ApplicationInfo().apply {
            enabled = true
            flags = ApplicationInfo.FLAG_SYSTEM
        }
    }

    private companion object {
        const val PACKAGE = "com.qinggan.app.vehiclesetting"
        const val VEHICLE_PACKAGE = "com.qinggan.app.vehicle"
        // This typo is the exact action exported by the inspected factory package.
        const val ACTION = "$PACKAGE.dirveassistance"
    }
}
