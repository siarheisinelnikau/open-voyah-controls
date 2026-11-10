package org.openvoyah.controls

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import org.openvoyah.controls.data.display.DisplayPreferences
import org.openvoyah.controls.data.frida.FridaNavigationFeed
import org.openvoyah.controls.data.frida.FridaWindowControls
import org.openvoyah.controls.platform.display.VirtualDisplayPreview
import org.openvoyah.controls.presentation.display.AppDisplayDefaultsViewModel
import org.openvoyah.controls.presentation.display.DisplayScreen
import org.openvoyah.controls.presentation.display.DisplayViewModel
import org.openvoyah.controls.presentation.display.previewDensityText
import org.openvoyah.controls.presentation.theme.CockpitDialogs
import org.openvoyah.controls.presentation.theme.ControlsTheme

class DisplayActivity : ComponentActivity() {
    private val preview = VirtualDisplayPreview(this)
    private val dialogs = CockpitDialogs(this)
    private val windows by lazy { FridaWindowControls(applicationContext) }
    private val model: DisplayViewModel by viewModels {
        viewModelFactory { initializer {
            DisplayViewModel(UiText.displayEvents(applicationContext), DisplayPreferences(applicationContext),
                FridaNavigationFeed(applicationContext))
        } }
    }
    private val defaults: AppDisplayDefaultsViewModel by viewModels {
        viewModelFactory { initializer { AppDisplayDefaultsViewModel(windows) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appDefaults = if (windows.supportsDisplayDefaults()) defaults else null
        setContent {
            ControlsTheme {
                DisplayScreen(model.state.collectAsStateWithLifecycle().value, preview.status.collectAsStateWithLifecycle().value,
                    appDefaults, ::finish, ::chooseDensity, ::mediaAccess, ::configureNavigation, model::clusterMedia) { modifier ->
                    AndroidView(factory = { context -> SurfaceView(context).apply { holder.addCallback(preview) } },
                        modifier = modifier)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.snapshots.collect(preview::update) }
        }
    }

    private fun chooseDensity() {
        val labels = DENSITIES.map(::previewDensityText).toTypedArray()
        dialogs.choices(UiText.get(R.string.display_density), labels, { index ->
            val dpi = DENSITIES[index]
            model.density(dpi)
            preview.density(dpi)
        })
    }

    private fun configureNavigation() {
        val state = model.state.value
        if (state.changingNavigation) return
        val enable = !state.navigationEnabled
        dialogs.message(UiText.get(R.string.display_navigation),
            UiText.get(if (enable) R.string.navigation_feed_enable else R.string.navigation_feed_disable),
            UiText.get(R.string.ui_187), Runnable { model.navigationEnabled(enable) }, UiText.get(R.string.ui_89))
    }

    private fun mediaAccess() {
        try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        catch (_: RuntimeException) { model.report(UiText.get(R.string.ui_134)) }
    }

    override fun onStart() {
        super.onStart()
        model.refresh()
        preview.start(model.state.value.density, model.snapshot())
    }

    override fun onStop() { preview.stop(); super.onStop() }
    override fun onDestroy() { dialogs.dismiss(); super.onDestroy() }

    private companion object { val DENSITIES = intArrayOf(0, 160, 200, 240, 320) }
}
