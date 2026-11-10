package org.openvoyah.controls.platform.apps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import kotlinx.coroutines.CancellationException
import org.openvoyah.controls.data.apps.RootPermissionWriter

internal data class AppPermission(val name: String, val label: String, val granted: Boolean, val changeable: Boolean)

internal data class AppPermissions(
    val identity: InstalledAppIdentity?,
    val origin: InstallOrigin,
    val permissions: List<AppPermission>,
    val specialAccess: List<AndroidAppAccess>,
    val hasUnavailableDeclarations: Boolean,
)

internal enum class PermissionChange { VERIFIED, PACKAGE_CHANGED, ANDROID_ONLY, UNCONFIRMED }

internal class InstalledAppPermissions(
    private val context: Context,
    private val writer: RootPermissionWriter = RootPermissionWriter(context),
) {
    private val packages get() = context.packageManager
    private val provenance = AppInstallProvenance(context)

    fun origin(packageName: String): InstallOrigin = provenance.packageInfo(packageName)?.let { provenance.origin(it) }
        ?: InstallOrigin.UNKNOWN

    @Suppress("DEPRECATION")
    fun read(packageName: String): AppPermissions? {
        val info = provenance.packageInfo(packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES)
            ?: return null
        val application = info.applicationInfo ?: return null
        val identity = provenance.identity(info)
        val origin = provenance.origin(info, identity)
        val appChangeable = origin == InstallOrigin.CONTROLS && application.targetSdkVersion >= 23 &&
            info.sharedUserId == null
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
                appChangeable && !requiresAndroidSettings(permission))
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        return AppPermissions(identity, origin, runtime, AndroidAppAccess.declaredBy(info), unavailable)
    }

    fun set(identity: InstalledAppIdentity, name: String, granted: Boolean): PermissionChange {
        val current = read(identity.packageName) ?: return PermissionChange.PACKAGE_CHANGED
        if (current.identity != identity) return PermissionChange.PACKAGE_CHANGED
        val permission = current.permissions.find { it.name == name && it.changeable }
            ?: return PermissionChange.ANDROID_ONLY
        if (permission.granted == granted) return PermissionChange.VERIFIED
        try {
            writer.set(userId(identity.uid), identity.packageName, name, granted)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The transport can fail after Android has handled the request. Read once and never replay it.
        }
        val updated = read(identity.packageName)
        return when {
            updated == null -> PermissionChange.UNCONFIRMED
            updated.identity != identity -> PermissionChange.PACKAGE_CHANGED
            updated.permissions.any { it.name == name && it.changeable && it.granted == granted } -> PermissionChange.VERIFIED
            else -> PermissionChange.UNCONFIRMED
        }
    }

    private fun requiresAndroidSettings(permission: PermissionInfo): Boolean = Build.VERSION.SDK_INT >= 29 &&
        (permission.name == Manifest.permission.ACCESS_BACKGROUND_LOCATION ||
            permission.flags and (PermissionInfo.FLAG_HARD_RESTRICTED or PermissionInfo.FLAG_SOFT_RESTRICTED) != 0)

    private fun userId(uid: Int): Int = uid / 100_000 // Android's per-user UID range on supported head units.
}
