package org.openvoyah.controls.platform.updates

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.core.content.IntentCompat
import org.openvoyah.controls.core.updates.ReleasePolicy
import org.openvoyah.controls.data.updates.UpdateException
import org.openvoyah.controls.data.updates.UpdateProblem
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.platform.apps.installStatusCode
import org.openvoyah.controls.platform.packages.PackageSessionRemoval
import org.openvoyah.controls.platform.packages.PackageSessionRemoval.Observation
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Ordinary Android PackageInstaller sessions always require the operating system's approval. */
internal class AndroidUpdatePackageSessions(context: Context) : UpdatePackageSessions {
    private val context = context.applicationContext
    private val packages get() = context.packageManager
    private val installer get() = packages.packageInstaller
    private val directory get() = File(context.cacheDir, "official-updates").canonicalFile

    override fun canInstall(): Boolean = packages.canRequestPackageInstalls()

    override fun sourceIdentity(file: File): String {
        require(file.absoluteFile == file.canonicalFile && file.parentFile == directory)
        val stat = Os.lstat(file.path)
        require(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() && stat.st_nlink == 1L)
        require(stat.st_size in 1..ReleasePolicy.MAX_APK_BYTES)
        return identity(stat)
    }

    override fun originalInstalled(target: UpdateInstallTarget): Boolean {
        val info = installedInfo()
        return info.packageName == target.packageName && info.versionName == target.originalVersion &&
            info.longVersionCode == target.originalVersionCode && info.lastUpdateTime == target.originalUpdatedAt &&
            info.applicationInfo?.sourceDir == target.originalPath && signers(info) == target.originalSigners
    }

    override fun create(target: UpdateInstallTarget): Int = installer.createSession(
        PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(target.packageName)
            setSize(target.bytes)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        },
    )

    override suspend fun stage(sessionId: Int, target: UpdateInstallTarget, progress: (Float) -> Unit) {
        val file = File(target.sourcePath)
        if (sourceIdentity(file) != target.sourceIdentity) throw UpdateException(UpdateProblem.SIZE_OR_DIGEST)
        installer.openSession(sessionId).use { session ->
            FileInputStream(file).use { input ->
                if (identity(Os.fstat(input.fd)) != target.sourceIdentity) throw UpdateException(UpdateProblem.SIZE_OR_DIGEST)
                session.openWrite("base.apk", 0, target.bytes).use { output ->
                    UpdateApkBytes.transfer(input, output, target.bytes, target.sha256, progress)
                    if (identity(Os.fstat(input.fd)) != target.sourceIdentity || sourceIdentity(file) != target.sourceIdentity) {
                        throw UpdateException(UpdateProblem.SIZE_OR_DIGEST)
                    }
                    session.fsync(output)
                    progress(1f)
                }
            }
        }
    }

    override fun commit(sessionId: Int, token: String) {
        val result = Intent(context, UpdateInstallResultReceiver::class.java)
            .setAction(RESULT_ACTION).setData(Uri.fromParts(RESULT_SCHEME, token, null))
        val flags = PendingIntent.FLAG_CANCEL_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val sender = PendingIntent.getBroadcast(context, sessionId, result, flags).intentSender
        installer.openSession(sessionId).use { it.commit(sender) }
    }

    override fun abandon(sessionId: Int?): Boolean {
        if (sessionId == null) return true
        if (Looper.myLooper() == Looper.getMainLooper()) return false
        val current = installer.getSessionInfo(sessionId)
        val owned = installer.mySessions.singleOrNull { it.sessionId == sessionId }
        fun matches(info: PackageInstaller.SessionInfo): Boolean =
            info.installerPackageName == context.packageName && info.appPackageName == context.packageName
        if (current != null || owned != null) {
            if (owned == null || !matches(owned) || current != null && !matches(current)) return false
        }
        if (current != null) {
            installer.abandonSession(sessionId)
        }
        return PackageSessionRemoval.await {
            val remaining = installer.getSessionInfo(sessionId)
            val listed = installer.mySessions.filter { it.sessionId == sessionId }
            when {
                remaining != null && !matches(remaining) || listed.size > 1 || listed.any { !matches(it) } -> Observation.UNKNOWN
                remaining == null && listed.isEmpty() -> Observation.ABSENT
                else -> Observation.PRESENT
            }
        }
    }

    override fun abandonOrphanedSelfSessions(): Boolean {
        val existing = installer.mySessions
        if (existing.any { it.appPackageName == null }) return false
        val own = existing.filter { it.appPackageName == context.packageName }
        for (session in own) if (!abandon(session.sessionId)) return false
        return installer.mySessions.none { it.appPackageName == null || it.appPackageName == context.packageName }
    }

    override suspend fun installed(target: UpdateInstallTarget): InstalledUpdateProof {
        val before = installedInfo()
        if (!matchesTarget(before, target)) return InstalledUpdateProof.DIFFERENT
        val app = checkNotNull(before.applicationInfo)
        val file = File(app.sourceDir)
        val pathStat = Os.lstat(file.path)
        if (!OsConstants.S_ISREG(pathStat.st_mode) || pathStat.st_size != target.bytes) return InstalledUpdateProof.DIFFERENT
        val expectedIdentity = identity(pathStat)
        FileInputStream(file).use { input ->
            if (identity(Os.fstat(input.fd)) != expectedIdentity) return unavailable("installed_file_replaced")
            try { UpdateApkBytes.transfer(input, null, target.bytes, target.sha256) }
            catch (failure: UpdateException) {
                if (failure.problem == UpdateProblem.SIZE_OR_DIGEST) return InstalledUpdateProof.DIFFERENT
                throw failure
            }
            if (identity(Os.fstat(input.fd)) != expectedIdentity || identity(Os.lstat(file.path)) != expectedIdentity) {
                return unavailable("installed_file_changed_while_reading")
            }
        }
        val after = installedInfo()
        if (!matchesTarget(after, target) || before.lastUpdateTime != after.lastUpdateTime ||
            before.applicationInfo?.sourceDir != after.applicationInfo?.sourceDir) return unavailable("package_changed_while_reading")
        return InstalledUpdateProof.MATCH
    }

    /** The installer turns this into an interrupted install; the reason is only recorded here. */
    private fun unavailable(reason: String): InstalledUpdateProof {
        ControlsLog.warn("update_install", "step", "verify", "outcome", "unavailable", "reason", reason)
        return InstalledUpdateProof.UNAVAILABLE
    }

    override fun deleteSource(target: UpdateInstallTarget) {
        val file = File(target.sourcePath)
        if (file.exists() && sourceIdentity(file) == target.sourceIdentity && !file.delete()) {
            ControlsLog.warn("update_install", "step", "delete_download", "outcome", "not_deleted")
        }
    }

    private fun matchesTarget(info: PackageInfo, target: UpdateInstallTarget): Boolean {
        val app = info.applicationInfo ?: return false
        return info.packageName == context.packageName && info.packageName == target.packageName &&
            info.versionName == target.version && info.longVersionCode == target.versionCode &&
            app.uid == Process.myUid() && app.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 &&
            info.splitNames.isNullOrEmpty() && app.splitSourceDirs.isNullOrEmpty() && signers(info) == target.signers
    }

    private fun installedInfo(): PackageInfo = packages.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)

    private fun signers(info: PackageInfo): Set<String> = info.signingInfo?.takeUnless { it.hasMultipleSigners() }
        ?.apkContentsSigners?.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet().orEmpty()

    private fun identity(stat: StructStat) = "${stat.st_dev}:${stat.st_ino}:${stat.st_size}:${stat.st_mtime}:${stat.st_mode}"

    companion object {
        const val RESULT_ACTION = "org.openvoyah.controls.action.SELF_UPDATE_RESULT"
        const val RESULT_SCHEME = "ovc-self-update"

        fun callback(context: Context, intent: Intent): UpdateSessionCallback? {
            if (intent.action != RESULT_ACTION || intent.data?.scheme != RESULT_SCHEME) return null
            val token = intent.data?.schemeSpecificPart ?: return null
            if (!token.matches(Regex("[0-9a-f]{32}"))) return null
            val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            if (id < 0) return null
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
            // Every status at or above FAILURE is handled alike; the raw status and the code that opens Android's
            // message tell them apart. The rest of that message quotes paths and package names and is not logged.
            val statusCode = installStatusCode(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            if (status == PackageInstaller.STATUS_SUCCESS || status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                ControlsLog.info("update_session_result", "session", id, "status", status, "status_code", statusCode)
            } else {
                ControlsLog.warn("update_session_result", "session", id, "status", status, "status_code", statusCode)
            }
            val kind = when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> UpdateCallbackKind.APPROVAL
                PackageInstaller.STATUS_SUCCESS -> UpdateCallbackKind.SUCCESS
                PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateCallbackKind.ABORTED
                else -> if (status >= PackageInstaller.STATUS_FAILURE) UpdateCallbackKind.FAILURE else return null
            }
            val approval = if (kind != UpdateCallbackKind.APPROVAL) null else {
                val request = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return null
                val handler = context.packageManager.queryIntentActivities(request,
                    PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY)
                    .firstOrNull { candidate -> candidate.activityInfo?.let {
                        it.enabled && it.exported && it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                    } == true }?.activityInfo ?: return null
                UpdateInstallApproval(id, request.setClassName(handler.packageName, handler.name))
            }
            return UpdateSessionCallback(id, token, kind, approval)
        }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
