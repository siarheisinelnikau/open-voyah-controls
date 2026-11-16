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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openvoyah.controls.R
import java.util.UUID

/** Stages a single APK in a session owned by Controls. Android presents and enforces confirmation. */
internal class LocalApkInstaller(
    context: Context,
    private val stager: LocalApkStager = LocalApkStager(context),
    private val sessions: LocalApkSessions = AndroidLocalApkSessions(context),
) {
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
        val packageName: String?,
    )

    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("apk_install_session", Context.MODE_PRIVATE)
    val state = progress.asStateFlow()

    suspend fun recover() = withContext(Dispatchers.IO) {
        try { staging.withLock {
            stager.cleanupInterrupted()
            if (preferences.contains("creating_package")) {
                val recovered = discardCreation()
                synchronized(lock) {
                    if (recovered) update(State(message = R.string.apk_install_ended))
                    else update(State(message = R.string.apps_install_discard_failed, interrupted = true))
                }
                return@withContext
            }
        } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            synchronized(lock) {
                update(progress.value.copy(message = R.string.apps_install_cleanup_failed,
                    interrupted = progress.value.interrupted || active() != null || preferences.contains("creating_package")))
            }
            return@withContext
        }
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
                pending.bytes in 1..LocalApkStager.MAX_APK_BYTES && pending.digest != null &&
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
        staging.withLock {
            synchronized(lock) {
                if (progress.value.busy || active() != null) return@withContext
                if (preferences.contains("creating_package")) { showInterrupted(); return@withContext }
                update(State(message = R.string.apps_install_preparing, busy = true))
            }
            var sessionId: Int? = null
            var staged: StagedLocalApk? = null
            var commitAttempted = false
            try {
                if (uri.scheme != "content") throw LocalApkStagingFailure(LocalApkRefusal.INVALID_APK)
                check(sessions.canInstall())
                stager.cleanupInterrupted()
                val inspected = stager.stage(uri)
                staged = inspected
                ensureActive()
                val token = UUID.randomUUID().toString()
                synchronized(lock) {
                    // This foreign target is durable before Android can allocate a session. No
                    // commit can occur until the creation marker is replaced by the returned ID.
                    check(preferences.edit().putString("creating_package", inspected.packageName)
                        .putString("token", token).commit())
                }
                val id = sessions.create(inspected.packageName, inspected.bytes)
                sessionId = id
                synchronized(lock) {
                    check(preferences.edit().putInt("session", id).putString("package", inspected.packageName)
                        .remove("creating_package").commit())
                }
                sessions.open(id).use { session ->
                    session.openWrite(inspected.bytes).use { output ->
                        stager.copy(inspected, output)
                        session.fsync(output)
                    }
                    ensureActive()
                    val result = Intent(context, ApkInstallResultReceiver::class.java)
                        .setAction(RESULT_ACTION).setData(Uri.fromParts(RESULT_SCHEME, token, null))
                    val flags = PendingIntent.FLAG_CANCEL_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                    val sender = PendingIntent.getBroadcast(context, id, result, flags).intentSender
                    synchronized(lock) {
                        check(preferences.edit().putLong("bytes", inspected.bytes).putString("digest", inspected.digest).commit())
                        update(State(message = R.string.apps_install_working, busy = true))
                    }
                    commitAttempted = true
                    session.commit(sender)
                }
            } catch (cancelled: CancellationException) {
                if (!commitAttempted) discardBeforeCommit(sessionId, R.string.apk_install_ended)
                throw cancelled
            } catch (failure: Exception) {
                val message = when ((failure as? LocalApkStagingFailure)?.refusal) {
                    LocalApkRefusal.OWN_PACKAGE -> R.string.apps_install_self_update
                    LocalApkRefusal.INVALID_APK -> R.string.apps_install_invalid_apk
                    LocalApkRefusal.CHANGED -> R.string.apps_install_changed_apk
                    null -> R.string.apps_install_failed
                }
                if (!commitAttempted) discardBeforeCommit(sessionId, message)
                else synchronized(lock) { if (active()?.id == sessionId && progress.value.approval == null) showInterrupted() }
            } finally {
                withContext(NonCancellable) {
                    try { staged?.let { stager.discard(it) } }
                    catch (_: Exception) { synchronized(lock) {
                        update(progress.value.copy(message = R.string.apps_install_cleanup_failed))
                    } }
                }
            }
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
        staging.withLock {
            try { stager.cleanupInterrupted() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                synchronized(lock) { update(State(message = R.string.apps_install_cleanup_failed, interrupted = true)) }
                return@withLock
            }
            synchronized(lock) {
                if (preferences.contains("creating_package")) {
                    if (discardCreation()) update(State(message = R.string.apk_install_ended))
                    else update(State(message = R.string.apps_install_discard_failed, interrupted = true))
                    return@synchronized
                }
                val pending = active() ?: return@synchronized
                if (!progress.value.interrupted) return@synchronized
                if (abandon(pending.id, pending.packageName) && clearActive()) {
                    update(State(message = R.string.apk_install_ended))
                } else update(State(message = R.string.apps_install_discard_failed, interrupted = true))
            }
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
        val discarded = if (preferences.contains("creating_package")) discardCreation()
            else sessionId == null || (abandon(sessionId, active()?.packageName) && clearActive())
        if (discarded) update(State(message = message))
        else showInterrupted()
    }

    /** A creation marker proves that our Android commit was never reached. Never infer success. */
    private fun discardCreation(): Boolean {
        return try {
            val target = preferences.getString("creating_package", null)
            if (target.isNullOrBlank() || target == context.packageName ||
                preferences.getString("token", null).isNullOrBlank()) return false
            fun matching(): List<LocalApkSessionInfo> {
                val current = sessions.current()
                check(current.size <= LocalApkSessions.MAX_SESSION_SCAN)
                // Unknown metadata cannot establish that a freshly allocated session is absent.
                check(current.none { it.packageName.isNullOrBlank() })
                return current.filter { it.packageName == target }
            }
            val current = matching()
            if (current.any { it.installer != context.packageName || it.sealed }) return false
            for (session in current) if (!abandon(session.id, target)) return false
            matching().isEmpty() && clearActive()
        } catch (_: Exception) { false }
    }

    private fun active(): Pending? {
        val id = preferences.getInt("session", -1)
        val token = preferences.getString("token", null)
        return if (id >= 0 && token != null) {
            Pending(id, token, preferences.getLong("bytes", 0), preferences.getString("digest", null),
                preferences.getString("completed_package", null), preferences.getString("package", null))
        } else null
    }

    private fun abandon(id: Int, expectedPackage: String?): Boolean = try {
        sessions.abandon(id, expectedPackage)
    } catch (_: RuntimeException) {
        false
    }

    private fun clearActive(): Boolean {
        val before = preferences.all
        val cleared = preferences.edit().remove("session").remove("token").remove("package")
            .remove("creating_package").remove("bytes").remove("digest").remove("approval_received")
            .remove("completed_package").commit()
        if (!cleared) {
            // Android updates the in-memory map even when commit reports a disk failure. Retain
            // the receipt in memory as well so reopening/discarding can retry after storage recovers.
            val restore = preferences.edit()
            for ((key, value) in before) when (value) {
                is String -> restore.putString(key, value)
                is Int -> restore.putInt(key, value)
                is Long -> restore.putLong(key, value)
                is Boolean -> restore.putBoolean(key, value)
            }
            restore.commit()
        }
        return cleared
    }

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
        val lock = Any()
        val staging = Mutex()
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
