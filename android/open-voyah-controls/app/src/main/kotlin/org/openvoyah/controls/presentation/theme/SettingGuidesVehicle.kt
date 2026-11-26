package org.openvoyah.controls.presentation.theme

import org.openvoyah.controls.R
import org.openvoyah.controls.presentation.theme.CockpitIcon.*
import org.openvoyah.controls.presentation.theme.Ink.*

internal val vehicleGuides = mapOf(
    R.string.headlight_monitoring_open to SettingGuide(R.array.guide_headlight_monitoring_open) {
        GuideStory(
            frame { monitorLamp(false, "17:30") },
            frame { monitorLamp(true, "17:40") },
            frame {
                monitorLamp(false, "17:40")
                signal("reading", 58, 59, 6, 11)
            },
        )
    },
    R.string.seat_memory_open_factory_on_tap to SettingGuide(R.array.guide_seat_memory_open_factory_on_tap) {
        story(
            {
                page("seats", CAR)
                lines("instructions", 47, 52, 49, 2)
            },
            {
                page("seats", FIRMWARE)
                box("seat-back", 20, 35, 12, 21, ACCENT)
                box("seat-base", 20, 51, 26, 8, ACCENT)
                word("slot", "1  2  3", 84, 48, 13)
            },
        )
    },
    R.string.service_reminder_show_on_vehicle to SettingGuide(R.array.guide_service_reminder_show_on_vehicle) {
        story({ reminderCard(false) }, { reminderCard(true) })
    },
    R.string.service_reminder_interval to SettingGuide(R.array.guide_service_reminder_interval) {
        story({ serviceDistance("35 000", "45 000", 61) }, { serviceDistance("35 000", "50 000", 91) })
    },
    R.string.service_reminder_current to SettingGuide(R.array.guide_service_reminder_current) {
        story(
            {
                screen("odometer-panel")
                word("odometer", "— km", 66, 27, 19, MUTED)
            },
            {
                screen("odometer-panel")
                word("odometer", "43 200 km", 66, 25, 17)
                word("remaining", "1 800 km", 66, 51, 15, ACCENT)
            },
        )
    },
    R.string.service_reminder_last to SettingGuide(R.array.guide_service_reminder_last) {
        story({ serviceDistance("—", "—", 79) }, { serviceDistance("40 000", "50 000", 79) })
    },
    R.string.service_reminder_lead_title to SettingGuide(R.array.guide_service_reminder_lead_title) {
        story(
            { serviceDistance("49 250", "50 000", 80) },
            {
                serviceDistance("49 250", "50 000", 80)
                word("notice", "!", 91, 23, 20, AMBER)
            },
        )
    },
    R.string.service_reminder_calendar_interval to SettingGuide(R.array.guide_service_reminder_calendar_interval) {
        story({ dates("18/04/26", "18/04/27") }, { dates("18/04/26", "18/10/26") })
    },
    R.string.service_reminder_calendar_lead_title to SettingGuide(R.array.guide_service_reminder_calendar_lead_title) {
        story(
            {
                timeline("reminder", listOf("04/10", "18/10"), 1)
                icon("service", SETTINGS, 98, 11, 24)
            },
            {
                timeline("reminder", listOf("04/10", "18/10"), 0)
                icon("service", SETTINGS, 98, 11, 24)
                word("notice", "!", 24, 21, 20, AMBER)
            },
        )
    },
    R.string.service_reminder_calendar_last_date to SettingGuide(R.array.guide_service_reminder_calendar_last_date) {
        story({ dates("—", "—") }, { dates("08/05/26", "08/05/27") })
    },
    R.string.vehicle_automation_lights_enabled to SettingGuide(R.array.guide_vehicle_automation_lights_enabled) {
        story({ gearLights("D", enabled = false, lampOn = false) }, { gearLights("D", enabled = true, lampOn = true) })
    },
    R.string.vehicle_automation_on_drive to SettingGuide(R.array.guide_vehicle_automation_on_drive) {
        story(
            { gearLights("D", enabled = true, lampOn = false) },
            { gearLights("D", enabled = true, lampOn = true, mode = "AUTO") },
        )
    },
    R.string.vehicle_automation_on_park to SettingGuide(R.array.guide_vehicle_automation_on_park) {
        story(
            { gearLights("P", enabled = true, lampOn = true) },
            { gearLights("P", enabled = true, lampOn = false, mode = "OFF") },
        )
    },
    R.string.vehicle_automation_memory_enabled to SettingGuide(R.array.guide_vehicle_automation_memory_enabled) {
        GuideStory(frame { driveMode(false) }, frame { driveMode(true) }, frame { driveMode(false); gesture("save", 50, 32) })
    },
    R.string.comfort_playback_door to SettingGuide(R.array.guide_comfort_playback_door) {
        story({ doorPlayback(false) }, { doorPlayback(true) })
    },
    R.string.comfort_playback_wake to SettingGuide(R.array.guide_comfort_playback_wake) {
        GuideStory(
            frame { sleepPlayback("☀", true) },
            frame { sleepPlayback("↥", true) },
            frame { sleepPlayback("☾", false) },
        )
    },
    R.string.comfort_brightness_enabled to SettingGuide(R.array.guide_comfort_brightness_enabled) {
        story({ brightness(false, 48) }, { brightness(true, 85) })
    },
    R.string.comfort_brightness_low to SettingGuide(R.array.guide_comfort_brightness_low) {
        story({ brightness(true, 35, dark = true) }, { brightness(true, 15, dark = true) })
    },
    R.string.comfort_brightness_high to SettingGuide(R.array.guide_comfort_brightness_high) {
        story({ brightness(true, 70) }, { brightness(true, 100) })
    },
    R.string.trip_gps_title to SettingGuide(R.array.guide_trip_gps_title) {
        story(
            {
                screen("trip")
                word("distance", "12.4 km", 66, 41, 20)
            },
            {
                mapCard("gps", 12, 10, 108, 57, true)
                word("distance", "12.4 km", 92, 59, 11)
            },
        )
    },
    R.string.trips_automatic to SettingGuide(R.array.guide_trips_automatic) {
        GuideStory(
            frame { tripRecording("P", false) },
            frame { tripRecording("D", true) },
            frame { tripRecording("D", false) },
        )
    },
    R.string.trip_permissions to SettingGuide(R.array.guide_trip_permissions) {
        story(
            {
                connector("readings", CAR, DISPLAY, false)
                word("range", "—", 104, 39, 20, MUTED)
            },
            {
                connector("readings", CAR, DISPLAY)
                word("range", "45%", 104, 39, 10)
            },
        )
    },
)

private fun GuideFrameBuilder.monitorLamp(live: Boolean, time: String) {
    icon("lamps", LIGHT, 20, 24, 31, if (live) ACCENT else MUTED)
    word("when", time, 92, 30, 15)
    if (live) word("live-dot", "●", 70, 50, 12, TEAL)
    else icon("history", ROUTINE, 62, 44, 16, MUTED)
}

private fun GuideFrameBuilder.reminderCard(show: Boolean) {
    page("vehicle", CAR)
    if (show) {
        box("service", 45, 36, 68, 24, ACTION)
        word("due", "750 km", 81, 48, 14)
    }
}

private fun GuideFrameBuilder.serviceDistance(start: String, end: String, value: Int) {
    gauge("service", 20, 40, 93, value, knob = false)
    word("start", "$start km", 34, 58, 11, width = 55)
    word("end", "$end km", 97, 58, 11, width = 55)
    icon("service", SETTINGS, 15 + value, 13, 20)
}

private fun GuideFrameBuilder.dates(first: String, last: String) {
    icon("calendar", ROUTINE, 12, 23, 31)
    word("last", first, 86, 22, 13, MUTED)
    word("next", last, 86, 53, 14, ACCENT)
    word("link", "↓", 87, 37, 14)
}

private fun GuideFrameBuilder.gearLights(gear: String, enabled: Boolean, lampOn: Boolean, mode: String? = null) {
    keycap("gear", gear, 12, 24)
    if (enabled) arrow("trigger", 64)
    icon("headlight", LIGHT, 87, if (mode == null) 22 else 13, 34, if (lampOn) ACCENT else MUTED)
    mode?.let { word("mode", it, 104, 57, 13) }
}

private fun GuideFrameBuilder.driveMode(saved: Boolean) {
    tile("car", CAR, 12, 23, 31)
    word("mode", "ECO", 65, 39, 14)
    icon("memory", DOWNLOAD, 92, 25, 25, if (saved) ACCENT else MUTED)
    if (saved) check("stored", 109, 59, true)
}

private fun GuideFrameBuilder.doorPlayback(paused: Boolean) {
    screen("car-top", 13, 22, 29, 34)
    box("driver-door", 6, 36, 11, 6, AMBER, 2)
    arrow("transition", 60)
    player("music", 78, 14, 46, 50, controls = true, title = "FM", playing = !paused)
}

private fun GuideFrameBuilder.sleepPlayback(event: String, playing: Boolean) {
    word("sleep", event, 24, 37, 26, AMBER)
    arrow("wake", 54)
    player("music", 71, 15, 53, 47, controls = true, title = "FM", playing = playing)
}

private fun GuideFrameBuilder.brightness(active: Boolean, intensity: Int, dark: Boolean = false) {
    word("ambient", if (dark) "☾" else "☀", 25, 38, 26, AMBER)
    screen("controls", 48, 13, 71, 51, if (intensity > 65) PAPER else if (intensity < 30) NIGHT else SURFACE)
    word("screen-level", "$intensity%", 83, 33, 15, if (intensity < 30) WHITE else if (intensity > 65) NIGHT else FG)
    if (active) gauge("level", 60, 49, 48, intensity, if (intensity < 30) WHITE else TEAL, knob = false)
}

private fun GuideFrameBuilder.tripRecording(gear: String, recording: Boolean) {
    keycap("gear", gear, 13, 23)
    arrow("trigger", 61)
    if (recording) {
        word("record", "●", 86, 30, 12, TEAL)
        word("time", "00:01", 102, 51, 14, ACCENT, width = 50)
    } else word("time", "— : —", 99, 37, 15, MUTED)
}
