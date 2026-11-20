package org.openvoyah.controls.platform.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import org.openvoyah.controls.domain.seatmemory.SeatMemoryAction
import org.openvoyah.controls.domain.seatmemory.SeatMemorySlot

internal const val FACTORY_VEHICLE_PACKAGE = "com.qinggan.app.vehicle"
internal const val FACTORY_VEHICLE_SETTINGS_PACKAGE = "com.qinggan.app.vehiclesetting"

internal enum class VehicleSettingsDestination(vararg val packageNames: String) {
    VEHICLE(FACTORY_VEHICLE_PACKAGE, FACTORY_VEHICLE_SETTINGS_PACKAGE),
    INFOTAINMENT("com.qinggan.app.setting"),
    LIGHTING(FACTORY_VEHICLE_SETTINGS_PACKAGE),
    ASSISTANCE(FACTORY_VEHICLE_SETTINGS_PACKAGE),
}

internal enum class FactorySeatShortcut { SEATS, VEHICLE }

internal enum class FactorySeatHandoffOutcome { REQUESTED, UNAVAILABLE, FAILED }

/** Uses activities publicly offered by the built-in OEM app. */
internal class VehicleSettingsLauncher(
    private val queryActivities: (Intent) -> List<ActivityInfo>,
    private val hasPermission: (String) -> Boolean,
    private val logHandoff: (String) -> Unit = { Log.i("OpenVoyahControls", it) },
) {
    constructor(context: Context) : this(
        queryActivities = { query -> context.packageManager.queryIntentActivities(query, 0).mapNotNull { it.activityInfo } },
        hasPermission = { permission ->
            context.packageManager.checkPermission(permission, context.packageName) == PackageManager.PERMISSION_GRANTED
        },
    )

    fun intent(destination: VehicleSettingsDestination): Intent? = destination.packageNames.firstNotNullOfOrNull { name ->
        when (destination) {
            VehicleSettingsDestination.LIGHTING -> resolveShortcut(name, "SHOW_LIGHT")
            // This spelling is the public action declared by the inspected factory APK.
            VehicleSettingsDestination.ASSISTANCE -> resolveShortcut(name, "dirveassistance")
            else -> resolve(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(name))
        }
    }

    fun seatsIntent(): Intent? = resolveShortcut(FACTORY_VEHICLE_PACKAGE, "SHOW_SEAT")

    fun seatMemoryShortcut(): FactorySeatShortcut? = FactorySeatShortcut.entries.firstOrNull { seatMemoryIntent(it) != null }

    /** Requests the displayed factory route again. REQUESTED records activity dispatch only. */
    fun requestSeatMemory(
        shortcut: FactorySeatShortcut,
        slot: SeatMemorySlot,
        action: SeatMemoryAction,
        startActivity: (Intent) -> Unit,
    ): FactorySeatHandoffOutcome {
        val outcome = try {
            val target = seatMemoryIntent(shortcut)
            if (target == null) FactorySeatHandoffOutcome.UNAVAILABLE else {
                startActivity(target)
                FactorySeatHandoffOutcome.REQUESTED
            }
        } catch (_: RuntimeException) {
            FactorySeatHandoffOutcome.FAILED
        }
        logHandoff("event=driver_memory_handoff slot=${slot.number} action=${action.name.lowercase()} " +
            "target=${shortcut.name.lowercase()} outcome=${outcome.name.lowercase()} elapsed_ms=${SystemClock.elapsedRealtime()}")
        return outcome
    }

    private fun seatMemoryIntent(shortcut: FactorySeatShortcut): Intent? = when (shortcut) {
        FactorySeatShortcut.SEATS -> seatsIntent()
        FactorySeatShortcut.VEHICLE -> intent(VehicleSettingsDestination.VEHICLE)
    }

    private fun resolveShortcut(packageName: String, action: String): Intent? =
        resolve(Intent("$packageName.$action").addCategory(Intent.CATEGORY_DEFAULT).setPackage(packageName))

    private fun resolve(query: Intent): Intent? {
        val packageName = checkNotNull(query.`package`)
        val matches = queryActivities(query).filter { activity ->
            val app = activity.applicationInfo
            activity.packageName == packageName && activity.exported && activity.enabled && app.enabled &&
                app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 &&
                (activity.permission.isNullOrEmpty() || hasPermission(activity.permission))
        }
        val target = matches.distinctBy { it.name }.singleOrNull() ?: return null
        return query.setComponent(ComponentName(target.packageName, target.name))
    }
}
