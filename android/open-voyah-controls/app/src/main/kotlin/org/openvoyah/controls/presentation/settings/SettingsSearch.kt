package org.openvoyah.controls.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.presentation.home.HomeActionRow
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitTextField
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout

/** The parts of the sections built from [SettingsSectionPage]; the name is the key a search result opens. */
internal enum class SettingsPartId(val section: SettingsSection, val title: Int, val icon: CockpitIcon) {
    HOME_GRID(SettingsSection.HOME_SCREEN, R.string.settings_part_grid, CockpitIcon.APPS),
    HOME_DESKTOPS(SettingsSection.HOME_SCREEN, R.string.settings_part_desktops, CockpitIcon.HOME),
    HOME_CARDS(SettingsSection.HOME_SCREEN, R.string.settings_part_cards, CockpitIcon.APPS),
    HOME_FACTORY(SettingsSection.HOME_SCREEN, R.string.settings_part_factory_home, CockpitIcon.FIRMWARE),
    WINDOWS_APPS(SettingsSection.WINDOWS, R.string.settings_part_app_windows, CockpitIcon.DISPLAY),
    WINDOWS_DISPLAYS(SettingsSection.WINDOWS, R.string.settings_part_displays, CockpitIcon.DISPLAY),
    BUTTONS_PANEL(SettingsSection.BUTTONS, R.string.settings_part_panel, CockpitIcon.BUTTONS),
    BUTTONS_STEERING(SettingsSection.BUTTONS, R.string.settings_part_steering, CockpitIcon.CAR),
    BUTTONS_HARDWARE(SettingsSection.BUTTONS, R.string.settings_part_hardware, CockpitIcon.SETTINGS),
    BUTTONS_DOCK(SettingsSection.BUTTONS, R.string.settings_part_dock, CockpitIcon.APPS),
    SOUND_STARTUP(SettingsSection.SOUND, R.string.settings_part_startup_volume, CockpitIcon.PLAY),
    SOUND_DUCKING(SettingsSection.SOUND, R.string.settings_part_ducking, CockpitIcon.VOICE),
    SOUND_MEDIA(SettingsSection.SOUND, R.string.settings_part_media, CockpitIcon.PLAY),
    VEHICLE_MAIN(SettingsSection.VEHICLE, R.string.settings_part_vehicle_main, CockpitIcon.CAR),
    VEHICLE_SERVICE(SettingsSection.VEHICLE, R.string.service_reminder_title, CockpitIcon.CAR),
    VEHICLE_ASSISTANCE(SettingsSection.VEHICLE, R.string.factory_assistance_title, CockpitIcon.CAR),
    AUTOMATION_VEHICLE(SettingsSection.AUTOMATION, R.string.settings_part_automation_vehicle, CockpitIcon.LIGHT),
    AUTOMATION_ROUTINES(SettingsSection.AUTOMATION, R.string.settings_my_routines, CockpitIcon.ROUTINE),
    AUTOMATION_RULES(SettingsSection.AUTOMATION, R.string.vehicle_automation_rules, CockpitIcon.ROUTINE),
    AUTOMATION_CLIMATE(SettingsSection.AUTOMATION, R.string.seasonal_climate_title, CockpitIcon.CLIMATE),
    APPEARANCE_THEME(SettingsSection.APPEARANCE, R.string.settings_part_theme, CockpitIcon.LIGHT),
    APPEARANCE_TEXT(SettingsSection.APPEARANCE, R.string.settings_part_text, CockpitIcon.LANGUAGE),
    APPEARANCE_SHAPE(SettingsSection.APPEARANCE, R.string.settings_part_shape, CockpitIcon.APPS),
    APPEARANCE_MOTION(SettingsSection.APPEARANCE, R.string.settings_part_motion, CockpitIcon.PLAY),
    LANGUAGE_APP(SettingsSection.LANGUAGE, R.string.settings_part_app_language, CockpitIcon.LANGUAGE),
    LANGUAGE_FACTORY(SettingsSection.LANGUAGE, R.string.settings_part_factory_language, CockpitIcon.FIRMWARE),
    SYSTEM_UPDATES(SettingsSection.SYSTEM, R.string.settings_part_service, CockpitIcon.DOWNLOAD),
    SYSTEM_BACKUP(SettingsSection.SYSTEM, R.string.settings_part_backup, CockpitIcon.DOWNLOAD),
    SYSTEM_START(SettingsSection.SYSTEM, R.string.settings_part_start, CockpitIcon.HOME),
    SYSTEM_CONNECTION(SettingsSection.SYSTEM, R.string.settings_part_connection, CockpitIcon.CAR),
    SYSTEM_FACTORY(SettingsSection.SYSTEM, R.string.settings_part_factory_profiles, CockpitIcon.LOCK),
    SYSTEM_DIAGNOSTICS(SettingsSection.SYSTEM, R.string.settings_part_diagnostics, CockpitIcon.SEARCH),
    SYSTEM_PERMISSIONS(SettingsSection.SYSTEM, R.string.action_permissions_title, CockpitIcon.LOCK),
    SYSTEM_ABOUT(SettingsSection.SYSTEM, R.string.settings_part_about, CockpitIcon.SYSTEM),
    SYSTEM_COMPANIONS(SettingsSection.SYSTEM, R.string.companion_apps_title, CockpitIcon.APPS);

    companion object {
        fun of(key: String?): SettingsPartId? = entries.firstOrNull { it.name == key }
    }
}

/** Builds the row of [id] for a section page. */
internal fun settingsPart(id: SettingsPartId, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
    SettingsPart(id.name, UiText.get(id.title), id.icon, content)

/**
 * One searchable setting. [route] is a page of its own (for example Quick actions) or the section page; [part] selects
 * the part of a section page. The labels are the ones the setting carries on screen.
 */
internal data class SettingsEntry(val title: Int, val section: SettingsSection, val part: SettingsPartId? = null,
                                  val route: ControlsRoute = section.route)

private fun entries(part: SettingsPartId, vararg titles: Int) = titles.map { SettingsEntry(it, part.section, part) }
private fun page(section: SettingsSection, route: ControlsRoute, vararg titles: Int) =
    titles.map { SettingsEntry(it, section, null, route) }

internal val SETTINGS_INDEX: List<SettingsEntry> = buildList {
    addAll(entries(SettingsPartId.HOME_GRID, R.string.apps_grid_tile_size, R.string.apps_grid_columns, R.string.apps_grid_icon_style,
        R.string.apps_grid_show_labels, R.string.apps_grid_sort_order, R.string.apps_grid_shortcuts, R.string.apps_grid_hidden_apps,
        R.string.apps_grid_show_search_field, R.string.apps_grid_hide_by_default))
    addAll(entries(SettingsPartId.HOME_DESKTOPS, R.string.apps_desktop_enable, R.string.apps_desktop_default,
        R.string.apps_grid_desktop_names, R.string.apps_grid_favorites_back, R.string.apps_grid_smooth_paging,
        R.string.apps_wallpaper_title, R.string.appearance_wallpaper_dimming, R.string.desktop_layouts_settings))
    addAll(entries(SettingsPartId.HOME_CARDS, R.string.desktop_info_settings, R.string.apps_grid_card_placement,
        R.string.apps_grid_card_outlines))
    addAll(entries(SettingsPartId.HOME_FACTORY, R.string.home_navigator_card, R.string.home_navigator_widget_scale,
        R.string.home_navigator_widget_settings, R.string.home_weather_card, R.string.home_weather_city,
        R.string.home_media_card_apps))
    addAll(entries(SettingsPartId.WINDOWS_APPS, R.string.window_hook_title, R.string.app_display_defaults_title,
        R.string.window_swipe_title, R.string.window_strip_title, R.string.settings_app_scale_hint_title))
    addAll(entries(SettingsPartId.WINDOWS_DISPLAYS, R.string.virtual_display, R.string.display_density, R.string.cluster_media_share,
        R.string.display_preview_details, R.string.display_preview_arrangement, R.string.display_navigation))
    addAll(entries(SettingsPartId.BUTTONS_PANEL, R.string.ui_69, R.string.ui_67))
    addAll(entries(SettingsPartId.BUTTONS_STEERING, R.string.oem_steering))
    addAll(entries(SettingsPartId.BUTTONS_HARDWARE, R.string.ui_43, R.string.ui_44, R.string.ui_53, R.string.ui_54, R.string.ui_55,
        R.string.ui_62, R.string.button_service_enable))
    addAll(entries(SettingsPartId.BUTTONS_DOCK, R.string.quick_actions_title, R.string.quick_actions_enabled,
        R.string.quick_actions_collapse_after_use, R.string.quick_actions_headlight_indicator, R.string.quick_actions_hide_on_virtual_display,
        R.string.quick_actions_idle_collapse))
    addAll(page(SettingsSection.VOICE, ControlsRoute.VOICE, R.string.voice_options_title, R.string.quick_voice_try,
        R.string.voice_recognition, R.string.voice_language, R.string.voice_recognition_engine, R.string.voice_online_title,
        R.string.default_levels, R.string.window_options, R.string.voice_vehicle_commands, R.string.voice_audio_settings,
        R.string.voice_spoken_feedback, R.string.voice_offline_voice, R.string.voice_ready_cue, R.string.voice_end_listening_cue,
        R.string.voice_capture_popup, R.string.ui_181))
    addAll(entries(SettingsPartId.SOUND_STARTUP, R.string.voice_startup_volume, R.string.voice_startup_volume_level,
        R.string.appearance_limit_media_on_launch))
    addAll(entries(SettingsPartId.SOUND_DUCKING, R.string.voice_duck_media, R.string.voice_duck_calls, R.string.voice_duck_level))
    addAll(entries(SettingsPartId.SOUND_MEDIA, R.string.display_media_access))
    addAll(entries(SettingsPartId.VEHICLE_MAIN, R.string.vehicle_settings_title, R.string.headlight_mode_title,
        R.string.vehicle_settings_open, R.string.vehicle_infotainment_open, R.string.vehicle_manual))
    // Seat memory keeps its page: the steering wheel keys and the factory Seats app open it with a driver selected.
    addAll(page(SettingsSection.VEHICLE, ControlsRoute.SEAT_MEMORY, R.string.vehicle_seat_memory))
    addAll(entries(SettingsPartId.VEHICLE_SERVICE, R.string.service_reminder_title))
    addAll(entries(SettingsPartId.VEHICLE_ASSISTANCE, R.string.factory_assistance_title,
        R.string.factory_assistance_part_subscription, R.string.factory_assistance_part_controller,
        R.string.factory_assistance_part_equipment))
    addAll(entries(SettingsPartId.AUTOMATION_VEHICLE, R.string.vehicle_automation_lights, R.string.vehicle_automation_drive_memory))
    addAll(entries(SettingsPartId.AUTOMATION_ROUTINES, R.string.routines_title, R.string.settings_my_routines))
    addAll(entries(SettingsPartId.AUTOMATION_RULES, R.string.vehicle_automation_rules))
    addAll(entries(SettingsPartId.AUTOMATION_CLIMATE, R.string.seasonal_climate_title))
    addAll(entries(SettingsPartId.APPEARANCE_THEME, R.string.appearance_palette, R.string.appearance_accent,
        R.string.appearance_high_contrast, R.string.appearance_background))
    addAll(entries(SettingsPartId.APPEARANCE_TEXT, R.string.appearance_text_size, R.string.appearance_navigation_label_size,
        R.string.appearance_page_title_size, R.string.appearance_font_family, R.string.appearance_bold_text))
    addAll(entries(SettingsPartId.APPEARANCE_SHAPE, R.string.appearance_spacing, R.string.appearance_corners))
    addAll(entries(SettingsPartId.APPEARANCE_MOTION, R.string.appearance_animate_routes, R.string.appearance_transition_speed))
    addAll(entries(SettingsPartId.LANGUAGE_APP, R.string.cockpit_language, R.string.keyboard_title))
    addAll(entries(SettingsPartId.LANGUAGE_FACTORY, R.string.text_overlays_title, R.string.firmware_translation_heading,
        R.string.cockpit_dictionary_russian, R.string.translation_import, R.string.cluster_title))
    addAll(entries(SettingsPartId.SYSTEM_UPDATES, R.string.updates_title, R.string.ui_61))
    addAll(entries(SettingsPartId.SYSTEM_PERMISSIONS, R.string.action_permissions_title))
    addAll(entries(SettingsPartId.SYSTEM_BACKUP, R.string.settings_archive_export, R.string.settings_archive_import))
    addAll(entries(SettingsPartId.SYSTEM_START, R.string.appearance_start_screen))
    addAll(entries(SettingsPartId.SYSTEM_CONNECTION, R.string.ui_73, R.string.ui_75, R.string.ui_63))
    addAll(entries(SettingsPartId.SYSTEM_FACTORY, R.string.profile_restore))
    addAll(entries(SettingsPartId.SYSTEM_DIAGNOSTICS, R.string.diagnostics_title, R.string.vehicle_automation_diagnostics))
    addAll(entries(SettingsPartId.SYSTEM_ABOUT, R.string.system_version_copy, R.string.system_restart_app, R.string.system_restart_media,
        R.string.system_reboot,
        R.string.activation_restore))
    addAll(entries(SettingsPartId.SYSTEM_COMPANIONS, R.string.companion_youtube, R.string.companion_music, R.string.companion_radio))
}

/** Settings whose label, part or section contains every word of [query], in the order of the Settings page. */
internal fun searchSettings(query: String, label: (Int) -> String = UiText::get): List<SettingsEntry> {
    val words = query.lowercase(Locale.ROOT).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    return SETTINGS_INDEX.filter { entry ->
        val text = (label(entry.title) + " " + (entry.part?.let { label(it.title) } ?: "")).lowercase(Locale.ROOT)
            .replace('ё', 'е')
        words.all { text.contains(it.replace('ё', 'е')) }
    }.distinctBy { it.title to it.route }
}

/** "Section · part" under a search result or a recent change. */
internal fun SettingsEntry.place(label: (Int) -> String = UiText::get): String =
    listOfNotNull(label(section.title), part?.let { label(it.title) }, routeTitle(route)?.takeIf { route != section.route }?.let(label))
        .distinct().joinToString(" · ")

internal fun routeTitle(route: ControlsRoute): Int? = when (route) {
    ControlsRoute.QUICK_ACTIONS -> R.string.quick_actions_title
    ControlsRoute.ROUTINES -> R.string.routines_title
    ControlsRoute.SEAT_MEMORY -> R.string.vehicle_seat_memory
    ControlsRoute.SERVICE_REMINDER -> R.string.service_reminder_title
    ControlsRoute.SEASONAL_CLIMATE -> R.string.seasonal_climate_title
    ControlsRoute.CONDITIONAL_RULES -> R.string.vehicle_automation_rules
    ControlsRoute.DISPLAY -> R.string.virtual_display
    ControlsRoute.TEXT_OVERLAYS -> R.string.text_overlays_title
    ControlsRoute.CLUSTER -> R.string.cluster_title
    ControlsRoute.KEYBOARD -> R.string.keyboard_title
    ControlsRoute.ACTION_PERMISSIONS -> R.string.action_permissions_title
    ControlsRoute.UPDATES -> R.string.updates_title
    ControlsRoute.FACTORY_ASSISTANCE -> R.string.factory_assistance_title
    ControlsRoute.DIAGNOSTICS -> R.string.diagnostics_title
    else -> SettingsSection.of(route)?.title
}

/** The section a page belongs to, for the recent changes and the search results. */
internal fun sectionOf(route: ControlsRoute): SettingsSection? = SettingsSection.of(route) ?: when (route) {
    ControlsRoute.QUICK_ACTIONS -> SettingsSection.BUTTONS
    ControlsRoute.DISPLAY -> SettingsSection.WINDOWS
    ControlsRoute.SEAT_MEMORY, ControlsRoute.SERVICE_REMINDER, ControlsRoute.FACTORY_ASSISTANCE -> SettingsSection.VEHICLE
    ControlsRoute.ROUTINES, ControlsRoute.SEASONAL_CLIMATE, ControlsRoute.CONDITIONAL_RULES -> SettingsSection.AUTOMATION
    ControlsRoute.TEXT_OVERLAYS, ControlsRoute.CLUSTER, ControlsRoute.KEYBOARD -> SettingsSection.LANGUAGE
    ControlsRoute.ACTION_PERMISSIONS, ControlsRoute.UPDATES, ControlsRoute.DIAGNOSTICS -> SettingsSection.SYSTEM
    else -> null
}

/**
 * The Settings page: search, the recent changes and the ten sections. [open] receives the part a search result or a
 * recent change selects.
 */
@Composable
internal fun SettingsHub(open: (ControlsRoute, String?) -> Unit) {
    val layout = LocalCockpitLayout.current
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val recent = remember(context) { RecentSettings(context).read() }
    CockpitTextField(query, { query = it.take(60) }, UiText.get(R.string.settings_search), Modifier.fillMaxWidth().testTag("settings_search"))
    if (query.isNotBlank()) {
        val found = searchSettings(query)
        if (found.isEmpty()) {
            Text(UiText.get(R.string.settings_search_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        found.forEach { entry ->
            HomeActionRow(UiText.get(entry.title), entry.place(), entry.section.icon, { open(entry.route, entry.part?.name) },
                Modifier.testTag("settings_result"))
        }
        return
    }
    if (recent.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(layout.spacing(6.dp))) {
            Text(UiText.get(R.string.settings_recent), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                recent.forEach { change ->
                    CockpitAction(change.label(), { open(change.route, change.part?.name) }, Modifier.testTag("settings_recent"))
                }
            }
        }
    }
    val spacing = Arrangement.spacedBy(layout.spacing(12.dp))
    val wideEnough = layout.columnsMinimum(740.dp, LocalDensity.current.fontScale, layout.navigationLabelScale)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= wideEnough) 2 else 1
        Column(verticalArrangement = spacing) {
            SettingsSection.entries.chunked(columns).forEach { row ->
                // Both tiles of a row take the taller one's height, also when one summary needs fewer lines.
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = spacing) {
                    row.forEach { section ->
                        HomeActionRow(UiText.get(section.title), UiText.get(section.summary), section.icon,
                            { open(section.route, null) },
                            Modifier.weight(1f).fillMaxHeight().testTag("settings_section_" + section.name))
                    }
                    if (columns == 2 && row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
