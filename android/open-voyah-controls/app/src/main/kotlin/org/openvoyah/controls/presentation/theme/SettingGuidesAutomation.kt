package org.openvoyah.controls.presentation.theme

import org.openvoyah.controls.R
import org.openvoyah.controls.presentation.theme.CockpitIcon.*
import org.openvoyah.controls.presentation.theme.Ink.*

internal val automationGuides = mapOf(
    R.string.routines_name to SettingGuide(R.array.guide_routines_name) {
        story({ routineLabel("…", false) }, { routineLabel("Morning drive", true) })
    },
    R.string.routines_page to SettingGuide(R.array.guide_routines_page) {
        story({ page("target", SETTINGS) }, { page("target", CAR) })
    },
    R.string.routines_layout to SettingGuide(R.array.guide_routines_layout) {
        story({ landscapeLayout(false) }, { landscapeLayout(true) })
    },
    R.string.routines_theme to SettingGuide(R.array.guide_routines_theme) {
        story({ page("routine-page", CAR, ink = NIGHT, fg = WHITE) }, { page("routine-page", CAR, ink = PAPER, fg = TEAL) })
    },
    R.string.routines_app to SettingGuide(R.array.guide_routines_app) {
        story({ page("routine-page", ROUTINE) }, { mapCard("app", 10, 10, 112, 55, true) })
    },
    R.string.routines_pin to SettingGuide(R.array.guide_routines_pin) {
        story({ routinePin(false) }, { routinePin(true) })
    },
    R.string.routines_startup_enabled to SettingGuide(R.array.guide_routines_startup_enabled) {
        story(
            {
                connector("startup", HOME, ROUTINE, false)
                toggle("enabled", false, 73, 50)
            },
            {
                connector("startup", HOME, ROUTINE)
                toggle("enabled", true, 73, 50)
            },
        )
    },
    R.string.routines_days to SettingGuide(R.array.guide_routines_days) {
        story({ calendar("days", (1..7).toSet()) }, { calendar("days", (1..5).toSet()) })
    },
    R.string.routines_frequency to SettingGuide(R.array.guide_routines_frequency) {
        story({ twoStarts(false) }, { twoStarts(true) })
    },
    R.string.routines_delay to SettingGuide(R.array.guide_routines_delay) {
        GuideStory(
            frame { scheduleDelay("0 s", 100) },
            frame { scheduleDelay("5 s", 100) },
            frame { scheduleDelay("2 s", 40) },
        )
    },
    R.string.routines_limit_hours to SettingGuide(R.array.guide_routines_limit_hours) {
        story({ timeWindow(null, null) }, { timeWindow("07:00", "09:00") })
    },
    R.string.routines_time_start to SettingGuide(R.array.guide_routines_time_start) {
        story({ timeWindow("07:00", "09:00", "06:59", false) }, { timeWindow("07:00", "09:00", "07:00", true) })
    },
    R.string.routines_time_end to SettingGuide(R.array.guide_routines_time_end) {
        story({ timeWindow("07:00", "09:00", "08:59", true) }, { timeWindow("07:00", "09:00", "09:00", false) })
    },
    R.string.rules_enabled to SettingGuide(R.array.guide_rules_enabled) {
        story(
            {
                icon("condition", CAR, 18, 25, 28, MUTED)
                toggle("rule", false, 78, 30)
            },
            {
                icon("condition", CAR, 18, 25, 28)
                toggle("rule", true, 78, 30)
                signal("watch", 51, 60, 7, 11)
            },
        )
    },
    R.string.rules_gear to SettingGuide(R.array.guide_rules_gear) {
        story({ condition("P", false) }, { condition("D", true) })
    },
    R.string.rules_doors to SettingGuide(R.array.guide_rules_doors) {
        story({ doorCondition(open = true, person = false) }, { doorCondition(open = false, person = false) })
    },
    R.string.rules_routine to SettingGuide(R.array.guide_rules_routine) {
        story(
            {
                check("conditions", 26, 37, true)
                arrow("then", 62)
                word("unassigned", "—", 103, 39, 17, MUTED)
            },
            {
                check("conditions", 26, 37, true)
                arrow("then", 62)
                tile("routine", ROUTINE, 88, 22, 30)
                word("run", "▸", 110, 55, 14, ACCENT)
            },
        )
    },
    R.string.rules_cooldown to SettingGuide(R.array.guide_rules_cooldown) {
        story(
            {
                timeline("cooldown", listOf("0 s", "30 s"), 0)
                icon("waiting", LOCK, 51, 11, 24)
            },
            {
                timeline("cooldown", listOf("0 s", "30 s"), 1)
                icon("ready", ROUTINE, 97, 11, 24)
            },
        )
    },
    R.string.rules_extra_conditions to SettingGuide(R.array.guide_rules_extra_conditions) {
        story(
            {
                tile("gear", CAR, 19, 21, 33)
                check("result", 102, 38, true)
            },
            {
                tile("gear", CAR, 8, 21, 31)
                word("and", "+", 49, 38, 18)
                word("lux", "☾", 78, 38, 23, AMBER)
                check("result", 109, 38, false)
            },
        )
    },
    R.string.rules_light to SettingGuide(R.array.guide_rules_light) {
        story({ compareSensor("☾", "500 lx", "≤ 100 lx", false) }, { compareSensor("☾", "50 lx", "≤ 100 lx", true) })
    },
    R.string.rules_charge to SettingGuide(R.array.guide_rules_charge) {
        story({ compareSensor("▣", "60%", "≥ 80%", false) }, { compareSensor("▣", "85%", "≥ 80%", true) })
    },
    R.string.rules_temperature to SettingGuide(R.array.guide_rules_temperature) {
        story({ compareSensor("°C", "15°", "≤ 0°", false) }, { compareSensor("°C", "−5°", "≤ 0°", true) })
    },
    R.string.rules_limit_hours to SettingGuide(R.array.guide_rules_limit_hours) {
        story({ ruleWindow("22:00", restricted = false, eligible = true) },
            { ruleWindow("22:00", restricted = true, eligible = false) })
    },
    R.string.rules_time_start to SettingGuide(R.array.guide_rules_time_start) {
        story({ ruleWindow("06:59", restricted = true, eligible = false) },
            { ruleWindow("07:00", restricted = true, eligible = true) })
    },
    R.string.rules_time_end to SettingGuide(R.array.guide_rules_time_end) {
        story({ ruleWindow("08:59", restricted = true, eligible = true) },
            { ruleWindow("09:00", restricted = true, eligible = false) })
    },
    R.string.rules_driving_mode to SettingGuide(R.array.guide_rules_driving_mode) {
        story({ modeCondition("ECO", false) }, { modeCondition("SPORT", true) })
    },
    R.string.seasonal_climate_enabled to SettingGuide(R.array.guide_seasonal_climate_enabled) {
        story({ climatePanel(false) }, { climatePanel(true) })
    },
    R.string.seasonal_climate_above_field to SettingGuide(R.array.guide_seasonal_climate_above_field) {
        story({ compareSensor("☀", "24°", "≥ 30°", false) }, { compareSensor("☀", "33°", "≥ 30°", true) })
    },
    R.string.seasonal_climate_below_field to SettingGuide(R.array.guide_seasonal_climate_below_field) {
        story({ compareSensor("❄", "3°", "≤ 0°", false) }, { compareSensor("❄", "−5°", "≤ 0°", true) })
    },
    R.string.seasonal_climate_gear to SettingGuide(R.array.guide_seasonal_climate_gear) {
        story(
            {
                condition("D", false)
                icon("preset", CLIMATE, 91, 25, 26, MUTED)
            },
            {
                condition("P", true)
                icon("preset", CLIMATE, 91, 25, 26)
            },
        )
    },
    R.string.seasonal_climate_closed_doors to SettingGuide(R.array.guide_seasonal_climate_closed_doors) {
        story({ doorCondition(open = true, person = true) }, { doorCondition(open = false, person = true) })
    },
    R.string.seasonal_climate_driver to SettingGuide(R.array.guide_seasonal_climate_driver) {
        story({ cabin("24°", "24°", 0) }, { cabin("20°", "24°", 0) })
    },
    R.string.seasonal_climate_passenger to SettingGuide(R.array.guide_seasonal_climate_passenger) {
        story({ cabin("24°", "24°", 1) }, { cabin("24°", "21°", 1) })
    },
    R.string.seasonal_climate_fan to SettingGuide(R.array.guide_seasonal_climate_fan) {
        story({ fan(2) }, { fan(5) })
    },
    R.string.seasonal_climate_driver_seat to SettingGuide(R.array.guide_seasonal_climate_driver_seat) {
        story({ seatLevel(0, 0) }, { seatLevel(2, 0) })
    },
    R.string.seasonal_climate_passenger_seat to SettingGuide(R.array.guide_seasonal_climate_passenger_seat) {
        story({ seatLevel(0, 1) }, { seatLevel(2, 1) })
    },
    R.string.rules_name to SettingGuide(R.array.guide_rules_name) {
        story(
            {
                screen("rule", 10, 17, 112, 46)
                word("name", "…", 66, 37, 16, MUTED)
            },
            {
                screen("rule", 10, 17, 112, 46)
                word("name", "Lights in Park", 66, 37, 15)
                box("line", 22, 51, 87, 2, ACCENT)
            },
        )
    },
    R.string.routines_startup to SettingGuide(R.array.guide_routines_startup) {
        story(
            {
                icon("launcher", HOME, 11, 23, 29)
                arrow("launch", 56)
                word("nothing", "—", 102, 35, 22, MUTED)
            },
            {
                icon("launcher", HOME, 11, 23, 29)
                arrow("launch", 56)
                word("pin", "★", 75, 35, 16, AMBER)
                icon("selected", ROUTINE, 94, 23, 28)
            },
        )
    },
    R.string.routines_sort to SettingGuide(R.array.guide_routines_sort) {
        story({ routineList(listOf("Trip", "Evening", "Morning")) }, { routineList(listOf("Evening", "Morning", "Trip")) })
    },
    R.string.routine_voice_phrase to SettingGuide(R.array.guide_routine_voice_phrase) {
        story(
            {
                icon("voice", VOICE, 13, 22, 29, MUTED)
                word("speech", "…", 77, 37, 20, MUTED)
            },
            {
                icon("voice", VOICE, 13, 22, 29)
                word("speech", "«Road trip»", 83, 28, 14, width = 81)
                icon("routine", ROUTINE, 73, 44, 22)
            },
        )
    },
    R.string.routine_steps_title to SettingGuide(R.array.guide_routine_steps_title) {
        story({ stepOrder(listOf(HOME, PLAY)) }, { stepOrder(listOf(HOME, ROUTINE, PLAY)) })
    },
)

private fun GuideFrameBuilder.routineLabel(name: String, saved: Boolean) {
    screen("routine", 10, 17, 112, 46)
    icon("routine-icon", ROUTINE, 17, 27, 25)
    word("name", name, 79, 38, 13, if (saved) FG else MUTED, width = 72)
}

private fun GuideFrameBuilder.landscapeLayout(saved: Boolean) {
    screen("desktop", ink = if (saved) NIGHT else SURFACE)
    if (saved) word("mountain", "▲", 42, 45, 43, TEAL)
    tile("map", HOME, if (saved) 65 else 24, 27, 24)
    tile("media", PLAY, if (saved) 94 else 54, 27, 24)
    if (saved) word("pin", "★", 109, 24, 12, AMBER)
}

private fun GuideFrameBuilder.routinePin(pinned: Boolean) {
    screen("library")
    val y = if (pinned) 15 else 46
    box("routine", 15, y, 102, 17, ACTION)
    icon("clock", ROUTINE, 19, y, 17)
    lines("routine-label", 47, y + 7, 41, 1)
    if (pinned) word("pin", "★", 105, y + 8, 12, AMBER)
    lines("other-listing", 46, if (pinned) 51 else 25, 48, 1)
}

private fun GuideFrameBuilder.twoStarts(once: Boolean) {
    timeline("day", listOf("07:00", "15:00"), if (once) 0 else 1)
    icon("first-run", ROUTINE, 11, 10, 22)
    if (once) check("second-skip", 110, 20, false) else icon("second-run", ROUTINE, 99, 10, 22)
}

private fun GuideFrameBuilder.scheduleDelay(time: String, level: Int) {
    icon("launcher", HOME, 13, 15, 24)
    word("time", time, 66, 27, 17)
    icon("run", ROUTINE, 93, 15, 24, if (level == 100) ACCENT else MUTED)
    gauge("delay", 22, 55, 88, level, knob = false)
}

private fun GuideFrameBuilder.timeWindow(start: String?, end: String?, time: String? = null, eligible: Boolean = true) {
    word("sample-time", time ?: "24 h", 65, 16, 15)
    box("day", 16, 37, 100, 4, BORDER)
    box("eligible", if (start == null) 16 else 40, 36, if (start == null) 100 else 49, 6, ACCENT)
    if (start != null && end != null) {
        word("start", start, 39, 58, 11)
        word("end", end, 90, 58, 11)
    }
    if (time != null) check("result", 108, 16, eligible)
}

private fun GuideFrameBuilder.condition(gear: String, matches: Boolean) {
    keycap("gear", gear, 15, 23)
    arrow("matches", 62)
    check("result", 82, 38, matches)
}

private fun GuideFrameBuilder.doorCondition(open: Boolean, person: Boolean) {
    screen("car-top", 21, 13, 41, 49, BORDER, radius = 9)
    box("door", if (open) 10 else 24, 31, 13, 18, if (open) AMBER else ACCENT, 2)
    if (person) icon("climate", CLIMATE, 78, 17, 30)
    check("result", 93, if (person) 57 else 38, !open)
}

private fun GuideFrameBuilder.compareSensor(symbol: String, actual: String, threshold: String, accepted: Boolean) {
    word("sensor", symbol, 25, 24, 21, AMBER)
    word("actual", actual, 76, 26, 16)
    word("threshold", threshold, 67, 53, 13, MUTED)
    check("eligible", 111, 40, accepted)
}

private fun GuideFrameBuilder.modeCondition(mode: String, matches: Boolean) {
    tile("car", CAR, 13, 24, 28)
    word("current-mode", mode, 72, 38, 14, width = 58)
    check("matches", 112, 40, matches)
}

private fun GuideFrameBuilder.climatePanel(active: Boolean) {
    icon("preset", CLIMATE, 20, 21, 33, if (active) ACCENT else MUTED)
    toggle("enabled", active, 81, 30)
    if (active) word("sample", "21°", 77, 58, 14, ACCENT)
}

private fun GuideFrameBuilder.cabin(driver: String, passenger: String, selected: Int) {
    screen("cabin", 10, 15, 112, 47)
    box("left", 16, 22, 47, 32, if (selected == 0) ACTION else SURFACE)
    box("right", 69, 22, 47, 32, if (selected == 1) ACTION else SURFACE)
    word("driver", driver, 39, 38, 19, if (selected == 0) ACCENT else MUTED)
    word("passenger", passenger, 92, 38, 19, if (selected == 1) ACCENT else MUTED)
}

private fun GuideFrameBuilder.fan(level: Int) {
    icon("fan", CLIMATE, 14, 22, 31)
    for (bar in 0..4) box("fan-$bar", 55 + bar * 12, 52 - bar * 5, 7, 8 + bar * 5,
        if (bar < level) ACCENT else BORDER, 2)
}

private fun GuideFrameBuilder.routineList(names: List<String>) {
    names.forEachIndexed { i, name ->
        val y = 19 + i * 19
        icon("icon-$name", ROUTINE, 20, y - 8, 16)
        word("routine-$name", name, 79, y, 13, width = 75)
    }
}

private fun GuideFrameBuilder.stepOrder(steps: List<CockpitIcon>) {
    val x = if (steps.size == 2) 23 else 10
    steps.forEachIndexed { index, glyph ->
        tile("step-$glyph", glyph, x + index * 39, 24, 29)
        if (index < steps.lastIndex) word("arrow-$index", "›", x + index * 39 + 34, 38, 16, MUTED)
    }
}

private fun GuideFrameBuilder.ruleWindow(clock: String, restricted: Boolean, eligible: Boolean) {
    icon("condition", CAR, 12, 12, 23)
    check("condition-match", 42, 23, true)
    word("clock", clock, 77, 23, 15)
    check("time-match", 111, 23, eligible)
    box("day", 14, 46, 104, 4, BORDER)
    box("window", if (restricted) 40 else 14, 45, if (restricted) 45 else 104, 6, ACCENT)
    if (restricted) {
        word("starts", "07:00", 39, 63, 10)
        word("ends", "09:00", 86, 63, 10)
    }
}
