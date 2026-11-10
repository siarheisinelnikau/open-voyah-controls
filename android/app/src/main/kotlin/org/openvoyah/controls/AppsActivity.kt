package org.openvoyah.controls

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import org.openvoyah.controls.data.frida.FridaWindowControls
import org.openvoyah.controls.platform.apps.AndroidAppAccess
import org.openvoyah.controls.platform.apps.AppsCatalog
import org.openvoyah.controls.platform.apps.AppsPlatform
import org.openvoyah.controls.platform.apps.InstalledAppPermissions
import org.openvoyah.controls.platform.apps.LocalApkInstaller
import org.openvoyah.controls.platform.apps.LocalApp
import org.openvoyah.controls.presentation.apps.AppSettingsScreen
import org.openvoyah.controls.presentation.apps.AppSettingsViewModel
import org.openvoyah.controls.presentation.apps.AppsScreen
import org.openvoyah.controls.presentation.apps.AppsViewModel
import org.openvoyah.controls.presentation.theme.ControlsTheme

class AppsActivity : ComponentActivity() {
    private lateinit var platform: AppsPlatform
    private lateinit var model: AppsViewModel
    private lateinit var settings: AppSettingsViewModel
    private lateinit var apkInstaller: LocalApkInstaller

    private val allow = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (platform.canInstall()) pickApk()
    }
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri == null) model.status(R.string.apps_install_failed)
            else lifecycleScope.launch { apkInstaller.stage(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        platform = AppsPlatform(this)
        apkInstaller = LocalApkInstaller(applicationContext)
        val permissions = InstalledAppPermissions(applicationContext)
        val provider = ViewModelProvider(this, viewModelFactory {
            initializer { AppsViewModel(AppsCatalog(applicationContext, permissions)) }
            initializer { AppSettingsViewModel(FridaWindowControls(applicationContext), permissions) }
        })
        model = provider[AppsViewModel::class.java]
        settings = provider[AppSettingsViewModel::class.java]
        setContent {
            ControlsTheme {
                val state by model.state.collectAsStateWithLifecycle()
                val selected by settings.state.collectAsStateWithLifecycle()
                val installation by apkInstaller.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
                LaunchedEffect(installation.revision) {
                    installation.message?.let(model::status)
                    if (!installation.busy && installation.message != null) model.refresh()
                }
                LaunchedEffect(installation.approval) {
                    val approval = installation.approval ?: return@LaunchedEffect
                    val intent = apkInstaller.takeApproval(approval) ?: return@LaunchedEffect
                    try {
                        startActivity(intent)
                    } catch (_: RuntimeException) {
                        apkInstaller.approvalUnavailable(approval.sessionId)
                    }
                }
                val savedScreens = rememberSaveableStateHolder()
                BackHandler(selected != null, onBack = settings::close)
                AnimatedContent(
                    selected, contentKey = { it?.app?.id },
                    transitionSpec = { fadeIn(tween(170)).togetherWith(fadeOut(tween(110))) },
                    label = "app settings",
                ) { current ->
                    if (current == null) savedScreens.SaveableStateProvider("apps") {
                        AppsScreen(
                            state = state,
                            back = ::finish,
                            install = { if (platform.canInstall()) pickApk() },
                            canInstall = platform::canInstall,
                            allowInstalls = { send(allow, platform.allowInstalls()) },
                            installEnabled = !installation.busy && !installation.interrupted,
                            discardInstall = if (installation.interrupted) {
                                { lifecycleScope.launch { apkInstaller.discardInterrupted() } }
                            } else null,
                            showSettings = settings::open,
                            open = { open(it, null, false) },
                        )
                    } else AppSettingsScreen(
                        current, settings, { platform.displays(current.app.launchEntry) }, { platform.windowsSupported },
                        open = { display, window -> open(current.app, display, window) },
                        openAdjacent = { display -> open(current.app, display, false, adjacent = true) },
                        appInfo = { openPermissionSettings(platform.appDetails(current.app.packageName)) },
                        specialAccess = { access -> openSpecialAccess(current.app, access) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
        settings.refreshPermissions()
        lifecycleScope.launch { apkInstaller.recover() }
    }

    private fun pickApk() = send(picker, platform.pickApk())

    private fun send(launcher: ActivityResultLauncher<Intent>, intent: Intent?) {
        try {
            requireNotNull(intent)
            launcher.launch(intent)
        } catch (_: RuntimeException) {
            model.status(R.string.apk_install_unavailable)
        }
    }

    private fun open(app: LocalApp, displayId: Int?, window: Boolean, adjacent: Boolean = false) {
        try {
            settings.notice(null)
            platform.launch(requireNotNull(app.launchEntry), displayId, window, adjacent)
        } catch (_: RuntimeException) {
            if (settings.state.value != null) settings.notice(R.string.app_display_failed)
            else model.status(R.string.app_display_failed)
        }
    }

    private fun openSpecialAccess(app: LocalApp, access: AndroidAppAccess) =
        openPermissionSettings(platform.specialAccess(app.packageName, access))

    private fun openPermissionSettings(intent: Intent?) {
        try {
            settings.notice(null)
            startActivity(requireNotNull(intent))
        } catch (_: RuntimeException) {
            settings.notice(R.string.apps_permission_settings_unavailable)
        }
    }
}
