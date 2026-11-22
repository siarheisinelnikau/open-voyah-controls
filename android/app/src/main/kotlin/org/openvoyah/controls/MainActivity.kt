package org.openvoyah.controls

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.data.routines.RoutineLayoutExecution
import org.openvoyah.controls.data.routines.RoutineLayoutPermit
import org.openvoyah.controls.data.routines.RoutinePreferences
import org.openvoyah.controls.data.settings.AppearancePreferences
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.apps.DesktopLayoutException
import org.openvoyah.controls.domain.apps.DesktopLayoutProblem
import org.openvoyah.controls.domain.routines.NamedRoutine
import org.openvoyah.controls.domain.routines.RoutineEditException
import org.openvoyah.controls.domain.routines.RoutinePlan
import org.openvoyah.controls.domain.routines.RoutineStartupDelay
import org.openvoyah.controls.domain.routines.RoutineStartupRequest
import org.openvoyah.controls.domain.settings.AppPalette
import org.openvoyah.controls.domain.settings.LauncherEntry
import org.openvoyah.controls.platform.apps.AppsPlatform
import org.openvoyah.controls.platform.apps.LauncherShortcuts
import org.openvoyah.controls.platform.audio.limitLauncherMediaVolume
import org.openvoyah.controls.platform.display.useControlsDensity
import org.openvoyah.controls.platform.vehicle.automation.VehicleWorkflowRuntime
import org.openvoyah.controls.presentation.home.HomeUpdatesViewModel
import org.openvoyah.controls.presentation.home.appearanceViewModel
import org.openvoyah.controls.presentation.navigation.ControlsHost
import org.openvoyah.controls.presentation.navigation.ControlsNavigation
import org.openvoyah.controls.presentation.navigation.ControlsNavigationState
import org.openvoyah.controls.presentation.navigation.ControlsNavigationViewModel
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.routines.RoutineAppNotice
import org.openvoyah.controls.presentation.routines.RoutineInteractionFrame
import org.openvoyah.controls.presentation.routines.RoutineStartupNotice
import org.openvoyah.controls.presentation.theme.controlsAccentOverlay
import org.openvoyah.controls.presentation.routines.route
import org.openvoyah.controls.presentation.theme.ControlsTheme
import org.openvoyah.controls.presentation.theme.SessionPalette

/** Normal screens share this host; Android overlays and the installer handshake keep separate tasks. */
class MainActivity : ComponentActivity() {
    private lateinit var navigation: ControlsNavigationViewModel
    private val appearancePreferences by lazy { AppearancePreferences(applicationContext) }
    private val routinePreferences by lazy { RoutinePreferences(applicationContext) }
    private val routineApps by lazy { LauncherShortcuts(applicationContext) }
    private val routineLayouts by lazy { RoutineLayoutExecution(applicationContext) }
    private class PendingStartup(val request: RoutineStartupRequest, val entry: ControlsNavigationState) {
        var layoutPermit: RoutineLayoutPermit? = null
    }
    private var pending by mutableStateOf<PendingStartup?>(null)
    private var secondsLeft by mutableIntStateOf(0)
    private var routineMessage by mutableStateOf<String?>(null)
    private var manualRoutineJob: Job? = null
    private var manualLayoutPermit: RoutineLayoutPermit? = null
    private var noticeVisible = false
    private var resumed = false
    private var focused = false
    private var startupTimer: Job? = null
    private var launcherMediaEntry: ControlsNavigationState? = null
    private var launcherMediaTask: Job? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        useControlsDensity(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The manifest theme is dark. With the Daytime palette saved, the window itself must open light, or every
        // start and every recreate() shows one near-black frame before Compose paints.
        val saved = appearancePreferences.load()
        if (saved.palette == AppPalette.DAY) setTheme(R.style.AppTheme_Day)
        // Android's own widgets (edge glow, text handles) take the Appearance accent too; Compose follows it live.
        theme.applyStyle(controlsAccentOverlay(saved.accent, saved.palette), true)
        super.onCreate(savedInstanceState)
        val provider = ViewModelProvider(this)
        navigation = provider[ControlsNavigationViewModel::class.java]
        provider[HomeUpdatesViewModel::class.java]
        val appearance = appearanceViewModel(this)
        if (savedInstanceState == null) enter(intent)
        val initialPalette = navigation.state.value.temporaryPalette
        val compose = ComposeView(this)
        setContentView(RoutineInteractionFrame(this, ::cancelStartup).apply { addView(compose) })
        compose.setContent {
            val look by appearance.state.collectAsStateWithLifecycle()
            val palette by navigation.temporaryPalette.collectAsStateWithLifecycle(initialPalette)
            SideEffect { SessionPalette.publish(palette) }
            ControlsTheme(appearance = look.settings, systemBars = window, temporaryPalette = palette) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        ControlsHost(navigation, appearance, ::listen, ::runManualRoutine, ::runRoutineShortcut,
                            ::runConditionalRoutine, restored = savedInstanceState != null)
                    }
                    pending?.let { launch ->
                        if (launch.request.schedule.delay != RoutineStartupDelay.IMMEDIATE ||
                            launch.request.routine.plan.desktopLayoutId != null) {
                            RoutineStartupNotice(launch.request.routine.name, secondsLeft, ::cancelStartup)
                        }
                        val entry by navigation.state.collectAsStateWithLifecycle()
                        LaunchedEffect(launch, entry) {
                            if (entry !== launch.entry) cancelStartup()
                            else {
                                withFrameNanos { }
                                if (pending === launch) {
                                    noticeVisible = true
                                    startLauncherWorkIfReady()
                                }
                            }
                        }
                    }
                    routineMessage?.let { message ->
                        RoutineAppNotice(message) { routineMessage = null }
                        LaunchedEffect(message) {
                            delay(8000)
                            if (routineMessage == message) routineMessage = null
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        enter(intent)
        dispatchVehicleRuleIfReady()
    }

    private fun enter(intent: Intent) {
        cancelStartup()
        cancelLauncherMedia()
        cancelManualRoutine()
        routineMessage = null
        if (intent.action == ControlsNavigation.START_CONTROLS) {
            val appearance = appearancePreferences.load()
            val startRoute = appearance.launcherEntry.startRoute()
            val request = routinePreferences.startupRequest()
            val needsForeground = request != null && (request.schedule.delay != RoutineStartupDelay.IMMEDIATE ||
                request.routine.plan.installedApp != null || request.routine.plan.desktopLayoutId != null)
            val prepared = request != null && !needsForeground && applyStartup(request, startRoute)
            if (!prepared) {
                navigation.enterFromLauncher(startRoute, null, null)
                if (request != null && needsForeground) {
                    secondsLeft = request.schedule.delay.seconds
                    pending = PendingStartup(request, navigation.state.value)
                }
            }
            if (appearance.limitMediaVolumeOnLaunch) launcherMediaEntry = navigation.state.value
            startLauncherWorkIfReady()
        } else {
            navigation.enter(ControlsNavigation.route(intent), ControlsNavigation.driverMemorySlot(intent))
        }
    }

    private fun LauncherEntry.startRoute() = when (this) {
        LauncherEntry.APP_GRID -> ControlsRoute.APPS
        LauncherEntry.VEHICLE -> ControlsRoute.VEHICLE
        LauncherEntry.SETTINGS -> ControlsRoute.SETTINGS
    }

    private fun applyStartup(request: RoutineStartupRequest, shownRoute: ControlsRoute, app: InstalledApps.Entry? = null) =
        routinePreferences.runStartup(request) { plan -> dispatchRoutine(plan, app, shownRoute) }

    private suspend fun runManualRoutine(routine: NamedRoutine, entry: ControlsNavigationState): Int? {
        if (entry.route != ControlsRoute.ROUTINES) throw CancellationException()
        return runRoutineFromEntry(routine, entry)
    }

    private fun runRoutineShortcut(routine: NamedRoutine, entry: ControlsNavigationState): Boolean {
        if (!routineEntryIsCurrent(entry) || manualRoutineJob != null) return false
        lifecycleScope.launch {
            val error = runRoutineFromEntry(routine, entry)
            if (error != null) routineMessage = UiText.get(error)
            else if (routine.plan.installedApp == null) routineMessage = UiText.get(R.string.routines_ran).format(routine.name)
        }
        return true
    }

    private suspend fun runRoutineFromEntry(routine: NamedRoutine, entry: ControlsNavigationState): Int? =
        runConditionalRoutine(routine, entry) { true }

    private suspend fun runConditionalRoutine(routine: NamedRoutine, entry: ControlsNavigationState,
        stillAllowed: () -> Boolean): Int? {
        if (!routineEntryIsCurrent(entry) || manualRoutineJob != null || !stillAllowed()) throw CancellationException()
        val job = currentCoroutineContext().job
        manualRoutineJob = job
        cancelStartup()
        routineMessage = null
        val layoutId = routine.plan.desktopLayoutId
        val permit = layoutId?.let { RoutineLayoutPermit { job.isActive && navigation.state.value === entry && stillAllowed() } }
        manualLayoutPermit = permit
        try {
            val app = routine.plan.installedApp?.let { withContext(Dispatchers.IO) { routineApps.find(it) } }
            if (!routineEntryIsCurrent(entry) || !stillAllowed()) throw CancellationException()
            if (layoutId == null) {
                routinePreferences.planFor(routine)
            } else {
                val layout = routineLayouts.prepare(layoutId)
                val applied = routineLayouts.runManual(routine, layout, checkNotNull(permit))
                if (!applied) return R.string.routines_changed
            }
            if (!routineEntryIsCurrent(entry) || !stillAllowed()) throw CancellationException()
            dispatchRoutine(routine.plan, app)
            return null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return routineError("manual", layoutId != null, failure)
        } finally {
            if (manualLayoutPermit === permit) manualLayoutPermit = null
            if (manualRoutineJob === job) manualRoutineJob = null
        }
    }

    private fun routineEntryIsCurrent(entry: ControlsNavigationState) =
        canDispatchInForeground() && navigation.state.value === entry

    private suspend fun layoutPermit(entry: ControlsNavigationState): RoutineLayoutPermit {
        val owner = currentCoroutineContext().job
        return RoutineLayoutPermit { owner.isActive && navigation.state.value === entry }
    }

    private fun cancelManualRoutine() {
        manualLayoutPermit?.cancel()
        manualRoutineJob?.cancel()
    }

    private fun dispatchRoutine(plan: RoutinePlan, app: InstalledApps.Entry?, startRoute: ControlsRoute? = null) {
        val target = plan.target.route
        if (startRoute == null) navigation.runRoutine(target, plan.target.desktop, plan.palette)
        else navigation.enterFromLauncher(target ?: startRoute, plan.target.desktop, plan.palette)
        plan.installedApp?.let { openRoutineApp(it, app) }
    }

    private fun routineError(mode: String, withLayout: Boolean, failure: Exception): Int {
        ControlsLog.warn("routine_run", "mode", mode, "outcome", "failed", "error", failure)
        return when {
            failure is RoutineEditException -> R.string.routines_changed
            failure is DesktopLayoutException && failure.problem == DesktopLayoutProblem.MISSING -> R.string.routines_layout_unavailable
            withLayout -> R.string.routines_layout_apply_failed
            else -> R.string.routines_load_failed
        }
    }

    private fun openRoutineApp(component: String, app: InstalledApps.Entry?) {
        if (app == null || app.component().flattenToString() != component) {
            routineMessage = UiText.get(R.string.routines_app_missing_notice).format(component)
            ControlsLog.info("routine_app_launch", "outcome", "unavailable")
            return
        }
        try {
            AppsPlatform(this).launch(app)
            routineMessage = UiText.get(R.string.routines_app_requested).format(app.label())
            ControlsLog.info("routine_app_launch", "outcome", "requested")
        } catch (failure: RuntimeException) {
            routineMessage = UiText.get(R.string.routines_app_failed).format(app.label())
            ControlsLog.warn("routine_app_launch", "outcome", "failed", "error", failure)
        }
    }

    private fun canDispatchInForeground() = resumed && hasWindowFocus() && !isFinishing && !isDestroyed

    private fun startLauncherWorkIfReady() {
        if (!canDispatchInForeground()) return
        val entry = launcherMediaEntry
        if (entry == null || navigation.state.value !== entry) {
            cancelLauncherMedia()
            startTimerIfReady()
            return
        }
        if (launcherMediaTask != null) return
        // Apply the opted-in limit before a routine can hand focus to another app.
        launcherMediaTask = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                limitLauncherMediaVolume(this@MainActivity, appearancePreferences) {
                    launcherMediaEntry === entry && navigation.state.value === entry && canDispatchInForeground()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: RuntimeException) {
                ControlsLog.warn("launcher_media_volume", "outcome", "settings_unavailable", "failure", failure)
            } finally {
                if (launcherMediaEntry === entry) {
                    launcherMediaEntry = null
                    launcherMediaTask = null
                    startTimerIfReady()
                }
            }
        }
        launcherMediaTask?.start()
    }

    private fun cancelLauncherMedia() {
        launcherMediaEntry = null
        launcherMediaTask?.cancel()
        launcherMediaTask = null
    }

    private fun startTimerIfReady() {
        val launch = pending ?: return
        if (startupTimer != null || !noticeVisible || !canDispatchInForeground()) return
        startupTimer = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            val plan = launch.request.routine.plan
            try {
                val layout = plan.desktopLayoutId?.let { id ->
                    launch.layoutPermit = layoutPermit(launch.entry)
                    routineLayouts.prepare(id)
                }
                for (second in launch.request.schedule.delay.seconds downTo 1) {
                    secondsLeft = second
                    delay(1000)
                }
                val app = plan.installedApp?.let { withContext(Dispatchers.IO) { routineApps.find(it) } }
                if (!canCompleteStartup(launch)) return@launch
                if (layout == null) {
                    clearStartup(launch)
                    applyStartup(launch.request, launch.entry.route, app)
                } else {
                    secondsLeft = 0
                    val applied = routineLayouts.runStartup(launch.request, layout, checkNotNull(launch.layoutPermit))
                    if (!canCompleteStartup(launch)) return@launch
                    clearStartup(launch)
                    if (applied) dispatchRoutine(plan, app, launch.entry.route)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (pending === launch) {
                    routineMessage = UiText.get(routineError("startup", plan.desktopLayoutId != null, failure))
                }
            } finally {
                clearStartup(launch)
            }
        }
        startupTimer?.start()
    }

    private fun canCompleteStartup(launch: PendingStartup) =
        pending === launch && canDispatchInForeground() && navigation.state.value === launch.entry

    private fun cancelStartup() {
        val launch = pending ?: return
        launch.layoutPermit?.cancel()
        startupTimer?.cancel()
        clearStartup(launch)
    }

    private fun clearStartup(launch: PendingStartup) {
        if (pending !== launch) return
        startupTimer = null
        pending = null
        noticeVisible = false
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        dispatchVehicleRuleIfReady()
        startLauncherWorkIfReady()
    }

    override fun onPause() {
        resumed = false
        cancelLauncherMedia()
        cancelStartup()
        cancelManualRoutine()
        routineMessage = null
        super.onPause()
    }

    override fun onStop() {
        cancelLauncherMedia()
        cancelStartup()
        cancelManualRoutine()
        super.onStop()
    }

    override fun onDestroy() {
        // A session palette ends with the screens that selected it; a later popup uses the saved palette again.
        if (isFinishing) SessionPalette.publish(null)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (focused && !hasFocus) {
            cancelLauncherMedia()
            cancelStartup()
            cancelManualRoutine()
        }
        focused = hasFocus
        if (hasFocus) {
            dispatchVehicleRuleIfReady()
            startLauncherWorkIfReady()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        cancelLauncherMedia()
        cancelStartup()
    }

    /** No executable data is accepted from the Intent; the runtime consumes one fresh in-memory token. */
    private fun dispatchVehicleRuleIfReady() {
        if (!canDispatchInForeground()) return
        val runtime = VehicleWorkflowRuntime.get(applicationContext)
        val decision = runtime.claim(intent) ?: return
        val entry = navigation.state.value
        lifecycleScope.launch {
            val outcome = try {
                if (runConditionalRoutine(decision.routine, entry) { runtime.isCurrent(decision) } == null) "completed" else "failed"
            } catch (_: CancellationException) { "cancelled" }
            catch (_: Exception) { "unconfirmed" }
            ControlsLog.info("conditional_rule_handoff", "rule", decision.rule.id, "outcome", outcome)
        }
    }

    private fun listen() = external(QuickVoiceActivity.intent(this))
    private fun external(intent: Intent) {
        try { startActivity(intent) }
        catch (_: RuntimeException) { Toast.makeText(this, UiText.get(R.string.ui_85), Toast.LENGTH_LONG).show() }
    }

    companion object {
        @JvmStatic fun appsIntent(context: Context): Intent = ControlsNavigation.intent(context, ControlsRoute.APPS)
        @JvmStatic fun settingsIntent(context: Context): Intent = ControlsNavigation.intent(context, ControlsRoute.SETTINGS)
    }
}
