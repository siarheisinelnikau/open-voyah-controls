package org.openvoyah.controls.presentation.theme

import org.openvoyah.controls.R
import org.openvoyah.controls.presentation.theme.CockpitIcon.*
import org.openvoyah.controls.presentation.theme.Ink.*

internal val appearanceGuides = mapOf(
    R.string.appearance_palette to SettingGuide(R.array.guide_appearance_palette) {
        story({ cockpitExample(NIGHT, WHITE) }, { cockpitExample(PAPER, NIGHT) })
    },
    R.string.appearance_accent to SettingGuide(R.array.guide_appearance_accent) {
        story({ cockpitExample(SURFACE, ACCENT) }, { cockpitExample(SURFACE, TEAL) })
    },
    R.string.appearance_background to SettingGuide(R.array.guide_appearance_background) {
        story({ cockpitExample(NIGHT, WHITE, landscape = true) }, { cockpitExample(NIGHT, WHITE) })
    },
    R.string.appearance_high_contrast to SettingGuide(R.array.guide_appearance_high_contrast) {
        story({ contrast(false) }, { contrast(true) })
    },
    R.string.appearance_text_size to SettingGuide(R.array.guide_appearance_text_size) {
        story({ typeSpecimen(13, 11, 10) }, { typeSpecimen(20, 15, 13) })
    },
    R.string.appearance_navigation_label_size to SettingGuide(R.array.guide_appearance_navigation_label_size) {
        story({ typeSpecimen(17, 12, 9) }, { typeSpecimen(17, 12, 15) })
    },
    R.string.appearance_page_title_size to SettingGuide(R.array.guide_appearance_page_title_size) {
        story({ typeSpecimen(15, 12, 9) }, { typeSpecimen(25, 12, 9) })
    },
    R.string.appearance_font_family to SettingGuide(R.array.guide_appearance_font_family) {
        story(
            { word("sample", "Aa 012", 66, 38, 29, style = MarkStyle.TEXT) },
            { word("sample", "Aa 012", 66, 38, 29, style = MarkStyle.GOLOS) },
        )
    },
    R.string.appearance_bold_text to SettingGuide(R.array.guide_appearance_bold_text) {
        story(
            { word("sample", "Aa 012", 66, 38, 26, style = MarkStyle.TEXT) },
            { word("sample", "Aa 012", 66, 38, 26, style = MarkStyle.BOLD) },
        )
    },
    R.string.appearance_spacing to SettingGuide(R.array.guide_appearance_spacing) {
        story({ page("page", SETTINGS, spacing = 6) }, { page("page", SETTINGS, spacing = 12) })
    },
    R.string.appearance_corners to SettingGuide(R.array.guide_appearance_corners) {
        story(
            {
                page("page", SETTINGS, radius = 2)
                box("button", 85, 39, 29, 18, ACTION, 2)
            },
            {
                page("page", SETTINGS, radius = 14)
                box("button", 85, 39, 29, 18, ACTION, 9)
            },
        )
    },
    R.string.appearance_animate_routes to SettingGuide(R.array.guide_appearance_animate_routes) {
        GuideStory(frame { transitionPage(0) }, frame { transitionPage(2) }, frame { transitionPage(1) })
    },
    R.string.appearance_transition_speed to SettingGuide(R.array.guide_appearance_transition_speed) {
        story({ race(true) }, { race(false) })
    },
    R.string.appearance_wallpaper_dimming to SettingGuide(R.array.guide_appearance_wallpaper_dimming) {
        story({ dimWallpaper(false) }, { dimWallpaper(true) })
    },
    R.string.appearance_start_screen to SettingGuide(R.array.guide_appearance_start_screen) {
        story(
            {
                arrow("open", 43)
                tile("launcher", HOME, 10, 26, 24)
                page("target", APPS, 61, 11, 62, 55)
            },
            {
                arrow("open", 43)
                tile("launcher", HOME, 10, 26, 24)
                page("target", SETTINGS, 61, 11, 62, 55)
            },
        )
    },
    R.string.appearance_limit_media_on_launch to SettingGuide(R.array.guide_appearance_limit_media_on_launch) {
        GuideStory(
            frame { launchMedia(74, 40, false) },
            frame { launchMedia(40, 40, true) },
            frame { launchMedia(74, 40, true) },
        )
    },
    R.string.appearance_launch_media_limit to SettingGuide(R.array.guide_appearance_launch_media_limit) {
        story({ launchMedia(48, 50, true) }, { launchMedia(30, 30, true) })
    },
)

private fun GuideFrameBuilder.cockpitExample(background: Ink, foreground: Ink, landscape: Boolean = false) {
    screen("cockpit", ink = background)
    if (landscape) {
        word("hill", "▲", 39, 45, 51, TEAL)
        word("moon", "●", 79, 26, 13, AMBER)
    }
    icon("menu", APPS, 16, 18, 24, foreground)
    box("selection", 52, 16, 57, 24, foreground, 5, outline = true)
    lines("caption", 62, 27, 38, 1, ink = foreground)
    lines("body", 52, 49, 57, 2, ink = foreground)
}

private fun GuideFrameBuilder.contrast(high: Boolean) {
    screen("display")
    val tone = if (high) FG else BORDER
    box("row", 18, 16, 96, 18, tone, 4, outline = true)
    box("row-two", 18, 43, 96, 18, tone, 4, outline = true)
    icon("label", CAR, 22, 17, 15, if (high) FG else MUTED)
    icon("label-two", ROUTINE, 22, 44, 15, if (high) FG else MUTED)
    lines("text", 47, 23, 50, 1, ink = if (high) FG else MUTED, thickness = if (high) 4 else 2)
    lines("text-two", 47, 50, 41, 1, ink = if (high) FG else MUTED, thickness = if (high) 4 else 2)
}

private fun GuideFrameBuilder.typeSpecimen(title: Int, body: Int, navigation: Int) {
    screen("sample")
    box("nav", 12, 11, 38, 55, ACTION, 4)
    icon("nav-glyph", SETTINGS, 21, 19, 20)
    word("nav-label", "Aa", 31, 52, navigation, ACCENT)
    word("title", "Aa", 84, 26, title)
    word("body", "Aa 123", 84, 50, body, MUTED, MarkStyle.TEXT, width = 68)
}

private fun GuideFrameBuilder.transitionPage(step: Int) {
    if (step != 2) page("old", HOME, 8 - step * 32, 11, 105, 56)
    if (step != 0) page("new", CAR, if (step == 1) 86 else 8, 11, 105, 56)
}

private fun GuideFrameBuilder.race(short: Boolean) {
    val w = if (short) 42 else 91
    timeline("speed", listOf("0", "0.25 s", "0.6 s"), if (short) 1 else 2, y = 51)
    box("distance", 19, 20, w, 5, ACCENT)
    word("finish", "▣", 25 + w, 22, 16, ACCENT)
}

private fun GuideFrameBuilder.dimWallpaper(dim: Boolean) {
    screen("wallpaper", ink = NIGHT)
    word("mountains", "▲▲", 64, 43, 39, if (dim) MUTED else TEAL)
    word("sun", "●", 93, 23, 15, if (dim) MUTED else AMBER)
    if (dim) box("dimming", 10, 9, 112, 59, NIGHT, 4)
    if (dim) {
        word("dim-mountains", "▲▲", 64, 43, 36, MUTED)
        word("dim-sun", "○", 93, 23, 12, MUTED)
    }
    tile("app", SETTINGS, 48, 32, 28)
}

private fun GuideFrameBuilder.launchMedia(value: Int, cap: Int, launch: Boolean) {
    icon("shortcut", HOME, 15, 12, 21, if (launch) ACCENT else MUTED)
    word("value", "$value%", 69, 24, 18)
    gauge("volume", 22, 47, 88, value)
    val x = 22 + 88 * cap / 100
    box("cap", x - 1, 39, 2, 17, AMBER, 1)
    word("cap-label", "$cap%", x, 64, 10, AMBER, width = 38)
}
