package org.openvoyah.controls.platform.apps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.R
import java.security.MessageDigest
import java.util.UUID

/** Stages a single APK in a session owned by Controls. Android presents and enforces confirmation. */
internal class LocalApkInstaller(context: Context) {
    data class Approval(val sessionId: Int, val intent: Intent)

    data class State(
        val message: Int? = null,
        val busy: Boolean = false,
        val interrupted: Boolean = false,
        val approval: Approval? = null,
        val revision: Long = 0,
    )

    private data class Pending(
        val id: Int,
        val token: String,
        val bytes: Long,
        val digest: String?,
        val completedPackage: String?,
    )

    private val context = context.applicationContext
    private val packageInstaller get() = context.packageManager.packageInstaller
    private val preferences = this.context.getSharedPreferences("apk_install_session", Context.MODE_PRIVATE)
    val state = progress.asStateFlow()

    suspend fun recover() = withContext(Dispatchers.IO) {
        val pending = synchronized(lock) {
            val current = active() ?: return@withContext
            if (current.completedPackage == null) {
                if (!progress.value.busy) showInterrupted()
                return@withContext
            }
            if (verifying) return@withContext
            verifying = true
            update(State(message = R.string.apps_install_verifying, busy = true))
            current
        }
        try {
            val verified = try {
                pending.bytes in 1..MAX_APK_BYTES && pending.digest != null &&
                    AppInstallProvenance(context).recordSuccessfulSession(
                        requireNotNull(pending.completedPackage), pending.bytes, pending.digest,
                    )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            synchronized(lock) {
                if (active() == pending) complete(if (verified) R.string.apk_installed else R.string.apps_install_unverified)
            }
        } finally {
            synchronized(lock) { verifying = false }
        }
    }

    suspend fun stage(uri: Uri) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (progress.value.busy || active() != null) return@withContext
            update(State(message = R.string.apps_install_preparing, busy = true))
        }
        var sessionId: Int? = null
        var commitAttempted = false
        try {
            check(uri.scheme == "content" && context.packageManager.canRequestPackageInstalls())
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            val id = packageInstaller.createSession(params)
            sessionId = id
            val token = UUID.randomUUID().toString()
            synchronized(lock) {
                check(preferences.edit().putInt("session", id).putString("token", token).commit())
            }
            packageInstaller.openSession(id).use { session ->
                val digest = MessageDigest.getInstance("SHA-256")
                var length = 0L
                val input = checkNotNull(context.contentResolver.openInputStream(uri))
                input.use {
                    session.openWrite("base.apk", 0, -1).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            length += count
                            check(length <= MAX_APK_BYTES)
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        check(length > 0)
                        session.fsync(output)
                    }
                }
                ensureActive()
                val result = Intent(context, ApkInstallResultReceiver::class.java)
                    .setAction(RESULT_ACTION).setData(Uri.fromParts(RESULT_SCHEME, token, null))
                val flags = PendingIntent.FLAG_CANCEL_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val sender = PendingIntent.getBroadcast(context, id, result, flags).intentSender
                synchronized(lock) {
                    check(preferences.edit().putLong("bytes", length).putString("digest", digest.digest().toHexString()).commit())
                    update(State(message = R.string.apps_install_working, busy = true))
                }
                commitAttempted = true
                session.commit(sender)
            }
        } catch (cancelled: CancellationException) {
            if (!commitAttempted) discardBeforeCommit(sessionId, R.string.apk_install_ended)
            throw cancelled
        } catch (_: Exception) {
            if (!commitAttempted) discardBeforeCommit(sessionId, R.string.apps_install_failed)
            else synchronized(lock) { if (active()?.id == sessionId && progress.value.approval == null) showInterrupted() }
        }
    }

    fun takeApproval(approval: Approval): Intent? = synchronized(lock) {
        if (progress.value.approval !== approval) return@synchronized null
        update(State(message = R.string.apps_install_working, busy = true))
        approval.intent
    }

    fun approvalUnavailable(sessionId: Int) = synchronized(lock) {
        if (active()?.id == sessionId) showInterrupted()
    }

    suspend fun discardInterrupted() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val pending = active() ?: return@synchronized
            if (!progress.value.interrupted) return@synchronized
            if (abandon(pending.id) && clearActive()) {
                update(State(message = R.string.apk_install_ended))
            } else update(State(message = R.string.apps_install_discard_failed, interrupted = true))
        }
    }

    /** Persist Android's result before opening the installed APK on a separate coroutine. */
    fun handleResult(result: Intent): Boolean = synchronized(lock) {
        val pending = active() ?: return@synchronized false
        if (result.action != RESULT_ACTION || result.data?.scheme != RESULT_SCHEME ||
            result.data?.schemeSpecificPart != pending.token ||
            result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != pending.id) return@synchronized false
        if (pending.completedPackage != null) return@synchronized false
        when (result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (preferences.getBoolean("approval_received", false)) return@synchronized false
                val approval = IntentCompat.getParcelableExtra(result, Intent.EXTRA_INTENT, Intent::class.java)
                if (approval == null || !preferences.edit().putBoolean("approval_received", true).commit()) showInterrupted()
                else update(State(message = R.string.apps_install_confirm, busy = true, approval = Approval(pending.id, approval)))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                val packageName = result.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                if (!packageName.isNullOrEmpty() && pending.digest != null &&
                    preferences.edit().putString("completed_package", packageName).commit()) {
                    update(State(message = R.string.apps_install_verifying, busy = true))
                    return@synchronized true
                }
                complete(R.string.apps_install_unverified)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> complete(R.string.apk_install_ended)
            else -> complete(R.string.apps_install_failed)
        }
        false
    }

    private fun discardBeforeCommit(sessionId: Int?, message: Int) = synchronized(lock) {
        if (sessionId == null || (abandon(sessionId) && clearActive())) update(State(message = message))
        else showInterrupted()
    }

    private fun active(): Pending? {
        val id = preferences.getInt("session", -1)
        val token = preferences.getString("token", null)
        return if (id >= 0 && token != null) {
            Pending(id, token, preferences.getLong("bytes", 0), preferences.getString("digest", null),
                preferences.getString("completed_package", null))
        } else null
    }

    private fun abandon(id: Int): Boolean = try {
        if (packageInstaller.getSessionInfo(id) != null) packageInstaller.abandonSession(id)
        true
    } catch (_: RuntimeException) {
        false
    }

    private fun clearActive() = preferences.edit().remove("session").remove("token")
        .remove("bytes").remove("digest").remove("approval_received").remove("completed_package").commit()

    private fun complete(message: Int) {
        if (clearActive()) update(State(message = message))
        else showInterrupted()
    }

    private fun showInterrupted() = update(State(message = R.string.apps_install_interrupted, interrupted = true))

    private fun update(value: State) {
        progress.value = value.copy(revision = progress.value.revision + 1)
    }

    private companion object {
        const val RESULT_ACTION = "org.openvoyah.controls.action.APK_INSTALL_RESULT"
        const val RESULT_SCHEME = "ovc-apk-install"
        const val MAX_APK_BYTES = 2_147_483_647L
        val lock = Any()
        val progress = MutableStateFlow(State())
        var verifying = false
    }
}

class ApkInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val verifier = try {
                LocalApkInstaller(context).takeIf { it.handleResult(intent) }
            } finally {
                result.finish()
            }
            verifier?.recover()
        }
    }
}
