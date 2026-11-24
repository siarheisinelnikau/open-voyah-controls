package org.openvoyah.controls.presentation.voice

import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.VoiceCatalog
import org.openvoyah.controls.core.voice.VoiceCommandUseCase
import org.openvoyah.controls.presentation.theme.CockpitIcon

internal enum class VoiceGuideCategory(val title: Int, val summary: Int, val icon: CockpitIcon) {
    APPS(R.string.voice_guide_group_apps, R.string.voice_guide_group_apps_summary, CockpitIcon.APPS),
    MEDIA(R.string.voice_guide_group_media, R.string.voice_guide_group_media_summary, CockpitIcon.PLAY),
    CLIMATE(R.string.voice_guide_group_climate, R.string.voice_guide_group_climate_summary, CockpitIcon.CLIMATE),
    SEATS(R.string.voice_guide_group_seats, R.string.voice_guide_group_seats_summary, CockpitIcon.CAR),
    LIGHTING(R.string.voice_guide_group_lighting, R.string.voice_guide_group_lighting_summary, CockpitIcon.LIGHT),
    WINDOWS(R.string.voice_guide_group_windows, R.string.voice_guide_group_windows_summary, CockpitIcon.DISPLAY),
    DRIVING(R.string.voice_guide_group_driving, R.string.voice_guide_group_driving_summary, CockpitIcon.ENERGY),
    VEHICLE(R.string.voice_guide_group_vehicle, R.string.voice_guide_group_vehicle_summary, CockpitIcon.CAR),
    CONTACTS(R.string.voice_guide_group_contacts, R.string.voice_guide_group_contacts_summary, CockpitIcon.VOICE),
}

private enum class CommandRequirement(val note: Int? = null) {
    LOCAL, APP(R.string.voice_guide_app), RESTART(R.string.voice_guide_restart), SPLIT(R.string.voice_guide_split),
    MEDIA(R.string.voice_guide_media), RATING(R.string.voice_guide_rating), VEHICLE(R.string.voice_guide_vehicle),
    PARK(R.string.voice_guide_park), MEMORY(R.string.voice_guide_memory), FRONT_CLIMATE(R.string.voice_guide_front_climate),
    UNAVAILABLE(R.string.voice_guide_unavailable), DIAL(R.string.voice_guide_dial),
}

internal data class VoiceCommandGuide(
    val command: VoiceCatalog.Command,
    val category: VoiceGuideCategory,
    val effect: String,
    val note: String?,
    val canAssignApp: Boolean,
) {
    private val searchable by lazy(LazyThreadSafetyMode.NONE) {
        (command.phrases() + effect).map(VoiceCatalog::normalize)
    }

    fun matches(query: String): Boolean = searchable.any { it.contains(query) }
}

internal fun describeVoiceCommand(command: VoiceCatalog.Command, assigned: Boolean): VoiceCommandGuide {
    val id = command.id()
    val contact = id.startsWith("Call_")
    val entry = if (contact) contactGuide else checkNotNull(commandGuides[id]) { "Missing guide for voice command: $id" }
    val canAssign = !contact && !VoiceCommandUseCase.isWeather(id) && entry.requirement != CommandRequirement.RESTART
    val usesAssignment = assigned && canAssign
    return VoiceCommandGuide(command, entry.category,
        UiText.get(if (usesAssignment) R.string.voice_guide_assigned else entry.effect),
        if (usesAssignment) null else entry.requirement.note?.let(UiText::get),
        canAssign)
}

internal fun filterVoiceGuides(
    guides: List<VoiceCommandGuide>,
    category: VoiceGuideCategory?,
    search: String,
): Map<VoiceGuideCategory, List<VoiceCommandGuide>> {
    val query = VoiceCatalog.normalize(search)
    val matched = guides.filter { guide ->
        (category == null || guide.category == category) &&
            (query.isBlank() || guide.matches(query))
    }.groupBy { it.category }
    return VoiceGuideCategory.entries.mapNotNull { group ->
        matched[group]?.let { commands ->
            group to if (group == VoiceGuideCategory.MEDIA) commands.sortedBy { mediaOrder[it.command.id()] ?: Int.MAX_VALUE }
                else commands
        }
    }.toMap()
}

private val mediaOrder = listOf(
    "MediaPlay", "MediaPause", "MediaNext", "MediaPrevious",
    "VolumeUp", "VolumeDown", "VolumeMute", "SetVolume", "Like", "RemoveLike", "DisLike",
).withIndex().associate { (index, id) -> id to index }

private data class CommandGuide(val category: VoiceGuideCategory, val effect: Int, val requirement: CommandRequirement)

private val contactGuide = CommandGuide(VoiceGuideCategory.CONTACTS, R.string.voice_effect_contact, CommandRequirement.DIAL)

private val commandGuides = mapOf(
    "AdaptiveLightOff" to CommandGuide(VoiceGuideCategory.LIGHTING, R.string.voice_effect_adaptive_light_off, CommandRequirement.VEHICLE),
    "AdaptiveLightOn" to CommandGuide(VoiceGuideCategory.LIGHTING, R.string.voice_effect_adaptive_light_on, CommandRequirement.VEHICLE),
    "Assistant" to CommandGuide(VoiceGuideCategory.APPS, R.string.voice_effect_assistant, CommandRequirement.LOCAL),
    "AutoMode" to CommandGuide(VoiceGuideCategory.LIGHTING, R.string.voice_effect_auto_mode, CommandRequirement.VEHICLE),
    "BackLightModeAuto" to CommandGuide(VoiceGuideCategory.LIGHTING, R.string.voice_effect_back_light_mode_auto, CommandRequirement.VEHICLE),
    "BackLightModeDay" to CommandGuide(VoiceGuideCategory.LIGHTING, R.string.voice_effect_back_light_mode_day, CommandRequirement.VEHICLE),
    "BackLightModeNight" to CommandGuide(VoiceGuideCategory.LIGHTING, R.string.voice_effect_back_light_mode_night, CommandRequirement.VEHICLE),
    "Camera" to CommandGuide(VoiceGuideCategory.APPS, R.string.voice_effect_camera, CommandRequirement.APP),
    "CarWashMode" to CommandGuide(VoiceGuideCategory.VEHICLE, R.string.voice_effect_car_wash_mode, CommandRequirement.PARK),
    "CloseAllWindowCommand" to CommandGuide(VoiceGuideCategory.WINDOWS, R.string.voice_effect_close_all_window_command, CommandRequirement.VEHICLE),
    "CloseDriverWindowCommand" to CommandGuide(VoiceGuideCategory.WINDOWS, R.string.voice_effect_close_driver_window_command, CommandRequirement.VEHICLE),
    "ClosePassengerWindowCommand" to CommandGuide(VoiceGuideCategory.WINDOWS, R.string.voice_effect_close_passenger_window_command, CommandRequirement.VEHICLE),
    "CloseSunroof" to CommandGuide(VoiceGuideCategory.WINDOWS, R.string.voice_effect_close_sunroof, CommandRequirement.VEHICLE),
    "CloseSunshade" to CommandGuide(VoiceGuideCategory.WINDOWS, R.string.voice_effect_close_sunshade, CommandRequirement.VEHICLE),
    "CloseTrunkCommand" to CommandGuide(VoiceGuideCategory.VEHICLE, R.string.voice_effect_close_trunk_command, CommandRequirement.PARK),
    "ComfortMode" to CommandGuide(VoiceGuideCategory.DRIVING, R.string.voice_effect_comfort_mode, CommandRequirement.VEHICLE),
    "ControlsSettings" to CommandGuide(VoiceGuideCategory.APPS, R.string.voice_effect_controls_settings, CommandRequirement.LOCAL),
    "DecreaseTemperature" to CommandGuide(VoiceGuideCategory.CLIMATE, R.string.voice_effect_decrease_temperature, CommandRequirement.VEHICLE),
    "DisableAllHeating" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_disable_all_heating, CommandRequirement.VEHICLE),
    "DisableDriverCondition" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_disable_driver_condition, CommandRequirement.VEHICLE),
    "DisableDriverHeat" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_disable_driver_heat, CommandRequirement.VEHICLE),
    "DisableDriverMassage" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_disable_driver_massage, CommandRequirement.VEHICLE),
    "DisablePassengerCondition" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_disable_passenger_condition, CommandRequirement.VEHICLE),
    "DisablePassengerHeat" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_disable_passenger_heat, CommandRequirement.VEHICLE),
    "DisablePassengerMassage" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_disable_passenger_massage, CommandRequirement.VEHICLE),
    "DisableRearLeftHeat" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_disable_rear_left_heat, CommandRequirement.VEHICLE),
    "DisableRearRightHeat" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_disable_rear_right_heat, CommandRequirement.VEHICLE),
    "DisableTrunkCommand" to CommandGuide(VoiceGuideCategory.VEHICLE, R.string.voice_effect_disable_trunk_command, CommandRequirement.PARK),
    "DisableWheelHeat" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_disable_wheel_heat, CommandRequirement.VEHICLE),
    "DisLike" to CommandGuide(VoiceGuideCategory.MEDIA, R.string.voice_effect_dis_like, CommandRequirement.RATING),
    "DriverA" to CommandGuide(VoiceGuideCategory.VEHICLE, R.string.voice_effect_driver_a, CommandRequirement.UNAVAILABLE),
    "DriverB" to CommandGuide(VoiceGuideCategory.VEHICLE, R.string.voice_effect_driver_b, CommandRequirement.UNAVAILABLE),
    "DriverC" to CommandGuide(VoiceGuideCategory.VEHICLE, R.string.voice_effect_driver_c, CommandRequirement.UNAVAILABLE),
    "EcoMode" to CommandGuide(VoiceGuideCategory.DRIVING, R.string.voice_effect_eco_mode, CommandRequirement.VEHICLE),
    "ElectroMode" to CommandGuide(VoiceGuideCategory.DRIVING, R.string.voice_effect_electro_mode, CommandRequirement.VEHICLE),
    "EnableAllHeating" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_all_heating, CommandRequirement.VEHICLE),
    "EnableConditionDriverLevelDefault" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_condition_driver_level_default, CommandRequirement.VEHICLE),
    "EnableConditionDriverLevelOne" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_condition_driver_level_one, CommandRequirement.VEHICLE),
    "EnableConditionDriverLevelTree" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_condition_driver_level_tree, CommandRequirement.VEHICLE),
    "EnableConditionDriverLevelTwo" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_condition_driver_level_two, CommandRequirement.VEHICLE),
    "EnableConditionPassengerLevelDefault" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_condition_passenger_level_default, CommandRequirement.VEHICLE),
    "EnableConditionPassengerLevelOne" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_condition_passenger_level_one, CommandRequirement.VEHICLE),
    "EnableConditionPassengerLevelTree" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_condition_passenger_level_tree, CommandRequirement.VEHICLE),
    "EnableConditionPassengerLevelTwo" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_condition_passenger_level_two, CommandRequirement.VEHICLE),
    "EnableHeatDriverLevelDefault" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_heat_driver_level_default, CommandRequirement.VEHICLE),
    "EnableHeatDriverLevelOne" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_heat_driver_level_one, CommandRequirement.VEHICLE),
    "EnableHeatDriverLevelTree" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_heat_driver_level_tree, CommandRequirement.VEHICLE),
    "EnableHeatDriverLevelTwo" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_heat_driver_level_two, CommandRequirement.VEHICLE),
    "EnableHeatPassengerLevelDefault" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_heat_passenger_level_default, CommandRequirement.VEHICLE),
    "EnableHeatPassengerLevelOne" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_heat_passenger_level_one, CommandRequirement.VEHICLE),
    "EnableHeatPassengerLevelTree" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_heat_passenger_level_tree, CommandRequirement.VEHICLE),
    "EnableHeatPassengerLevelTwo" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_heat_passenger_level_two, CommandRequirement.VEHICLE),
    "EnableHeatRearLeftLevelDefault" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_heat_rear_left_level_default, CommandRequirement.VEHICLE),
    "EnableHeatRearLeftLevelOne" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_heat_rear_left_level_one, CommandRequirement.VEHICLE),
    "EnableHeatRearLeftLevelTree" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_heat_rear_left_level_tree, CommandRequirement.VEHICLE),
    "EnableHeatRearLeftLevelTwo" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_heat_rear_left_level_two, CommandRequirement.VEHICLE),
    "EnableHeatRearRightLevelDefault" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_heat_rear_right_level_default, CommandRequirement.VEHICLE),
    "EnableHeatRearRightLevelOne" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_heat_rear_right_level_one, CommandRequirement.VEHICLE),
    "EnableHeatRearRightLevelTree" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_heat_rear_right_level_tree, CommandRequirement.VEHICLE),
    "EnableHeatRearRightLevelTwo" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_heat_rear_right_level_two, CommandRequirement.VEHICLE),
    "EnableMassageDriverLevelDefault" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_massage_driver_level_default, CommandRequirement.VEHICLE),
    "EnableMassageDriverLevelOne" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_massage_driver_level_one, CommandRequirement.VEHICLE),
    "EnableMassageDriverLevelTree" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_massage_driver_level_tree, CommandRequirement.VEHICLE),
    "EnableMassageDriverLevelTwo" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_massage_driver_level_two, CommandRequirement.VEHICLE),
    "EnableMassagePassengerLevelDefault" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_massage_passenger_level_default, CommandRequirement.VEHICLE),
    "EnableMassagePassengerLevelOne" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_massage_passenger_level_one, CommandRequirement.VEHICLE),
    "EnableMassagePassengerLevelTree" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_massage_passenger_level_tree, CommandRequirement.VEHICLE),
    "EnableMassagePassengerLevelTwo" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_massage_passenger_level_two, CommandRequirement.VEHICLE),
    "EnableMaxRecuperation" to CommandGuide(VoiceGuideCategory.DRIVING, R.string.voice_effect_enable_max_recuperation, CommandRequirement.VEHICLE),
    "EnableMinRecuperation" to CommandGuide(VoiceGuideCategory.DRIVING, R.string.voice_effect_enable_min_recuperation, CommandRequirement.VEHICLE),
    "EnableStandardRecuperation" to CommandGuide(VoiceGuideCategory.DRIVING, R.string.voice_effect_enable_standard_recuperation, CommandRequirement.VEHICLE),
    "EnableTrunkCommand" to CommandGuide(VoiceGuideCategory.VEHICLE, R.string.voice_effect_enable_trunk_command, CommandRequirement.PARK),
    "EnableWheelHeat" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_enable_wheel_heat, CommandRequirement.VEHICLE),
    "FastDecreaseTemperature" to CommandGuide(VoiceGuideCategory.CLIMATE, R.string.voice_effect_fast_decrease_temperature, CommandRequirement.VEHICLE),
    "FastIncreaseTemperature" to CommandGuide(VoiceGuideCategory.CLIMATE, R.string.voice_effect_fast_increase_temperature, CommandRequirement.VEHICLE),
    "FlowBody" to CommandGuide(VoiceGuideCategory.CLIMATE, R.string.voice_effect_flow_body, CommandRequirement.VEHICLE),
    "FlowBodyLegs" to CommandGuide(VoiceGuideCategory.CLIMATE, R.string.voice_effect_flow_body_legs, CommandRequirement.VEHICLE),
    "FlowLegs" to CommandGuide(VoiceGuideCategory.CLIMATE, R.string.voice_effect_flow_legs, CommandRequirement.VEHICLE),
    "FlowWindow" to CommandGuide(VoiceGuideCategory.CLIMATE, R.string.voice_effect_flow_window, CommandRequirement.VEHICLE),
    "FlowWindowLegs" to CommandGuide(VoiceGuideCategory.CLIMATE, R.string.voice_effect_flow_window_legs, CommandRequirement.VEHICLE),
    "ForcedElectroMode" to CommandGuide(VoiceGuideCategory.DRIVING, R.string.voice_effect_forced_electro_mode, CommandRequirement.VEHICLE),
    "FuelMode" to CommandGuide(VoiceGuideCategory.DRIVING, R.string.voice_effect_fuel_mode, CommandRequirement.VEHICLE),
    "GetFromMemoryCellOne" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_get_from_memory_cell_one, CommandRequirement.MEMORY),
    "GetFromMemoryCellThree" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_get_from_memory_cell_three, CommandRequirement.MEMORY),
    "GetFromMemoryCellTwo" to CommandGuide(VoiceGuideCategory.SEATS, R.string.voice_effect_get_from_memory_cell_two, CommandRequirement.MEMORY),
    "HybridMode" to CommandGuide(VoiceGuideCategory.DRIVING, R.string.voice_effect_hybrid_mode, CommandRequirement.VEHICLE),
    "IncreaseTemperature" to CommandGuide(VoiceGuideCategory.CLIMATE, R.string.voice_effect_increase_temperature, CommandRequirement.VEHICLE),
    "IndividualMode" to CommandGuide(VoiceGuideCategory.DRIVING, R.string.voice_effect_individual_mode, CommandRequirement.VEHICLE),
    "IntellectMode" to CommandGuide(VoiceGuideCategory.DRIVING, R.string.voice_effect_intellect_mode, CommandRequirement.VEHICLE),
    "LampOff" to CommandGuide(VoiceGuideCategory.LIGHTING, R.string.voice_effect_lamp_off, CommandRequirement.PARK),
    "Like" to CommandGuide(VoiceGuideCategory.MEDIA, R.string.voice_effect_like, CommandRequirement.RATING),
    "LowBeam" to CommandGuide(VoiceGuideCategory.LIGHTING, R.string.voice_effect_low_beam, CommandRequirement.VEHICLE),
    "Magic" to CommandGuide(VoiceGuideCategory.VEHICLE, R.string.voice_effect_magic, CommandRequirement.PARK),
    "MediaNext" to CommandGuide(VoiceGuideCategory.MEDIA, R.string.voice_effect_media_next, CommandRequirement.MEDIA),
    "MediaPause" to CommandGuide(VoiceGuideCategory.MEDIA, R.string.voice_effect_media_pause, CommandRequirement.MEDIA),
    "MediaPlay" to CommandGuide(VoiceGuideCategory.MEDIA, R.string.voice_effect_media_play, CommandRequirement.MEDIA),
    "MediaPrevious" to CommandGuide(VoiceGuideCategory.MEDIA, R.string.voice_effect_media_previous, CommandRequirement.MEDIA),
    "Music" to CommandGuide(VoiceGuideCategory.APPS, R.string.voice_effect_music, CommandRequirement.APP),
    "Navigator" to CommandGuide(VoiceGuideCategory.APPS, R.string.voice_effect_navigator, CommandRequirement.APP),
    "OffroadMode" to CommandGuide(VoiceGuideCategory.DRIVING, R.string.voice_effect_offroad_mode, CommandRequirement.VEHICLE),
    "OpenAllWindowCommand" to CommandGuide(VoiceGuideCategory.WINDOWS, R.string.voice_effect_open_all_window_command, CommandRequirement.VEHICLE),
    "OpenDriverWindowCommand" to CommandGuide(VoiceGuideCategory.WINDOWS, R.string.voice_effect_open_driver_window_command, CommandRequirement.VEHICLE),
    "OpenElectricityPort" to CommandGuide(VoiceGuideCategory.VEHICLE, R.string.voice_effect_open_electricity_port, CommandRequirement.PARK),
    "OpenFuelPort" to CommandGuide(VoiceGuideCategory.VEHICLE, R.string.voice_effect_open_fuel_port, CommandRequirement.PARK),
    "OpenPassengerWindowCommand" to CommandGuide(VoiceGuideCategory.WINDOWS, R.string.voice_effect_open_passenger_window_command, CommandRequirement.VEHICLE),
    "OpenSunroof" to CommandGuide(VoiceGuideCategory.WINDOWS, R.string.voice_effect_open_sunroof, CommandRequirement.VEHICLE),
    "OpenSunroofVentilation" to CommandGuide(VoiceGuideCategory.WINDOWS, R.string.voice_effect_open_sunroof_ventilation, CommandRequirement.VEHICLE),
    "OpenSunroofWithPercent" to CommandGuide(VoiceGuideCategory.WINDOWS, R.string.voice_effect_open_sunroof_with_percent, CommandRequirement.VEHICLE),
    "OpenSunshade" to CommandGuide(VoiceGuideCategory.WINDOWS, R.string.voice_effect_open_sunshade, CommandRequirement.VEHICLE),
    "OpenSunshadeWithPercent" to CommandGuide(VoiceGuideCategory.WINDOWS, R.string.voice_effect_open_sunshade_with_percent, CommandRequirement.VEHICLE),
    "OpenTrunkCommand" to CommandGuide(VoiceGuideCategory.VEHICLE, R.string.voice_effect_open_trunk_command, CommandRequirement.PARK),
    "PositionLamp" to CommandGuide(VoiceGuideCategory.LIGHTING, R.string.voice_effect_position_lamp, CommandRequirement.PARK),
    "PowerHold" to CommandGuide(VoiceGuideCategory.VEHICLE, R.string.voice_effect_power_hold, CommandRequirement.PARK),
    "Radio" to CommandGuide(VoiceGuideCategory.APPS, R.string.voice_effect_radio, CommandRequirement.APP),
    "RearFog" to CommandGuide(VoiceGuideCategory.LIGHTING, R.string.voice_effect_rear_fog, CommandRequirement.VEHICLE),
    "RebootTbox" to CommandGuide(VoiceGuideCategory.VEHICLE, R.string.voice_effect_reboot_tbox, CommandRequirement.VEHICLE),
    "RemoveLike" to CommandGuide(VoiceGuideCategory.MEDIA, R.string.voice_effect_remove_like, CommandRequirement.RATING),
    "RestartMusic" to CommandGuide(VoiceGuideCategory.APPS, R.string.voice_effect_restart_music, CommandRequirement.RESTART),
    "RestartNavigator" to CommandGuide(VoiceGuideCategory.APPS, R.string.voice_effect_restart_navigator, CommandRequirement.RESTART),
    "SaveMode" to CommandGuide(VoiceGuideCategory.DRIVING, R.string.voice_effect_save_mode, CommandRequirement.VEHICLE),
    "SaveModeWithSOC" to CommandGuide(VoiceGuideCategory.DRIVING, R.string.voice_effect_save_mode_with_soc, CommandRequirement.VEHICLE),
    "Sentry" to CommandGuide(VoiceGuideCategory.VEHICLE, R.string.voice_effect_sentry, CommandRequirement.PARK),
    "SetBrightness" to CommandGuide(VoiceGuideCategory.LIGHTING, R.string.voice_effect_set_brightness, CommandRequirement.VEHICLE),
    "SetDriverTemperature" to CommandGuide(VoiceGuideCategory.CLIMATE, R.string.voice_effect_set_driver_temperature, CommandRequirement.FRONT_CLIMATE),
    "SetFanSpeed" to CommandGuide(VoiceGuideCategory.CLIMATE, R.string.voice_effect_set_fan_speed, CommandRequirement.FRONT_CLIMATE),
    "SetPassengerTemperature" to CommandGuide(VoiceGuideCategory.CLIMATE, R.string.voice_effect_set_passenger_temperature, CommandRequirement.FRONT_CLIMATE),
    "SetVolume" to CommandGuide(VoiceGuideCategory.MEDIA, R.string.voice_effect_set_volume, CommandRequirement.LOCAL),
    "SmokeCommand" to CommandGuide(VoiceGuideCategory.WINDOWS, R.string.voice_effect_smoke_command, CommandRequirement.VEHICLE),
    "SnowMode" to CommandGuide(VoiceGuideCategory.DRIVING, R.string.voice_effect_snow_mode, CommandRequirement.VEHICLE),
    "Split" to CommandGuide(VoiceGuideCategory.APPS, R.string.voice_effect_split, CommandRequirement.SPLIT),
    "SportMode" to CommandGuide(VoiceGuideCategory.DRIVING, R.string.voice_effect_sport_mode, CommandRequirement.VEHICLE),
    "SystemSettings" to CommandGuide(VoiceGuideCategory.APPS, R.string.voice_effect_system_settings, CommandRequirement.LOCAL),
    "VentilationCommand" to CommandGuide(VoiceGuideCategory.WINDOWS, R.string.voice_effect_ventilation_command, CommandRequirement.VEHICLE),
    "VolumeDown" to CommandGuide(VoiceGuideCategory.MEDIA, R.string.voice_effect_volume_down, CommandRequirement.LOCAL),
    "VolumeMute" to CommandGuide(VoiceGuideCategory.MEDIA, R.string.voice_effect_volume_mute, CommandRequirement.LOCAL),
    "VolumeUp" to CommandGuide(VoiceGuideCategory.MEDIA, R.string.voice_effect_volume_up, CommandRequirement.LOCAL),
    "Weather" to CommandGuide(VoiceGuideCategory.VEHICLE, R.string.voice_effect_weather, CommandRequirement.UNAVAILABLE),
    "WeatherTomorrow" to CommandGuide(VoiceGuideCategory.VEHICLE, R.string.voice_effect_weather_tomorrow, CommandRequirement.UNAVAILABLE),
    "WiperMaintain" to CommandGuide(VoiceGuideCategory.VEHICLE, R.string.voice_effect_wiper_maintain, CommandRequirement.PARK),
)

internal val guidedVoiceCommandIds: Set<String> get() = commandGuides.keys
