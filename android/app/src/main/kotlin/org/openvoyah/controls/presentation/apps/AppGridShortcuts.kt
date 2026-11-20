package org.openvoyah.controls.presentation.apps

import org.openvoyah.controls.R
import org.openvoyah.controls.domain.apps.AppGridShortcut
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.CockpitIcon

internal data class ShortcutDetails(
    val key: String,
    val label: Int,
    val icon: CockpitIcon,
    val route: ControlsRoute? = null,
)

internal val AppGridShortcut.details: ShortcutDetails get() = shortcutDetails.getValue(this)

private val shortcutDetails = mapOf(
    AppGridShortcut.QUICK_VOICE to ShortcutDetails("controls-listen", R.string.quick_voice_title, CockpitIcon.VOICE),
    AppGridShortcut.BUTTONS to ShortcutDetails("controls-buttons", R.string.cockpit_buttons, CockpitIcon.BUTTONS, ControlsRoute.BUTTONS),
    AppGridShortcut.DISPLAY to ShortcutDetails("controls-display", R.string.virtual_display, CockpitIcon.DISPLAY, ControlsRoute.DISPLAY),
    AppGridShortcut.VEHICLE to ShortcutDetails("controls-vehicle", R.string.vehicle_settings_title, CockpitIcon.CAR, ControlsRoute.VEHICLE),
    AppGridShortcut.VOICE_SETTINGS to ShortcutDetails("controls-voice", R.string.voice_options_title, CockpitIcon.VOICE, ControlsRoute.VOICE),
    AppGridShortcut.UPDATES to ShortcutDetails("controls-updates", R.string.updates_title, CockpitIcon.DOWNLOAD, ControlsRoute.UPDATES),
    AppGridShortcut.INSTALL_APK to ShortcutDetails("controls-install", R.string.install_local_apk, CockpitIcon.DOWNLOAD),
)
