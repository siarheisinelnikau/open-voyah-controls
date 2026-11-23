package org.openvoyah.controls.platform.apps

import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Looper
import org.openvoyah.controls.platform.packages.PackageSessionRemoval
import org.openvoyah.controls.platform.packages.PackageSessionRemoval.Observation
import java.io.Closeable
import java.io.OutputStream

internal data class LocalApkSessionInfo(
    val id: Int,
    val installer: String?,
    val packageName: String?,
    val sealed: Boolean,
)

/** A narrow fixture seam; production sessions remain Android-owned and require Android confirmation. */
internal interface LocalApkSessions {
    fun canInstall(): Boolean
    fun create(packageName: String, bytes: Long): Int
    fun open(id: Int): Session
    fun current(): List<LocalApkSessionInfo>
    fun abandon(id: Int, expectedPackage: String? = null): Boolean

    interface Session : Closeable {
        fun openWrite(bytes: Long): OutputStream
        fun fsync(output: OutputStream)
        fun commit(sender: IntentSender)
    }

    companion object { const val MAX_SESSION_SCAN = 64 }
}

internal class AndroidLocalApkSessions(context: Context) : LocalApkSessions {
    private val context = context.applicationContext
    private val installer get() = context.packageManager.packageInstaller

    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun create(packageName: String, bytes: Long): Int {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setSize(bytes)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        return installer.createSession(params)
    }

    override fun open(id: Int): LocalApkSessions.Session {
        val session = installer.openSession(id)
        return object : LocalApkSessions.Session {
            override fun openWrite(bytes: Long): OutputStream = session.openWrite("base.apk", 0, bytes)
            override fun fsync(output: OutputStream) { session.fsync(output) }
            override fun commit(sender: IntentSender) { session.commit(sender) }
            override fun close() { session.close() }
        }
    }

    override fun current(): List<LocalApkSessionInfo> {
        val current = installer.mySessions
        check(current.size <= LocalApkSessions.MAX_SESSION_SCAN)
        return current.map {
            LocalApkSessionInfo(it.sessionId, it.installerPackageName, it.appPackageName, it.isSealed)
        }
    }

    override fun abandon(id: Int, expectedPackage: String?): Boolean {
        return try {
            if (Looper.myLooper() == Looper.getMainLooper()) return false
            val info = installer.getSessionInfo(id)
            val owned = current().singleOrNull { it.id == id }
            val target = expectedPackage ?: owned?.packageName
            fun matches(owner: String?, name: String?): Boolean = owner == context.packageName &&
                !target.isNullOrBlank() && target != context.packageName && name == target
            if (info != null || owned != null) {
                if (owned == null || !matches(owned.installer, owned.packageName) ||
                    info != null && !matches(info.installerPackageName, info.appPackageName)) return false
            }
            if (info != null) {
                // Dispatch once, then observe removal. Repeating abandon is not a completion proof.
                installer.abandonSession(id)
            }
            PackageSessionRemoval.await {
                val remaining = installer.getSessionInfo(id)
                val listed = current().filter { it.id == id }
                when {
                    remaining != null && !matches(remaining.installerPackageName, remaining.appPackageName) ||
                        listed.size > 1 || listed.any { !matches(it.installer, it.packageName) } -> Observation.UNKNOWN
                    remaining == null && listed.isEmpty() -> Observation.ABSENT
                    else -> Observation.PRESENT
                }
            }
        } catch (_: RuntimeException) { false }
    }
}
