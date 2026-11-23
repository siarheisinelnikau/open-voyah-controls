package org.openvoyah.controls.platform.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.domain.vehicle.ExteriorLamp
import org.openvoyah.controls.domain.vehicle.HeadlightInput
import org.openvoyah.controls.domain.vehicle.HeadlightSource
import org.openvoyah.controls.domain.vehicle.HeadlightStatus
import org.openvoyah.controls.platform.apps.apkSha256
import org.openvoyah.controls.platform.apps.currentSignerDigests
import org.openvoyah.controls.platform.ui.FACTORY_VEHICLE_PACKAGE
import org.openvoyah.controls.platform.ui.FACTORY_VEHICLE_SETTINGS_PACKAGE
import org.openvoyah.controls.platform.ui.VehicleSettingsDestination
import org.openvoyah.controls.platform.ui.VehicleSettingsLauncher

/** Reports what the current Android user and Controls installation can see without opening a factory screen. */
internal suspend fun headlightDiagnosticReport(context: Context, status: HeadlightStatus): String {
    val capturedAt = System.currentTimeMillis()
    val app = context.applicationContext
    return withContext(Dispatchers.IO) {
        val packages = app.packageManager
        val requested = requestedPermissions(packages, app.packageName)
        val factory = VehicleSettingsLauncher(app)
        val current = JSONObject()
        val earlier = JSONObject()
        val last = JSONObject()
        val historyWriteFailures = JSONArray()
        status.currentObservation?.let { current.put("selector", observation(it.mode.key, it.confirmedAtMillis, it.source)
            .put("history_write_failed", status.saveFailed)) }
        status.earlierReport?.let { earlier.put("selector", earlierReport(it.value.key, it.reportedAtMillis)) }
        status.lastConfirmed?.let { last.put("selector", observation(it.mode.key, it.confirmedAtMillis, it.source)) }
        if (status.saveFailed) historyWriteFailures.put("selector")
        for (lamp in ExteriorLamp.entries) {
            val reading = status.lamps[lamp] ?: continue
            reading.current?.let { current.put(lamp.key, observation(it.activity.key, it.confirmedAtMillis, it.source)
                .put("history_write_failed", reading.saveFailed)) }
            reading.earlierReport?.let { earlier.put(lamp.key, earlierReport(it.value.key, it.reportedAtMillis)) }
            reading.lastConfirmed?.let { last.put(lamp.key, observation(it.activity.key, it.confirmedAtMillis, it.source)) }
            if (reading.saveFailed) historyWriteFailures.put(lamp.key)
        }
        JSONObject()
            .put("report_version", 2)
            .put("captured_at_ms", capturedAt)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("android_api", Build.VERSION.SDK_INT)
            .put("android_product", Build.PRODUCT)
            .put("android_build", Build.FINGERPRINT)
            .put("android_automotive", JSONObject()
                .put("advertised", exposedValue { packages.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) })
                .put("requested_permissions", JSONObject().apply {
                    for (permission in AUTOMOTIVE_REPORT_PERMISSIONS) {
                        put(permission, requested?.contains(permission) ?: JSONObject.NULL)
                    }
                })
                .put("permissions", JSONObject().apply {
                    for (permission in AUTOMOTIVE_REPORT_PERMISSIONS) {
                        put(permission, exposedValue { app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED })
                    }
                }))
            .put("factory_packages", JSONObject().apply {
                put(QingganHeadlightReader.PACKAGE, readVehiclePackageEvidence(packages, QingganHeadlightReader.PACKAGE, fingerprintBase = true))
                put(FACTORY_VEHICLE_PACKAGE, readVehiclePackageEvidence(packages, FACTORY_VEHICLE_PACKAGE))
                put(FACTORY_VEHICLE_SETTINGS_PACKAGE, readVehiclePackageEvidence(packages, FACTORY_VEHICLE_SETTINGS_PACKAGE))
            })
            .put("factory_entry_points", JSONObject()
                .put("lighting", factoryEntry { factory.intent(VehicleSettingsDestination.LIGHTING)?.component })
                .put("seats", factoryEntry { factory.seatsIntent()?.component }))
            .put("lighting_inputs", JSONObject().apply {
                for (input in HeadlightInput.entries) {
                    put(input.name.lowercase(Locale.ROOT), status.inputStates[input]?.name?.lowercase(Locale.ROOT) ?: JSONObject.NULL)
                }
            })
            .put("current", current)
            .put("earlier_android_reports", earlier)
            .put("last_confirmed", last)
            .put("history_write_failures", historyWriteFailures)
            .toString(2)
    }
}

internal suspend fun readVehiclePackageEvidence(
    packages: PackageManager, name: String, fingerprintBase: Boolean = false,
): JSONObject {
    val packageFlags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.MATCH_DISABLED_COMPONENTS
    val info = try {
        packages.getPackageInfo(name, packageFlags)
    } catch (_: PackageManager.NameNotFoundException) {
        return JSONObject().put("status", "not_visible_or_absent")
    } catch (_: RuntimeException) {
        return JSONObject().put("status", "query_failed")
    }
    val app = info.applicationInfo
    val signers = info.currentSignerDigests()
    val row = JSONObject()
        .put("status", "visible")
        .put("version_code", info.longVersionCode)
        .put("version_name", info.versionName ?: JSONObject.NULL)
        .put("enabled", app?.enabled ?: JSONObject.NULL)
        .put("system_app", app?.let { it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
            ?: JSONObject.NULL)
        .put("split_apk_count", app?.let { it.splitSourceDirs?.size ?: 0 } ?: JSONObject.NULL)
        .put("current_signer_sha256", JSONArray(signers.sorted()))
    if (fingerprintBase) {
        val apk = app?.sourceDir?.let(::File)
        val result = if (apk == null) ApkFingerprint("unreadable") else fingerprintApk(packages, info, apk, packageFlags)
        row.put("base_apk_fingerprint_status", result.status)
            .put("base_apk_sha256", result.digest ?: JSONObject.NULL)
    }
    return row
}

private data class ApkFingerprint(val status: String, val digest: String? = null)

private suspend fun fingerprintApk(packages: PackageManager, info: PackageInfo, apk: File, packageFlags: Int): ApkFingerprint = try {
    val bytes = apk.length()
    if (bytes > CAN_APK_REPORT_LIMIT_BYTES) ApkFingerprint("size_limit")
    else {
        val digest = apkSha256(apk, bytes)
        if (digest == null) ApkFingerprint("unreadable")
        else {
            val current = packages.getPackageInfo(info.packageName, packageFlags)
            if (current.applicationInfo?.sourceDir == apk.path && current.longVersionCode == info.longVersionCode &&
                current.lastUpdateTime == info.lastUpdateTime && current.currentSignerDigests() == info.currentSignerDigests()) {
                ApkFingerprint("read", digest)
            } else ApkFingerprint("package_changed")
        }
    }
} catch (_: PackageManager.NameNotFoundException) {
    ApkFingerprint("package_changed")
} catch (_: IOException) {
    ApkFingerprint("unreadable")
} catch (_: SecurityException) {
    ApkFingerprint("unreadable")
}

private fun factoryEntry(resolve: () -> ComponentName?): JSONObject = try {
    val component = resolve()?.flattenToString()
    if (component == null) JSONObject().put("status", "unavailable")
    else JSONObject().put("status", "resolved").put("component", component)
} catch (_: RuntimeException) {
    JSONObject().put("status", "query_failed")
}

private fun exposedValue(read: () -> Boolean): Any = try {
    read()
} catch (_: RuntimeException) {
    JSONObject.NULL
}

private fun requestedPermissions(packages: PackageManager, packageName: String): Set<String>? = try {
    packages.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet().orEmpty()
} catch (_: PackageManager.NameNotFoundException) {
    null
} catch (_: RuntimeException) {
    null
}

private fun observation(value: String, at: Long, source: HeadlightSource?) = JSONObject()
    .put("value", value)
    .put("confirmed_at_ms", at)
    .put("source", source?.key ?: JSONObject.NULL)

private fun earlierReport(value: String, at: Long) = JSONObject()
    .put("value", value)
    .put("reported_at_ms", at)
    .put("source", HeadlightSource.ANDROID_AUTOMOTIVE.key)

private const val CAN_APK_REPORT_LIMIT_BYTES = 32L * 1024 * 1024
private const val CAR_SEAT_PERMISSION = "android.car.permission.CONTROL_CAR_SEATS"
private val AUTOMOTIVE_REPORT_PERMISSIONS = listOf(HEADLIGHT_SWITCH_PERMISSION, HEADLIGHT_ACTIVITY_PERMISSION, CAR_SEAT_PERMISSION)
