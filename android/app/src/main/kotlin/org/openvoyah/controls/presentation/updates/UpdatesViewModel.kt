package org.openvoyah.controls.presentation.updates

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.data.updates.DownloadProgress
import org.openvoyah.controls.data.updates.OfficialUpdateRepository
import org.openvoyah.controls.data.updates.UpdateCheck
import org.openvoyah.controls.data.updates.UpdateCheckPhase
import org.openvoyah.controls.data.updates.UpdateException
import org.openvoyah.controls.data.updates.UpdatePreferences
import org.openvoyah.controls.data.updates.UpdateProblem
import org.openvoyah.controls.platform.updates.PreparedUpdateInstall
import org.openvoyah.controls.platform.updates.UpdateInstallApproval
import org.openvoyah.controls.platform.updates.UpdateInstallPhase
import org.openvoyah.controls.platform.updates.UpdateInstallPort
import org.openvoyah.controls.platform.updates.UpdateInstallState
import org.openvoyah.controls.platform.updates.UpdatePreparation
import org.openvoyah.controls.platform.updates.UpdateVerifier
import java.io.File

internal enum class UpdateWork { IDLE, DOWNLOADING, VERIFYING, PREPARING }

internal data class UpdatesState(
    val installedVersion: String = BuildConfig.VERSION_NAME,
    val checkOnStartup: Boolean,
    val preferenceSaving: Boolean = false,
    val check: UpdateCheck = UpdateCheck(),
    val work: UpdateWork = UpdateWork.IDLE,
    val download: DownloadProgress? = null,
    val installation: UpdateInstallState = UpdateInstallState(),
    val installAvailable: Boolean = false,
    val localInstallProblem: UpdateProblem? = null,
    val problem: UpdateProblem? = null,
    val message: String? = null,
    val cancelled: Boolean = false,
) {
    val busy: Boolean get() = work != UpdateWork.IDLE || check.phase == UpdateCheckPhase.CHECKING ||
        installation.phase in ACTIVE_INSTALL_PHASES
    val canInstall: Boolean get() = !busy && check.candidate != null && installAvailable && localInstallProblem == null &&
        installation.phase != UpdateInstallPhase.INTERRUPTED
    val canCancelDownload: Boolean get() = work == UpdateWork.DOWNLOADING || work == UpdateWork.VERIFYING

    private companion object {
        val ACTIVE_INSTALL_PHASES = setOf(UpdateInstallPhase.PREPARING, UpdateInstallPhase.STAGING,
            UpdateInstallPhase.AWAITING_APPROVAL, UpdateInstallPhase.INSTALLING, UpdateInstallPhase.VERIFYING)
    }
}

/** The screen can request an install only through a real, positively verified preparation capability. */
internal class UpdatesViewModel(
    private val repository: OfficialUpdateRepository,
    private val preferences: UpdatePreferences,
    private val verifier: UpdateVerifier,
    private val installer: UpdateInstallPort? = null,
) : ViewModel() {
    private val mutable = MutableStateFlow(UpdatesState(checkOnStartup = preferences.checkOnStartup(),
        check = repository.checks.value, installAvailable = installer != null,
        localInstallProblem = verifier.installationProblem, installation = installer?.state?.value ?: UpdateInstallState()))
    val state = mutable.asStateFlow()
    private var downloadJob: Job? = null
    private var recoveryJob: Job? = null

    init {
        viewModelScope.launch { repository.checks.collect { check -> mutable.update { it.copy(check = check) } } }
        if (installer != null) viewModelScope.launch {
            installer.state.collect { installation -> mutable.update { it.copy(installation = installation) } }
        }
    }

    fun check() {
        if (mutable.value.busy) return
        mutable.update { it.copy(problem = null, message = null, cancelled = false) }
        viewModelScope.launch { repository.check() }
    }

    fun checkOnStartup(enabled: Boolean) {
        val before = mutable.value
        if (before.preferenceSaving || !mutable.compareAndSet(before, before.copy(preferenceSaving = true))) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { preferences.checkOnStartup(enabled) }
                mutable.update { it.copy(checkOnStartup = enabled, preferenceSaving = false, problem = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(preferenceSaving = false, problem = UpdateProblem.PREFERENCE) }
            }
        }
    }

    fun downloadAndInstall() {
        val before = mutable.value
        val candidate = before.check.candidate ?: return
        val port = installer
        if (port == null || verifier.installationProblem != null) {
            mutable.update { it.copy(problem = verifier.installationProblem ?: UpdateProblem.DEVICE_PREPARATION) }
            return
        }
        if (!before.canInstall || !mutable.compareAndSet(before, before.copy(work = UpdateWork.DOWNLOADING,
                problem = null, message = null, cancelled = false, download = DownloadProgress(0, candidate.bytes)))) return
        downloadJob = viewModelScope.launch {
            var file: File? = null
            var prepared: PreparedUpdateInstall? = null
            var commitAttempted = false
            try {
                val downloaded = repository.download(candidate) { progress -> mutable.update { it.copy(download = progress) } }
                file = downloaded
                mutable.update { it.copy(work = UpdateWork.VERIFYING) }
                val verified = verifier.verify(candidate, downloaded)
                mutable.update { it.copy(work = UpdateWork.PREPARING) }
                when (val preparation = port.prepare(verified)) {
                    is UpdatePreparation.Blocked -> {
                        mutable.update { it.copy(problem = preparation.problem, message = preparation.message) }
                        return@launch
                    }
                    is UpdatePreparation.Ready -> prepared = preparation.prepared
                }
                ensureActive()
                // After this boundary, only the durable installer journal can resolve cancellation or process death.
                commitAttempted = true
                port.commit(checkNotNull(prepared))
            } catch (cancelled: CancellationException) {
                if (!commitAttempted) mutable.update { it.copy(cancelled = true) }
                throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(problem = if (error is UpdateException) error.problem else UpdateProblem.INSTALLATION) }
            } finally {
                // Keep the whole cleanup together: returning to a cancelled caller between IO calls
                // can skip the remaining file cleanup and leave a live screen permanently busy.
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        if (!commitAttempted) {
                            val token = prepared
                            if (token != null) {
                                try { port.cancel(token) }
                                catch (_: Exception) { mutable.update { it.copy(problem = UpdateProblem.DEVICE_PREPARATION) } }
                            }
                            try {
                                val source = file
                                if (source != null && source.exists() && !source.delete()) {
                                    mutable.update { it.copy(problem = it.problem ?: UpdateProblem.STORAGE) }
                                }
                            } catch (_: Exception) { mutable.update { it.copy(problem = it.problem ?: UpdateProblem.STORAGE) } }
                        }
                    } finally {
                        mutable.update { it.copy(work = UpdateWork.IDLE, download = null) }
                    }
                }
            }
        }
    }

    fun cancelDownload() {
        if (mutable.value.canCancelDownload) downloadJob?.cancel()
    }

    fun recover() {
        val port = installer ?: return
        if (recoveryJob?.isActive == true) return
        recoveryJob = viewModelScope.launch {
            try { port.recover() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(problem = UpdateProblem.INSTALLATION) } }
        }
    }

    fun discardInterrupted() {
        val port = installer ?: return
        if (mutable.value.installation.phase != UpdateInstallPhase.INTERRUPTED || recoveryJob?.isActive == true) return
        recoveryJob = viewModelScope.launch {
            try { port.discardInterrupted() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(problem = UpdateProblem.DEVICE_PREPARATION) } }
        }
    }

    fun takeApproval(approval: UpdateInstallApproval): Intent? = installer?.takeApproval(approval)
    fun approvalUnavailable(sessionId: Int) { installer?.approvalUnavailable(sessionId) }
    fun installationSettingsUnavailable() { mutable.update { it.copy(problem = UpdateProblem.INSTALL_PERMISSION) } }
}
