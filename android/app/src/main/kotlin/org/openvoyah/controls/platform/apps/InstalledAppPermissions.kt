package org.openvoyah.controls.platform.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.Process
import org.openvoyah.controls.data.apps.RootPermissionWriter
import java.security.MessageDigest

internal data class InstalledAppIdentity(
    val packageName: String,
    val uid: Int,
    val installedAt: Long,
    val updatedAt: Long,
    val signingCertificates: Set<String>,
)

internal data class AppPermission(val name: String, val label: String, val granted: Boolean)
internal data class AppPermissions(val identity: InstalledAppIdentity, val permissions: List<AppPermission>)

internal class InstalledAppPermissions(
    private val context: Context,
    private val writer: RootPermissionWriter = RootPermissionWriter(),
) {
    private val packages get() = context.packageManager

    fun managed(packageName: String): Boolean {
        if (packageName == context.packageName) return false
        return try {
            val app = packages.getApplicationInfo(packageName, 0)
            val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            app.flags and systemFlags == 0 && userId(app.uid) == userId(Process.myUid()) &&
                installer(packageName) == context.packageName
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun installer(packageName: String): String? = if (Build.VERSION.SDK_INT >= 30) {
        packages.getInstallSourceInfo(packageName).installingPackageName
    } else {
        packages.getInstallerPackageName(packageName)
    }

    @Suppress("DEPRECATION")
    fun read(packageName: String): AppPermissions? {
        if (!managed(packageName)) return null
        val info = try {
            packages.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val application = info.applicationInfo ?: return null
        val signatures = info.signingInfo?.apkContentsSigners ?: return null
        if (signatures.isEmpty() || !managed(packageName)) return null
        val digest = MessageDigest.getInstance("SHA-256")
        val identity = InstalledAppIdentity(
            packageName, application.uid, info.firstInstallTime, info.lastUpdateTime,
            signatures.map { digest.digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) } }.toSet(),
        )
        val available = if (application.targetSdkVersion >= 23) {
            info.requestedPermissions.orEmpty().mapNotNull { name ->
                val permission = try {
                    packages.getPermissionInfo(name, 0)
                } catch (_: PackageManager.NameNotFoundException) {
                    return@mapNotNull null
                }
                if (permission.packageName != "android" || permission.protection != PermissionInfo.PROTECTION_DANGEROUS) {
                    return@mapNotNull null
                }
                AppPermission(name, permission.loadLabel(packages).toString(),
                    packages.checkPermission(name, packageName) == PackageManager.PERMISSION_GRANTED)
            }.distinctBy { it.name }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        } else emptyList()
        return AppPermissions(identity, available)
    }

    fun set(identity: InstalledAppIdentity, name: String, granted: Boolean): Boolean {
        val current = read(identity.packageName) ?: return false
        if (current.identity != identity || current.permissions.none { it.name == name }) return false
        if (current.permissions.any { it.name == name && it.granted == granted }) return true
        writer.set(userId(identity.uid), identity.packageName, name, granted)
        val updated = read(identity.packageName)
        return updated?.identity == identity && updated.permissions.any { it.name == name && it.granted == granted }
    }

    private fun userId(uid: Int): Int = uid / 100_000 // Android's per-user UID range on supported head units.
}
