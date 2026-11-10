package org.openvoyah.controls.presentation.maintenance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitColumns
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitSection

internal enum class FirmwareAction {
    CLUSTER, WINDOW_SERVICE, RUSSIAN, ENGLISH, DISABLE_TRANSLATION, IMPORT, EXPORT, RESTORE_TRANSLATION, RESTORE_PROFILE,
}

@Composable
internal fun FirmwareScreen(product: String, translationEnabled: Boolean, windowStatus: Int?,
                            state: MaintenanceWork.State, back: () -> Unit, select: (FirmwareAction) -> Unit) {
    val enabled = !state.working
    CockpitDetail(label(R.string.firmware_settings), label(R.string.firmware_screen_description), back) {
        CockpitColumns(first = {
            CockpitSection(label(R.string.firmware_device_heading), CockpitIcon.SETTINGS, product) {
                windowStatus?.let {
                    CockpitChoice(label(R.string.window_hook_title), label(it), enabled) {
                        select(FirmwareAction.WINDOW_SERVICE)
                    }
                }
                CockpitChoice(label(R.string.cluster_title), enabled = enabled) { select(FirmwareAction.CLUSTER) }
                state.message?.let { CockpitFeedback(it, state.failed) }
            }
            CockpitSection(label(R.string.firmware_recovery_heading), CockpitIcon.LOCK, label(R.string.profile_restore_info)) {
                CockpitAction(label(R.string.profile_restore), { select(FirmwareAction.RESTORE_PROFILE) }, enabled = enabled)
            }
        }, second = {
            CockpitSection(label(R.string.firmware_translation_heading), CockpitIcon.LANGUAGE, label(R.string.firmware_info)) {
                CockpitFeedback(label(if (translationEnabled) R.string.translation_on else R.string.translation_off))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CockpitAction(label(R.string.cockpit_dictionary_russian), { select(FirmwareAction.RUSSIAN) }, enabled = enabled)
                    CockpitAction(label(R.string.cockpit_dictionary_english), { select(FirmwareAction.ENGLISH) }, enabled = enabled)
                    CockpitAction(label(R.string.translation_disable), { select(FirmwareAction.DISABLE_TRANSLATION) }, enabled = enabled)
                }
                CockpitChoice(label(R.string.translation_import), enabled = enabled) { select(FirmwareAction.IMPORT) }
                CockpitChoice(label(R.string.translation_export), enabled = enabled) { select(FirmwareAction.EXPORT) }
                CockpitChoice(label(R.string.translation_restore), enabled = enabled) { select(FirmwareAction.RESTORE_TRANSLATION) }
            }
        })
    }
}

internal enum class ClusterAction(val label: Int) {
    INSPECT(R.string.cluster_inspect), IMPORT(R.string.cluster_import), PREPARE(R.string.cluster_prepare),
    EXPORT(R.string.cluster_export), APPLY(R.string.cluster_apply), RESTORE(R.string.cluster_restore),
}

@Composable
internal fun ClusterScreen(state: MaintenanceWork.State, back: () -> Unit, select: (ClusterAction) -> Unit) {
    val enabled = !state.working
    CockpitDetail(label(R.string.cluster_title), label(R.string.cluster_info), back) {
        CockpitColumns(first = {
            CockpitSection(label(R.string.cluster_package_heading), CockpitIcon.DISPLAY) {
                CockpitAction(label(ClusterAction.INSPECT.label), { select(ClusterAction.INSPECT) }, Modifier.fillMaxWidth(), enabled, primary = true)
                CockpitChoice(label(ClusterAction.IMPORT.label), enabled = enabled) { select(ClusterAction.IMPORT) }
            }
            CockpitSection(label(R.string.cluster_status_heading), CockpitIcon.SETTINGS) {
                CockpitFeedback(state.message ?: label(R.string.cluster_initial), state.failed)
            }
        }, second = {
            CockpitSection(label(R.string.cluster_backup_heading), CockpitIcon.DOWNLOAD) {
                CockpitChoice(label(ClusterAction.PREPARE.label), enabled = enabled) { select(ClusterAction.PREPARE) }
                CockpitChoice(label(ClusterAction.EXPORT.label), enabled = enabled) { select(ClusterAction.EXPORT) }
            }
            CockpitSection(label(R.string.cluster_install_heading), CockpitIcon.LOCK, label(R.string.cluster_install_description)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CockpitAction(label(ClusterAction.APPLY.label), { select(ClusterAction.APPLY) },
                        Modifier.widthIn(min = 210.dp), enabled)
                    CockpitAction(label(ClusterAction.RESTORE.label), { select(ClusterAction.RESTORE) },
                        Modifier.widthIn(min = 210.dp), enabled)
                }
            }
        })
    }
}

private fun label(id: Int): String = UiText.get(id)
