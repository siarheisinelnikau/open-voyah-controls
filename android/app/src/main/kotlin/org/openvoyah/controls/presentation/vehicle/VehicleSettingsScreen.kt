package org.openvoyah.controls.presentation.vehicle

import androidx.compose.runtime.Composable
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.vehicle.HeadlightStatus
import org.openvoyah.controls.platform.ui.VehicleSettingsDestination
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitColumns
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitSection

@Composable
internal fun VehicleSettingsScreen(
    available: Set<VehicleSettingsDestination>, status: String?, lighting: HeadlightStatus, back: () -> Unit,
    open: (VehicleSettingsDestination) -> Unit, manual: () -> Unit, seatMemory: () -> Unit,
    serviceReminder: () -> Unit, serviceStatus: String?,
    monitoring: HeadlightMonitoringState, setMonitoring: (Boolean) -> Unit, inspectLightingSources: () -> Unit,
    automation: () -> Unit, assistance: () -> Unit,
) {
    CockpitDetail(label(R.string.vehicle_settings_title), label(R.string.vehicle_settings_description), back) {
        CockpitColumns(first = {
            HeadlightStatusSection(lighting, monitoring, setMonitoring, if (VehicleSettingsDestination.LIGHTING in available) {
                { open(VehicleSettingsDestination.LIGHTING) }
            } else null, inspectLightingSources)
            CockpitSection(label(R.string.vehicle_cabin_title), CockpitIcon.SETTINGS, label(R.string.vehicle_cabin_description)) {
                CockpitChoice(label(R.string.vehicle_seat_memory), onClick = seatMemory)
                if (VehicleSettingsDestination.INFOTAINMENT in available) {
                    CockpitChoice(label(R.string.vehicle_infotainment_open)) { open(VehicleSettingsDestination.INFOTAINMENT) }
                }
            }
        }, second = {
            CockpitChoice(label(R.string.vehicle_automation_entry), label(R.string.vehicle_automation_entry_summary), onClick = automation)
            CockpitSection(label(R.string.vehicle_assistance_title), CockpitIcon.BUTTONS) {
                CockpitChoice(label(R.string.factory_assistance_title), onClick = assistance)
                if (VehicleSettingsDestination.VEHICLE in available) {
                    CockpitChoice(label(R.string.vehicle_settings_open)) { open(VehicleSettingsDestination.VEHICLE) }
                }
                status?.let { CockpitFeedback(it, error = true) }
            }
            CockpitChoice(label(R.string.service_reminder_title), serviceStatus, onClick = serviceReminder)
            CockpitChoice(label(R.string.vehicle_manual), onClick = manual)
        })
    }
}

private fun label(id: Int): String = UiText.get(id)
