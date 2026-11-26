package org.openvoyah.controls.presentation.theme

import org.openvoyah.controls.R
import org.openvoyah.controls.presentation.theme.CockpitIcon.*
import org.openvoyah.controls.presentation.theme.Ink.*

internal val cardsGuides = mapOf(
    R.string.desktop_info_clock to SettingGuide(R.array.guide_desktop_info_clock) {
        story({ cardSlot(0) }, { cardSlot(2) { x, w -> word("time", "17:40", x + w / 2, 38, 20) } })
    },
    R.string.desktop_info_service to SettingGuide(R.array.guide_desktop_info_service) {
        story(
            { cardSlot(0) },
            {
                cardSlot(1) { x, w -> icon("wrench", SETTINGS, x + w / 2 - 8, 21, 16); word("next", "750 km", x + w / 2, 48, 12, width = w - 3) }
            },
        )
    },
    R.string.desktop_info_media to SettingGuide(R.array.guide_desktop_info_media) {
        story(
            { cardSlot(1) { x, w -> player("track", x, 17, w, 41, title = "FM") } },
            { cardSlot(2) { x, w -> player("track", x, 17, w, 41, art = true, controls = true, title = "FM") } },
        )
    },
    R.string.desktop_info_headlights to SettingGuide(R.array.guide_desktop_info_headlights) {
        story(
            { cardSlot(0) },
            {
                cardSlot(1) { x, w -> icon("light", LIGHT, x + w / 2 - 12, 23, 24); word("mode", "AUTO", x + w / 2, 54, 9) }
            },
        )
    },
    R.string.desktop_info_phone to SettingGuide(R.array.guide_desktop_info_phone) {
        story(
            { cardSlot(0) },
            {
                cardSlot(2) { x, w -> word("phone", "☎    ★", x + w / 2, 31, 17, ACCENT); word("favorite", "Anna", x + w / 2, 49, 12) }
            },
        )
    },
    R.string.desktop_info_route to SettingGuide(R.array.guide_desktop_info_route) {
        story({ cardSlot(0) }, { cardSlot(2) { x, w -> route("road", x, 17, w, 41, "300 m", next = "Abay", ink = ACTION) } })
    },
    R.string.comfort_power_hold to SettingGuide(R.array.guide_comfort_power_hold) {
        story(
            { cardSlot(0) },
            {
                cardSlot(1) { x, w -> icon("power", ENERGY, x + w / 2 - 10, 21, 20); word("idle", "—", x + w / 2, 49, 12) }
            },
        )
    },
    R.string.comfort_car_wash to SettingGuide(R.array.guide_comfort_car_wash) {
        story(
            { cardSlot(0) },
            {
                cardSlot(1) { x, w -> icon("car", CAR, x + w / 2 - 12, 28, 24); word("drops", "· · ·", x + w / 2, 22, 13, ACCENT) }
            },
        )
    },
    R.string.factory_radio_card to SettingGuide(R.array.guide_factory_radio_card) {
        story(
            { cardSlot(0) },
            {
                cardSlot(2) { x, w -> word("station", "97.5 FM", x + w / 2, 30, 16); word("tune", "‹      ›", x + w / 2, 50, 15, ACCENT) }
            },
        )
    },
    R.string.trip_current to SettingGuide(R.array.guide_trip_current) {
        story(
            { cardSlot(0) },
            {
                cardSlot(2) { x, w -> word("elapsed", "08:42", x + w / 2, 29, 13); word("distance", "5.6 km", x + w / 2, 48, 15, ACCENT) }
            },
        )
    },
    R.string.trip_levels to SettingGuide(R.array.guide_trip_levels) {
        story(
            { cardSlot(0) },
            {
                cardSlot(2) { x, w -> word("battery", "▣  62%", x + w / 2, 28, 13); word("fuel", "18 L", x + w / 2, 48, 14, ACCENT) }
            },
        )
    },
    R.string.trip_usage_last to SettingGuide(R.array.guide_trip_usage_last) {
        story(
            { cardSlot(0) },
            {
                cardSlot(2) { x, w -> word("efficiency", "16.5 kWh", x + w / 2, 29, 13); word("measure", "/100 km", x + w / 2, 46, 12, MUTED) }
            },
        )
    },
    R.string.trip_tyres to SettingGuide(R.array.guide_trip_tyres) {
        story(
            { cardSlot(0) },
            {
                cardSlot(2) { x, w -> icon("car", CAR, x + w / 2 - 11, 27, 22); for (i in 0..3) word("tyre-$i", "2.4", x + if (i % 2 == 0) 14 else w - 14, 24 + i / 2 * 25, 10) }
            },
        )
    },
    R.string.trip_odometer to SettingGuide(R.array.guide_trip_odometer) {
        story(
            { cardSlot(0) },
            {
                cardSlot(2) { x, w -> word("total", "43 200", x + w / 2, 33, 19); word("unit", "km", x + w / 2, 51, 10, MUTED) }
            },
        )
    },
    R.string.trip_suspension to SettingGuide(R.array.guide_trip_suspension) {
        story(
            { cardSlot(0) },
            {
                cardSlot(2) { x, w -> icon("ride-height", CAR, x + 10, 25, 25); word("height", "↕", x + w - 17, 38, 19, ACCENT) }
            },
        )
    },
)

private fun GuideFrameBuilder.cardSlot(kind: Int, content: GuideFrameBuilder.(x: Int, width: Int) -> Unit = { _, _ -> }) {
    screen("desktop")
    val x = 17
    val w = if (kind == 1) 42 else 77
    if (kind != 0) {
        box("card", x, 17, w, 41, ACTION)
        content(x, w)
    }
    tile("maps", HOME, if (kind == 0) 21 else x + w + 6, 25, 19)
    if (kind != 2) tile("music", PLAY, if (kind == 0) 48 else x + w + 30, 25, 19)
}
