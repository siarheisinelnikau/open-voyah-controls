package org.openvoyah.controls.presentation.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
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
import org.openvoyah.controls.presentation.theme.CockpitSection

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
            if (status != null || problem != null || message != null || state.cancelled) {
                CockpitSection(text(R.string.updates_status_heading), CockpitIcon.SETTINGS) {
                    CockpitFeedback(message ?: problem?.let(::problemText) ?: status
                        ?: text(R.string.updates_cancelled), error = problem != null)
                    val progress = state.download?.let { it.bytes.toFloat() / it.total } ?: state.installation.progress
                    if (progress != null && state.busy) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth())
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
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .toggleable(state.checkOnStartup, enabled = !state.preferenceSaving, role = Role.Switch, onValueChange = startup),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text(R.string.updates_startup_toggle), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(state.checkOnStartup, onCheckedChange = null, enabled = !state.preferenceSaving)
                }
                Text(text(R.string.updates_user_action), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        })
    }
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
