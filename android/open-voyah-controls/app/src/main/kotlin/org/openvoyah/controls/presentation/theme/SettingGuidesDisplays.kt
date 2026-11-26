package org.openvoyah.controls.presentation.theme

import org.openvoyah.controls.R
import org.openvoyah.controls.presentation.theme.CockpitIcon.*
import org.openvoyah.controls.presentation.theme.Ink.*

internal val displaysGuides = mapOf(
    R.string.window_hook_title to SettingGuide(R.array.guide_window_hook_title) {
        story({ page("window", APPS) }, { page("window", APPS, 35, 7, 89, 64); icon("display-tool", SETTINGS, 10, 28, 22) })
    },
    R.string.window_strip_title to SettingGuide(R.array.guide_window_strip_title) {
        story(
            { windowFrame(0) },
            {
                windowFrame(23)
                box("system-margin", 10, 12, 20, 54, BORDER)
                icon("system", BUTTONS, 11, 31, 18)
            },
        )
    },
    R.string.app_window_panel_width to SettingGuide(R.array.guide_app_window_panel_width) {
        story(
            {
                windowFrame(22)
                word("margin", "↔", 20, 57, 14, ACCENT)
            },
            {
                windowFrame(43)
                word("margin", "↔", 30, 57, 24, ACCENT)
            },
        )
    },
    R.string.app_window_width to SettingGuide(R.array.guide_app_window_width) {
        story({ windowFrame(0, 113) }, { windowFrame(0, 77); word("width", "70%", 100, 60, 12) })
    },
    R.string.app_window_dpi to SettingGuide(R.array.guide_app_window_dpi) {
        story({ scaledApp(13, 24) }, { scaledApp(18, 32) })
    },
    R.string.app_display_defaults_title to SettingGuide(R.array.guide_app_display_defaults_title) {
        story({ screensWithDpi("160", "160") }, { screensWithDpi("200", "160") })
    },
    R.string.window_swipe_title to SettingGuide(R.array.guide_window_swipe_title) {
        story({ dualScreen(0, 0) }, { dualScreen(1, 0) })
    },
    R.string.window_swipe_fingers to SettingGuide(R.array.guide_window_swipe_fingers) {
        GuideStory(
            frame {
                dualScreen(0, 0)
                gesture("fingers", 29, 51)
            },
            frame {
                dualScreen(1, 0)
                gesture("fingers", 90, 51, 2)
            },
            frame {
                dualScreen(0, 0)
                gesture("fingers", 56, 51, 2)
            },
        )
    },
    R.string.window_swipe_after to SettingGuide(R.array.guide_window_swipe_after) {
        story(
            {
                dualScreen(1, 0)
                icon("left-home", HOME, 25, 27, 21)
            },
            {
                dualScreen(1, 0)
                icon("left-grid", APPS, 25, 27, 21)
            },
        )
    },
    R.string.display_density to SettingGuide(R.array.guide_display_density) {
        story({ compactPreview(9) }, { compactPreview(14) })
    },
    R.string.display_preview_show_media to SettingGuide(R.array.guide_display_preview_show_media) {
        story({ previewPanes(false, true) }, { previewPanes(true, true) })
    },
    R.string.display_preview_show_navigation to SettingGuide(R.array.guide_display_preview_show_navigation) {
        story({ previewPanes(true, false) }, { previewPanes(true, true) })
    },
    R.string.display_preview_show_playback_progress to SettingGuide(R.array.guide_display_preview_show_playback_progress) {
        story(
            { player("song", 11, 9, 110, 60, art = true) },
            {
                player("song", 11, 9, 110, 60, art = true, progress = true)
                word("time", "1:24 / 3:36", 88, 57, 10, width = 57)
            },
        )
    },
    R.string.display_preview_show_album_artwork to SettingGuide(R.array.guide_display_preview_show_album_artwork) {
        story(
            { player("song", 11, 9, 110, 60, progress = true) },
            { player("song", 11, 9, 110, 60, art = true, progress = true) },
        )
    },
    R.string.display_preview_show_transport_controls to SettingGuide(R.array.guide_display_preview_show_transport_controls) {
        story(
            { player("song", 10, 9, 112, 60, subtitle = "Atlas") },
            { player("song", 10, 9, 112, 60, subtitle = "Atlas", controls = true) },
        )
    },
    R.string.display_preview_arrangement to SettingGuide(R.array.guide_display_preview_arrangement) {
        story({ previewPanes(true, true) }, { previewPanes(true, true, stacked = true) })
    },
    R.string.display_preview_pane_order to SettingGuide(R.array.guide_display_preview_pane_order) {
        story({ previewPanes(true, true) }, { previewPanes(true, true, navigationFirst = true) })
    },
    R.string.display_preview_media_source to SettingGuide(R.array.guide_display_preview_media_source) {
        story({ preferredPlayer(false) }, { preferredPlayer(true) })
    },
    R.string.display_preview_media_fallback to SettingGuide(R.array.guide_display_preview_media_fallback) {
        story(
            {
                screen("preview")
                word("missing", "♪ —", 66, 39, 24, MUTED)
            },
            {
                screen("preview")
                player("alternate", 14, 14, 104, 50, art = true, title = "Radio FM", subtitle = "97.5 MHz")
            },
        )
    },
    R.string.display_preview_seek_step to SettingGuide(R.array.guide_display_preview_seek_step) {
        GuideStory(frame { seekPreview(14, 5) }, frame { seekPreview(58, 30) }, frame { seekPreview(14, 30) })
    },
    R.string.display_preview_details to SettingGuide(R.array.guide_display_preview_details) {
        story(
            { previewPanes(true, true) },
            {
                screen("editor")
                icon("options", SETTINGS, 18, 27, 26)
                toggle("artwork", true, 74, 15)
                toggle("route", false, 74, 44)
            },
        )
    },
    R.string.display_preview_text_size to SettingGuide(R.array.guide_display_preview_text_size) {
        story(
            {
                word("song", "Night Drive", 66, 23, 12)
                word("distance", "300 m", 66, 51, 12, ACCENT)
            },
            {
                word("song", "Night Drive", 66, 23, 21)
                word("distance", "300 m", 66, 51, 24, ACCENT)
            },
        )
    },
    R.string.display_preview_palette to SettingGuide(R.array.guide_display_preview_palette) {
        story({ previewPanes(true, true, light = false) }, { previewPanes(true, true, light = true) })
    },
    R.string.display_preview_pane_balance to SettingGuide(R.array.guide_display_preview_pane_balance) {
        story({ previewPanes(true, true, mediaWidth = 54) }, { previewPanes(true, true, mediaWidth = 74) })
    },
    R.string.display_preview_hide_empty_panes to SettingGuide(R.array.guide_display_preview_hide_empty_panes) {
        story(
            {
                screen("preview")
                player("media", 13, 15, 49, 48, title = "97.5 FM")
                box("empty", 67, 15, 51, 48, BORDER)
                word("missing", "—", 92, 39, 16, MUTED)
            },
            {
                screen("preview")
                player("media", 13, 15, 105, 48, title = "97.5 FM")
            },
        )
    },
    R.string.display_preview_media_subtitle to SettingGuide(R.array.guide_display_preview_media_subtitle) {
        story(
            { player("song", 10, 9, 112, 60, art = true, progress = true) },
            { player("song", 10, 9, 112, 60, art = true, progress = true, subtitle = "Atlas") },
        )
    },
    R.string.display_preview_show_current_road to SettingGuide(R.array.guide_display_preview_show_current_road) {
        story(
            { route("route", 13, 9, 106, 58, "300 m", next = "Abay") },
            { route("route", 13, 9, 106, 58, "300 m", current = "Dostyk", next = "Abay") },
        )
    },
    R.string.display_preview_show_next_road to SettingGuide(R.array.guide_display_preview_show_next_road) {
        story(
            { route("route", 14, 9, 104, 60, "300 m", current = "Dostyk") },
            { route("route", 14, 9, 104, 60, "300 m", current = "Dostyk", next = "Abay") },
        )
    },
    R.string.display_preview_road_names to SettingGuide(R.array.guide_display_preview_road_names) {
        story(
            { route("route", 10, 9, 112, 60, "300 m", current = "Достық", next = "Абай") },
            { route("route", 10, 9, 112, 60, "300 m", current = "Dostyq", next = "Abay") },
        )
    },
    R.string.display_preview_show_turn_distance to SettingGuide(R.array.guide_display_preview_show_turn_distance) {
        story({ route("route", 17, 10, 98, 58) }, { route("route", 17, 10, 98, 58, "300 m") })
    },
    R.string.display_preview_distance_units to SettingGuide(R.array.guide_display_preview_distance_units) {
        story(
            { route("route", 16, 10, 100, 58, "1 km", next = "Abay") },
            { route("route", 16, 10, 100, 58, "0.6 mi", next = "Abay") },
        )
    },
    R.string.display_media_access to SettingGuide(R.array.guide_display_media_access) {
        story(
            {
                connector("permission", PLAY, DISPLAY, false)
                icon("lock", LOCK, 52, 49, 24, MUTED)
            },
            {
                connector("permission", PLAY, DISPLAY)
                word("track", "♪", 104, 38, 21)
            },
        )
    },
    R.string.display_navigation to SettingGuide(R.array.guide_display_navigation) {
        story(
            {
                tile("navigator", HOME, 12, 22, 31)
                arrow("feed", 64)
                icon("empty", DISPLAY, 88, 23, 32, MUTED)
            },
            {
                tile("navigator", HOME, 12, 22, 31)
                arrow("feed", 64)
                route("display", 82, 17, 41, 42, ink = ACTION)
            },
        )
    },
    R.string.passenger_workspace_title to SettingGuide(R.array.guide_passenger_workspace_title) {
        story({ dualScreen(0, 0) }, { dualScreen(0, 0); workspace(78, 19, 44, 39) })
    },
    R.string.passenger_workspace_compact to SettingGuide(R.array.guide_passenger_workspace_compact) {
        story({ workspace(size = 24) }, { workspace(size = 14) })
    },
    R.string.passenger_workspace_clock to SettingGuide(R.array.guide_passenger_workspace_clock) {
        story({ workspace() }, { workspace(); word("clock", "17:40", 72, 21, 14) })
    },
    R.string.passenger_workspace_date to SettingGuide(R.array.guide_passenger_workspace_date) {
        story(
            {
                workspace()
                word("clock", "17:40", 69, 19, 13)
            },
            {
                workspace()
                word("clock", "17:40", 69, 17, 13)
                word("date", "26/11", 69, 29, 10, MUTED)
            },
        )
    },
    R.string.passenger_workspace_startup to SettingGuide(R.array.guide_passenger_workspace_startup) {
        story(
            {
                tile("startup", ENERGY, 11, 24, 26)
                arrow("startup-arrow", 54)
                icon("passenger", DISPLAY, 84, 24, 30, MUTED)
            },
            {
                tile("startup", ENERGY, 11, 24, 26)
                arrow("startup-arrow", 54)
                workspace(76, 13, 47, 49)
            },
        )
    },
    R.string.passenger_workspace_app_slot to SettingGuide(R.array.guide_passenger_workspace_app_slot) { slot ->
        story({ workspace(selected = slot) }, { workspace(selected = slot, replacement = VOICE) })
    },
    R.string.passenger_workspace_dock_slot to SettingGuide(R.array.guide_passenger_workspace_dock_slot) { slot ->
        story({ passengerDock(slot, false) }, { passengerDock(slot, true) })
    },
    R.string.app_display_defaults_central to SettingGuide(R.array.guide_app_display_defaults_central) {
        story({ screensWithDpi("160", "160") }, { screensWithDpi("200", "160", highlight = 0) })
    },
    R.string.app_display_defaults_secondary to SettingGuide(R.array.guide_app_display_defaults_secondary) {
        story({ screensWithDpi("160", "—") }, { screensWithDpi("160", "190", highlight = 1) })
    },
    R.string.app_pair_left to SettingGuide(R.array.guide_app_pair_left) {
        story({ pairWindow(HOME, PLAY, selected = 0) }, { pairWindow(ROUTINE, PLAY, selected = 0) })
    },
    R.string.app_pair_right to SettingGuide(R.array.guide_app_pair_right) {
        story({ pairWindow(HOME, PLAY, selected = 1) }, { pairWindow(HOME, VOICE, selected = 1) })
    },
    R.string.app_pair_proportion to SettingGuide(R.array.guide_app_pair_proportion) {
        story({ pairWindow(HOME, PLAY) }, { pairWindow(HOME, PLAY, split = 81) })
    },
    R.string.app_pair_name to SettingGuide(R.array.guide_app_pair_name) {
        story({ pairWindow(HOME, PLAY, name = "Maps + Music") }, { pairWindow(HOME, PLAY, name = "Road trip") })
    },
    R.string.app_pair_dock to SettingGuide(R.array.guide_app_pair_dock) {
        story(
            {
                page("page", APPS, 28, 8, 94, 60)
                box("dock", 8, 8, 18, 60, BORDER)
            },
            {
                page("page", APPS, 28, 8, 94, 60)
                box("dock", 8, 8, 18, 60, BORDER)
                icon("pair", DISPLAY, 10, 27, 14)
            },
        )
    },
    R.string.app_window_restore to SettingGuide(R.array.guide_app_window_restore) {
        story(
            { windowFrame(24, 63); word("scale", "200 DPI", 66, 57, 10); word("reset", "↶", 105, 39, 17, ACCENT) },
            { windowFrame(0); word("scale", "160 DPI", 66, 57, 10) },
        )
    },
)

private fun GuideFrameBuilder.windowFrame(margin: Int, width: Int = 113) {
    screen("display")
    val w = minOf(width, 110 - margin)
    box("app-window", 11 + margin, 11, w, 55, ACTION)
    icon("content", APPS, 11 + margin + w / 2 - 14, 25, 28)
}

private fun GuideFrameBuilder.scaledApp(textSize: Int, button: Int) {
    screen("app")
    word("value", "21°", 66, 24, textSize)
    tile("adjust", CLIMATE, 66 - button / 2, 34, button)
}

private fun GuideFrameBuilder.screensWithDpi(left: String, right: String, highlight: Int? = null) {
    screen("left-screen", 7, 15, 56, 48)
    screen("right-screen", 69, 15, 56, 48)
    highlight?.let {
        box("highlight", if (it == 0) 7 else 69, 15, 56, 48, ACCENT, 5, outline = true)
    }
    word("left-scale", left, 35, 34, 16)
    word("right-scale", right, 97, 34, 16)
    word("left-dpi", "DPI", 35, 51, 9, MUTED)
    word("right-dpi", "DPI", 97, 51, 9, MUTED)
}

private fun GuideFrameBuilder.dualScreen(side: Int, verticalOffset: Int) {
    screen("center-screen", 7, 15, 57, 46)
    screen("side-screen", 68, 15, 57, 46)
    val x = if (side == 0) 22 else 83
    tile("moving-app", PLAY, x, 25 + verticalOffset, 26)
}

private fun GuideFrameBuilder.compactPreview(scale: Int) {
    screen("display")
    icon("song", PLAY, 20, 25, 26)
    word("track", "Night Drive", 78, 25, scale, width = 72)
    word("time", "1:24", 78, 45, scale, MUTED)
}

private fun GuideFrameBuilder.previewPanes(media: Boolean, navigation: Boolean, stacked: Boolean = false,
                                            navigationFirst: Boolean = false, light: Boolean = false, mediaWidth: Int = 54) {
    screen("preview", ink = if (light) PAPER else NIGHT)
    val both = media && navigation
    val width = if (both && !stacked) mediaWidth else 106
    val height = if (both && stacked) 25 else 50
    val routeWidth = if (both && !stacked) 102 - width else 106
    if (media) {
        val x = if (both && navigationFirst && !stacked) 17 + routeWidth else 13
        val y = if (both && navigationFirst && stacked) 43 else 14
        player("media", x, y, width, height, progress = true, title = "97.5 FM", ink = if (light) PAPER else TEAL,
            fg = if (light) NIGHT else WHITE)
    }
    if (navigation) {
        val x = if (both && !stacked && !navigationFirst) 17 + width else 13
        val y = if (both && stacked && !navigationFirst) 42 else 14
        route("nav", x, y, routeWidth, height, if (routeWidth >= 60) "300 m" else null, ink = if (light) WHITE else NIGHT,
            fg = if (light) TEAL else WHITE)
    }
}

private fun GuideFrameBuilder.preferredPlayer(preferred: Boolean) {
    word("fm", "FM", 23, 22, 13, if (preferred) MUTED else ACCENT)
    word("music", "♪", 23, 54, 21, if (preferred) ACCENT else MUTED)
    val y = if (preferred) 54 else 22
    arrow("chosen", 49, y)
    player("selected", 62, 12, 60, 55, progress = true, title = if (preferred) "Atlas" else "97.5 FM")
}

private fun GuideFrameBuilder.seekPreview(progress: Int, seconds: Int) {
    screen("player")
    word("seek", "+$seconds s  ››", 66, 25, 15)
    gauge("timeline", 23, 49, 86, progress)
}

private fun GuideFrameBuilder.workspace(x: Int = 8, y: Int = 7, w: Int = 116, h: Int = 64,
                                        size: Int = 20, selected: Int = -1, replacement: CockpitIcon? = null) {
    screen("workspace", x, y, w, h)
    val top = if (w > 90) 26 else 6
    val tileSize = minOf(size, (w - 12) / 3, (h - top - 6) / 2)
    listOf(APPS, PLAY, HOME, SETTINGS, CAR, ROUTINE).forEachIndexed { index, app ->
        val dx = x + 5 + (index % 3) * (w - 10) / 3
        val dy = y + top + (index / 3) * (tileSize + 4)
        if (selected == index) box("slot", dx - 2, dy - 2, tileSize + 4, tileSize + 4, ACCENT, 5, outline = true)
        tile("slot-$index", if (selected == index) replacement ?: app else app, dx, dy, tileSize)
    }
}

private fun GuideFrameBuilder.passengerDock(selected: Int, replacement: Boolean) {
    screen("workspace")
    box("dock", 14, 47, 103, 17, ACTION)
    listOf(HOME, ROUTINE).forEachIndexed { index, app ->
        val x = 35 + index * 48
        icon("dock-$index", if (index == selected && replacement) PLAY else app, x, 47, 17)
        if (index == selected) box("selected", x - 4, 45, 25, 21, ACCENT, 4, outline = true)
    }
    icon("grid", APPS, 55, 14, 25)
}

private fun GuideFrameBuilder.pairWindow(left: CockpitIcon, right: CockpitIcon, split: Int = 60,
                                        selected: Int = -1, name: String? = null) {
    screen("display")
    val y = if (name == null) 13 else 27
    val h = 65 - y
    box("left-pane", 13, y, split - 15, h, if (selected == 0) TEAL else ACTION)
    box("right-pane", split, y, 118 - split, h, if (selected == 1) TEAL else ACTION)
    icon("left", left, (split + 13) / 2 - 10, y + h / 2 - 10, 20, if (selected == 0) WHITE else ACCENT)
    icon("right", right, (118 + split) / 2 - 10, y + h / 2 - 10, 20, if (selected == 1) WHITE else ACCENT)
    name?.let { word("name", it, 66, 17, 11) }
}
