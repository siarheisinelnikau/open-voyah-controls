package org.openvoyah.controls

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.openvoyah.controls.data.updates.AppUpdates
import org.openvoyah.controls.data.updates.UpdatePreferences
import org.openvoyah.controls.platform.updates.UpdateApkVerifier
import org.openvoyah.controls.platform.updates.UpdateInstallPort
import org.openvoyah.controls.platform.updates.PackageUpdateInstaller
import org.openvoyah.controls.presentation.theme.ControlsTheme
import org.openvoyah.controls.presentation.updates.UpdatesScreen
import org.openvoyah.controls.presentation.updates.UpdatesViewModel

/** Native Android permission/installation handoffs surround the app-owned Compose screen. */
class UpdatesActivity : ComponentActivity() {
    private val model: UpdatesViewModel by viewModels { viewModelFactory { initializer {
        UpdatesViewModel(AppUpdates.repository(applicationContext), UpdatePreferences(applicationContext),
            UpdateApkVerifier(applicationContext), installPort())
    } } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ControlsTheme {
                val state = model.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED).value
                val lifecycleState = lifecycle.currentStateAsState().value
                val approval = state.installation.approval
                LaunchedEffect(approval, lifecycleState) {
                    if (approval != null && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
                        val intent = model.takeApproval(approval) ?: return@LaunchedEffect
                        try { startActivity(intent) }
                        catch (_: RuntimeException) { model.approvalUnavailable(approval.sessionId) }
                    }
                }
                UpdatesScreen(state, ::finish, model::check, model::checkOnStartup, model::downloadAndInstall,
                    model::cancelDownload, model::discardInterrupted, ::allowInstallation)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.recover()
    }

    private fun allowInstallation() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", packageName, null))
            val handler = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY)
                .firstOrNull { it.activityInfo?.let { info -> info.enabled && info.exported } == true }?.activityInfo
                ?: return model.installationSettingsUnavailable()
            startActivity(intent.setClassName(handler.packageName, handler.name))
        } catch (_: RuntimeException) { model.installationSettingsUnavailable() }
    }

    private fun installPort(): UpdateInstallPort = PackageUpdateInstaller.get(applicationContext)
}
