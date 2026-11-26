# Setting guides

Each entry below has its own explanation and before-and-after scene in English, Russian and Kazakh. Some entries also show an intermediate event so the trigger and result can be read in sequence.

The catalogue contains **294** guide definitions across these settings surfaces.

The inventory counts setting controls and configuration entry points. Repeated rows count by control family. For example, the passenger workspace has six app positions and two dock positions, with a scene that highlights the position being edited. Default voice seat levels use the selected seat and feature. Action permissions name the affected action. A page heading, status, Save, Cancel or a one-time vehicle action is outside the inventory unless it opens or edits a setting.

The shared dialog owns its animation and replay. These examples use sample data. The animation stops when the dialog closes or its lifecycle is hidden, and the final comparison is immediately visible with screen animations off.

## Home screen and apps (48)

App grid and its desktop, wallpaper, layout, icon, hide and permission editors. The factory home controls depend on firmware. Widget scale and settings require the Navigator card. The weather city appears when weather is enabled. [SettingGuidesHome.kt](../app/src/main/kotlin/org/openvoyah/controls/presentation/theme/SettingGuidesHome.kt)

| Setting | Resource key |
| --- | --- |
| Tile size | `apps_grid_tile_size` |
| Grid columns | `apps_grid_columns` |
| App icon colors | `apps_grid_icon_style` |
| Show app names | `apps_grid_show_labels` |
| App order | `apps_grid_sort_order` |
| Edit app order | `apps_grid_edit_manual_order` |
| Built-in shortcuts | `apps_grid_shortcuts` |
| Hidden apps | `apps_grid_hidden_apps` |
| Always show search in All apps | `apps_grid_show_search_field` |
| Hide firmware and service apps by default | `apps_grid_hide_by_default` |
| Extra desktops | `apps_desktop_enable` |
| Default desktop | `apps_desktop_default` |
| Desktop names | `apps_grid_desktop_names` |
| Back on favorites desktops | `apps_grid_favorites_back` |
| Smooth desktop switching | `apps_grid_smooth_paging` |
| Desktop wallpaper | `apps_wallpaper_title` |
| Desktop layouts | `desktop_layouts_settings` |
| Layout name | `desktop_layouts_name` |
| Desktop cards | `desktop_info_settings` |
| Desktop | `desktop_info_desktop` |
| Desktop card placement | `apps_grid_card_placement` |
| Show desktop card outlines | `apps_grid_card_outlines` |
| Clock format | `desktop_info_clock_format` |
| Media card style | `desktop_info_media_style` |
| When you tap the media card | `desktop_info_media_open_target` |
| Media card source | `desktop_info_media_source` |
| Show on headlight card | `desktop_info_headlights_content` |
| Edit phone favorites | `desktop_info_phone_edit` |
| Show on navigation card | `desktop_info_route_content` |
| When you tap the navigation card | `desktop_info_route_open_target` |
| Yandex Navigator instead of the Baidu map on the home screen | `home_navigator_card` |
| Navigator widget scale (more is larger) | `home_navigator_widget_scale` |
| Navigator widget settings: theme, map scale | `home_navigator_widget_settings` |
| Weather on the home screen (Open-Meteo) | `home_weather_card` |
| City for the weather when the car's location is unknown | `home_weather_city` |
| Voyah Music and Voyah Radio in the media card | `home_media_card_apps` |
| Hide Funn Audio, Ximalaya and Yunting in the media card | `home_media_card_hide_online` |
| Voyah Music and Voyah Radio on the instrument cluster | `home_cluster_card_apps` |
| Large player view | `driver_media_layout` |
| Compact navigation card | `driver_navigation_card` |
| Show Yandex Navigator on the driver display | `driver_navigator_open` |
| Restore media card | `driver_navigator_close` |
| Hide from the app list | `apps_hide` |
| Remove the white background (experimental) | `app_icon_plain` |
| Reorder pinned apps | `apps_grid_reorder_pins` |
| Show firmware and hidden apps | `app_picker_show_all` |
| Icon | `app_icon_title` |
| Permissions | `apps_permissions` |
| Add favorite | `desktop_info_phone_add` |
| Use the app's icon | `app_icon_reset` |

## Desktop card visibility and size (15)

Inside Desktop cards. Each card has its own scene. A card can be configured even when live vehicle readings for it are unavailable. [SettingGuidesCards.kt](../app/src/main/kotlin/org/openvoyah/controls/presentation/theme/SettingGuidesCards.kt)

| Setting | Resource key |
| --- | --- |
| Clock | `desktop_info_clock` |
| Maintenance | `desktop_info_service` |
| Media | `desktop_info_media` |
| Headlight mode | `desktop_info_headlights` |
| Phone favorites | `desktop_info_phone` |
| Navigation | `desktop_info_route` |
| Power Hold | `comfort_power_hold` |
| Car wash mode | `comfort_car_wash` |
| Factory FM/AM | `factory_radio_card` |
| Current trip | `trip_current` |
| Charge and fuel | `trip_levels` |
| Consumption over the last 2.5 km | `trip_usage_last` |
| Tyre pressure | `trip_tyres` |
| Odometer | `trip_odometer` |
| Suspension displacement | `trip_suspension` |

## Windows and displays (48)

The virtual display’s Details dialog, app-window profiles, central and second-display DPI, window swipe, app pairs and the passenger workspace. Features tied to an additional display or window service appear where supported. [SettingGuidesDisplays.kt](../app/src/main/kotlin/org/openvoyah/controls/presentation/theme/SettingGuidesDisplays.kt)

| Setting | Resource key |
| --- | --- |
| Window service | `window_hook_title` |
| Let Android reserve the panel strip (experimental) | `window_strip_title` |
| Minimum left margin | `app_window_panel_width` |
| App width | `app_window_width` |
| App scale (DPI) | `app_window_dpi` |
| App DPI defaults | `app_display_defaults_title` |
| Move windows with a swipe | `window_swipe_title` |
| Swipe | `window_swipe_fingers` |
| After a window leaves the central screen, show | `window_swipe_after` |
| Virtual display density | `display_density` |
| Show media | `display_preview_show_media` |
| Show route details | `display_preview_show_navigation` |
| Show playback progress | `display_preview_show_playback_progress` |
| Show album artwork | `display_preview_show_album_artwork` |
| Show playback controls | `display_preview_show_transport_controls` |
| Preview arrangement | `display_preview_arrangement` |
| First preview pane | `display_preview_pane_order` |
| Preview media source | `display_preview_media_source` |
| When selected player is unavailable | `display_preview_media_fallback` |
| Seek interval | `display_preview_seek_step` |
| Preview details | `display_preview_details` |
| Preview text size | `display_preview_text_size` |
| Preview palette | `display_preview_palette` |
| Side-by-side width | `display_preview_pane_balance` |
| Hide empty panes | `display_preview_hide_empty_panes` |
| Media subtitle | `display_preview_media_subtitle` |
| Show current road | `display_preview_show_current_road` |
| Show next road | `display_preview_show_next_road` |
| Road names | `display_preview_road_names` |
| Show distance to turn | `display_preview_show_turn_distance` |
| Distance units | `display_preview_distance_units` |
| Media access | `display_media_access` |
| Navigation feed | `display_navigation` |
| Passenger workspace | `passenger_workspace_title` |
| Compact tiles | `passenger_workspace_compact` |
| Show clock | `passenger_workspace_clock` |
| Show date | `passenger_workspace_date` |
| Open on Controls startup | `passenger_workspace_startup` |
| App slots 1–6 | `passenger_workspace_app_slot` |
| Dock slots 1–2 | `passenger_workspace_dock_slot` |
| Central screen apps | `app_display_defaults_central` |
| Built-in secondary screen apps | `app_display_defaults_secondary` |
| Left app | `app_pair_left` |
| Right app | `app_pair_right` |
| Window sizes | `app_pair_proportion` |
| Name (optional) | `app_pair_name` |
| Show in the dock | `app_pair_dock` |
| Restore defaults | `app_window_restore` |

## Hardware buttons, launcher panel and steering wheel (18)

Includes assignment, input device, press type, recognition and timing controls. The service and configured bindings determine which rows are available. [SettingGuidesButtons.kt](../app/src/main/kotlin/org/openvoyah/controls/presentation/theme/SettingGuidesButtons.kt)

| Setting | Resource key |
| --- | --- |
| Panel assignments enabled | `ui_69` |
| Capture hardware buttons | `ui_43` |
| Press | `oem_short` |
| Hold | `oem_hold` |
| This code on all devices | `ui_98` |
| Assignment enabled | `ui_99` |
| Android keyCode, for example 85 | `ui_91` |
| Button name | `ui_95` |
| Hold: 250–2000 ms | `ui_115` |
| Debounce: 0–500 ms | `ui_116` |
| OEM steering wheel buttons | `oem_steering` |
| OEM panel buttons · Frida | `ui_67` |
| Assignments | `ui_44` |
| Enable button service | `button_service_enable` |
| ＋ Detect a button press | `ui_53` |
| Add by code | `ui_54` |
| Change intervals | `ui_59` |
| Reset assignments | `ui_62` |

## Quick actions (28)

The shortcut bar, wide and compact arrangements and installed app shortcuts. Some controls depend on the selected bar scope, collapse mode or enabled shortcuts. [SettingGuidesDock.kt](../app/src/main/kotlin/org/openvoyah/controls/presentation/theme/SettingGuidesDock.kt)

| Setting | Resource key |
| --- | --- |
| Show shortcut bar | `quick_actions_enabled` |
| Show on | `quick_actions_area` |
| Hide on the Virtual display screen | `quick_actions_hide_on_virtual_display` |
| Show headlight status | `quick_actions_headlight_indicator` |
| Headlight status to show | `quick_actions_headlight_content` |
| When you tap headlight status | `quick_actions_headlight_tap` |
| Apps shortcut opens | `quick_actions_apps_destination` |
| Side on wide screens | `quick_actions_side` |
| Bar width on wide screens | `quick_actions_wide_width` |
| Shortcut position on wide screens | `quick_actions_rail_alignment` |
| Edge on compact screens | `quick_actions_compact_edge` |
| Show shortcut labels | `quick_actions_labels` |
| Shortcut labels on compact screens | `quick_actions_compact_labels` |
| Bar at startup | `quick_actions_start_state` |
| Collapse after choosing a shortcut | `quick_actions_collapse_after_use` |
| Collapse when idle | `quick_actions_idle_collapse` |
| Keep page width when the side bar collapses | `quick_actions_keep_side_inset` |
| Expand button size | `quick_actions_handle_size` |
| Expand button position on the side bar | `quick_actions_side_handle_position` |
| Expand button side on compact screens | `quick_actions_compact_handle_side` |
| Expand button color | `quick_actions_handle_accent` |
| Dim expand button when idle | `quick_actions_dim_idle_handle` |
| High-contrast expand button | `quick_actions_contrast_handle` |
| Label the expand button on compact screens | `quick_actions_compact_handle_label` |
| Larger shortcuts | `quick_actions_larger_shortcuts` |
| Routine shortcut | `quick_actions_routine_shortcut` |
| Shortcuts | `quick_actions_shortcuts` |
| App shortcuts | `quick_actions_app_shortcuts` |

## Voice and sound (38)

Recognition, offline models, optional online interpretation, seat defaults, window commands, voice contacts, command permissions, audio cues, capture display, media volume and ducking. [SettingGuidesVoice.kt](../app/src/main/kotlin/org/openvoyah/controls/presentation/theme/SettingGuidesVoice.kt)

| Setting | Resource key |
| --- | --- |
| Recognition | `voice_recognition` |
| Recognition language | `voice_language` |
| Engine | `voice_recognition_engine` |
| Online interpretation | `voice_online_title` |
| Use OpenRouter when a phrase is not in the catalog | `voice_online_switch` |
| OpenRouter API key | `voice_online_key` |
| Default command levels | `default_levels` |
| Window ventilation settings | `window_options` |
| Double driver window ventilation pulse | `double_smoke` |
| Double ventilation pulse | `double_ventilation` |
| Vehicle commands | `voice_vehicle_commands` |
| Voice audio | `voice_audio_settings` |
| Spoken feedback | `voice_spoken_feedback` |
| Speech rate | `voice_speech_rate` |
| Spoken feedback volume | `voice_speech_volume` |
| Feedback style | `voice_feedback_style` |
| Spoken reply channel | `voice_reply_channel` |
| Offline voice | `voice_offline_voice` |
| Play a short sound before listening | `voice_ready_cue` |
| Ready signal | `voice_ready_style` |
| Ready phrase | `voice_ready_phrase` |
| Ready sound volume | `voice_ready_cue_volume` |
| End of listening sound | `voice_end_listening_cue` |
| End sound volume | `voice_end_cue_volume` |
| Listening and popup | `voice_capture_popup` |
| Maximum listening time | `voice_listening_limit` |
| Show the result for | `voice_result_duration` |
| Show recognized phrase in popup | `voice_show_recognized_phrase` |
| Media volume at startup | `voice_startup_volume` |
| Media volume after the head unit starts | `voice_startup_volume_level` |
| Lower media volume while listening | `voice_duck_media` |
| Lower media during phone calls | `voice_duck_calls` |
| Media level while lowered | `voice_duck_level` |
| Voice settings and recovery | `ui_177` |
| Add voice contact | `ui_181` |
| Voice name, for example mom | `ui_193` |
| Phone number | `ui_194` |
| App… | `ui_188` |

## Appearance and launch (17)

Theme, typography, spacing, motion and the Open Controls launcher shortcut. The media launch cap is under Sound and media. [SettingGuidesAppearance.kt](../app/src/main/kotlin/org/openvoyah/controls/presentation/theme/SettingGuidesAppearance.kt)

| Setting | Resource key |
| --- | --- |
| Color theme | `appearance_palette` |
| Accent color | `appearance_accent` |
| Screen background | `appearance_background` |
| High contrast | `appearance_high_contrast` |
| Text size | `appearance_text_size` |
| Navigation label size | `appearance_navigation_label_size` |
| Page title size | `appearance_page_title_size` |
| Font family | `appearance_font_family` |
| Bold text | `appearance_bold_text` |
| Spacing | `appearance_spacing` |
| Corner style | `appearance_corners` |
| Animate screen transitions | `appearance_animate_routes` |
| Transition speed | `appearance_transition_speed` |
| Wallpaper dimming | `appearance_wallpaper_dimming` |
| Start screen | `appearance_start_screen` |
| Limit media volume on launch | `appearance_limit_media_on_launch` |
| Launch volume limit | `appearance_launch_media_limit` |

## Vehicle, maintenance and trips (22)

Headlight feedback, seat memory, adaptive brightness, playback comfort, service reminder, trip recording and gear-triggered vehicle settings. Device integrations can be unavailable. [SettingGuidesVehicle.kt](../app/src/main/kotlin/org/openvoyah/controls/presentation/theme/SettingGuidesVehicle.kt)

| Setting | Resource key |
| --- | --- |
| Monitor while Controls is open | `headlight_monitoring_open` |
| Open factory seat controls directly | `seat_memory_open_factory_on_tap` |
| Show upcoming service on the Vehicle screen | `service_reminder_show_on_vehicle` |
| Service interval (km) | `service_reminder_interval` |
| Current odometer (km) | `service_reminder_current` |
| Odometer at last service (km) | `service_reminder_last` |
| When to show the reminder | `service_reminder_lead_title` |
| Calendar interval (months, optional) | `service_reminder_calendar_interval` |
| Remind before the service date | `service_reminder_calendar_lead_title` |
| Last service date | `service_reminder_calendar_last_date` |
| Use saved light actions | `vehicle_automation_lights_enabled` |
| When Drive is selected | `vehicle_automation_on_drive` |
| When Park is selected | `vehicle_automation_on_park` |
| Also save automatically when a trip ends in P | `vehicle_automation_memory_enabled` |
| Pause when the driver’s door opens | `comfort_playback_door` |
| Pause on sleep and resume on wake | `comfort_playback_wake` |
| Adjust brightness to ambient light | `comfort_brightness_enabled` |
| Low-light brightness | `comfort_brightness_low` |
| Bright-light brightness | `comfort_brightness_high` |
| GPS route | `trip_gps_title` |
| Record trips automatically | `trips_automatic` |
| Allow access to vehicle readings | `trip_permissions` |

## Routines and automation (41)

Routine editor and startup schedule, conditional rule editor, extra conditions and seasonal climate presets. Saved entries repeat with the same controls. [SettingGuidesAutomation.kt](../app/src/main/kotlin/org/openvoyah/controls/presentation/theme/SettingGuidesAutomation.kt)

| Setting | Resource key |
| --- | --- |
| Routine name | `routines_name` |
| Page to open | `routines_page` |
| Desktop layout | `routines_layout` |
| Session theme | `routines_theme` |
| App to open | `routines_app` |
| Pin to top | `routines_pin` |
| Run selected routine at startup | `routines_startup_enabled` |
| Days of the week | `routines_days` |
| Startup frequency | `routines_frequency` |
| Startup delay | `routines_delay` |
| Limit to a time range | `routines_limit_hours` |
| From | `routines_time_start` |
| Until | `routines_time_end` |
| Enable rule | `rules_enabled` |
| Gearbox | `rules_gear` |
| Doors | `rules_doors` |
| Saved routine | `rules_routine` |
| Minimum interval | `rules_cooldown` |
| Additional conditions | `rules_extra_conditions` |
| Ambient light | `rules_light` |
| Battery charge | `rules_charge` |
| Outside temperature | `rules_temperature` |
| Limit to a time range | `rules_limit_hours` |
| From | `rules_time_start` |
| Until | `rules_time_end` |
| Driving mode | `rules_driving_mode` |
| Use this scenario | `seasonal_climate_enabled` |
| Minimum outside temperature, °C | `seasonal_climate_above_field` |
| Maximum outside temperature, °C | `seasonal_climate_below_field` |
| Gear | `seasonal_climate_gear` |
| Require all doors closed | `seasonal_climate_closed_doors` |
| Driver, °C | `seasonal_climate_driver` |
| Passenger, °C | `seasonal_climate_passenger` |
| Front fan, 1–8 | `seasonal_climate_fan` |
| Driver seat | `seasonal_climate_driver_seat` |
| Passenger seat | `seasonal_climate_passenger_seat` |
| Rule name | `rules_name` |
| Startup routine | `routines_startup` |
| Sort routines | `routines_sort` |
| Voice phrase (optional) | `routine_voice_phrase` |
| Steps | `routine_steps_title` |

## Language, input and system (17)

Controls language, keyboard, factory text dictionaries and overlays, backups, automatic update checks, allowed actions and diagnostic filtering. [SettingGuidesSystem.kt](../app/src/main/kotlin/org/openvoyah/controls/presentation/theme/SettingGuidesSystem.kt)

| Setting | Resource key |
| --- | --- |
| Allowed actions | `action_permissions_title` |
| Interface language | `cockpit_language` |
| Choose a keyboard | `keyboard_choice_section` |
| Factory apps language | `text_overlays_title` |
| Off | `text_overlays_off` |
| Russian dictionary | `cockpit_dictionary_russian` |
| English dictionary | `cockpit_dictionary_english` |
| Kazakh dictionary | `cockpit_dictionary_kazakh` |
| Android interface translation | `firmware_translation_heading` |
| Import JSON dictionary | `translation_import` |
| Disable translation | `translation_disable` |
| Restore previous dictionary | `translation_restore` |
| Apps | `text_overlays_apps_heading` |
| Check for updates on startup | `updates_startup_toggle` |
| Export app settings | `settings_archive_export` |
| Import app settings | `settings_archive_import` |
| Warnings and errors only (%d) | `diagnostics_problems_only` |
