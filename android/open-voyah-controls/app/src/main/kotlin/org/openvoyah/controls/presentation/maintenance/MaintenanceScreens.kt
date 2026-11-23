package org.openvoyah.controls.presentation.maintenance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import org.openvoyah.controls.presentation.theme.CockpitReveal
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.CockpitSwitch

internal enum class FirmwareAction {
    WINDOW_SERVICE, WINDOW_STRIP, RUSSIAN, ENGLISH, KAZAKH, DISABLE_TRANSLATION, IMPORT, EXPORT, RESTORE_TRANSLATION, RESTORE_PROFILE,
}

/**
 * The parts of the firmware maintenance, each shown in the Settings section it belongs to: the window service in
 * Windows and screens, the Android translation in Language and input, the account record in System.
 */
internal enum class FirmwarePart { WINDOWS, TRANSLATION, PROFILE }

/**
 * The experimental option that lets Android itself reserve the launcher panel strip. [changeable] is false while
 * the window service it depends on is off and there is nothing to turn off either.
 */
internal data class WindowStripRow(val checked: Boolean, val status: String?, val changeable: Boolean)

/** [translationEnabled] is null until the stored dictionary has been read; [windowStrip] until the agent answered. */
@Composable
internal fun FirmwareScreen(part: FirmwarePart, product: String, windowsSupported: Boolean, translationEnabled: Boolean?, windowStatus: Int?,
                            windowStrip: WindowStripRow?, state: MaintenanceWork.State, select: (FirmwareAction) -> Unit) {
    val enabled = !state.working
    when (part) {
        FirmwarePart.WINDOWS -> CockpitSection(label(R.string.settings_part_app_windows), CockpitIcon.SETTINGS, product) {
            if (!windowsSupported) {
                Text(label(R.string.settings_windows_unsupported), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            windowStatus?.let {
                CockpitChoice(label(R.string.window_hook_title), label(it), enabled) {
                    select(FirmwareAction.WINDOW_SERVICE)
                }
            }
            // The row keeps its last content while it collapses.
            val shownStrip = remember { arrayOfNulls<WindowStripRow>(1) }
            if (windowStrip != null) shownStrip[0] = windowStrip
            CockpitReveal(windowStatus != null && windowStrip != null) {
                shownStrip[0]?.let { row ->
                    Column {
                        CockpitSwitch(label(R.string.window_strip_title), row.checked, enabled && row.changeable) {
                            select(FirmwareAction.WINDOW_STRIP)
                        }
                        CockpitFeedback(row.status)
                    }
                }
            }
            Progress(state)
        }
        FirmwarePart.TRANSLATION ->
            CockpitSection(label(R.string.firmware_translation_heading), CockpitIcon.LANGUAGE, label(R.string.firmware_info)) {
                CockpitFeedback(translationEnabled?.let { label(if (it) R.string.translation_on else R.string.translation_off) })
                // Commands are buttons; rows are kept for the file pickers, which open another screen.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CockpitAction(label(R.string.cockpit_dictionary_russian), { select(FirmwareAction.RUSSIAN) }, enabled = enabled)
                    CockpitAction(label(R.string.cockpit_dictionary_english), { select(FirmwareAction.ENGLISH) }, enabled = enabled)
                    CockpitAction(label(R.string.cockpit_dictionary_kazakh), { select(FirmwareAction.KAZAKH) }, enabled = enabled)
                    CockpitAction(label(R.string.translation_disable), { select(FirmwareAction.DISABLE_TRANSLATION) }, enabled = enabled)
                    CockpitAction(label(R.string.translation_restore), { select(FirmwareAction.RESTORE_TRANSLATION) }, enabled = enabled)
                }
                CockpitChoice(label(R.string.translation_import), enabled = enabled) { select(FirmwareAction.IMPORT) }
                CockpitChoice(label(R.string.translation_export), enabled = enabled) { select(FirmwareAction.EXPORT) }
                Progress(state)
            }
        FirmwarePart.PROFILE ->
            CockpitSection(label(R.string.firmware_recovery_heading), CockpitIcon.LOCK, label(R.string.profile_restore_info)) {
                CockpitAction(label(R.string.profile_restore), { select(FirmwareAction.RESTORE_PROFILE) }, enabled = enabled)
                Progress(state)
            }
    }
}

@Composable
private fun Progress(state: MaintenanceWork.State) {
    state.message?.let { CockpitFeedback(it, state.failed) }
    if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
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
                if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }, second = {
            CockpitSection(label(R.string.cluster_backup_heading), CockpitIcon.DOWNLOAD) {
                // The backup runs as soon as it is tapped, so it is a command; the export row opens the file picker.
                CockpitAction(label(ClusterAction.PREPARE.label), { select(ClusterAction.PREPARE) }, Modifier.fillMaxWidth(), enabled)
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
