package org.openvoyah.controls.presentation.display

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.openvoyah.controls.MediaAccessService
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.display.DisplayPreferences
import org.openvoyah.controls.data.display.DisplayPreviewPreferences
import org.openvoyah.controls.data.frida.FridaNavigationFeed
import org.openvoyah.controls.domain.display.DisplayPreviewArrangement
import org.openvoyah.controls.domain.display.DisplayPreviewPaneOrder
import org.openvoyah.controls.domain.display.PreviewMediaFallback
import org.openvoyah.controls.domain.display.PreviewSeekStep
import org.openvoyah.controls.platform.display.VirtualDisplayPreview
import org.openvoyah.controls.platform.media.PreviewMediaController
import org.openvoyah.controls.platform.media.PreviewMediaSources
import org.openvoyah.controls.presentation.theme.CockpitDialogs
import org.openvoyah.controls.presentation.theme.ControlsTheme

/** The route owns its surface, dialogs and models; the host owns Android permission handoffs. */
@Composable
internal fun DisplayDestination(back: () -> Unit) {
    val activity = LocalContext.current.displayActivity()
    val app = activity.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val store = remember(activity) { ViewModelStore() }
    val provider = remember(store, app) {
        ViewModelProvider(object : ViewModelStoreOwner {
            override val viewModelStore = store
        }, viewModelFactory {
            initializer {
                DisplayViewModel(UiText.displayEvents(app), DisplayPreferences(app), FridaNavigationFeed(app),
                    DisplayPreviewPreferences(app), PreviewMediaController(app), PreviewMediaSources(app),
                    Locale.forLanguageTag(UiText.language()))
            }
        })
    }
    val model = remember(provider) { provider[DisplayViewModel::class.java] }
    val preview = remember(activity) { VirtualDisplayPreview(activity) }
    val dialogs = remember(activity) { CockpitDialogs(activity) }
    val scope = rememberCoroutineScope()
    var started by remember(lifecycleOwner) { mutableStateOf(false) }
    var sourcePicker by remember(lifecycleOwner) { mutableStateOf<Job?>(null) }
    var needsMediaAccess by remember(lifecycleOwner) { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME, lifecycleOwner) {
        val listener = ComponentName(app, MediaAccessService::class.java)
        needsMediaAccess = app.getSystemService(NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(listener) == false
    }

    DisposableEffect(preview, dialogs, store) {
        onDispose {
            preview.stop()
            dialogs.dismiss()
            store.clear()
        }
    }
    LaunchedEffect(lifecycleOwner, model, preview, dialogs) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            started = true
            try {
                model.refresh()
                launch { model.observeMediaPreview() }
                preview.start(model.state.value.density, model.previewSnapshot())
                model.previewStates.collect(preview::update)
            } finally {
                started = false
                sourcePicker?.cancel()
                preview.stop()
                dialogs.dismiss()
            }
        }
    }

    fun active(): Boolean = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    val state by model.state.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
    val status by preview.status.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
    val media by model.player.collectAsStateWithLifecycle(initialValue = null, lifecycleOwner = lifecycleOwner)
    val fontScale = LocalDensity.current.fontScale
    val previewDpi = effectivePreviewDpi(state.density, activity.resources.displayMetrics.densityDpi)
    val canvasDensity = remember(previewDpi, fontScale) { previewCanvasDensity(previewDpi, fontScale) }
    SideEffect { preview.fontScale(fontScale) }
    val preferred = state.previewContent.preferredMediaPackage
    var preferredLabel by remember(preferred) { mutableStateOf(preferred) }
    LaunchedEffect(model, preferred, started) {
        if (started && preferred.isNotEmpty()) preferredLabel = model.mediaSourceLabel(preferred)
    }
    DisplayScreen(
        state, status, media, preferredLabel, back,
        needsMediaAccess = needsMediaAccess,
        canvasDensity = canvasDensity,
        chooseDensity = {
            if (active()) dialogs.choices(UiText.get(R.string.display_density),
                PREVIEW_DENSITIES.map(::previewDensityText).toTypedArray(), { index ->
                    if (active()) {
                        val dpi = PREVIEW_DENSITIES[index]
                        model.density(dpi)
                        preview.density(dpi)
                    }
                })
        },
        mediaAccess = {
            if (active()) try { activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            catch (_: RuntimeException) { model.report(UiText.get(R.string.ui_134)) }
        },
        configureNavigation = {
            val current = model.state.value
            if (active() && !current.changingNavigation) {
                val enable = !current.navigationEnabled
                dialogs.message(UiText.get(R.string.display_navigation),
                    UiText.get(if (enable) R.string.navigation_feed_enable else R.string.navigation_feed_disable),
                    UiText.get(R.string.ui_187), Runnable { if (active()) model.navigationEnabled(enable) },
                    UiText.get(R.string.ui_89))
            }
        },
        clusterMedia = { if (active()) model.clusterMedia(it) },
        previewMedia = { if (active()) model.showPreviewMedia(it) },
        previewNavigation = { if (active()) model.showPreviewNavigation(it) },
        playbackProgress = { if (active()) model.showPlaybackProgress(it) },
        albumArtwork = { if (active()) model.showAlbumArtwork(it) },
        transportControls = { if (active()) model.showTransportControls(it) },
        transport = { action, revision, step -> if (active()) model.transport(action, revision, step) },
        chooseArrangement = {
            if (active()) {
                val options = DisplayPreviewArrangement.entries
                val labels = options.map {
                    val name = previewArrangementText(it)
                    if (it == DisplayPreviewArrangement.SIDE_BY_SIDE) {
                        "$name\n${UiText.get(R.string.display_preview_arrangement_fit)}"
                    } else name
                }
                dialogs.choices(UiText.get(R.string.display_preview_arrangement), labels.toTypedArray(), { selected ->
                    if (active()) model.previewArrangement(options[selected])
                })
            }
        },
        choosePaneOrder = {
            if (active()) {
                val options = DisplayPreviewPaneOrder.entries
                dialogs.choices(UiText.get(R.string.display_preview_pane_order), options.map(::previewPaneOrderText).toTypedArray(),
                    { selected -> if (active()) model.previewPaneOrder(options[selected]) })
            }
        },
        chooseSeekStep = {
            if (active()) {
                val options = PreviewSeekStep.entries
                dialogs.choices(UiText.get(R.string.display_preview_seek_step), options.map(::previewSeekStepText).toTypedArray(),
                    { selected -> if (active()) model.seekStep(options[selected]) })
            }
        },
        previewDetails = {
            if (active()) dialogs.show { close ->
                val live by model.state.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
                DisplayPreviewDetails(
                    live, close,
                    mediaSubtitle = { if (active()) model.mediaSubtitle(it) },
                    showCurrentRoad = { if (active()) model.showCurrentRoad(it) },
                    showNextRoad = { if (active()) model.showNextRoad(it) },
                    showTurnDistance = { if (active()) model.showTurnDistance(it) },
                    hideEmptyPanes = { if (active()) model.hideEmptyPanes(it) },
                    palette = { if (active()) model.previewPalette(it) },
                    paneBalance = { if (active()) model.previewPaneBalance(it) },
                    distanceUnits = { if (active()) model.previewDistanceUnits(it) },
                    roadNames = { if (active()) model.previewRoadNames(it) },
                    textSize = { if (active()) model.previewTextSize(it) },
                )
            }
        },
        chooseSource = {
            if (active() && sourcePicker?.isActive != true) sourcePicker = scope.launch {
                val options = model.availableMediaSources()
                if (active()) {
                    val automatic = UiText.get(R.string.display_preview_automatic_source)
                    val labels = listOf(automatic) + options.map { choice ->
                        if (choice.label == choice.packageName) choice.label else "${choice.label}\n${choice.packageName}"
                    }
                    dialogs.choices(UiText.get(R.string.display_preview_media_source), labels.toTypedArray(), { choice ->
                        if (active()) model.preferredMediaSource(options.getOrNull(choice - 1)?.packageName.orEmpty())
                    })
                }
            }
        },
        chooseMediaFallback = {
            if (active() && model.state.value.previewContent.preferredMediaPackage.isNotEmpty()) {
                val options = PreviewMediaFallback.entries
                dialogs.choices(UiText.get(R.string.display_preview_media_fallback), options.map(::previewFallbackText).toTypedArray(),
                    { selected -> if (active()) model.mediaFallback(options[selected]) })
            }
        },
    ) { modifier ->
        Box(modifier.onGloballyPositioned { preview.surfaceVisibility(!it.boundsInWindow().isEmpty) }) {
            AndroidView(
                factory = { context -> SurfaceView(context).apply { holder.addCallback(preview) } },
                modifier = Modifier.matchParentSize(),
                onRelease = { view ->
                    view.holder.removeCallback(preview)
                    preview.surfaceVisibility(false)
                    preview.surfaceDestroyed(view.holder)
                },
            )
            if (started && status == VirtualDisplayPreview.Status.Unavailable) {
                val content by preview.previewContent.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
                val visible by preview.visible.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
                ControlsTheme { DisplayPreviewScreen(content, visible, canvasDensity) }
            }
        }
    }
}

private tailrec fun Context.displayActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.displayActivity()
    else -> error("Display destination requires an activity host")
}

private val PREVIEW_DENSITIES = intArrayOf(0, 160, 200, 240, 320)
