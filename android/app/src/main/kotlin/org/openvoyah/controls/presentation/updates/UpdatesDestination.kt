package org.openvoyah.controls.presentation.updates

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.openvoyah.controls.data.updates.AppUpdates
import org.openvoyah.controls.data.updates.UpdatePreferences
import org.openvoyah.controls.platform.updates.PackageUpdateInstaller
import org.openvoyah.controls.platform.updates.UpdateApkVerifier

/** The active destination owns the native permission and installation handoffs. */
@Composable
internal fun UpdatesDestination(back: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.updatesActivity() }
    val model = remember(activity) {
        val app = activity.applicationContext
        ViewModelProvider(activity, viewModelFactory { initializer {
            UpdatesViewModel(AppUpdates.repository(app), UpdatePreferences(app),
                UpdateApkVerifier(app), PackageUpdateInstaller.get(app))
        } })["updates.destination", UpdatesViewModel::class.java]
    }
    val owner = LocalLifecycleOwner.current
    val lifecycle = owner.lifecycle
    val state by model.state.collectAsStateWithLifecycle(
        lifecycleOwner = owner, minActiveState = Lifecycle.State.RESUMED)
    val lifecycleState by lifecycle.currentStateAsState()

    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.recover()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val approval = state.installation.approval
    LaunchedEffect(approval, lifecycleState, lifecycle, model) {
        if (approval != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            val intent = model.takeApproval(approval) ?: return@LaunchedEffect
            try { activity.startActivity(intent) }
            catch (_: RuntimeException) { model.approvalUnavailable(approval.sessionId) }
        }
    }

    UpdatesScreen(state, back, model::check, model::checkOnStartup, model::downloadAndInstall,
        model::cancelDownload, model::discardInterrupted, allowInstallation = {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.fromParts("package", activity.packageName, null))
                    val handler = activity.packageManager.queryIntentActivities(intent,
                        PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY)
                        .firstOrNull { it.activityInfo?.let { info -> info.enabled && info.exported } == true }
                        ?.activityInfo
                    if (handler == null) model.installationSettingsUnavailable()
                    else activity.startActivity(intent.setClassName(handler.packageName, handler.name))
                } catch (_: RuntimeException) { model.installationSettingsUnavailable() }
            }
        })
}

private tailrec fun Context.updatesActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.updatesActivity()
    else -> error("Updates requires a ComponentActivity host")
}
