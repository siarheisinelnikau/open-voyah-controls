package org.openvoyah.controls.presentation.updates

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.updates.UpdateCheckPhase
import org.openvoyah.controls.data.updates.UpdateProblem
import org.openvoyah.controls.platform.updates.UpdateInstallPhase
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitColumns
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitMotion
import org.openvoyah.controls.presentation.theme.CockpitReveal
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.theme.LocalCockpitMotion

@Composable
internal fun UpdatesScreen(state: UpdatesState, back: () -> Unit, check: () -> Unit, startup: (Boolean) -> Unit,
                           install: () -> Unit, cancel: () -> Unit, discard: () -> Unit, allowInstallation: () -> Unit) {
    CockpitDetail(text(R.string.updates_title), text(R.string.updates_installed_version).format(state.installedVersion), back) {
        CockpitColumns(first = {
            CockpitSection(text(R.string.updates_release_heading), CockpitIcon.DOWNLOAD) {
                val candidate = state.check.candidate
                if (candidate != null) {
                    Text(text(R.string.updates_available_version).format(candidate.version), style = MaterialTheme.typography.titleLarge)
                }
                CockpitFeedback(checkMessage(state), state.check.phase == UpdateCheckPhase.FAILED && !state.check.automatic)
                // Indeterminate, so it animates without end: compose it only while the check is actually running.
                if (state.check.phase == UpdateCheckPhase.CHECKING) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                CockpitAction(text(R.string.updates_check), check, Modifier.fillMaxWidth(), !state.busy,
                    primary = candidate == null)
                if (candidate != null) {
                    CockpitAction(text(R.string.updates_download_install), install, Modifier.fillMaxWidth(), state.canInstall, primary = true)
                    if (!state.installAvailable) CockpitFeedback(text(R.string.updates_install_unavailable))
                    else state.localInstallProblem?.let { CockpitFeedback(problemText(it), error = true) }
                }
            }
            val status = workMessage(state)
            val showInstallResult = state.work == UpdateWork.IDLE || state.work == UpdateWork.PREPARING
            val problem = state.problem ?: state.installation.problem.takeIf { showInstallResult }
            val message = state.message ?: state.installation.message.takeIf { showInstallResult }
            // Null exactly when nothing is left to report, so the section collapses without showing a stale fallback text.
            val feedback = message ?: problem?.let(::problemText) ?: status ?: text(R.string.updates_cancelled).takeIf { state.cancelled }
            CockpitReveal(feedback != null) {
                CockpitSection(text(R.string.updates_status_heading), CockpitIcon.SETTINGS) {
                    CockpitFeedback(feedback, error = problem != null)
                    val progress = state.download?.let { it.bytes.toFloat() / it.total } ?: state.installation.progress
                    if (progress != null && state.busy) UpdateProgress(progress.coerceIn(0f, 1f))
                    if (state.canCancelDownload) CockpitAction(text(R.string.updates_cancel_download), cancel, Modifier.fillMaxWidth())
                    if (state.installation.phase == UpdateInstallPhase.INTERRUPTED) {
                        CockpitAction(text(R.string.updates_cancel_pending), discard, Modifier.fillMaxWidth())
                    }
                    if (problem == UpdateProblem.INSTALL_PERMISSION) {
                        CockpitAction(text(R.string.updates_allow_installation), allowInstallation, Modifier.fillMaxWidth())
                    }
                }
            }
        }, second = {
            CockpitSection(text(R.string.updates_startup_heading), CockpitIcon.SETTINGS, text(R.string.updates_startup_description)) {
                CockpitSwitch(R.string.updates_startup_toggle, state.checkOnStartup, !state.preferenceSaving, startup)
                Text(text(R.string.updates_user_action), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        })
    }
}

/** Smooths the jumps between progress reports. A bar shown again for new work starts at its first value instead of sweeping back. */
@Composable
private fun UpdateProgress(fraction: Float) {
    val animate = LocalCockpitMotion.current.enabled
    val shown by animateFloatAsState(fraction, if (animate) CockpitMotion.change<Float>() else snap<Float>(), label = "update progress")
    LinearProgressIndicator(progress = { shown }, modifier = Modifier.fillMaxWidth())
}

private fun checkMessage(state: UpdatesState): String? = when (state.check.phase) {
    UpdateCheckPhase.IDLE -> text(R.string.updates_check_description)
    UpdateCheckPhase.CHECKING -> text(R.string.updates_checking)
    UpdateCheckPhase.COMPLETE -> text(if (state.check.candidate == null) R.string.updates_no_update else R.string.updates_verify_before_install)
    UpdateCheckPhase.FAILED -> if (state.check.automatic) text(R.string.updates_check_description)
        else problemText(state.check.problem ?: UpdateProblem.NETWORK)
}

private fun workMessage(state: UpdatesState): String? = when (state.work) {
    UpdateWork.DOWNLOADING -> text(R.string.updates_downloading).format(state.download?.let { it.bytes * 100 / it.total } ?: 0)
    UpdateWork.VERIFYING -> text(R.string.updates_verifying)
    UpdateWork.PREPARING -> text(R.string.updates_preparing)
    UpdateWork.IDLE -> when (state.installation.phase) {
        UpdateInstallPhase.IDLE -> null
        UpdateInstallPhase.PREPARING -> text(R.string.updates_preparing)
        UpdateInstallPhase.STAGING -> text(R.string.updates_staging)
        UpdateInstallPhase.AWAITING_APPROVAL -> text(R.string.updates_approval)
        UpdateInstallPhase.INSTALLING -> text(R.string.updates_installing)
        UpdateInstallPhase.VERIFYING -> text(R.string.updates_verifying_installed)
        UpdateInstallPhase.INSTALLED -> text(R.string.updates_installed)
        UpdateInstallPhase.INTERRUPTED -> text(R.string.updates_interrupted)
        UpdateInstallPhase.FAILED -> problemText(state.installation.problem ?: UpdateProblem.INSTALLATION)
        UpdateInstallPhase.CANCELLED -> text(R.string.updates_cancelled)
    }
}

internal fun problemText(problem: UpdateProblem): String = text(when (problem) {
    UpdateProblem.NETWORK -> R.string.updates_error_network
    UpdateProblem.RATE_LIMIT -> R.string.updates_error_rate_limit
    UpdateProblem.CATALOG -> R.string.updates_error_catalog
    UpdateProblem.DOWNLOAD -> R.string.updates_error_download
    UpdateProblem.UNTRUSTED_DOWNLOAD -> R.string.updates_error_address
    UpdateProblem.SIZE_OR_DIGEST -> R.string.updates_error_digest
    UpdateProblem.STORAGE -> R.string.updates_error_storage
    UpdateProblem.RELEASE_SIGNER -> R.string.updates_error_signer
    UpdateProblem.PACKAGE -> R.string.updates_error_package
    UpdateProblem.DEBUGGABLE -> R.string.updates_error_debug
    UpdateProblem.SPLIT -> R.string.updates_error_split
    UpdateProblem.VERSION, UpdateProblem.VERSION_CODE -> R.string.updates_error_version
    UpdateProblem.MIN_SDK -> R.string.updates_error_android
    UpdateProblem.ABI -> R.string.updates_error_abi
    UpdateProblem.RUNTIME -> R.string.updates_error_runtime
    UpdateProblem.DEVICE_PREPARATION -> R.string.updates_error_preparation
    UpdateProblem.INSTALL_PERMISSION -> R.string.updates_error_permission
    UpdateProblem.INSTALLATION -> R.string.updates_error_installation
    UpdateProblem.PREFERENCE -> R.string.updates_error_preference
})

private fun text(id: Int): String = UiText.get(id)
