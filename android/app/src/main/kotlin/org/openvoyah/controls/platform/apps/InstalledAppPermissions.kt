package org.openvoyah.controls.platform.apps

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import kotlinx.coroutines.CancellationException
import org.openvoyah.controls.data.apps.RootPermissionWriter
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.apps.AppControlRefusal
import org.openvoyah.controls.domain.apps.AppOpMode

internal data class AppPermission(val name: String, val label: String, val granted: Boolean, val changeable: Boolean)

/** One declared special access. [allowed] is null when Android did not report its state to Controls. */
internal data class AppSpecialAccess(val access: AndroidAppAccess, val allowed: Boolean?, val changeable: Boolean)

/** [refusal] says why Controls leaves this app's permissions alone; null means Controls manages them. */
internal data class AppPermissions(
    val identity: InstalledAppIdentity?,
    val origin: InstallOrigin,
    val refusal: AppControlRefusal?,
    val permissions: List<AppPermission>,
    val specialAccess: List<AppSpecialAccess>,
    val hasUnavailableDeclarations: Boolean,
)

internal enum class PermissionChange { VERIFIED, PACKAGE_CHANGED, ANDROID_ONLY, UNCONFIRMED }

internal data class AppInstallationInfo(val origin: InstallOrigin, val firstInstallTime: Long? = null)

/** The permissions part of the app settings page; tests replace it to show a page without a real package. */
internal interface AppPermissionControl {
    fun read(packageName: String): AppPermissions?
    fun set(identity: InstalledAppIdentity, name: String, granted: Boolean): PermissionChange
    fun setSpecialAccess(identity: InstalledAppIdentity, access: AndroidAppAccess, allowed: Boolean): PermissionChange
}

internal class InstalledAppPermissions(
    private val context: Context,
    private val writer: RootPermissionWriter = RootPermissionWriter(context),
) : AppPermissionControl {
    private val packages get() = context.packageManager
    private val provenance = AppInstallProvenance(context)
    private val facts = AppControlFacts(context)

    fun origin(packageName: String): InstallOrigin = installInfo(packageName).origin

    fun installInfo(packageName: String): AppInstallationInfo {
        val info = provenance.packageInfo(packageName) ?: return AppInstallationInfo(InstallOrigin.UNKNOWN)
        return AppInstallationInfo(provenance.origin(info), info.firstInstallTime.takeIf { it > 0L })
    }

    @Suppress("DEPRECATION")
    override fun read(packageName: String): AppPermissions? {
        val info = provenance.packageInfo(packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES)
            ?: return null
        val application = info.applicationInfo ?: return null
        val identity = provenance.identity(info)
        val origin = provenance.origin(info, identity)
        val refusal = facts.refusal(info)
        val manageable = refusal == null && identity != null
        // Android grants an app built for Android 5.1 or older its permissions at installation; only its own
        // settings could take them back, through a compatibility flag `pm` does not set.
        val runtimeChangeable = manageable && application.targetSdkVersion >= 23
        var unavailable = false
        val runtime = info.requestedPermissions.orEmpty().distinct().mapNotNull { name ->
            val permission = try {
                packages.getPermissionInfo(name, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                unavailable = true
                return@mapNotNull null
            }
            if (permission.packageName != "android" || permission.protection != PermissionInfo.PROTECTION_DANGEROUS) {
                return@mapNotNull null
            }
            AppPermission(name, permission.loadLabel(packages).toString(),
                packages.checkPermission(name, packageName) == PackageManager.PERMISSION_GRANTED,
                runtimeChangeable && !requiresAndroidSettings(permission))
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        val declared = AndroidAppAccess.declaredBy(info)
        val operations = declared.mapNotNull { it.operation }
        // A managed app's modes come from the root shell, the same source that confirms a change; Android 9 and 11
        // refuse an ordinary app another app's modes. Other apps are shown with what Android reports, if anything.
        val rootModes = if (manageable && operations.isNotEmpty()) readModes(androidUserId(application.uid), packageName, operations) else null
        val special = declared.map { access ->
            val permission = access.permission
            val operation = access.operation
            val allowed = when {
                permission == null || operation == null -> null
                manageable -> rootModes?.get(operation)?.allows(granted(permission, packageName))
                else -> reportedByAndroid(permission, operation, application.uid, packageName)
            }
            AppSpecialAccess(access, allowed, manageable && allowed != null)
        }
        return AppPermissions(identity, origin, refusal, runtime, special, unavailable)
    }

    override fun set(identity: InstalledAppIdentity, name: String, granted: Boolean): PermissionChange =
        change(identity, "permission", name, granted,
            current = { details -> details.permissions.find { it.name == name && it.changeable }?.granted },
            write = { writer.set(androidUserId(identity.uid), identity.packageName, name, granted) })

    override fun setSpecialAccess(identity: InstalledAppIdentity, access: AndroidAppAccess, allowed: Boolean): PermissionChange {
        val operation = access.operation ?: return PermissionChange.ANDROID_ONLY
        val mode = if (allowed) AppOpMode.ALLOWED else access.revoked
        return change(identity, "access", access.name, allowed,
            current = { details -> details.specialAccess.find { it.access == access && it.changeable }?.allowed },
            write = { writer.setAppOp(androidUserId(identity.uid), identity.packageName, identity.uid, operation, mode) })
    }

    /**
     * Writes once and confirms by reading Android's state back. [current] returns null when Controls may not change
     * this entry. The transport can fail after Android has handled the request, so a failure is read, never replayed.
     */
    private fun change(
        identity: InstalledAppIdentity,
        kind: String,
        name: String,
        wanted: Boolean,
        current: (AppPermissions) -> Boolean?,
        write: () -> Unit,
    ): PermissionChange {
        val before = read(identity.packageName) ?: return PermissionChange.PACKAGE_CHANGED
        if (before.identity != identity) return PermissionChange.PACKAGE_CHANGED
        val state = current(before) ?: return PermissionChange.ANDROID_ONLY
        if (state == wanted) return PermissionChange.VERIFIED
        try {
            write()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ControlsLog.warn("app_permission_change", failure, kind, name, "granted", wanted)
        }
        val after = read(identity.packageName)
        val outcome = when {
            after == null -> PermissionChange.UNCONFIRMED
            after.identity != identity -> PermissionChange.PACKAGE_CHANGED
            current(after) == wanted -> PermissionChange.VERIFIED
            else -> PermissionChange.UNCONFIRMED
        }
        ControlsLog.info("app_permission_change", kind, name, "granted", wanted, "outcome", outcome)
        return outcome
    }

    private fun readModes(user: Int, packageName: String, operations: List<String>): Map<String, AppOpMode>? = try {
        writer.readAppOps(user, packageName, operations)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        ControlsLog.warn("app_access_read", failure, "operations", operations.size)
        null
    }

    private fun granted(permission: String, packageName: String): Boolean =
        packages.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED

    /** Android's own rule for an app-op permission, read without root. Null when Android refuses to report it. */
    @Suppress("DEPRECATION")
    private fun reportedByAndroid(permission: String, operation: String, uid: Int, packageName: String): Boolean? {
        val raw = try {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return null
            if (Build.VERSION.SDK_INT >= 29) appOps.unsafeCheckOpRawNoThrow(operation, uid, packageName)
            else appOps.checkOpNoThrow(operation, uid, packageName)
        } catch (_: RuntimeException) {
            return null
        }
        val mode = when (raw) {
            AppOpsManager.MODE_ALLOWED -> AppOpMode.ALLOWED
            AppOpsManager.MODE_IGNORED -> AppOpMode.IGNORED
            AppOpsManager.MODE_ERRORED -> AppOpMode.ERRORED
            AppOpsManager.MODE_DEFAULT -> AppOpMode.DEFAULT
            MODE_FOREGROUND -> AppOpMode.FOREGROUND
            else -> return null
        }
        return mode.allows(granted(permission, packageName))
    }

    private fun requiresAndroidSettings(permission: PermissionInfo): Boolean = Build.VERSION.SDK_INT >= 29 &&
        (permission.name == Manifest.permission.ACCESS_BACKGROUND_LOCATION ||
            permission.flags and (PermissionInfo.FLAG_HARD_RESTRICTED or PermissionInfo.FLAG_SOFT_RESTRICTED) != 0)

    private companion object {
        /** `AppOpsManager.MODE_FOREGROUND`, added in Android 10; Android 9 never reports it. */
        const val MODE_FOREGROUND = 4
    }
}

/** Android's per-user UID range on supported head units. */
internal fun androidUserId(uid: Int): Int = uid / 100_000
