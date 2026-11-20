package org.openvoyah.controls.presentation.dock

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.openvoyah.controls.R
import org.openvoyah.controls.data.dock.QuickActionPreferences
import org.openvoyah.controls.data.routines.RoutinePreferences
import org.openvoyah.controls.domain.dock.QuickAction
import org.openvoyah.controls.platform.apps.LauncherShortcuts
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitIcon

@Composable
internal fun quickActionsModel(): QuickActionsViewModel {
    val activity = checkNotNull(LocalActivity.current as? ComponentActivity)
    return remember(activity) {
        val context = activity.applicationContext
        ViewModelProvider(activity, viewModelFactory { initializer {
            QuickActionsViewModel(QuickActionPreferences(context), LauncherShortcuts(context), RoutinePreferences(context).observe())
        } })[QuickActionsViewModel::class.java]
    }
}

internal class QuickActionTarget(
    val route: ControlsRoute,
    @StringRes val label: Int,
    @StringRes val title: Int = label,
    val glyph: @Composable () -> Unit,
) {
    constructor(route: ControlsRoute, icon: CockpitIcon, @StringRes label: Int, @StringRes title: Int = label) :
        this(route, label, title, { CockpitGlyph(icon) })
}

internal val QuickAction.target: QuickActionTarget get() = when (this) {
    QuickAction.APPS -> QuickActionTarget(ControlsRoute.APPS, CockpitIcon.APPS, R.string.quick_actions_apps,
        R.string.appearance_app_grid)
    QuickAction.BUTTONS -> QuickActionTarget(ControlsRoute.BUTTONS, CockpitIcon.BUTTONS, R.string.cockpit_buttons)
    QuickAction.VOICE_SETTINGS -> QuickActionTarget(ControlsRoute.VOICE, CockpitIcon.VOICE, R.string.quick_actions_commands,
        R.string.voice_options_title)
    QuickAction.DISPLAY -> QuickActionTarget(ControlsRoute.DISPLAY, CockpitIcon.DISPLAY, R.string.quick_actions_display,
        R.string.virtual_display)
    QuickAction.VEHICLE -> QuickActionTarget(ControlsRoute.VEHICLE, CockpitIcon.CAR, R.string.quick_actions_vehicle,
        R.string.vehicle_settings_title)
    QuickAction.AUTOMATION -> QuickActionTarget(ControlsRoute.VEHICLE_AUTOMATION, CockpitIcon.ENERGY,
        R.string.vehicle_automation_entry)
    QuickAction.SETTINGS -> QuickActionTarget(ControlsRoute.SETTINGS, CockpitIcon.SETTINGS, R.string.cockpit_settings)
    QuickAction.SEAT_MEMORY -> QuickActionTarget(ControlsRoute.SEAT_MEMORY, R.string.quick_actions_seat_memory, R.string.seat_memory_title) {
        Icon(painterResource(R.drawable.ic_quick_action_seat), contentDescription = null, modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary)
    }
    QuickAction.ROUTINES -> QuickActionTarget(ControlsRoute.ROUTINES, CockpitIcon.ENERGY, R.string.routines_title)
}
