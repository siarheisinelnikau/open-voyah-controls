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
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import org.openvoyah.controls.data.apps.AppIconPreferences
import org.openvoyah.controls.appWindowConfiguration
import org.openvoyah.controls.data.apps.AppGridPreferences
import org.openvoyah.controls.data.apps.RootAppStopper
import org.openvoyah.controls.data.apps.RootPermissionWriter
import org.openvoyah.controls.data.apps.appRootShell
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.apps.AppGridPins
import org.openvoyah.controls.platform.apps.AndroidAppAccess
import org.openvoyah.controls.platform.apps.AppsCatalog
import org.openvoyah.controls.platform.apps.AppsPlatform
import org.openvoyah.controls.platform.apps.InstalledAppPermissions
import org.openvoyah.controls.platform.apps.InstalledAppStopper
import org.openvoyah.controls.platform.apps.LocalApkInstaller
import org.openvoyah.controls.platform.apps.LocalApp
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.CockpitMotion
import org.openvoyah.controls.presentation.theme.LocalCockpitMotion

@Composable
internal fun AppsDestination(
    entry: Long,
    entryDesktop: AppGridDesktop?,
    navigate: (ControlsRoute) -> Unit,
    listen: () -> Unit,
    animateDesktopPaging: Boolean,
) {
    val activity = checkNotNull(LocalActivity.current as? ComponentActivity) { "Apps require a ComponentActivity host" }
    val owner = LocalLifecycleOwner.current
    val platform = remember(activity) { AppsPlatform(activity) }
    val installer = remember(activity) { LocalApkInstaller(activity.applicationContext) }
    val provider = remember(activity) {
        val app = activity.applicationContext
        // One root shell for both actions, so they share one verification of the firmware evidence.
        val root by lazy { appRootShell(app) }
        val permissions = InstalledAppPermissions(app, RootPermissionWriter { root(it) })
        ViewModelProvider(activity, viewModelFactory {
            initializer { AppsViewModel(AppsCatalog(app, permissions), AppGridPreferences(app)) }
            initializer {
                AppSettingsViewModel(appWindowConfiguration(app), permissions, InstalledAppStopper(app, RootAppStopper { root(it) }))
            }
        })
    }
    val model = remember(provider) { provider["apps-catalog", AppsViewModel::class.java] }
    val settings = remember(provider) { provider[APP_SETTINGS_MODEL_KEY, AppSettingsViewModel::class.java] }
    val state by model.state.collectAsStateWithLifecycle()
    // A changed app icon shows in the grid and the dock without waiting for the next return to the grid.
    LaunchedEffect(model) { AppIconPreferences(activity.applicationContext).changes().collect { model.refresh() } }
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
                settings.refresh()
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
    val desktop = state.desktopFor(entry, entryDesktop)
    // The move mode belongs to one grid entry and one desktop; a new entry or another desktop ends it.
    var moving by remember(entry, desktop) { mutableStateOf<AppMoveState?>(null) }
    val motion = LocalCockpitMotion.current
    BackHandler(selected != null, onBack = settings::close)
    AnimatedContent(selected, contentKey = { it?.app?.id },
        transitionSpec = {
            if (!motion.enabled) ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
            else fadeIn(tween(motion.route.enterMillis, easing = CockpitMotion.Arrive))
                .togetherWith(fadeOut(tween(motion.route.exitMillis, easing = CockpitMotion.Leave)))
        }, label = "app details") { current ->
        if (current == null) saved.SaveableStateProvider("apps-grid") {
            AppsScreen(state, entry, desktop, navigate, listen, animateDesktopPaging,
                gridActive = selected == null && lifecycle == Lifecycle.State.RESUMED,
                install = { if (platform.canInstall()) send(picker, platform.pickApk()) },
                canInstall = platform::canInstall,
                allowInstalls = { send(allow, platform.allowInstalls()) },
                installEnabled = !installation.busy && !installation.interrupted,
                discardInstall = if (installation.interrupted) {
                    { activity.lifecycleScope.launch { installer.discardInterrupted() } }
                } else null,
                selectDesktop = { choice ->
                    if (owner.lifecycle.currentState == Lifecycle.State.RESUMED && settings.state.value == null) {
                        model.clearGridFailure()
                        model.selectDesktop(entry, choice)
                    }
                },
                changeDesktopPin = { app, pinned ->
                    if (owner.lifecycle.currentState == Lifecycle.State.RESUMED && settings.state.value == null &&
                        desktop != AppGridDesktop.MAIN && model.state.value.desktopFor(entry, entryDesktop) == desktop) {
                        model.changePin(app, pinned, desktop)
                    }
                },
                swapDesktopPins = { app, other ->
                    if (owner.lifecycle.currentState == Lifecycle.State.RESUMED && settings.state.value == null &&
                        model.state.value.desktopFor(entry, entryDesktop) == desktop) {
                        model.swapPins(app, other, desktop)
                    }
                },
                showSettings = { app ->
                    model.clearGridFailure()
                    settings.open(app)
                }, open = { open(it, null, false) },
                move = moving,
                endMove = { moving = null },
                moveApp = { app, target, visible ->
                    if (owner.lifecycle.currentState == Lifecycle.State.RESUMED && settings.state.value == null &&
                        model.state.value.desktopFor(entry, entryDesktop) == desktop) {
                        model.clearGridFailure()
                        model.moveApp(app, target, desktop, visible)
                    }
                })
        } else {
            val app = current.app
            val grid = state.grid
            val edit = state.gridEdit?.takeIf { it.component == app.id && (it.desktop == null || it.desktop == desktop) }
            val shortcut = if (app.launchEntry == null || !AppGridPins.supports(app.id)) null else AppShortcutGridState(
                pinned = grid?.getOrNull()?.pins(desktop)?.contains(app.id),
                hidden = grid?.getOrNull()?.hides(app.id, app.hiddenByDefault),
                isFavorites = desktop != AppGridDesktop.MAIN,
                working = state.gridEdit?.working == true,
                notice = if (grid?.isFailure == true) R.string.apps_grid_settings_read_failed else edit?.error,
            )
            fun editingThisApp() = owner.lifecycle.currentState == Lifecycle.State.RESUMED && settings.state.value?.app?.id == app.id
            // Moving needs the app on this desktop's grid, where the move mode shows it.
            val onGrid = remember(grid, state.apps, app.id, desktop) {
                grid?.getOrNull()?.arrange(state.apps, LocalApp::id, LocalApp::hiddenByDefault, desktop)?.any { it.id == app.id } == true
            }
            // Android settings pages offered only where the firmware has them; most head units ship without them.
            val androidPages = remember(app.packageName) { mutableMapOf<AndroidAppAccess?, Intent?>() }
            fun androidPage(access: AndroidAppAccess?): (() -> Unit)? {
                // A missing page is the usual answer on head units, so it is remembered as well.
                if (access !in androidPages) {
                    androidPages[access] = if (access == null) platform.appDetails(app.packageName)
                        else platform.specialAccess(app.packageName, access)
                }
                return androidPages[access]?.let { intent -> { permission(intent) } }
            }
            AppSettingsScreen(current, settings, { platform.displays(app.launchEntry) }, { platform.windowsSupported },
                shortcut = shortcut,
                setPinned = { pinned ->
                    if (editingThisApp()) {
                        settings.notice(null)
                        model.changePin(app, pinned, desktop)
                    }
                },
                setHidden = { hide ->
                    if (editingThisApp()) {
                        settings.notice(null)
                        model.setAppHidden(app, hide)
                    }
                },
                move = if (shortcut != null && onGrid) {
                    {
                        if (editingThisApp()) {
                            settings.close()
                            if (settings.state.value == null) moving = AppMoveState(app.id)
                        }
                    }
                } else null,
                open = { display, window -> open(app, display, window) },
                openAdjacent = { display -> open(app, display, false, adjacent = true) },
                appInfo = androidPage(null),
                specialAccess = ::androidPage)
        }
    }
}
