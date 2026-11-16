package org.openvoyah.controls

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openvoyah.controls.presentation.apps.AppsDestination
import org.openvoyah.controls.presentation.cluster.ClusterDestination
import org.openvoyah.controls.presentation.display.DisplayDestination
import org.openvoyah.controls.presentation.firmware.FirmwareDestination
import org.openvoyah.controls.presentation.home.ControlsSettingsDestination
import org.openvoyah.controls.presentation.home.HomeUpdatesViewModel
import org.openvoyah.controls.presentation.navigation.ControlsNavigation
import org.openvoyah.controls.presentation.navigation.ControlsNavigationViewModel
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.navigation.DestinationLifecycle
import org.openvoyah.controls.presentation.theme.CockpitMotion
import org.openvoyah.controls.presentation.theme.ControlsTheme
import org.openvoyah.controls.presentation.updates.UpdatesDestination
import org.openvoyah.controls.presentation.vehicle.VehicleSettingsDestination
import org.openvoyah.controls.presentation.voice.VoiceDestination

/** Normal screens share this host; Android overlays and the installer handshake keep separate tasks. */
class MainActivity : ComponentActivity() {
    private lateinit var navigation: ControlsNavigationViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val provider = ViewModelProvider(this)
        navigation = provider[ControlsNavigationViewModel::class.java]
        provider[HomeUpdatesViewModel::class.java]
        if (savedInstanceState == null) navigation.enter(ControlsNavigation.route(intent))
        setContent {
            ControlsTheme {
                val stack by navigation.stack.collectAsStateWithLifecycle()
                val entry by navigation.entries.collectAsStateWithLifecycle()
                val route = stack.last()
                val screens = rememberSaveableStateHolder()
                BackHandler(stack.size > 1) { navigation.back() }
                AnimatedContent(route, transitionSpec = {
                    (fadeIn(tween(CockpitMotion.ENTER)) + slideInHorizontally(tween(CockpitMotion.ENTER)) { it / 45 })
                        .togetherWith(fadeOut(tween(CockpitMotion.EXIT)) + slideOutHorizontally(tween(CockpitMotion.EXIT)) { -it / 70 })
                }, label = "controls destination") { target ->
                    DestinationLifecycle(target == route) {
                        screens.SaveableStateProvider(target.name) {
                            when (target) {
                                ControlsRoute.APPS -> AppsDestination(entry, navigation::open, ::listen, ::systemSettings)
                                ControlsRoute.SETTINGS, ControlsRoute.BUTTONS -> ControlsSettingsDestination(
                                    target == ControlsRoute.BUTTONS, navigation::back, navigation::open)
                                ControlsRoute.VOICE -> VoiceDestination(navigation::back, ::listen)
                                ControlsRoute.DISPLAY -> DisplayDestination(navigation::back)
                                ControlsRoute.VEHICLE -> VehicleSettingsDestination(navigation::back)
                                ControlsRoute.FIRMWARE -> FirmwareDestination(navigation::back) { navigation.open(ControlsRoute.CLUSTER) }
                                ControlsRoute.CLUSTER -> ClusterDestination(navigation::back)
                                ControlsRoute.UPDATES -> UpdatesDestination(navigation::back)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navigation.enter(ControlsNavigation.route(intent))
    }

    private fun listen() = external(QuickVoiceActivity.intent(this))
    private fun systemSettings() = external(Intent(Settings.ACTION_SETTINGS))
    private fun external(intent: Intent) {
        try { startActivity(intent) }
        catch (_: RuntimeException) { Toast.makeText(this, UiText.get(R.string.ui_85), Toast.LENGTH_LONG).show() }
    }

    companion object {
        @JvmStatic fun appsIntent(context: Context): Intent = ControlsNavigation.intent(context, ControlsRoute.APPS)
        @JvmStatic fun settingsIntent(context: Context): Intent = ControlsNavigation.intent(context, ControlsRoute.SETTINGS)
    }
}
