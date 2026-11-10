package org.openvoyah.controls.presentation.vehicle

import androidx.compose.runtime.Composable
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.platform.ui.VehicleSettingsDestination
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitColumns
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitSection

@Composable
internal fun VehicleSettingsScreen(
    available: Set<VehicleSettingsDestination>, status: String?, back: () -> Unit,
    open: (VehicleSettingsDestination) -> Unit, manual: () -> Unit,
) {
    CockpitDetail(label(R.string.vehicle_settings_title), label(R.string.vehicle_settings_description), back) {
        CockpitColumns(first = {
            CockpitSection(label(R.string.vehicle_assistance_title), CockpitIcon.BUTTONS, label(R.string.vehicle_assistance_description)) {
                if (VehicleSettingsDestination.VEHICLE in available) {
                    CockpitAction(label(R.string.vehicle_settings_open), { open(VehicleSettingsDestination.VEHICLE) }, primary = true)
                } else CockpitFeedback(label(R.string.vehicle_settings_unavailable))
                CockpitAction(label(R.string.vehicle_manual), manual)
                status?.let { CockpitFeedback(it, error = true) }
            }
        }, second = {
            CockpitSection(label(R.string.vehicle_cabin_title), CockpitIcon.SETTINGS, label(R.string.vehicle_cabin_description)) {
                if (VehicleSettingsDestination.INFOTAINMENT in available) {
                    CockpitChoice(label(R.string.vehicle_infotainment_open)) { open(VehicleSettingsDestination.INFOTAINMENT) }
                }
            }
            CockpitSection(label(R.string.vehicle_energy_title), CockpitIcon.ENERGY, label(R.string.vehicle_energy_description)) { }
        })
    }
}

private fun label(id: Int): String = UiText.get(id)
