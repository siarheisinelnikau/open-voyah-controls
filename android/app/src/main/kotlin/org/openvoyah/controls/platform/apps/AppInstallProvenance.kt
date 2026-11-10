package org.openvoyah.controls.platform.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserHandle
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

internal data class InstalledAppIdentity(
    val packageName: String,
    val uid: Int,
    val installedAt: Long,
    val updatedAt: Long,
    val version: Long,
    val signingCertificates: Set<String>,
) {
    fun receipt(): String = listOf(uid, installedAt, updatedAt, version, signingCertificates.sorted().joinToString(","))
        .joinToString("|")
}

internal enum class InstallOrigin { CONTROLS, ADB, OTHER, UNKNOWN, UNVERIFIED, SYSTEM }

internal data class AndroidInstallSource(val installer: String?, val initiator: String?) {
    fun identifies(controls: String, reportsInitiator: Boolean): Boolean =
        installer == controls && (!reportsInitiator || initiator == controls)

    fun origin(controls: String, reportsInitiator: Boolean, hasReceipt: Boolean): InstallOrigin = when {
        identifies(controls, reportsInitiator) && hasReceipt -> InstallOrigin.CONTROLS
        installer == controls || initiator == controls -> InstallOrigin.UNVERIFIED
        installer == "com.android.shell" || initiator == "com.android.shell" -> InstallOrigin.ADB
        installer != null || initiator != null -> InstallOrigin.OTHER
        else -> InstallOrigin.UNKNOWN
    }
}

internal class AppInstallProvenance(context: Context) {
    private val context = context.applicationContext
    private val packages get() = context.packageManager
    private val receipts = this.context.getSharedPreferences("verified_apk_installs", Context.MODE_PRIVATE)
    private val reportsInitiator get() = Build.VERSION.SDK_INT >= 30

    fun packageInfo(packageName: String, flags: Int = 0): PackageInfo? = try {
        packages.getPackageInfo(packageName, flags or PackageManager.GET_SIGNING_CERTIFICATES)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun identity(info: PackageInfo): InstalledAppIdentity? {
        val app = info.applicationInfo ?: return null
        val signatures = info.signingInfo?.apkContentsSigners ?: return null
        if (signatures.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        return InstalledAppIdentity(info.packageName, app.uid, info.firstInstallTime, info.lastUpdateTime,
            info.longVersionCode,
            signatures.map { digest.digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) } }.toSet())
    }

    fun origin(info: PackageInfo, identity: InstalledAppIdentity? = identity(info)): InstallOrigin {
        val app = info.applicationInfo ?: return InstallOrigin.UNKNOWN
        if (systemOrOwn(app)) return InstallOrigin.SYSTEM
        val matchingReceipt = identity != null && UserHandle.getUserHandleForUid(identity.uid) == Process.myUserHandle() &&
            receipts.getString(identity.packageName, null) == identity.receipt()
        return source(info.packageName).origin(context.packageName, reportsInitiator, matchingReceipt)
    }

    suspend fun recordSuccessfulSession(packageName: String, expectedBytes: Long, expectedDigest: String): Boolean {
        val info = packageInfo(packageName) ?: return false
        val app = info.applicationInfo ?: return false
        if (systemOrOwn(app) || UserHandle.getUserHandleForUid(app.uid) != Process.myUserHandle()) return false
        val identity = identity(info) ?: return false
        if (!source(packageName).identifies(context.packageName, reportsInitiator)) return false
        val apk = File(app.sourceDir)
        if (expectedBytes <= 0 || !apk.isFile || apk.length() != expectedBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            var bytes = 0L
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count == -1) break
                bytes += count
                if (bytes > expectedBytes) return false
                digest.update(buffer, 0, count)
            }
            if (bytes != expectedBytes) return false
        }
        if (digest.digest().toHexString() != expectedDigest) return false
        val current = packageInfo(packageName) ?: return false
        if (identity(current) != identity || !source(packageName).identifies(context.packageName, reportsInitiator)) return false
        coroutineContext.ensureActive()
        return receipts.edit().putString(packageName, identity.receipt()).commit()
    }

    private fun systemOrOwn(app: ApplicationInfo): Boolean =
        app.packageName == context.packageName || app.flags and
            (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    @Suppress("DEPRECATION")
    private fun source(packageName: String): AndroidInstallSource = try {
        if (Build.VERSION.SDK_INT >= 30) {
            val source = packages.getInstallSourceInfo(packageName)
            AndroidInstallSource(source.installingPackageName, source.initiatingPackageName)
        } else AndroidInstallSource(packages.getInstallerPackageName(packageName), null)
    } catch (_: PackageManager.NameNotFoundException) {
        AndroidInstallSource(null, null)
    } catch (_: SecurityException) {
        AndroidInstallSource(null, null)
    } catch (_: IllegalArgumentException) {
        AndroidInstallSource(null, null)
    }
}

internal fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
