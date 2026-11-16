package org.openvoyah.controls.presentation.display

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.display.DisplayPreferences
import org.openvoyah.controls.data.frida.FridaNavigationFeed
import org.openvoyah.controls.data.frida.FridaWindowControls
import org.openvoyah.controls.platform.display.VirtualDisplayPreview
import org.openvoyah.controls.presentation.theme.CockpitDialogs

/** The route owns its surface, dialogs and models; the host owns Android permission handoffs. */
@Composable
internal fun DisplayDestination(back: () -> Unit) {
    val activity = LocalContext.current.displayActivity()
    val app = activity.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val store = remember(activity) { ViewModelStore() }
    val windows = remember(app) { FridaWindowControls(app) }
    val provider = remember(store, app, windows) {
        ViewModelProvider(object : ViewModelStoreOwner {
            override val viewModelStore = store
        }, viewModelFactory {
            initializer { DisplayViewModel(UiText.displayEvents(app), DisplayPreferences(app), FridaNavigationFeed(app)) }
            initializer { AppDisplayDefaultsViewModel(windows) }
        })
    }
    val model = remember(provider) { provider[DisplayViewModel::class.java] }
    val defaults = remember(provider, windows) {
        if (windows.supportsDisplayDefaults()) provider[AppDisplayDefaultsViewModel::class.java] else null
    }
    val preview = remember(activity) { VirtualDisplayPreview(activity) }
    val dialogs = remember(activity) { CockpitDialogs(activity) }
    var started by remember(lifecycleOwner) { mutableStateOf(false) }

    DisposableEffect(preview, dialogs, store) {
        onDispose {
            preview.stop()
            dialogs.dismiss()
            store.clear()
        }
    }
    LaunchedEffect(lifecycleOwner, model, defaults, preview, dialogs) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            defaults?.close()
            started = true
            try {
                model.refresh()
                preview.start(model.state.value.density, model.snapshot())
                model.snapshots.collect(preview::update)
            } finally {
                started = false
                preview.stop()
                dialogs.dismiss()
                defaults?.close()
            }
        }
    }

    fun active(): Boolean = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    val state by model.state.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
    val status by preview.status.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
    DisplayScreen(
        state, status, if (started) defaults else null, back,
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
    ) { modifier ->
        AndroidView(
            factory = { context -> SurfaceView(context).apply { holder.addCallback(preview) } },
            modifier = modifier,
            onRelease = { view ->
                view.holder.removeCallback(preview)
                preview.surfaceDestroyed(view.holder)
            },
        )
    }
}

private tailrec fun Context.displayActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.displayActivity()
    else -> error("Display destination requires an activity host")
}

private val PREVIEW_DENSITIES = intArrayOf(0, 160, 200, 240, 320)
