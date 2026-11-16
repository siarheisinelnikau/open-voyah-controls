package org.openvoyah.controls.presentation.apps

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import org.openvoyah.controls.R
import org.openvoyah.controls.data.frida.FridaWindowControls
import org.openvoyah.controls.platform.apps.AppsCatalog
import org.openvoyah.controls.platform.apps.AppsPlatform
import org.openvoyah.controls.platform.apps.InstalledAppPermissions
import org.openvoyah.controls.platform.apps.LocalApkInstaller
import org.openvoyah.controls.platform.apps.LocalApp
import org.openvoyah.controls.presentation.navigation.ControlsRoute

@Composable
internal fun AppsDestination(entry: Long, navigate: (ControlsRoute) -> Unit, listen: () -> Unit, system: () -> Unit) {
    val activity = checkNotNull(LocalActivity.current as? ComponentActivity) { "Apps require a ComponentActivity host" }
    val owner = LocalLifecycleOwner.current
    val platform = remember(activity) { AppsPlatform(activity) }
    val installer = remember(activity) { LocalApkInstaller(activity.applicationContext) }
    val provider = remember(activity) {
        val app = activity.applicationContext
        val permissions = InstalledAppPermissions(app)
        ViewModelProvider(activity, viewModelFactory {
            initializer { AppsViewModel(AppsCatalog(app, permissions)) }
            initializer { AppSettingsViewModel(FridaWindowControls(app), permissions) }
        })
    }
    val model = remember(provider) { provider["apps-catalog", AppsViewModel::class.java] }
    val settings = remember(provider) { provider["app-settings", AppSettingsViewModel::class.java] }
    val state by model.state.collectAsStateWithLifecycle()
    val selected by settings.state.collectAsStateWithLifecycle()
    val installation by installer.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val lifecycle by owner.lifecycle.currentStateAsState()
    var pendingPicker by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri == null) model.status(R.string.apps_install_failed)
            else activity.lifecycleScope.launch { installer.stage(uri) }
        }
    }
    fun send(launcher: ActivityResultLauncher<Intent>, intent: Intent?) {
        try { launcher.launch(requireNotNull(intent)) }
        catch (_: RuntimeException) { model.status(R.string.apk_install_unavailable) }
    }
    val allow = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingPicker = platform.canInstall()
    }
    LaunchedEffect(entry) { settings.enterGrid(entry) }
    LaunchedEffect(pendingPicker, lifecycle) {
        if (pendingPicker && lifecycle == Lifecycle.State.RESUMED) {
            pendingPicker = false
            if (platform.canInstall()) send(picker, platform.pickApk())
        }
    }
    LaunchedEffect(installation.revision) {
        installation.message?.let(model::status)
        if (!installation.busy && installation.message != null) model.refresh()
    }
    LaunchedEffect(installation.approval, lifecycle) {
        if (lifecycle != Lifecycle.State.RESUMED || owner.lifecycle.currentState != Lifecycle.State.RESUMED) return@LaunchedEffect
        val approval = installation.approval ?: return@LaunchedEffect
        val intent = installer.takeApproval(approval) ?: return@LaunchedEffect
        try { activity.startActivity(intent) }
        catch (_: RuntimeException) { installer.approvalUnavailable(approval.sessionId) }
    }
    DisposableEffect(owner, model, settings, installer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                model.refresh()
                settings.refreshPermissions()
                activity.lifecycleScope.launch { installer.recover() }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    fun open(app: LocalApp, display: Int?, window: Boolean, adjacent: Boolean = false) {
        try {
            settings.notice(null)
            platform.launch(requireNotNull(app.launchEntry), display, window, adjacent)
        } catch (_: RuntimeException) {
            if (settings.state.value != null) settings.notice(R.string.app_display_failed)
            else model.status(R.string.app_display_failed)
        }
    }
    fun permission(intent: Intent?) {
        try { settings.notice(null); activity.startActivity(requireNotNull(intent)) }
        catch (_: RuntimeException) { settings.notice(R.string.apps_permission_settings_unavailable) }
    }
    val saved = rememberSaveableStateHolder()
    BackHandler(selected != null, onBack = settings::close)
    AnimatedContent(selected, contentKey = { it?.app?.id },
        transitionSpec = { fadeIn(tween(170)).togetherWith(fadeOut(tween(110))) }, label = "app details") { current ->
        if (current == null) saved.SaveableStateProvider("apps-grid") {
            AppsScreen(state, entry, navigate, listen, system,
                install = { if (platform.canInstall()) send(picker, platform.pickApk()) },
                canInstall = platform::canInstall,
                allowInstalls = { send(allow, platform.allowInstalls()) },
                installEnabled = !installation.busy && !installation.interrupted,
                discardInstall = if (installation.interrupted) {
                    { activity.lifecycleScope.launch { installer.discardInterrupted() } }
                } else null,
                showSettings = settings::open, open = { open(it, null, false) })
        } else AppSettingsScreen(current, settings, { platform.displays(current.app.launchEntry) }, { platform.windowsSupported },
            open = { display, window -> open(current.app, display, window) },
            openAdjacent = { display -> open(current.app, display, false, adjacent = true) },
            appInfo = { permission(platform.appDetails(current.app.packageName)) },
            specialAccess = { permission(platform.specialAccess(current.app.packageName, it)) })
    }
}
