package org.openvoyah.controls.presentation.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.Locale
import org.openvoyah.controls.AppRestartActivity
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.frida.FridaSystemRestarts
import org.openvoyah.controls.data.frida.SystemRestartScripts
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitDialogs
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitSection

/** The last section of Settings: the build identity and the three restart actions. */
@Composable
internal fun SystemMaintenanceSection() {
    val activity = checkNotNull(LocalActivity.current as? ComponentActivity) { "System settings require a ComponentActivity host" }
    val model = remember(activity) {
        val app = activity.applicationContext
        ViewModelProvider(activity, viewModelFactory { initializer { SystemMaintenanceViewModel(FridaSystemRestarts(app)) } })[
            SystemMaintenanceViewModel::class.java]
    }
    val owner = LocalLifecycleOwner.current
    val state by model.state.collectAsStateWithLifecycle(lifecycleOwner = owner)
    val dialogs = remember(activity) { CockpitDialogs(activity) }
    DisposableEffect(dialogs) { onDispose { dialogs.dismiss() } }
    val version = remember(activity) { AppVersionInfo.current(activity) }
    var copied by remember { mutableStateOf(false) }
    fun active(): Boolean = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        val (title, body) = describe(notice)
        dialogs.message(title, body, text(R.string.ui_83), onDismiss = Runnable(model::acknowledge))
    }

    SystemMaintenance(
        version = version.lines(text(R.string.system_version_build), text(R.string.system_version_commit),
            text(R.string.system_version_installed), Locale.forLanguageTag(UiText.language())),
        working = state.working,
        copied = copied,
        copy = { lines ->
            if (active()) copied = copyVersion(activity, lines.joinToString("\n"))
        },
        restartApp = {
            if (active()) dialogs.message(text(R.string.system_restart_app_title), text(R.string.system_restart_app_message),
                text(R.string.system_restart_confirm), Runnable { if (active()) AppRestartActivity.start(activity) }, text(R.string.ui_89))
        },
        restartMedia = {
            if (active()) dialogs.message(text(R.string.system_restart_media_title), text(R.string.system_restart_media_message),
                text(R.string.system_restart_confirm), Runnable { if (active()) model.restartMultimedia() }, text(R.string.ui_89))
        },
        reboot = {
            if (active()) dialogs.message(text(R.string.system_reboot_title), text(R.string.system_reboot_message),
                text(R.string.system_reboot_confirm), Runnable { if (active()) model.rebootSystem() }, text(R.string.ui_89))
        },
    )
}

/** Stateless body, so Compose tests render it without a bridge or an Activity-scoped model. */
@Composable
internal fun SystemMaintenance(
    version: List<String>,
    working: SystemOperation?,
    copied: Boolean,
    copy: (List<String>) -> Unit,
    restartApp: () -> Unit,
    restartMedia: () -> Unit,
    reboot: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    CockpitSection(text(R.string.system_section_title), CockpitIcon.SYSTEM) {
        SelectionContainer(Modifier.testTag("system_version")) {
            Text(version.joinToString("\n"), style = MaterialTheme.typography.bodyMedium)
        }
        CockpitAction(text(if (copied) R.string.system_version_copied else R.string.system_version_copy), { copy(version) })

        val idle = working == null
        CockpitAction(text(R.string.system_restart_app), restartApp, Modifier.fillMaxWidth(), enabled = idle)
        Text(text(R.string.system_restart_app_hint), color = muted)
        CockpitAction(text(R.string.system_restart_media), restartMedia, Modifier.fillMaxWidth(), enabled = idle)
        Text(text(R.string.system_restart_media_hint), color = muted)
        CockpitAction(text(R.string.system_reboot), reboot, Modifier.fillMaxWidth(), enabled = idle, destructive = true)
        Text(text(R.string.system_reboot_hint), color = muted)
        if (working != null) {
            Text(text(if (working == SystemOperation.MEDIA) R.string.system_restart_media_working else R.string.system_reboot_working))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

internal fun describe(notice: SystemNotice): Pair<String, String> = when (notice) {
    is SystemNotice.MediaRestarted -> text(R.string.system_restart_media_done_title) to mediaSummary(notice.result)
    SystemNotice.RebootRequested -> text(R.string.system_reboot) to text(R.string.system_reboot_requested)
    is SystemNotice.Failed -> text(R.string.ui_84) to (notice.message?.takeIf { it.isNotBlank() } ?: text(R.string.ui_84))
}

internal fun mediaSummary(result: SystemRestartScripts.MediaRestart): String = buildString {
    append(text(R.string.system_restart_media_stopped).format(result.stopped.size))
    append(' ')
    append(if (result.centerBack) text(R.string.system_restart_media_center_back).format(result.seconds ?: 0)
        else text(R.string.system_restart_media_center_missing))
    if (result.failed.isNotEmpty()) append(' ').append(text(R.string.system_restart_media_failed).format(result.failed.joinToString(", ")))
}

private fun copyVersion(context: Context, report: String): Boolean = try {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText(text(R.string.system_version_title), report)) != null
} catch (failure: RuntimeException) {
    ControlsLog.warn("app_version_copy", failure, "outcome", "failed")
    false
}

private fun text(id: Int): String = UiText.get(id)
